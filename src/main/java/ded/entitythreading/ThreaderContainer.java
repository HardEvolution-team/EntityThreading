package ded.entitythreading;

import com.google.common.eventbus.EventBus;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.LoadController;
import net.minecraftforge.fml.common.ModMetadata;

public class ThreaderContainer extends DummyModContainer {
    public ThreaderContainer() {
        super(new ModMetadata());
        ModMetadata metadata = getMetadata();
        metadata.modId = "entity_threader";
        metadata.name = "Mob_Entity_Threader";
        metadata.description = "Parallel entity ticking for Minecraft 1.12.2";
        metadata.version = "0.1";
    }

    @Override
    public boolean registerBus(EventBus bus, LoadController controller) {
        bus.register(this);
        return true;
    }
}
