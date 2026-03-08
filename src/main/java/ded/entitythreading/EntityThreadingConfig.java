package ded.entitythreading;

import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = "entity_threader", name = "EntityThreading")
public class EntityThreadingConfig {

    @Config.Comment("Master switch to enable/disable parallel entity ticking.")
    public static boolean enabled = true;

    @Config.Comment({
            "Thread count mode:",
            "  auto   = CPU cores / 2 (minimum 2, maximum 4)",
            "  manual = Use the exact value from 'manualThreadCount'",
            "  max    = Use all available CPU cores minus 1"
    })
    public static String threadMode = "auto";

    @Config.Comment({
            "Number of worker threads when threadMode = manual.",
            "Recommended: 2-4 for most setups."
    })
    @Config.RangeInt(min = 1, max = 16)
    public static int manualThreadCount = 3;

    @Config.Comment({
            "Entity classes to exclude from parallel ticking.",
            "Use full class names. These entities will always tick on the main thread.",
            "EntityItem is blacklisted by default because Quark hooks it with a WeakHashMap (not thread-safe).",
            "Example: \"net.minecraft.entity.boss.EntityDragon\""
    })
    public static String[] blacklistedEntities = new String[] {
            "net.minecraft.entity.item.EntityItem",
            "net.minecraft.entity.item.EntityXPOrb"
    };

    @Config.Comment("Enable debug logging (entity counts, thread stats per tick).")
    public static boolean debugLogging = false;

    @Config.Comment("Enable asynchronous pathfinding. EXPERIMENTAL — may cause AI glitches.")
    public static boolean asyncPathfinding = false;

    @Config.Comment("Minimum number of entities to enable parallel ticking. Below this, main thread is faster.")
    @Config.RangeInt(min = 10, max = 1000)
    public static int minEntitiesForThreading = 100;

    @Config.Comment("Minimum batch size per worker thread. Too small = thread overhead outweighs benefits.")
    @Config.RangeInt(min = 10, max = 500)
    public static int minBatchSize = 50;

    public static int getEffectiveThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        switch (threadMode.toLowerCase()) {
            case "max":
                return Math.max(2, cores - 1);
            case "manual":
                return Math.max(1, Math.min(manualThreadCount, cores));
            case "auto":
            default:
                return Math.max(2, Math.min(cores / 2, 4));
        }
    }

    @Mod.EventBusSubscriber(modid = "entity_threader")
    public static class ConfigSyncHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals("entity_threader")) {
                ConfigManager.sync("entity_threader", Config.Type.INSTANCE);
                EntityTickScheduler.reinitialize();
            }
        }
    }
}
