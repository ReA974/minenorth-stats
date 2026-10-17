package fr.minenorth.stats;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Génère config/minenorth_stats/site/ : index.html, app.js, style.css, chart.umd.min.js, data.js (pour file://) et data.json. */
public final class SiteBuilder {
    private static final Gson GSON = new Gson();
    private static final String[] STATIC = {"index.html", "app.js", "style.css", "chart.umd.min.js"};
    private static final int FICHE_DAYS = 60;

    private SiteBuilder() {}

    public static Path siteDir() { return FMLPaths.CONFIGDIR.get().resolve("minenorth_stats").resolve("site"); }

    /** Construit les données sur le thread serveur, écrit les fichiers en tâche de fond. Renvoie le dossier du site. */
    public static Path generate(MinecraftServer server) {
        final String json = GSON.toJson(build());
        final Path dir = siteDir();
        Store.runIo(() -> {
            try {
                Files.createDirectories(dir);
                for (String f : STATIC) {
                    try (InputStream in = SiteBuilder.class.getResourceAsStream("/assets/minenorthstats/site/" + f)) {
                        if (in != null) Files.copy(in, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.writeString(dir.resolve("data.json"), json, StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("data.js"), "window.MN_DATA = " + json + ";\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                MineNorthStats.LOG.error("Generation du site impossible : {}", e.toString());
            }
        });
        return dir;
    }

    private static String hash(String uuid) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((Store.data.salt + uuid).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h).substring(0, 12);
        } catch (Exception e) {
            return Integer.toHexString((Store.data.salt + uuid).hashCode());
        }
    }

    static JsonObject build() {
        Data d = Store.data;
        boolean anon = StatsConfig.anonymize();
        String mode = StatsConfig.display();
        JsonObject root = new JsonObject();
        root.addProperty("generated", System.currentTimeMillis());
        root.addProperty("today", Store.today());
        root.addProperty("retentionDays", StatsConfig.keepDays());
        root.addProperty("mode", anon ? "anonyme" : mode);
        root.addProperty("sampleMinutes", StatsConfig.sampleMinutes());

        String since60 = LocalDate.now(ZoneId.systemDefault()).minusDays(FICHE_DAYS).toString();
        JsonArray players = new JsonArray();
        Map<String, long[]> items = new HashMap<>(); // objet -> {total, propriétaires}
        Map<String, long[]> comp = new TreeMap<>();   // entreprise -> {membres, solde cumulé}
        String since7 = LocalDate.now(ZoneId.systemDefault()).minusDays(7).toString();

        List<Map.Entry<String, Data.PlayerRec>> sorted = new ArrayList<>(d.players.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue().last, a.getValue().last));
        for (Map.Entry<String, Data.PlayerRec> en : sorted) {
            String uuid = en.getKey();
            Data.PlayerRec r = en.getValue();
            String id = anon ? hash(uuid) : uuid;
            String label;
            if (anon) label = "Joueur " + id.substring(0, 6).toUpperCase();
            else if (mode.equals("pseudo")) label = r.name.isBlank() ? uuid.substring(0, 8) : r.name;
            else if (mode.equals("rp")) label = !r.rp.isBlank() ? r.rp : (r.name.isBlank() ? uuid.substring(0, 8) : r.name);
            else label = uuid.substring(0, 8);
            JsonObject p = new JsonObject();
            p.addProperty("id", id);
            p.addProperty("label", label);
            p.addProperty("first", r.first);
            p.addProperty("last", r.last);
            p.addProperty("online", r.cur != 0);
            long playMs = r.playMs + (r.cur != 0 ? System.currentTimeMillis() - r.cur : 0);
            p.addProperty("playMin", playMs / 60000);
            p.addProperty("nSessions", r.nSessions + (r.cur != 0 ? 1 : 0));
            JsonArray ss = new JsonArray();
            int from = Math.max(0, r.sessions.size() - 30);
            for (int i = from; i < r.sessions.size(); i++) { JsonArray s = new JsonArray(); s.add(r.sessions.get(i)[0]); s.add(r.sessions.get(i)[1]); ss.add(s); }
            if (r.cur != 0) { JsonArray s = new JsonArray(); s.add(r.cur); s.add(System.currentTimeMillis()); ss.add(s); }
            p.add("sessions", ss);
            JsonArray ad = new JsonArray();
            JsonObject days = new JsonObject();
            for (Map.Entry<String, Data.DayRec> de : r.days.entrySet()) {
                ad.add(de.getKey());
                if (de.getKey().compareTo(since60) < 0) continue;
                Data.DayRec dr = de.getValue();
                JsonObject o = new JsonObject();
                o.addProperty("p", dr.playS);
                if (dr.bal >= 0) o.addProperty("b", dr.bal);
                o.add("c", GSON.toJsonTree(dr.c));
                days.add(de.getKey(), o);
            }
            p.add("ad", ad);
            p.add("days", days);
            p.add("tot", GSON.toJsonTree(r.tot));
            p.addProperty("bal", r.balance);
            p.add("companies", GSON.toJsonTree(r.companies));
            p.addProperty("police", r.police);
            p.addProperty("secours", r.secours);
            p.add("licences", GSON.toJsonTree(r.licences));
            p.add("items", GSON.toJsonTree(r.items));
            players.add(p);
            for (Map.Entry<String, Integer> it : r.items.entrySet()) {
                long[] a = items.computeIfAbsent(it.getKey(), k -> new long[2]);
                a[0] += it.getValue();
                a[1]++;
            }
            for (String c : r.companies) {
                long[] a = comp.computeIfAbsent(c, k -> new long[2]);
                a[0]++;
                if (r.balance > 0) a[1] += r.balance;
            }
        }
        root.add("players", players);

        JsonObject hours = new JsonObject();
        d.hours.forEach((day, arr) -> hours.add(day, GSON.toJsonTree(arr)));
        root.add("hours", hours);

        // Cartes de chaleur agrégées par dimension : [[cx, cz, n], ...]
        JsonObject hAll = new JsonObject(), h7 = new JsonObject();
        Map<String, Map<String, Integer>> allByDim = new HashMap<>(), sevenByDim = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> de : d.heat.entrySet()) {
            boolean recent = de.getKey().compareTo(since7) >= 0;
            for (Map.Entry<String, Integer> ce : de.getValue().entrySet()) {
                String key = ce.getKey();
                int cut = key.indexOf('|');
                String dim = key.substring(0, cut), cell = key.substring(cut + 1);
                allByDim.computeIfAbsent(dim, k -> new HashMap<>()).merge(cell, ce.getValue(), Integer::sum);
                if (recent) sevenByDim.computeIfAbsent(dim, k -> new HashMap<>()).merge(cell, ce.getValue(), Integer::sum);
            }
        }
        allByDim.forEach((dim, cells) -> hAll.add(dim, cellsToJson(cells)));
        sevenByDim.forEach((dim, cells) -> h7.add(dim, cellsToJson(cells)));
        JsonObject heat = new JsonObject();
        heat.add("all", hAll);
        heat.add("d7", h7);
        root.add("heat", heat);

        JsonArray series = new JsonArray();
        for (Data.Point pt : d.series) {
            JsonArray a = new JsonArray();
            a.add(pt.t); a.add(pt.online); a.add(pt.money); a.add(pt.accounts);
            series.add(a);
        }
        root.add("series", series);
        root.add("eco", GSON.toJsonTree(d.eco));

        List<Map.Entry<String, long[]>> its = new ArrayList<>(items.entrySet());
        its.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        JsonArray topItems = new JsonArray();
        for (int i = 0; i < Math.min(150, its.size()); i++) {
            JsonArray a = new JsonArray();
            a.add(its.get(i).getKey()); a.add(its.get(i).getValue()[0]); a.add(its.get(i).getValue()[1]);
            topItems.add(a);
        }
        root.add("items", topItems);

        JsonArray companies = new JsonArray();
        comp.forEach((name, v) -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("members", v[0]);
            o.addProperty("balance", v[1]);
            companies.add(o);
        });
        root.add("companies", companies);
        return root;
    }

    private static JsonArray cellsToJson(Map<String, Integer> cells) {
        JsonArray out = new JsonArray();
        for (Map.Entry<String, Integer> e : cells.entrySet()) {
            String[] xy = e.getKey().split("\\|");
            JsonArray a = new JsonArray();
            a.add(Integer.parseInt(xy[0])); a.add(Integer.parseInt(xy[1])); a.add(e.getValue());
            out.add(a);
        }
        return out;
    }
}
