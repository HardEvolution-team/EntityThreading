package ded.otherthreading;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(modid = "other_threader", name = "OtherThreading", version = "1.0", dependencies = "after:entity_threader")
public class OtherThreadingMod {

    @Mod.Instance("other_threader")
    public static OtherThreadingMod instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ConfigManager.sync("other_threader", Config.Type.INSTANCE);
        System.out.println("[OtherThreading] Parallel Block/TileEntity ticking initialized.");
    }
}
