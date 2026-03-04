package demonscythe.entitythreading.transform.mixin;

import demonscythe.entitythreading.transform.IMixinWorld;

import demonscythe.entitythreading.schedule.DeferredActionQueue;
import demonscythe.entitythreading.schedule.EntityTickScheduler;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts unsafe World methods called from entity worker threads.
 * Defers them to the main thread via DeferredActionQueue.
 */
@Mixin(World.class)
public abstract class MixinWorld implements IMixinWorld {

    @Shadow
    public abstract Chunk getChunk(int chunkX, int chunkZ);

    @Shadow
    public abstract boolean isChunkLoaded(int x, int z, boolean allowEmpty);

    /**
     * Helper to move an entity between chunk lists when it crosses a boundary.
     * Mirrors the inlined logic in World.updateEntityWithOptionalForce.
     */
    @Override
    public void entitythreading$updateChunkPos(Entity entity) {
        int i = MathHelper.floor(entity.posX / 16.0D);
        int j = MathHelper.floor(entity.posY / 16.0D);
        int k = MathHelper.floor(entity.posZ / 16.0D);

        if (!entity.addedToChunk || entity.chunkCoordX != i || entity.chunkCoordY != j || entity.chunkCoordZ != k) {

            // Remove from old chunk
            if (entity.addedToChunk && this.isChunkLoaded(entity.chunkCoordX, entity.chunkCoordZ, true)) {
                this.getChunk(entity.chunkCoordX, entity.chunkCoordZ).removeEntityAtIndex(entity, entity.chunkCoordY);
            }

            // Update flags and add to new chunk
            if (!entity.setPositionNonDirty() && !this.isChunkLoaded(i, k, true)) {
                entity.addedToChunk = false;
            } else {
                this.getChunk(i, k).addEntity(entity);
            }
        }
    }

    // === Block State Changes (Endermen, FallingBlock, Creeper explosions) ===
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z", at = @At("HEAD"), cancellable = true)
    private void onSetBlockState(BlockPos pos, IBlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos immutable = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((World) (Object) this).setBlockState(immutable, newState, flags));
            cir.setReturnValue(true);
        }
    }

    // === Block Tick Scheduling (alias) ===
    @Inject(method = "scheduleBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleBlockUpdate(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos immutable = pos.toImmutable();
            DeferredActionQueue
                    .enqueue(() -> ((World) (Object) this).scheduleBlockUpdate(immutable, blockIn, delay, priority));
            ci.cancel();
        }
    }

    // === Block Tick Scheduling (alias 2) ===
    @Inject(method = "scheduleUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleUpdate(BlockPos pos, Block blockIn, int delay, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            World world = (World) (Object) this;
            BlockPos immutable = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> world.scheduleUpdate(immutable, blockIn, delay));
            ci.cancel();
        }
    }

    // === Block Tick Scheduling (alias 3) ===
    @Inject(method = "updateBlockTick", at = @At("HEAD"), cancellable = true)
    private void onUpdateBlockTick(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            World world = (World) (Object) this;
            BlockPos immutable = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> world.updateBlockTick(immutable, blockIn, delay, priority));
            ci.cancel();
        }
    }

    // === Entity Spawning (arrows, items, potions, split slimes) ===
    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void onSpawnEntity(Entity entityIn, CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            World world = (World) (Object) this;
            DeferredActionQueue.enqueue(() -> world.spawnEntity(entityIn));
            cir.setReturnValue(true);
        }
    }

    // === Entity Removal (death, pickup, despawn) ===
    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void onRemoveEntity(Entity entityIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            World world = (World) (Object) this;
            DeferredActionQueue.enqueue(() -> world.removeEntity(entityIn));
            ci.cancel();
        }
    }

    // === Remove Entity from World ===
    @Inject(method = "removeEntityDangerously", at = @At("HEAD"), cancellable = true)
    private void onRemoveEntityDangerously(Entity entityIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            World world = (World) (Object) this;
            DeferredActionQueue.enqueue(() -> world.removeEntityDangerously(entityIn));
            ci.cancel();
        }
    }

    // === Sound Playback (mobs making sounds during tick) ===
    @Inject(method = "playSound(Lnet/minecraft/entity/player/EntityPlayer;DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FF)V", at = @At("HEAD"), cancellable = true)
    private void onPlaySound(net.minecraft.entity.player.EntityPlayer player, double x, double y, double z,
            SoundEvent soundIn, SoundCategory category, float volume, float pitch, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            World world = (World) (Object) this;
            DeferredActionQueue.enqueue(() -> world.playSound(player, x, y, z, soundIn, category, volume, pitch));
            ci.cancel();
        }
    }

    // === LOW-LEVEL ENTITY LIST MODIFICATIONS (Prevents CME in getEntities) ===
    @Inject(method = "onEntityAdded", at = @At("HEAD"), cancellable = true)
    private void onOnEntityAdded(Entity entityIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            World world = (World) (Object) this;
            DeferredActionQueue.enqueue(() -> world.onEntityAdded(entityIn));
            ci.cancel();
        }
    }

    @Inject(method = "onEntityRemoved", at = @At("HEAD"), cancellable = true)
    private void onOnEntityRemoved(Entity entityIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> ((World) (Object) this).onEntityRemoved(entityIn));
            ci.cancel();
        }
    }
}
