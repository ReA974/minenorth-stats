package fr.minenorth.stats;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

/** /stats site | info | jeton | oublier &lt;joueur&gt; (opérateurs niveau 3). */
public final class StatsCommand {
    private StatsCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("stats").requires(s -> s.hasPermission(3))
                .then(Commands.literal("site").executes(c -> {
                    var dir = SiteBuilder.generate(c.getSource().getServer());
                    c.getSource().sendSuccess(() -> Component.literal("§aSite genere dans " + dir + " (index.html). Ouvrez-le dans un navigateur."
                            + (HttpSite.running() ? " §7Aussi sur le port " + StatsConfig.httpPort() + " (/stats jeton)." : "")), true);
                    return 1;
                }))
                .then(Commands.literal("info").executes(c -> {
                    long online = c.getSource().getServer().getPlayerList().getPlayerCount();
                    c.getSource().sendSuccess(() -> Component.literal("§eStats : " + Store.data.players.size() + " joueurs suivis, "
                            + Store.data.series.size() + " relevés, conservation " + StatsConfig.keepDays() + " j, affichage "
                            + (StatsConfig.anonymize() ? "anonyme" : StatsConfig.display()) + ", " + online + " en ligne. Web : "
                            + (HttpSite.running() ? "actif" : "désactivé")), false);
                    return 1;
                }))
                .then(Commands.literal("jeton").executes(c -> {
                    if (!HttpSite.running()) {
                        c.getSource().sendFailure(Component.literal("Le serveur web est désactivé (config minenorth_stats.toml, [http] actif = true)."));
                        return 0;
                    }
                    c.getSource().sendSuccess(() -> Component.literal("§eAdresse : http://" + StatsConfig.httpBind() + ":" + StatsConfig.httpPort()
                            + "/?jeton=" + HttpSite.token() + " §7(ne le partagez pas)"), false);
                    return 1;
                }))
                .then(Commands.literal("oublier").then(Commands.argument("joueur", StringArgumentType.word())
                        .suggests((c, b) -> {
                            Store.data.players.values().forEach(r -> { if (!r.name.isBlank()) b.suggest(r.name); });
                            return b.buildFuture();
                        })
                        .executes(c -> {
                            String q = StringArgumentType.getString(c, "joueur");
                            String key = find(c.getSource(), q);
                            if (key == null) {
                                c.getSource().sendFailure(Component.literal("Joueur inconnu dans les statistiques : " + q));
                                return 0;
                            }
                            Data.PlayerRec r = Store.data.players.remove(key);
                            Store.save();
                            String n = r != null && !r.name.isBlank() ? r.name : key;
                            c.getSource().sendSuccess(() -> Component.literal("§aToutes les statistiques de " + n + " ont été effacées. "
                                    + "§7(Il sera de nouveau suivi à sa prochaine connexion ; régénérez le site avec /stats site.)"), true);
                            return 1;
                        }))));
    }

    /** Retrouve un joueur suivi par UUID, pseudo ou nom RP (sans tenir compte de la casse). */
    private static String find(CommandSourceStack src, String q) {
        try {
            String u = UUID.fromString(q).toString();
            if (Store.data.players.containsKey(u)) return u;
        } catch (IllegalArgumentException ignored) {}
        ServerPlayer online = src.getServer().getPlayerList().getPlayerByName(q);
        if (online != null && Store.data.players.containsKey(online.getUUID().toString())) return online.getUUID().toString();
        for (Map.Entry<String, Data.PlayerRec> e : Store.data.players.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(q)) return e.getKey();
        }
        return null;
    }
}
