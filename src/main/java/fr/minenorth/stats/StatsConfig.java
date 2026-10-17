package fr.minenorth.stats;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;
import java.util.function.Supplier;

/** config/minenorth_stats.toml (serveur dédié). */
public final class StatsConfig {
    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.IntValue SAMPLE_MIN, SITE_MIN, KEEP_DAYS, HTTP_PORT;
    private static final ForgeConfigSpec.ConfigValue<String> DISPLAY, HTTP_BIND, HTTP_TOKEN;
    private static final ForgeConfigSpec.BooleanValue ANON, SKIP_OPS, HTTP_ON;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> LICENCES;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("collecte");
        SAMPLE_MIN = b.comment("Toutes les N minutes : relevé des soldes, inventaires, métiers et sauvegarde. (Les positions sont relevées toutes les 30 s.)")
                .defineInRange("echantillonMinutes", 5, 1, 60);
        KEEP_DAYS = b.comment("Durée de conservation en jours. Au-delà, jours, positions et joueurs inactifs sont effacés.")
                .defineInRange("conservationJours", 180, 7, 3650);
        SKIP_OPS = b.comment("Ne pas suivre les opérateurs (niveau 2 et plus).")
                .define("ignorerOperateurs", false);
        LICENCES = b.comment("Identifiants des permis/licences à relever (mod Permis), ex. permisconduire.")
                .defineListAllowEmpty("licences", List.of("permisconduire"), o -> o instanceof String);
        b.pop();
        b.push("site");
        SITE_MIN = b.comment("Génère le site dans config/minenorth_stats/site/ toutes les N minutes (0 = seulement avec /stats site).")
                .defineInRange("siteMinutes", 30, 0, 1440);
        DISPLAY = b.comment("Identification des joueurs sur le site : \"uuid\" (par défaut, UUID abrégé), \"pseudo\" (pseudo Minecraft) ou \"rp\" (nom RP Prénom Nom).")
                .define("affichage", "uuid");
        ANON = b.comment("Anonymisation : remplace tout identifiant par un code irréversible (« Joueur A1B2C3 »). Prioritaire sur « affichage ».")
                .define("anonymiser", false);
        b.pop();
        b.push("http");
        HTTP_ON = b.comment("Mini serveur web intégré pour consulter le site depuis un navigateur. Désactivé par défaut. Le trafic n'est PAS chiffré : préférez un tunnel SSH / VPN / reverse proxy HTTPS.")
                .define("actif", false);
        HTTP_PORT = b.comment("Port TCP du mini serveur web.").defineInRange("port", 8765, 1024, 65535);
        HTTP_BIND = b.comment("Adresse d'écoute. 127.0.0.1 = uniquement depuis la machine du serveur (recommandé). 0.0.0.0 = toutes les interfaces.")
                .define("adresse", "127.0.0.1");
        HTTP_TOKEN = b.comment("Jeton d'accès (ouvrir http://hote:port/?jeton=...). Vide = un jeton aléatoire est généré dans <monde>/data/minenorth_stats/jeton.txt (aussi : /stats jeton).")
                .define("jeton", "");
        b.pop();
        SPEC = b.build();
    }

    private StatsConfig() {}

    private static <T> T val(Supplier<T> s, T def) {
        try { return s.get(); } catch (IllegalStateException e) { return def; }
    }

    public static int sampleMinutes() { return val(SAMPLE_MIN::get, 5); }
    public static int siteMinutes() { return val(SITE_MIN::get, 30); }
    public static int keepDays() { return val(KEEP_DAYS::get, 180); }
    public static boolean skipOps() { return val(SKIP_OPS::get, false); }
    public static boolean anonymize() { return val(ANON::get, false); }
    public static String display() {
        String s = val(DISPLAY::get, "uuid").trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("pseudo") || s.equals("rp") ? s : "uuid";
    }
    public static boolean httpEnabled() { return val(HTTP_ON::get, false); }
    public static int httpPort() { return val(HTTP_PORT::get, 8765); }
    public static String httpBind() { return val(HTTP_BIND::get, "127.0.0.1"); }
    public static String httpToken() { return val(HTTP_TOKEN::get, "").trim(); }
    @SuppressWarnings("unchecked")
    public static List<String> licences() { return (List<String>) val(LICENCES::get, List.of("permisconduire")); }
}
