package ded.tileentitythreading.transform.mixin;

import ded.tileentitythreading.schedule.TileEntityDeferredQueue;
import ded.tileentitythreading.schedule.TileEntityTickScheduler;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts thread-unsafe World methods called from tile entity worker threads.
 * Defers them to main thread via TileEntityDeferredQueue.
 *
 * ONLY intercepts when called from a tile entity worker thread (isTileEntityThread()).
 * Main thread calls pass through unmodified — zero overhead.
 *
 * NOTE: The entity threading MixinWorld already intercepts these same methods for
 * entity worker threads (isEntityThread()). Both mixins coexist because they check
 * different ThreadLocal flags.
 */
@Mixin(value = World.class, priority = 999)
public abstract class MixinWorldTileEntity {

    // === DEFERRED INTERCEPTORS — only active on tile entity worker threads ===

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void tileThreading$onSetBlockState(BlockPos pos, IBlockState newState, int flags,
            CallbackInfoReturnable<Boolean> cir) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(() -> ((World) (Object) this).setBlockState(p, newState, flags));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onSpawnEntity(Entity entityIn, CallbackInfoReturnable<Boolean> cir) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(() -> {
                try {
                    ((World) (Object) this).spawnEntity(entityIn);
                } catch (Exception e) {
                    // Safe to ignore
                }
            });
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onRemoveEntity(Entity entityIn, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(() -> {
                try {
                    ((World) (Object) this).removeEntity(entityIn);
                } catch (Exception e) {
                    // Safe to ignore
                }
            });
            ci.cancel();
        }
    }

    @Inject(method = "removeEntityDangerously", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onRemoveEntityDangerously(Entity entityIn, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(() -> {
                try {
                    ((World) (Object) this).removeEntityDangerously(entityIn);
                } catch (Exception e) {
                    // Safe to ignore
                }
            });
            ci.cancel();
        }
    }

    @Inject(method = "playSound(Lnet/minecraft/entity/player/EntityPlayer;DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void tileThreading$onPlaySound(net.minecraft.entity.player.EntityPlayer player,
            double x, double y, double z, SoundEvent soundIn, SoundCategory category,
            float volume, float pitch, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(
                    () -> ((World) (Object) this).playSound(player, x, y, z, soundIn, category, volume, pitch));
            ci.cancel();
        }
    }

    @Inject(method = "notifyNeighborsOfStateChange", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onNotifyNeighbors(BlockPos pos, Block blockType,
            boolean updateObservers, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(
                    () -> ((World) (Object) this).notifyNeighborsOfStateChange(p, blockType, updateObservers));
            ci.cancel();
        }
    }

    @Inject(method = "checkLight", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onCheckLight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(() -> ((World) (Object) this).checkLight(p));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "checkLightFor", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onCheckLightFor(EnumSkyBlock lightType, BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(() -> ((World) (Object) this).checkLightFor(lightType, p));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setTileEntity", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onSetTileEntity(BlockPos pos, TileEntity te, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(() -> ((World) (Object) this).setTileEntity(p, te));
            ci.cancel();
        }
    }

    @Inject(method = "removeTileEntity", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onRemoveTileEntity(BlockPos pos, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(() -> ((World) (Object) this).removeTileEntity(p));
            ci.cancel();
        }
    }

    /**
     * On tile entity worker threads, use the pre-built chunk snapshot.
     * NEVER access ChunkProvider from worker threads.
     */
    @Inject(method = "getChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onGetChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            Chunk cached = TileEntityTickScheduler.getChunkFromSnapshot(x, z);
            if (cached != null) {
                cir.setReturnValue(cached);
            } else {
                cir.setReturnValue(new net.minecraft.world.chunk.EmptyChunk((World) (Object) this, x, z));
            }
        }
    }

    @Inject(method = "isChunkLoaded(IIZ)Z", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onIsChunkLoaded(int x, int z, boolean allowEmpty,
            CallbackInfoReturnable<Boolean> cir) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            cir.setReturnValue(TileEntityTickScheduler.getChunkFromSnapshot(x, z) != null);
        }
    }

    @Inject(method = "onEntityAdded", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onOnEntityAdded(Entity entityIn, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(() -> {
                try {
                    ((World) (Object) this).onEntityAdded(entityIn);
                } catch (Exception e) {
                    // Safe to ignore
                }
            });
            ci.cancel();
        }
    }

    @Inject(method = "onEntityRemoved", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onOnEntityRemoved(Entity entityIn, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(() -> {
                try {
                    ((World) (Object) this).onEntityRemoved(entityIn);
                } catch (Exception e) {
                    // Safe to ignore
                }
            });
            ci.cancel();
        }
    }

    @Inject(method = "scheduleBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onScheduleBlockUpdate(BlockPos pos, Block blockIn,
            int delay, int priority, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(
                    () -> ((World) (Object) this).scheduleBlockUpdate(p, blockIn, delay, priority));
            ci.cancel();
        }
    }

    @Inject(method = "scheduleUpdate", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onScheduleUpdate(BlockPos pos, Block blockIn, int delay, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(() -> ((World) (Object) this).scheduleUpdate(p, blockIn, delay));
            ci.cancel();
        }
    }

    @Inject(method = "updateBlockTick", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onUpdateBlockTick(BlockPos pos, Block blockIn,
            int delay, int priority, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(
                    () -> ((World) (Object) this).updateBlockTick(p, blockIn, delay, priority));
            ci.cancel();
        }
    }

    /**
     * Defer markBlockRangeForRenderUpdate — client-heavy, not thread-safe.
     */
    @Inject(method = "markBlockRangeForRenderUpdate(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onMarkBlockRangeForRenderUpdate(int x1, int y1, int z1,
            int x2, int y2, int z2, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(
                    () -> ((World) (Object) this).markBlockRangeForRenderUpdate(x1, y1, z1, x2, y2, z2));
            ci.cancel();
        }
    }

    /**
     * Thread-safe getBlockState: on worker threads, read directly from the chunk snapshot
     * instead of going through World (which may trigger chunk loading).
     */
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onGetBlockState(BlockPos pos, CallbackInfoReturnable<IBlockState> cir) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            int chunkX = pos.getX() >> 4;
            int chunkZ = pos.getZ() >> 4;
            Chunk chunk = TileEntityTickScheduler.getChunkFromSnapshot(chunkX, chunkZ);
            if (chunk != null) {
                cir.setReturnValue(chunk.getBlockState(pos));
            }
            // If chunk not in snapshot, fall through to vanilla (returns default air state)
        }
    }
}
