package ded.chunkgenthreading;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = "chunk_gen_threader", name = "ChunkGenThreading")
public class ChunkGenThreadingConfig {

    @Config.Comment("Master switch to enable/disable parallel chunk generation.")
    public static boolean enabled = true;

    @Config.Comment({
            "Thread count mode:",
            "  auto   = CPU cores - 2 (safe default, leaves cores for main thread + entity threading)",
            "  manual = Use the exact value from 'manualThreadCount'",
            "  max    = All CPU cores minus 1 (maximum throughput)"
    })
    public static String threadMode = "auto";

    @Config.Comment({
            "Number of worker threads when threadMode = manual.",
            "Recommended: 4-8. Set to your physical core count for best results."
    })
    @Config.RangeInt(min = 1, max = 32)
    public static int manualThreadCount = 6;

    @Config.Comment({
            "Look-ahead radius: how many chunks around the requested chunk to pre-generate in parallel.",
            "3 = 7x7 = 49 chunks submitted at once. With 6 workers this means 8 iterations to finish all.",
            "Higher = more chunks pre-generated before player arrives = better hit rate.",
            "Lower = less memory usage, less aggressive."
    })
    @Config.RangeInt(min = 1, max = 8)
    public static int lookAheadRadius = 3;

    @Config.Comment({
            "Maximum concurrent chunk generation futures in flight.",
            "Should be at least lookAheadRadius^2 * 4."
    })
    @Config.RangeInt(min = 16, max = 512)
    public static int maxQueuedChunks = 128;

    @Config.Comment("Enable debug logging: prints hit rate stats every 5 seconds.")
    public static boolean debugLogging = false;

    @Config.Comment({
            "Timeout in milliseconds to wait for a parallel chunk generation task.",
            "If a chunk takes longer than this, it is generated on the main thread (vanilla speed)."
    })
    @Config.RangeInt(min = 500, max = 30000)
    public static int chunkGenTimeoutMs = 5000;

    @Config.Comment("Cooldown in seconds after a timeout before parallel generation resumes.")
    @Config.RangeInt(min = 5, max = 120)
    public static int cooldownSeconds = 15;

    public static int getEffectiveThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        switch (threadMode.toLowerCase().trim()) {
            case "max":
                return Math.max(2, cores - 1);
            case "manual":
                return Math.max(1, Math.min(manualThreadCount, cores - 1));
            case "auto":
            default:
                return Math.max(2, cores - 2);
        }
    }

    @Mod.EventBusSubscriber(modid = "chunk_gen_threader")
    public static class ConfigSyncHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals("chunk_gen_threader")) {
                ConfigManager.sync("chunk_gen_threader", Config.Type.INSTANCE);
                ParallelChunkGenScheduler.reinitialize();
                System.out.println("[ChunkGenThreading] Config reloaded. Threads: " + getEffectiveThreadCount());
            }
        }
    }
}
