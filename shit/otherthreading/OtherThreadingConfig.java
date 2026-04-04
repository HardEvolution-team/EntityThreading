package ded.otherthreading;

import ded.otherthreading.schedule.BlockTickScheduler;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = "other_threader", name = "OtherThreading")
public class OtherThreadingConfig {

    @Config.Comment("Master switch to enable/disable parallel block ticking.")
    public static boolean blockThreadingEnabled = true;

    @Config.Comment("Master switch to enable/disable parallel TileEntity ticking.")
    public static boolean tileEntityThreadingEnabled = true;

    @Config.Comment({
            "Thread count for block/TE worker threads.",
            "Higher = better for huge redstone/tech builds, but more CPU overhead."
    })
    @Config.RangeInt(min = 1, max = 16)
    public static int threadCount = 6;

    @Config.Comment("Enable debug logging for OtherThreading.")
    public static boolean debugLogging = false;

    @Config.Comment("Minimum number of block ticks to enable parallel execution.")
    @Config.RangeInt(min = 10, max = 1000)
    public static int minBlockTicksForThreading = 50;

    @Config.Comment("Minimum number of TileEntities to enable parallel execution.")
    @Config.RangeInt(min = 10, max = 1000)
    public static int minTileEntitiesForThreading = 50;

    @Mod.EventBusSubscriber(modid = "other_threader")
    public static class ConfigSyncHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals("other_threader")) {
                ConfigManager.sync("other_threader", Config.Type.INSTANCE);
                BlockTickScheduler.reinitialize();
            }
        }
    }
}
