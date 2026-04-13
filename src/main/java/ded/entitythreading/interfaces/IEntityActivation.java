package ded.entitythreading.interfaces;

/**
 * Interface mixed into {@link net.minecraft.entity.Entity} to track
 * tick frequency based on distance from nearest player.
 */
public interface IEntityActivation {
    /**
     * Returns how often this entity should tick.
     * 1 = every tick, 2 = every 2nd tick, 4 = every 4th, 8 = every 8th.
     */
    int entitythreading$getTickInterval();

    void entitythreading$setTickInterval(int interval);
}
