package ded.entitythreading.interfaces;

import net.minecraft.entity.Entity;

/**
 * Interface mixed into {@link net.minecraft.world.World} to expose
 * chunk position updates and direct entity ticking for the scheduler.
 */
public interface IMixinWorld {
    void entitythreading$updateChunkPos(Entity entity);
    void entitythreading$tickEntityDirectly(Entity entity);
}
