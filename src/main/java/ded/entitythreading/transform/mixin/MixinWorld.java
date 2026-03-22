package ded.entitythreading.transform.mixin;

import ded.entitythreading.schedule.DeferredActionQueue;
import ded.entitythreading.schedule.EntityTickScheduler;
import ded.entitythreading.transform.IMixinWorld;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts thread-unsafe World methods called from entity worker threads.
 * Defers them to main thread via DeferredActionQueue.
 *
 * ONLY intercepts when called from a worker thread (isEntityThread()).
 * Main thread calls pass through unmodified — zero overhead.
 */
@Mixin(World.class)
public abstract class MixinWorld implements IMixinWorld {

    @Shadow
    public abstract Chunk getChunk(int chunkX, int chunkZ);

    @Shadow
    public abstract void updateEntity(Entity ent);

    @Shadow
    public abstract boolean isChunkLoaded(int x, int z, boolean allowEmpty);

    // === IMixinWorld implementations ===

    @Override
    public void entitythreading$updateChunkPos(Entity entity) {
        int i = MathHelper.floor(entity.posX / 16.0D);
        int j = MathHelper.floor(entity.posY / 16.0D);
        int k = MathHelper.floor(entity.posZ / 16.0D);

        if (!entity.addedToChunk || entity.chunkCoordX != i || entity.chunkCoordY != j || entity.chunkCoordZ != k) {
            if (entity.addedToChunk && this.isChunkLoaded(entity.chunkCoordX, entity.chunkCoordZ, true)) {
                this.getChunk(entity.chunkCoordX, entity.chunkCoordZ).removeEntityAtIndex(entity, entity.chunkCoordY);
            }
            if (!entity.setPositionNonDirty() && !this.isChunkLoaded(i, k, true)) {
                entity.addedToChunk = false;
            } else {
                this.getChunk(i, k).addEntity(entity);
            }
        }
    }

    @Override
    public void entitythreading$tickEntityDirectly(Entity entity) {
        this.updateEntity(entity);
    }

    // === DEFERRED INTERCEPTORS — only active on worker threads ===

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z", at = @At("HEAD"), cancellable = true)
    private void onSetBlockState(BlockPos pos, IBlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((World) (Object) this).setBlockState(p, newState, flags));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void onSpawnEntity(Entity entityIn, CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> {
                try {
                    ((World) (Object) this).spawnEntity(entityIn);
                } catch (Exception e) {
                    // "Entity is already tracked" or similar — safe to ignore
                }
            });
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void onRemoveEntity(Entity entityIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> {
                try {
                    ((World) (Object) this).removeEntity(entityIn);
                } catch (Exception e) {
                    // Entity might already be removed — safe to ignore
                }
            });
            ci.cancel();
        }
    }

    @Inject(method = "removeEntityDangerously", at = @At("HEAD"), cancellable = true)
    private void onRemoveEntityDangerously(Entity entityIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> {
                try {
                    ((World) (Object) this).removeEntityDangerously(entityIn);
                } catch (Exception e) {
                    // Entity might already be removed — safe to ignore
                }
            });
            ci.cancel();
        }
    }

    @Inject(method = "playSound(Lnet/minecraft/entity/player/EntityPlayer;DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FF)V", at = @At("HEAD"), cancellable = true)
    private void onPlaySound(net.minecraft.entity.player.EntityPlayer player, double x, double y, double z,
            SoundEvent soundIn, SoundCategory category, float volume, float pitch, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(
                    () -> ((World) (Object) this).playSound(player, x, y, z, soundIn, category, volume, pitch));
            ci.cancel();
        }
    }

    @Inject(method = "notifyNeighborsOfStateChange", at = @At("HEAD"), cancellable = true)
    private void onNotifyNeighbors(BlockPos pos, Block blockType, boolean updateObservers, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue
                    .enqueue(() -> ((World) (Object) this).notifyNeighborsOfStateChange(p, blockType, updateObservers));
            ci.cancel();
        }
    }

    @Inject(method = "checkLight", at = @At("HEAD"), cancellable = true)
    private void onCheckLight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((World) (Object) this).checkLight(p));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "checkLightFor", at = @At("HEAD"), cancellable = true)
    private void onCheckLightFor(EnumSkyBlock lightType, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((World) (Object) this).checkLightFor(lightType, p));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setTileEntity", at = @At("HEAD"), cancellable = true)
    private void onSetTileEntity(BlockPos pos, TileEntity te, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((World) (Object) this).setTileEntity(p, te));
            ci.cancel();
        }
    }

    @Inject(method = "removeTileEntity", at = @At("HEAD"), cancellable = true)
    private void onRemoveTileEntity(BlockPos pos, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((World) (Object) this).removeTileEntity(p));
            ci.cancel();
        }
    }

    /**
     * On entity worker threads, ALWAYS use the pre-built chunk snapshot.
     * NEVER access ChunkProvider from worker threads — it uses Long2ObjectOpenHashMap
     * which is not thread-safe and causes deadlocks (infinite loops in hash map).
     */
    @Inject(method = "getChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void onGetChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            Chunk cached = EntityTickScheduler.getChunkFromSnapshot(x, z);
            if (cached != null) {
                cir.setReturnValue(cached);
            } else {
                cir.setReturnValue(new Chunk((World) (Object) this, x, z));
            }
        }
    }

    /**
     * isChunkLoaded also accesses ChunkProvider — redirect to chunk snapshot on worker threads.
     */
    @Inject(method = "isChunkLoaded(IIZ)Z", at = @At("HEAD"), cancellable = true)
    private void onIsChunkLoaded(int x, int z, boolean allowEmpty, CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            cir.setReturnValue(EntityTickScheduler.getChunkFromSnapshot(x, z) != null);
        }
    }

    @Inject(method = "onEntityAdded", at = @At("HEAD"), cancellable = true)
    private void onOnEntityAdded(Entity entityIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> {
                try {
                    ((World) (Object) this).onEntityAdded(entityIn);
                } catch (Exception e) {
                    // Entity might already be tracked — safe to ignore
                }
            });
            ci.cancel();
        }
    }

    @Inject(method = "onEntityRemoved", at = @At("HEAD"), cancellable = true)
    private void onOnEntityRemoved(Entity entityIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> {
                try {
                    ((World) (Object) this).onEntityRemoved(entityIn);
                } catch (Exception e) {
                    // Entity might already be removed — safe to ignore
                }
            });
            ci.cancel();
        }
    }

    @Inject(method = "scheduleBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleBlockUpdate(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((World) (Object) this).scheduleBlockUpdate(p, blockIn, delay, priority));
            ci.cancel();
        }
    }

    @Inject(method = "scheduleUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleUpdate(BlockPos pos, Block blockIn, int delay, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((World) (Object) this).scheduleUpdate(p, blockIn, delay));
            ci.cancel();
        }
    }

    @Inject(method = "updateBlockTick", at = @At("HEAD"), cancellable = true)
    private void onUpdateBlockTick(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((World) (Object) this).updateBlockTick(p, blockIn, delay, priority));
            ci.cancel();
        }
    }
}