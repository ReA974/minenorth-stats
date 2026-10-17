package fr.minenorth.stats;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/** Mini serveur web (désactivé par défaut) qui sert UNIQUEMENT les 6 fichiers du site, derrière un jeton. */
public final class HttpSite {
    private static final Set<String> FILES = Set.of("index.html", "app.js", "style.css", "chart.umd.min.js", "data.js", "data.json");
    private static final String COOKIE = "mnstats";
    private static HttpServer server;
    private static String token = "";
    private static final Map<String, long[]> FAILS = new ConcurrentHashMap<>(); // ip -> {nombre, début de fenêtre}

    private HttpSite() {}

    public static String token() { return token; }

    public static boolean running() { return server != null; }

    /** Jeton effectif : celui de la config, sinon celui généré dans &lt;monde&gt;/data/minenorth_stats/jeton.txt. */
    public static String resolveToken() {
        String cfg = StatsConfig.httpToken();
        if (!cfg.isEmpty()) return cfg;
        try {
            Path f = Store.dir.resolve("jeton.txt");
            if (Files.exists(f)) {
                String t = Files.readString(f, StandardCharsets.UTF_8).trim();
                if (t.length() >= 16) return t;
            }
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            String t = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            Files.writeString(f, t, StandardCharsets.UTF_8);
            return t;
        } catch (IOException e) {
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        }
    }

    public static void start() {
        stop();
        if (!StatsConfig.httpEnabled()) return;
        try {
            token = resolveToken();
            if (token.length() < 8) {
                MineNorthStats.LOG.error("Jeton web trop court (8 caracteres minimum) : serveur web NON demarre.");
                return;
            }
            server = HttpServer.create(new InetSocketAddress(StatsConfig.httpBind(), StatsConfig.httpPort()), 16);
            server.createContext("/", HttpSite::handle);
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "MineNorthStats-HTTP");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            MineNorthStats.LOG.info("Site stats sur http://{}:{}/  (jeton : /stats jeton)", StatsConfig.httpBind(), StatsConfig.httpPort());
        } catch (Throwable t) {
            MineNorthStats.LOG.error("Serveur web impossible a demarrer : {}", t.toString());
            server = null;
        }
    }

    public static void stop() {
        if (server != null) {
            try { server.stop(0); } catch (Throwable ignored) {}
            server = null;
        }
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:");
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        ex.close();
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, "text/plain", new byte[0]); return; }
            String ip = ex.getRemoteAddress().getAddress().getHostAddress();
            long now = System.currentTimeMillis();
            long[] f = FAILS.get(ip);
            if (f != null && now - f[1] < 60_000 && f[0] >= 10) {
                send(ex, 429, "text/plain; charset=utf-8", "Trop d'essais, reessayez dans une minute.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String query = ex.getRequestURI().getRawQuery();
            String given = null;
            if (query != null) {
                for (String kv : query.split("&")) {
                    if (kv.startsWith("jeton=")) given = URLDecoder.decode(kv.substring(6), StandardCharsets.UTF_8);
                }
            }
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            String fromCookie = null;
            if (cookie != null) {
                for (String c : cookie.split(";")) {
                    c = c.trim();
                    if (c.startsWith(COOKIE + "=")) fromCookie = c.substring(COOKIE.length() + 1);
                }
            }
            boolean viaQuery = given != null && same(given, token);
            boolean ok = viaQuery || (fromCookie != null && same(fromCookie, token));
            if (!ok) {
                FAILS.compute(ip, (k, v) -> v == null || now - v[1] >= 60_000 ? new long[]{1, now} : new long[]{v[0] + 1, v[1]});
                send(ex, 401, "text/plain; charset=utf-8", "Acces refuse : jeton manquant ou invalide.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            FAILS.remove(ip);
            String path = ex.getRequestURI().getPath();
            String name = path.equals("/") ? "index.html" : path.substring(1);
            if (viaQuery) {
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
                ex.getResponseHeaders().set("Location", "/" + (name.equals("index.html") ? "" : name));
                send(ex, 302, "text/plain", new byte[0]);
                return;
            }
            Path file = SiteBuilder.siteDir().resolve(name);
            if (!FILES.contains(name) || !Files.isRegularFile(file)) {
                send(ex, 404, "text/plain; charset=utf-8", "Introuvable (le site est-il genere ? /stats site)".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                    : name.endsWith(".css") ? "text/css; charset=utf-8"
                    : name.endsWith(".json") ? "application/json; charset=utf-8"
                    : "text/javascript; charset=utf-8";
            send(ex, 200, type, Files.readAllBytes(file));
        } catch (Throwable t) {
            try { send(ex, 500, "text/plain", new byte[0]); } catch (Throwable ignored) {}
        }
    }
}
