package ded.entitythreading.transform.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
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
 * COMPREHENSIVE World interceptor for tile entity worker threads.
 * Covers ALL methods tile entities might call during their update().
 *
 * Strategy:
 *   - READ operations (getBlockState, getTileEntity, etc.) → route through chunk snapshot (safe)
 *   - WRITE operations (setBlockState, spawnEntity, etc.) → defer to main thread
 *   - VISUAL operations (markBlockRangeForRenderUpdate, etc.) → defer to main thread
 *   - NOTIFICATION operations (notifyBlockUpdate, etc.) → defer to main thread
 *
 * Only intercepts when isTileEntityThread() == true. Zero overhead on main thread.
 */
@Mixin(value = World.class, priority = 999)
public abstract class MixinWorldTileEntity {

    @Shadow
    public abstract boolean isBlockLoaded(BlockPos pos);

    // =========================================================================
    // READ OPERATIONS — route through chunk snapshot for thread safety
    // =========================================================================

    /**
     * getChunk(int, int) is THE critical method — almost all reads go through this.
     * Route to chunk snapshot on worker threads.
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

    /**
     * isChunkLoaded — check against snapshot instead of ChunkProvider.
     */
    @Inject(method = "isChunkLoaded(IIZ)Z", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onIsChunkLoaded(int x, int z, boolean allowEmpty,
            CallbackInfoReturnable<Boolean> cir) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            cir.setReturnValue(TileEntityTickScheduler.getChunkFromSnapshot(x, z) != null);
        }
    }

    /**
     * getTileEntity — bypass the non-thread-safe pendingTileEntityList.
     * Go straight to chunk snapshot's tile entity map (safe for reads).
     * This is CRITICAL for IC2/mod machines that access neighbor tile entities.
     */
    @Inject(method = "getTileEntity", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onGetTileEntity(BlockPos pos, CallbackInfoReturnable<TileEntity> cir) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            if (pos.getY() < 0 || pos.getY() >= 256) {
                cir.setReturnValue(null);
                return;
            }
            Chunk chunk = TileEntityTickScheduler.getChunkFromSnapshot(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null) {
                // Use CHECK type to avoid creating new tile entities (not thread-safe)
                cir.setReturnValue(chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK));
            } else {
                cir.setReturnValue(null);
            }
        }
    }

    // =========================================================================
    // WRITE OPERATIONS — defer to main thread
    // =========================================================================

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

    // =========================================================================
    // ENTITY OPERATIONS — defer to main thread
    // =========================================================================

    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onSpawnEntity(Entity entityIn, CallbackInfoReturnable<Boolean> cir) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(() -> {
                try { ((World) (Object) this).spawnEntity(entityIn); } catch (Exception e) { }
            });
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onRemoveEntity(Entity entityIn, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(() -> {
                try { ((World) (Object) this).removeEntity(entityIn); } catch (Exception e) { }
            });
            ci.cancel();
        }
    }

    @Inject(method = "removeEntityDangerously", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onRemoveEntityDangerously(Entity entityIn, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(() -> {
                try { ((World) (Object) this).removeEntityDangerously(entityIn); } catch (Exception e) { }
            });
            ci.cancel();
        }
    }

    @Inject(method = "onEntityAdded", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onOnEntityAdded(Entity entityIn, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(() -> {
                try { ((World) (Object) this).onEntityAdded(entityIn); } catch (Exception e) { }
            });
            ci.cancel();
        }
    }

    @Inject(method = "onEntityRemoved", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onOnEntityRemoved(Entity entityIn, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(() -> {
                try { ((World) (Object) this).onEntityRemoved(entityIn); } catch (Exception e) { }
            });
            ci.cancel();
        }
    }

    // =========================================================================
    // NOTIFICATION / UPDATE OPERATIONS — defer to main thread
    // =========================================================================

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

    /**
     * notifyBlockUpdate — sends block change to clients. Thread-unsafe (packet sending).
     */
    @Inject(method = "notifyBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onNotifyBlockUpdate(BlockPos pos, IBlockState oldState,
            IBlockState newState, int flags, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(
                    () -> ((World) (Object) this).notifyBlockUpdate(p, oldState, newState, flags));
            ci.cancel();
        }
    }

    /**
     * markBlockRangeForRenderUpdate — visual only, defer.
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

    // =========================================================================
    // SCHEDULING OPERATIONS — defer to main thread
    // =========================================================================

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

    // =========================================================================
    // LIGHT OPERATIONS — defer to main thread
    // =========================================================================

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

    // =========================================================================
    // SOUND / VISUAL — defer to main thread
    // =========================================================================

    @Inject(method = "playSound(Lnet/minecraft/entity/player/EntityPlayer;DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FF)V",
            at = @At("HEAD"), cancellable = true)
    private void tileThreading$onPlaySound(EntityPlayer player,
            double x, double y, double z, SoundEvent soundIn, SoundCategory category,
            float volume, float pitch, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            TileEntityDeferredQueue.enqueue(
                    () -> ((World) (Object) this).playSound(player, x, y, z, soundIn, category, volume, pitch));
            ci.cancel();
        }
    }

    @Inject(method = "playEvent(Lnet/minecraft/entity/player/EntityPlayer;ILnet/minecraft/util/math/BlockPos;I)V",
            at = @At("HEAD"), cancellable = true)
    private void tileThreading$onPlayEvent(EntityPlayer player, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(() -> ((World) (Object) this).playEvent(player, type, p, data));
            ci.cancel();
        }
    }

    @Inject(method = "sendBlockBreakProgress", at = @At("HEAD"), cancellable = true)
    private void tileThreading$onSendBlockBreakProgress(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
        if (TileEntityTickScheduler.isTileEntityThread()) {
            BlockPos p = pos.toImmutable();
            TileEntityDeferredQueue.enqueue(
                    () -> ((World) (Object) this).sendBlockBreakProgress(breakerId, p, progress));
            ci.cancel();
        }
    }
}
