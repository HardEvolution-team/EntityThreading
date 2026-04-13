package ded.entitythreading.schedule;

import ded.entitythreading.config.EntityThreadingConfig;
import ded.entitythreading.interfaces.IEntityActivation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.world.World;

import java.util.List;

/**
 * Determines tick frequency for each entity based on distance to the nearest player.
 *
 * <p>Distance tiers (configurable per entity type):
 * <ul>
 *   <li>0 – tier1 blocks: every tick (interval = 1)</li>
 *   <li>tier1 – tier2 blocks: every 2nd tick (interval = 2)</li>
 *   <li>tier2 – tier3 blocks: every 4th tick (interval = 4)</li>
 *   <li>tier3+ blocks: every 8th tick (interval = 8)</li>
 * </ul>
 *
 * <p>Entities that must always tick at full rate (players, projectiles, bosses, etc.)
 * are forced to interval = 1.
 */
public final class EntityActivationRange {

    /** Newly spawned entities get full tick rate for this many ticks. */
    private static final int GRACE_PERIOD_TICKS = 20;

    private EntityActivationRange() {}

    /**
     * Called once per world tick before entities are updated.
     * Computes and assigns tick interval for every loaded entity.
     */
    public static void activateEntities(World world) {
        if (!EntityThreadingConfig.entityActivationRange) {
            return;
        }

        List<EntityPlayer> players = world.playerEntities;
        List<Entity> entities = world.loadedEntityList;

        // Pre-compute squared tier boundaries for each entity category
        final int monsterT1Sq = square(EntityThreadingConfig.activationRangeMonstersTier1);
        final int monsterT2Sq = square(EntityThreadingConfig.activationRangeMonstersTier2);
        final int monsterT3Sq = square(EntityThreadingConfig.activationRangeMonstersTier3);

        final int animalT1Sq = square(EntityThreadingConfig.activationRangeAnimalsTier1);
        final int animalT2Sq = square(EntityThreadingConfig.activationRangeAnimalsTier2);
        final int animalT3Sq = square(EntityThreadingConfig.activationRangeAnimalsTier3);

        final int miscT1Sq = square(EntityThreadingConfig.activationRangeMiscTier1);
        final int miscT2Sq = square(EntityThreadingConfig.activationRangeMiscTier2);
        final int miscT3Sq = square(EntityThreadingConfig.activationRangeMiscTier3);

        for (int i = 0, size = entities.size(); i < size; i++) {
            Entity entity;
            try {
                entity = entities.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            if (entity == null) continue;

            IEntityActivation activation = (IEntityActivation) entity;

            // Entities that must always tick at full rate
            if (shouldAlwaysTick(entity)) {
                activation.entitythreading$setTickInterval(1);
                continue;
            }

            // Pick tier boundaries based on entity type
            int t1Sq, t2Sq, t3Sq;
            if (entity instanceof EntityMob) {
                t1Sq = monsterT1Sq;
                t2Sq = monsterT2Sq;
                t3Sq = monsterT3Sq;
            } else if (entity instanceof EntityAnimal) {
                t1Sq = animalT1Sq;
                t2Sq = animalT2Sq;
                t3Sq = animalT3Sq;
            } else {
                t1Sq = miscT1Sq;
                t2Sq = miscT2Sq;
                t3Sq = miscT3Sq;
            }

            double nearestDistSq = nearestPlayerDistanceSq(entity, players);
            int interval = computeInterval(nearestDistSq, t1Sq, t2Sq, t3Sq);
            activation.entitythreading$setTickInterval(interval);
        }
    }

    /**
     * Should be called from the entity tick hook to decide whether to skip this tick.
     *
     * @return true if the entity should be ticked this tick, false to skip
     */
    public static boolean shouldTickThisTick(Entity entity) {
        if (!EntityThreadingConfig.entityActivationRange) {
            return true;
        }
        IEntityActivation activation = (IEntityActivation) entity;
        int interval = activation.entitythreading$getTickInterval();
        if (interval <= 1) {
            return true;
        }
        // Use ticksExisted so each entity's phase is naturally staggered
        return (entity.ticksExisted % interval) == 0;
    }

    // ─── internals ───────────────────────────────────────────────────────

    /**
     * Returns the squared horizontal distance to the nearest non-spectator player,
     * or {@link Double#MAX_VALUE} if no player is found.
     */
    private static double nearestPlayerDistanceSq(Entity entity, List<EntityPlayer> players) {
        double minDistSq = Double.MAX_VALUE;
        for (int i = 0, size = players.size(); i < size; i++) {
            EntityPlayer player;
            try {
                player = players.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            if (player == null || player.isSpectator()) continue;

            double dx = entity.posX - player.posX;
            double dz = entity.posZ - player.posZ;
            double distSq = dx * dx + dz * dz;
            if (distSq < minDistSq) {
                minDistSq = distSq;
            }
        }
        return minDistSq;
    }

    /**
     * Maps squared distance to a tick interval using three tier boundaries.
     *
     * <pre>
     *   dist <= tier1  →  1  (every tick)
     *   dist <= tier2  →  2  (every 2nd tick)
     *   dist <= tier3  →  4  (every 4th tick)
     *   dist >  tier3  →  8  (every 8th tick)
     * </pre>
     */
    private static int computeInterval(double distSq, int t1Sq, int t2Sq, int t3Sq) {
        if (distSq <= t1Sq) return 1;
        if (distSq <= t2Sq) return 2;
        if (distSq <= t3Sq) return 4;
        return 8;
    }

    private static boolean shouldAlwaysTick(Entity entity) {
        if (entity instanceof EntityPlayer) return true;
        if (entity instanceof EntityArrow) return true;
        if (entity instanceof EntityThrowable) return true;
        if (entity instanceof EntityFireball) return true;
        if (entity instanceof EntityTNTPrimed) return true;
        if (entity instanceof EntityFallingBlock) return true;
        if (entity instanceof EntityMinecart) return true;
        if (entity instanceof EntityDragon) return true;
        if (entity instanceof EntityWither) return true;
        if (entity.isBeingRidden()) return true;
        if (entity.ticksExisted < GRACE_PERIOD_TICKS) return true;
        return false;
    }

    private static int square(int value) {
        return value * value;
    }
}
