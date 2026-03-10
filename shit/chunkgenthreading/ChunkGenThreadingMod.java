package ded.chunkgenthreading;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

@Mod(modid = "chunk_gen_threader", name = "ChunkGenThreading", version = "1.0", acceptableRemoteVersions = "*")
public class ChunkGenThreadingMod {

    @Mod.Instance("chunk_gen_threader")
    public static ChunkGenThreadingMod instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ConfigManager.sync("chunk_gen_threader", Config.Type.INSTANCE);
        System.out.println("[ChunkGenThreading] v1.0 initialized — " +
                ChunkGenThreadingConfig.getEffectiveThreadCount() + " worker threads, " +
                "look-ahead radius " + ChunkGenThreadingConfig.lookAheadRadius);
    }

    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        ParallelChunkGenScheduler.shutdown();
    }
}
