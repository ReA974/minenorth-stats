package fr.minenorth.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stockage JSON : lecture au démarrage, écriture atomique (fichier temporaire puis renommage) hors du thread serveur. */
public final class Store {
    private static final Gson GSON = new GsonBuilder().create();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MineNorthStats-IO");
        t.setDaemon(true);
        return t;
    });

    public static Data data = new Data();
    public static Path dir;
    private static String lastPruneDay = "";

    private Store() {}

    public static String today() { return LocalDate.now(ZoneId.systemDefault()).toString(); }

    public static String dayOf(long ms) { return java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString(); }

    public static void load(MinecraftServer server) {
        dir = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("minenorth_stats");
        data = new Data();
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve("stats.json");
            if (!Files.exists(f)) f = dir.resolve("stats.json.bak");
            if (Files.exists(f)) {
                Data d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
                if (d != null) data = d;
            }
        } catch (Exception e) {
            MineNorthStats.LOG.error("Lecture de stats.json impossible, nouvelle base : {}", e.toString());
            try { Files.copy(dir.resolve("stats.json"), dir.resolve("stats.json.corrompu"), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
            data = new Data();
        }
        if (data.salt == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            data.salt = java.util.HexFormat.of().formatHex(b);
            data.created = System.currentTimeMillis();
        }
        // Reprise après un arrêt brutal : une session restée ouverte se termine à la dernière trace connue.
        for (Data.PlayerRec r : data.players.values()) {
            if (r.cur != 0) closeSession(r, Math.max(r.cur, r.last));
        }
    }

    public static void closeSession(Data.PlayerRec r, long end) {
        if (r.cur == 0) return;
        long start = r.cur;
        r.cur = 0;
        if (end < start) end = start;
        r.playMs += end - start;
        r.nSessions++;
        r.sessions.add(new long[]{start, end});
        while (r.sessions.size() > 200) r.sessions.remove(0);
    }

    public static void save() {
        if (dir == null) return;
        final String json = GSON.toJson(data);
        final Path d = dir;
        IO.execute(() -> write(d, json));
    }

    /** Écriture synchrone (arrêt du serveur). */
    public static void saveNow() {
        if (dir == null) return;
        final String json = GSON.toJson(data);
        final Path d = dir;
        try {
            IO.submit(() -> write(d, json)).get();
        } catch (Exception e) {
            MineNorthStats.LOG.error("Sauvegarde finale impossible : {}", e.toString());
        }
    }

    private static void write(Path d, String json) {
        try {
            Path tmp = d.resolve("stats.json.tmp");
            Path f = d.resolve("stats.json");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            if (Files.exists(f)) Files.copy(f, d.resolve("stats.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            MineNorthStats.LOG.error("Sauvegarde de stats.json impossible : {}", e.toString());
        }
    }

    public static void runIo(Runnable r) { IO.execute(r); }

    /** Efface tout ce qui dépasse la durée de conservation (une fois par jour). */
    public static void prune() {
        String today = today();
        if (today.equals(lastPruneDay)) return;
        lastPruneDay = today;
        String limit = LocalDate.now(ZoneId.systemDefault()).minusDays(StatsConfig.keepDays()).toString();
        long limitMs = System.currentTimeMillis() - StatsConfig.keepDays() * 86_400_000L;
        data.hours.keySet().removeIf(k -> k.compareTo(limit) < 0);
        data.heat.keySet().removeIf(k -> k.compareTo(limit) < 0);
        data.eco.keySet().removeIf(k -> k.compareTo(limit) < 0);
        data.series.removeIf(p -> p.t < limitMs);
        // Séries anciennes (> 7 jours) : un point par heure.
        long recent = System.currentTimeMillis() - 7 * 86_400_000L;
        List<Data.Point> kept = new ArrayList<>();
        long lastHour = -1;
        for (Data.Point p : data.series) {
            if (p.t >= recent) { kept.add(p); continue; }
            long h = p.t / 3_600_000L;
            if (h != lastHour) { kept.add(p); lastHour = h; }
        }
        data.series = kept;
        for (Iterator<Map.Entry<String, Data.PlayerRec>> it = data.players.entrySet().iterator(); it.hasNext(); ) {
            Data.PlayerRec r = it.next().getValue();
            if (r.cur == 0 && r.last < limitMs) { it.remove(); continue; }
            r.days.keySet().removeIf(k -> k.compareTo(limit) < 0);
            r.sessions.removeIf(s -> s[1] < limitMs);
        }
    }

    public static Data.PlayerRec rec(String uuid) { return data.players.computeIfAbsent(uuid, k -> new Data.PlayerRec()); }

    public static Data.DayRec day(Data.PlayerRec r, String day) { return r.days.computeIfAbsent(day, k -> new Data.DayRec()); }

    public static void add(Data.PlayerRec r, String key, long n) {
        r.tot.merge(key, n, Long::sum);
        day(r, today()).c.merge(key, n, Long::sum);
    }
}
