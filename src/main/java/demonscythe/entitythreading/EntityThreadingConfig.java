package demonscythe.entitythreading;

import demonscythe.entitythreading.schedule.EntityTickScheduler;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = "entity_threader", name = "EntityThreading")
public class EntityThreadingConfig {

    @Config.Comment("Master switch to enable/disable entity threading.")
    public static boolean enabled = true;

    @Config.Comment({
        "Thread count mode:",
        "  auto   = Use all available CPU cores minus 2 (minimum 4)",
        "  manual = Use the exact value from 'manualThreadCount'",
        "  max    = Use ALL available CPU cores for maximum throughput"
    })
    public static String threadMode = "auto";

    @Config.Comment({
        "Number of worker threads when threadMode = manual.",
        "Ignored in auto/max modes.",
        "For 10k+ entities, use 8-16 threads."
    })
    @Config.RangeInt(min = 1, max = 128)
    public static int manualThreadCount = 8;

    @Config.Comment({
        "How to distribute entities across threads:",
        "  balanced = Split all entities evenly across all threads (best for 10k+)",
        "  region   = Group by chunk region (better spatial locality, less parallelism)"
    })
    public static String distributionMode = "balanced";

    @Config.Comment({
        "Region size when distributionMode = region (in chunks).",
        "Entities in NxN chunk areas are grouped together.",
        "Default: 2"
    })
    @Config.RangeInt(min = 1, max = 8)
    public static int regionSize = 2;

    @Config.Comment({
        "Max ticks an empty entity group stays cached before being evicted.",
        "Only used in region mode. Default: 600"
    })
    @Config.RangeInt(min = 20, max = 6000)
    public static int groupEvictionTicks = 600;

    @Config.Comment({
        "Entity classes to exclude from threaded ticking.",
        "Full class names. Example: \"net.minecraft.entity.boss.EntityDragon\""
    })
    public static String[] blacklistedEntities = new String[]{};

    @Config.Comment("Enable debug logging (prints entity counts, thread stats each tick).")
    public static boolean debugLogging = false;

    /**
     * Compute the actual thread count based on the current threadMode.
     */
    public static int getEffectiveThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        switch (threadMode.toLowerCase()) {
            case "max":
                return Math.max(2, cores);
            case "manual":
                return Math.max(1, manualThreadCount);
            case "auto":
            default:
                return Math.max(4, cores - 2);
        }
    }

    @Mod.EventBusSubscriber(modid = "entity_threader")
    public static class ConfigSyncHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals("entity_threader")) {
                ConfigManager.sync("entity_threader", Config.Type.INSTANCE);
                // Hot-reload thread pool and blacklist
                EntityTickScheduler.reinitialize();
            }
        }
    }
}
