package ded.tileentitythreading;

import ded.tileentitythreading.schedule.TileEntityTickScheduler;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = "tile_entity_threader", name = "TileEntityThreading")
public class TileEntityThreadingConfig {

    @Config.Comment("Master switch to enable/disable parallel tile entity ticking.")
    public static boolean enabled = true;

    @Config.Comment({
            "Thread count mode:",
            "  auto   = CPU cores - 2 (minimum 4) — optimized for large tile entity counts",
            "  manual = Use the exact value from 'manualThreadCount'",
            "  max    = Use all available CPU cores minus 1"
    })
    public static String threadMode = "auto";

    @Config.Comment({
            "Number of worker threads when threadMode = manual.",
            "For 60,000+ tile entities, use 8-16 threads.",
            "Recommended: match your physical core count."
    })
    @Config.RangeInt(min = 1, max = 32)
    public static int manualThreadCount = 8;

    @Config.Comment({
            "TileEntity classes to exclude from parallel ticking.",
            "Use full class names. These tile entities will always tick on the main thread.",
            "Example: \"net.minecraft.tileentity.TileEntityPiston\""
    })
    public static String[] blacklistedTileEntities = new String[] {};

    @Config.Comment("Enable debug logging (aggregated tile entity stats every 5 seconds).")
    public static boolean debugLogging = true;

    @Config.Comment("Minimum number of tickable tile entities to enable parallel ticking. Below this, main thread is faster.")
    @Config.RangeInt(min = 1, max = 500)
    public static int minTilesForThreading = 10;

    @Config.Comment("Minimum batch size per ForkJoinPool task split. Too small = fork overhead outweighs benefits.")
    @Config.RangeInt(min = 4, max = 500)
    public static int minBatchSize = 16;

    public static int getEffectiveThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        switch (threadMode.toLowerCase()) {
            case "max":
                return Math.max(2, cores - 1);
            case "manual":
                return Math.max(1, Math.min(manualThreadCount, cores));
            case "auto":
            default:
                // For 60k+ tile entities, we need more threads
                // auto = cores - 2, minimum 4
                return Math.max(4, cores - 2);
        }
    }

    @Mod.EventBusSubscriber(modid = "tile_entity_threader")
    public static class ConfigSyncHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals("tile_entity_threader")) {
                ConfigManager.sync("tile_entity_threader", Config.Type.INSTANCE);
                TileEntityTickScheduler.reinitialize();
            }
        }
    }
}
