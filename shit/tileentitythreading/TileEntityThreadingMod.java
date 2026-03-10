package ded.tileentitythreading;

import ded.tileentitythreading.schedule.TileEntityTickScheduler;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod(modid = "tile_entity_threader", name = "TileEntityThreading", version = "2.0", acceptableRemoteVersions = "*")
public class TileEntityThreadingMod {

    @Mod.Instance("tile_entity_threader")
    public static TileEntityThreadingMod instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ConfigManager.sync("tile_entity_threader", Config.Type.INSTANCE);
        System.out.println("[TileEntityThreading] v2.0 initialized — ForkJoinPool work-stealing scheduler");
    }

    /**
     * Listens for chunk load/unload events to invalidate the cached chunk snapshot.
     * This allows the snapshot to be rebuilt only when needed, not every tick.
     */
    @Mod.EventBusSubscriber(modid = "tile_entity_threader")
    public static class ChunkEventHandler {

        @SubscribeEvent
        public static void onChunkLoad(ChunkEvent.Load event) {
            TileEntityTickScheduler.markSnapshotDirty();
        }

        @SubscribeEvent
        public static void onChunkUnload(ChunkEvent.Unload event) {
            TileEntityTickScheduler.markSnapshotDirty();
        }
    }
}
