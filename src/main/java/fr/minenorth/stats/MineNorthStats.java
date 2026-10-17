package fr.minenorth.stats;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/** MineNorth Stats : collecte légère des statistiques des joueurs et site web de tableaux de bord (côté serveur uniquement). */
@Mod(MineNorthStats.MOD_ID)
public class MineNorthStats {
    public static final String MOD_ID = "minenorthstats";
    public static final Logger LOG = LogUtils.getLogger();

    public MineNorthStats() {
        // Mod serveur : un client sans lui peut se connecter, et le mod ne fait rien côté client.
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> IExtensionPoint.DisplayTest.IGNORESERVERONLY, (a, b) -> true));
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) return;
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, StatsConfig.SPEC, "minenorth_stats.toml");
        MinecraftForge.EVENT_BUS.register(new Collector());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        Store.load(e.getServer());
        HttpSite.start();
        SiteBuilder.generate(e.getServer());
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        Collector.closeAll();
        Store.saveNow();
        HttpSite.stop();
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        StatsCommand.register(e.getDispatcher());
    }
}
