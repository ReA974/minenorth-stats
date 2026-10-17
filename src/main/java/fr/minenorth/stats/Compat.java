package fr.minenorth.stats;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Liens facultatifs avec l'API MineNorth, par réflexion (aucune dépendance de compilation) :
 * sans l'API ou sans un des mods, la valeur neutre est renvoyée et le mod continue de fonctionner.
 */
public final class Compat {
    private Compat() {}

    /** Ligne d'historique bancaire (BankTx). */
    public record Tx(long time, String category, long cents) {}

    private static boolean tried;
    private static Class<?> mn;
    private static Method displayName, bank, companies, police, secours, licences;
    private static Method hasAccount, balance, history, activeCompanies, memberIds, polOnDuty, polIs, secOnDuty, secIs, licHas;

    private static boolean init() {
        if (tried) return mn != null;
        tried = true;
        try {
            if (!ModList.get().isLoaded("minenorthapi")) return false;
            Class<?> m = Class.forName("fr.minenorth.api.MineNorth");
            displayName = m.getMethod("displayName", MinecraftServer.class, UUID.class);
            bank = m.getMethod("bank");
            companies = m.getMethod("companies");
            police = m.getMethod("police");
            secours = m.getMethod("secours");
            licences = m.getMethod("licences");
            Class<?> b = Class.forName("fr.minenorth.api.BankService");
            hasAccount = b.getMethod("hasAccount", MinecraftServer.class, UUID.class);
            balance = b.getMethod("balance", MinecraftServer.class, UUID.class);
            history = b.getMethod("history", MinecraftServer.class, UUID.class, int.class);
            Class<?> c = Class.forName("fr.minenorth.api.CompanyService");
            activeCompanies = c.getMethod("activeCompanies", MinecraftServer.class);
            memberIds = c.getMethod("memberIds", MinecraftServer.class, int.class);
            Class<?> p = Class.forName("fr.minenorth.api.PoliceService");
            polOnDuty = p.getMethod("onDuty", MinecraftServer.class, UUID.class);
            polIs = p.getMethod("isPolice", MinecraftServer.class, UUID.class);
            Class<?> s = Class.forName("fr.minenorth.api.SecoursService");
            secOnDuty = s.getMethod("onDuty", MinecraftServer.class, UUID.class);
            secIs = s.getMethod("isSecours", MinecraftServer.class, UUID.class);
            licHas = Class.forName("fr.minenorth.api.LicenceService").getMethod("has", MinecraftServer.class, UUID.class, String.class);
            mn = m;
            MineNorthStats.LOG.info("API MineNorth detectee : soldes, metiers, services et permis seront releves.");
            return true;
        } catch (Throwable t) {
            MineNorthStats.LOG.info("API MineNorth indisponible ({}) : seules les statistiques de base sont collectees.", t.toString());
            mn = null;
            return false;
        }
    }

    private static Object call(Method owner, Method m, Object... args) throws Exception {
        return m.invoke(owner.invoke(null), args);
    }

    /** Nom RP « Prénom Nom », ou chaîne vide s'il n'y a pas d'identité (ou pas d'API). */
    public static String rpName(MinecraftServer s, UUID id, String pseudo) {
        if (!init()) return "";
        try {
            Object o = displayName.invoke(null, s, id);
            return o instanceof String str && !str.isBlank() && !str.equals(pseudo) && !str.equals("Inconnu") ? str : "";
        } catch (Throwable t) { return ""; }
    }

    /** Solde en centimes, -1 s'il n'y a pas de compte (ou pas d'API). */
    public static long balance(MinecraftServer s, UUID id) {
        if (!init()) return -1;
        try {
            if (!(boolean) call(bank, hasAccount, s, id)) return -1;
            return (long) call(bank, balance, s, id);
        } catch (Throwable t) { return -1; }
    }

    /** Dernières opérations du compte (plus récent d'abord). */
    public static List<Tx> history(MinecraftServer s, UUID id, int limit) {
        List<Tx> out = new ArrayList<>();
        if (!init()) return out;
        try {
            Object list = call(bank, history, s, id, limit);
            if (list instanceof Collection<?> col) {
                for (Object o : col) {
                    Class<?> k = o.getClass();
                    out.add(new Tx((long) k.getMethod("time").invoke(o), String.valueOf(k.getMethod("category").invoke(o)),
                            (long) k.getMethod("cents").invoke(o)));
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Entreprises actives : nom -> membres (patron et employés). */
    public static java.util.Map<String, List<UUID>> companies(MinecraftServer s) {
        java.util.Map<String, List<UUID>> out = new java.util.LinkedHashMap<>();
        if (!init()) return out;
        try {
            Object svc = companies.invoke(null);
            Object list = activeCompanies.invoke(svc, s);
            if (list instanceof Collection<?> col) {
                for (Object o : col) {
                    String[] parts = String.valueOf(o).split("\\|", 2);
                    if (parts.length < 2) continue;
                    int id = Integer.parseInt(parts[0].trim());
                    @SuppressWarnings("unchecked") List<UUID> ms = (List<UUID>) memberIds.invoke(svc, s, id);
                    out.put(parts[1], ms == null ? List.of() : ms);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static boolean isPolice(MinecraftServer s, UUID id) { return flag(police, polIs, s, id); }
    public static boolean policeOnDuty(MinecraftServer s, UUID id) { return flag(police, polOnDuty, s, id); }
    public static boolean isSecours(MinecraftServer s, UUID id) { return flag(secours, secIs, s, id); }
    public static boolean secoursOnDuty(MinecraftServer s, UUID id) { return flag(secours, secOnDuty, s, id); }

    private static boolean flag(Method owner, Method m, MinecraftServer s, UUID id) {
        if (!init()) return false;
        try { return (boolean) call(owner, m, s, id); } catch (Throwable t) { return false; }
    }

    public static boolean hasLicence(MinecraftServer s, UUID id, String licence) {
        if (!init()) return false;
        try { return (boolean) call(licences, licHas, s, id, licence); } catch (Throwable t) { return false; }
    }
}
