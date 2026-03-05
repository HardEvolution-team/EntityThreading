package ded.entitythreading;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(modid = "entity_threader", name = "EntityThreading", version = "1.0", acceptableRemoteVersions = "*", updateJSON = "")
public class EntityThreadingMod {

    @Mod.Instance("entity_threader")
    public static EntityThreadingMod instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // This ensures the mod metadata and config are correctly registered by Forge
        System.out.println("[EntityThreading] Mod initialized. Config should now be visible in GUI.");
    }
}
