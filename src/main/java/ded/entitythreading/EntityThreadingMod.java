package ded.entitythreading;

import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION)
@Mod.EventBusSubscriber
public final class EntityThreadingMod {

    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_NAME);

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (Reference.MOD_ID.equals(event.getModID())) {
            ConfigManager.sync(Reference.MOD_ID, Config.Type.INSTANCE);
            EntityTickScheduler.reinitialize();
            LOGGER.info("Configuration reloaded (virtual-thread mode active, no pool to reinitialize).");
        }
    }
}
