package ded.entitythreading.mixin;

import ded.entitythreading.interfaces.IEntityActivation;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Entity.class)
public abstract class EntityMixin implements IEntityActivation {

    /**
     * Tick interval based on distance to nearest player.
     * <ul>
     *   <li>1 = every tick (full rate, within tier1 range)</li>
     *   <li>2 = every 2nd tick (tier1–tier2 range)</li>
     *   <li>4 = every 4th tick (tier2–tier3 range)</li>
     *   <li>8 = every 8th tick (beyond tier3 range)</li>
     * </ul>
     * Volatile because it's written by the main thread during
     * {@code EntityActivationRange.activateEntities()} and read
     * by worker threads during parallel ticking.
     */
    @Unique
    private volatile int entitythreading$tickInterval = 1;

    @Override
    public int entitythreading$getTickInterval() {
        return entitythreading$tickInterval;
    }

    @Override
    public void entitythreading$setTickInterval(int interval) {
        this.entitythreading$tickInterval = interval;
    }
}
