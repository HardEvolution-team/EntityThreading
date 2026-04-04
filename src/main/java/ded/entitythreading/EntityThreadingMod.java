package ded.entitythreading;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(modid = "entity_threader", name = "EntityThreading", version = Tags.VERSION, acceptableRemoteVersions = "*")
public class EntityThreadingMod {

    @Mod.Instance("entity_threader")
    public static EntityThreadingMod instance;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        System.out.println("[EntityThreading] Mod initialized.");
    }
}
