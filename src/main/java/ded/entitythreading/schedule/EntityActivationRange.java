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

public final class EntityActivationRange {

    public static void activateEntities(World world) {
        if (!EntityThreadingConfig.entityActivationRange) return;

        List<EntityPlayer> players = world.playerEntities;
        List<Entity> entities = world.loadedEntityList;

        int monsterRangeSq = EntityThreadingConfig.activationRangeMonsters * EntityThreadingConfig.activationRangeMonsters;
        int animalRangeSq = EntityThreadingConfig.activationRangeAnimals * EntityThreadingConfig.activationRangeAnimals;
        int miscRangeSq = EntityThreadingConfig.activationRangeMisc * EntityThreadingConfig.activationRangeMisc;

        for (Entity value : entities) {
            Entity entity;
            try {
                entity = value;
            } catch (Exception e) {
                continue;
            }
            if (entity == null) continue;

            if (shouldAlwaysTick(entity)) {
                ((IEntityActivation) entity).entitythreading$setActive(true);
                continue;
            }

            int rangeSq;
            if (entity instanceof EntityMob) {
                rangeSq = monsterRangeSq;
            } else if (entity instanceof EntityAnimal) {
                rangeSq = animalRangeSq;
            } else {
                rangeSq = miscRangeSq;
            }

            boolean active = false;
            for (EntityPlayer entityPlayer : players) {
                EntityPlayer player;
                try {
                    player = entityPlayer;
                } catch (Exception e) {
                    continue;
                }
                if (player == null || player.isSpectator()) continue;
                double dx = entity.posX - player.posX;
                double dz = entity.posZ - player.posZ;
                if (dx * dx + dz * dz <= rangeSq) {
                    active = true;
                    break;
                }
            }

            ((IEntityActivation) entity).entitythreading$setActive(active);
        }
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
        return entity.ticksExisted < 20;
    }
}
