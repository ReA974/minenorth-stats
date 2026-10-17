package fr.minenorth.stats;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Écoute les événements du jeu (aucun travail lourd dans les événements : un simple compteur) et échantillonne périodiquement. */
public final class Collector {
    private static final int LIGHT_TICKS = 600;           // 30 s : positions, temps de jeu, services
    private static final int MAX_HEAT_CELLS_PER_DAY = 4000;
    private static final int MAX_ITEMS_PER_PLAYER = 60;

    private int ticks;
    private long lastLight = System.currentTimeMillis();
    private long lastSiteMs = System.currentTimeMillis();
    private int sinceHeavy;

    // ------------------------------------------------------------------ utilitaires

    private static boolean tracked(Entity e) {
        if (!(e instanceof ServerPlayer sp) || e instanceof FakePlayer) return false;
        return !(StatsConfig.skipOps() && sp.hasPermissions(2));
    }

    private static Data.PlayerRec recOf(ServerPlayer p) {
        return Store.rec(p.getUUID().toString());
    }

    // ------------------------------------------------------------------ connexions

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!tracked(e.getEntity())) return;
        ServerPlayer p = (ServerPlayer) e.getEntity();
        Data.PlayerRec r = recOf(p);
        long now = System.currentTimeMillis();
        if (r.first == 0) r.first = now;
        if (r.cur != 0) Store.closeSession(r, Math.max(r.cur, r.last)); // session précédente restée ouverte
        r.cur = now;
        r.last = now;
        r.name = p.getGameProfile().getName();
        r.rp = Compat.rpName(p.server, p.getUUID(), r.name);
        Store.day(r, Store.today()); // marque le jour comme actif
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!tracked(e.getEntity())) return;
        ServerPlayer p = (ServerPlayer) e.getEntity();
        Data.PlayerRec r = recOf(p);
        long now = System.currentTimeMillis();
        r.last = now;
        snapshotItems(p, r);
        Store.closeSession(r, now);
    }

    /** Ferme les sessions encore ouvertes (arrêt du serveur). */
    public static void closeAll() {
        long now = System.currentTimeMillis();
        for (Data.PlayerRec r : Store.data.players.values()) {
            if (r.cur != 0) { r.last = now; Store.closeSession(r, now); }
        }
    }

    // ------------------------------------------------------------------ activités

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent e) {
        if (!e.isCanceled() && tracked(e.getPlayer())) Store.add(recOf((ServerPlayer) e.getPlayer()), "casses", 1);
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!e.isCanceled() && tracked(e.getEntity())) Store.add(recOf((ServerPlayer) e.getEntity()), "poses", 1);
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) {
        if (tracked(e.getEntity())) Store.add(recOf((ServerPlayer) e.getEntity()), "morts", 1);
        Entity killer = e.getSource().getEntity();
        if (killer != null && killer != e.getEntity() && tracked(killer)) {
            Store.add(recOf((ServerPlayer) killer), e.getEntity() instanceof Player ? "kills_joueurs" : "kills_mobs", 1);
        }
    }

    @SubscribeEvent
    public void onCraft(PlayerEvent.ItemCraftedEvent e) {
        if (tracked(e.getEntity())) Store.add(recOf((ServerPlayer) e.getEntity()), "craft", 1);
    }

    /** Trajet : monter dans une entité d'un autre mod (véhicule MTS, etc.). Chevaux, bateaux et wagonnets vanilla ne comptent pas. */
    @SubscribeEvent
    public void onMount(EntityMountEvent e) {
        if (!e.isMounting() || e.getLevel().isClientSide || !tracked(e.getEntityMounting())) return;
        if (isModdedVehicle(e.getEntityBeingMounted())) Store.add(recOf((ServerPlayer) e.getEntityMounting()), "trajets_vehicule", 1);
    }

    private static boolean isModdedVehicle(Entity v) {
        if (v == null) return false;
        var key = ForgeRegistries.ENTITY_TYPES.getKey(v.getType());
        return key != null && !key.getNamespace().equals("minecraft");
    }

    // ------------------------------------------------------------------ cadence

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (++ticks < LIGHT_TICKS) return;
        ticks = 0;
        MinecraftServer server = e.getServer();
        try {
            light(server);
            if (++sinceHeavy * 0.5 >= StatsConfig.sampleMinutes()) {
                sinceHeavy = 0;
                heavy(server);
            }
            int siteMin = StatsConfig.siteMinutes();
            if (siteMin > 0 && System.currentTimeMillis() - lastSiteMs >= siteMin * 60_000L) {
                lastSiteMs = System.currentTimeMillis();
                SiteBuilder.generate(server);
            }
        } catch (Throwable t) {
            MineNorthStats.LOG.error("Erreur de collecte (ignoree) : ", t);
        }
    }

    /** Toutes les 30 s : temps de jeu, heures de pointe, positions, véhicule, service. */
    private void light(MinecraftServer server) {
        long now = System.currentTimeMillis();
        int elapsed = (int) Math.max(1, Math.min(120, (now - lastLight) / 1000));
        lastLight = now;
        String today = Store.today();
        int hour = LocalDateTime.now(ZoneId.systemDefault()).getHour();
        int[] hrs = Store.data.hours.computeIfAbsent(today, k -> new int[24]);
        Map<String, Integer> heat = Store.data.heat.computeIfAbsent(today, k -> new HashMap<>());
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!tracked(p)) continue;
            Data.PlayerRec r = recOf(p);
            r.last = now;
            Store.day(r, today).playS += elapsed;
            hrs[hour] += elapsed;
            String cell = p.level().dimension().location() + "|" + (p.blockPosition().getX() >> 4) + "|" + (p.blockPosition().getZ() >> 4);
            if (heat.size() < MAX_HEAT_CELLS_PER_DAY || heat.containsKey(cell)) heat.merge(cell, 1, Integer::sum);
            if (isModdedVehicle(p.getVehicle())) Store.add(r, "vehicule_s", elapsed);
            UUID id = p.getUUID();
            if (Compat.policeOnDuty(server, id)) Store.add(r, "service_police_s", elapsed);
            if (Compat.secoursOnDuty(server, id)) Store.add(r, "service_secours_s", elapsed);
        }
    }

    /** Toutes les N minutes : soldes, inventaires, métiers, permis, économie, nettoyage, sauvegarde. */
    private void heavy(MinecraftServer server) {
        long now = System.currentTimeMillis();
        String today = Store.today();
        List<String> licenceIds = StatsConfig.licences();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!tracked(p)) continue;
            Data.PlayerRec r = recOf(p);
            r.name = p.getGameProfile().getName();
            r.rp = Compat.rpName(server, p.getUUID(), r.name);
            snapshotItems(p, r);
            r.police = Compat.isPolice(server, p.getUUID());
            r.secours = Compat.isSecours(server, p.getUUID());
            List<String> lic = new ArrayList<>();
            for (String l : licenceIds) if (Compat.hasLicence(server, p.getUUID(), l)) lic.add(l);
            r.licences = lic;
            tallyBank(server, p.getUUID(), r);
        }
        // Soldes de tous les joueurs connus (même hors ligne) + économie du jour.
        List<Long> balances = new ArrayList<>();
        long total = 0;
        for (Map.Entry<String, Data.PlayerRec> en : Store.data.players.entrySet()) {
            Data.PlayerRec r = en.getValue();
            long b;
            try { b = Compat.balance(server, UUID.fromString(en.getKey())); } catch (IllegalArgumentException ex) { b = -1; }
            r.balance = b;
            if (b >= 0) {
                Store.day(r, today).bal = b;
                balances.add(b);
                total += b;
            }
        }
        Map<String, List<UUID>> companies = Compat.companies(server);
        if (!companies.isEmpty()) {
            Map<String, List<String>> byPlayer = new HashMap<>();
            companies.forEach((name, ms) -> ms.forEach(u -> byPlayer.computeIfAbsent(u.toString(), k -> new ArrayList<>()).add(name)));
            Store.data.players.forEach((id, r) -> r.companies = byPlayer.getOrDefault(id, new ArrayList<>()));
        }
        if (!balances.isEmpty()) {
            balances.sort(Long::compare);
            Data.Eco eco = new Data.Eco();
            eco.total = total;
            eco.accounts = balances.size();
            eco.median = balances.get(balances.size() / 2);
            int topN = Math.max(1, balances.size() / 10);
            long top = 0;
            for (int i = balances.size() - topN; i < balances.size(); i++) top += balances.get(i);
            eco.top10 = total > 0 ? (int) (top * 1000 / total) : 0;
            Store.data.eco.put(today, eco);
        }
        Data.Point pt = new Data.Point();
        pt.t = now;
        pt.online = (int) server.getPlayerList().getPlayers().stream().filter(Collector::tracked).count();
        pt.money = total;
        pt.accounts = balances.size();
        Store.data.series.add(pt);
        Store.prune();
        Store.save();
    }

    /** Dépenses / revenus / salaires depuis le dernier relevé (historique bancaire, uniquement les nouvelles lignes). */
    private static void tallyBank(MinecraftServer server, UUID id, Data.PlayerRec r) {
        List<Compat.Tx> txs = Compat.history(server, id, 40);
        if (txs.isEmpty()) return;
        long max = r.lastTx;
        boolean first = r.lastTx == 0;
        for (Compat.Tx t : txs) {
            if (t.time() > max) max = t.time();
            if (first || t.time() <= r.lastTx) continue;
            if (t.cents() < 0) Store.add(r, "depenses_c", -t.cents()); else Store.add(r, "revenus_c", t.cents());
            if ("SALARY".equals(t.category())) Store.add(r, "salaires_c", Math.abs(t.cents()));
            if ("PAYMENT".equals(t.category())) Store.add(r, "paiements", 1);
        }
        r.lastTx = max;
    }

    private static void snapshotItems(ServerPlayer p, Data.PlayerRec r) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) count(m, p.getInventory().getItem(i));
        for (int i = 0; i < p.getEnderChestInventory().getContainerSize(); i++) count(m, p.getEnderChestInventory().getItem(i));
        Map<String, Integer> top = new java.util.LinkedHashMap<>();
        m.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).limit(MAX_ITEMS_PER_PLAYER)
                .forEach(en -> top.put(en.getKey(), en.getValue()));
        r.items = top;
    }

    private static void count(Map<String, Integer> m, ItemStack s) {
        if (s.isEmpty()) return;
        var key = ForgeRegistries.ITEMS.getKey(s.getItem());
        if (key != null) m.merge(key.toString(), s.getCount(), Integer::sum);
    }
}
