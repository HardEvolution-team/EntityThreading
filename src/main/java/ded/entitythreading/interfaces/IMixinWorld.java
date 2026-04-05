package ded.entitythreading.interfaces;

import net.minecraft.entity.Entity;

public interface IMixinWorld {
    void entitythreading$updateChunkPos(Entity entity);

    void entitythreading$tickEntityDirectly(Entity entity);
}
