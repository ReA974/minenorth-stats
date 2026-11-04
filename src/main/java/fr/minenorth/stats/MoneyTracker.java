package fr.minenorth.stats;

import com.mojang.authlib.GameProfile;
import fr.minenorth.api.MineNorth;
import fr.minenorth.api.MoneyFlowEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Masse monétaire : argent créé contre argent détruit, par jour et en cumulé (événements {@link MoneyFlowEvent} de l'API, postés par le trésor
 * public et la banque). Les mouvements des admins et OP (permission 2, ou rôle staff du panneau admin) sont comptés à part et exclus des totaux.
 */
public final class MoneyTracker {
    @SubscribeEvent
    public void onFlow(MoneyFlowEvent e) {
        Data d = Store.data;
        if (d.money == null) d.money = new java.util.TreeMap<>();
        if (d.moneyTot == null) d.moneyTot = new Data.Money();
        boolean ex = excluded(e.server(), e.player());
        add(d.money.computeIfAbsent(Store.today(), k -> new Data.Money()), e, ex);
        add(d.moneyTot, e, ex);
    }

    private static void add(Data.Money m, MoneyFlowEvent e, boolean ex) {
        long c = e.cents();
        if (ex) {
            if (e.created()) m.exCreated += c; else m.exDestroyed += c;
            return;
        }
        String src = category(e.source());
        if (e.created()) { m.created += c; m.cSrc.merge(src, c, Long::sum); }
        else { m.destroyed += c; m.dSrc.merge(src, c, Long::sum); }
    }

    /** « police:amende » -> « police:amende » ; les sources trop détaillées (après un 2e « : ») sont regroupées. */
    private static String category(String s) {
        String[] p = s.split(":", 3);
        String out = p.length >= 2 ? p[0] + ":" + p[1] : p[0];
        return out.length() > 48 ? out.substring(0, 48) : out;
    }

    /** Admin ou OP : permission 2 (connecté ou liste des OP) ou rôle staff du panneau admin. */
    static boolean excluded(MinecraftServer s, UUID id) {
        if (id == null) return false;
        try {
            ServerPlayer p = s.getPlayerList().getPlayer(id);
            if (p != null) {
                if (p.hasPermissions(2)) return true;
            } else if (s.getProfileCache() != null) {
                Optional<GameProfile> gp = s.getProfileCache().get(id);
                if (gp.isPresent() && s.getPlayerList().isOp(gp.get())) return true;
            }
            return MineNorth.staff().isStaff(s, id);
        } catch (Throwable t) {
            return false;
        }
    }
}
