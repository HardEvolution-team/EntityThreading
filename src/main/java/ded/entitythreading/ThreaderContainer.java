
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
        metadata.description = "Adds hooks and scheduling to thread groups of mob entities";
        metadata.version = "0.1";
    }

    @Override
    public boolean registerBus(EventBus bus, LoadController controller) {
        bus.register(this);
        return true;
    }
}
