package ded.otherthreading.transform.mixin;

import ded.otherthreading.schedule.BlockTickScheduler;
import ded.otherthreading.schedule.OtherDeferredActionQueue;
import ded.otherthreading.schedule.ParallelLightingEngine;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
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
 * Intercepts world mutations from OtherThreading worker threads.
 * Redesigned for TRUE PARALLELISM with Chunk Locking.
 */
@Mixin(World.class)
public abstract class MixinWorldEffects {

    @Shadow(remap = false) public abstract void markAndNotifyBlock(BlockPos pos, Chunk chunk, IBlockState iblockstate, IBlockState newState, int flags);

    @Inject(method = "getChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void onGetChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (BlockTickScheduler.isWorkerThread()) {
            Chunk cached = BlockTickScheduler.getChunkFromSnapshot(x, z);
            if (cached != null) {
                cir.setReturnValue(cached);
            } else {
                cir.setReturnValue(new ded.entitythreading.world.SafeEmptyChunk((World) (Object) this, x, z));
            }
        }
    }

    @Inject(method = "isChunkLoaded(IIZ)Z", at = @At("HEAD"), cancellable = true)
    private void onIsChunkLoaded(int x, int z, boolean allowEmpty, CallbackInfoReturnable<Boolean> cir) {
        if (BlockTickScheduler.isWorkerThread()) {
            cir.setReturnValue(BlockTickScheduler.getChunkFromSnapshot(x, z) != null);
        }
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z", at = @At("HEAD"), cancellable = true)
    private void onSetBlockState(BlockPos pos, IBlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (BlockTickScheduler.isWorkerThread()) {
            Chunk chunk = ((World)(Object)this).getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                cir.setReturnValue(false);
                return;
            }
            
            synchronized (chunk) {
                IBlockState iblockstate = chunk.setBlockState(pos, newState);
                if (iblockstate == null) {
                    cir.setReturnValue(false);
                } else {
                    this.markAndNotifyBlock(pos, chunk, iblockstate, newState, flags);
                    cir.setReturnValue(true);
                }
            }
        }
    }

    @Inject(method = "checkLightFor", at = @At("HEAD"), cancellable = true)
    private void onCheckLightFor(EnumSkyBlock lightType, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (BlockTickScheduler.isWorkerThread()) {
            ParallelLightingEngine.enqueue(pos.toImmutable(), lightType);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "checkLight", at = @At("HEAD"), cancellable = true)
    private void onCheckLight(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (BlockTickScheduler.isWorkerThread()) {
            ParallelLightingEngine.enqueue(pos.toImmutable(), EnumSkyBlock.BLOCK);
            ParallelLightingEngine.enqueue(pos.toImmutable(), EnumSkyBlock.SKY);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "playSound(Lnet/minecraft/entity/player/EntityPlayer;DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FF)V", at = @At("HEAD"), cancellable = true)
    private void onPlaySound(net.minecraft.entity.player.EntityPlayer player, double x, double y, double z,
                             SoundEvent soundIn, SoundCategory category, float volume, float pitch, CallbackInfo ci) {
        if (BlockTickScheduler.isWorkerThread()) {
            OtherDeferredActionQueue.enqueue(() -> ((World)(Object)this).playSound(player, x, y, z, soundIn, category, volume, pitch));
            ci.cancel();
        }
    }


    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void onSpawnEntity(Entity entityIn, CallbackInfoReturnable<Boolean> cir) {
        if (BlockTickScheduler.isWorkerThread()) {
            OtherDeferredActionQueue.enqueue(() -> ((World)(Object)this).spawnEntity(entityIn));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setTileEntity", at = @At("HEAD"), cancellable = true)
    private void onSetTileEntity(BlockPos pos, net.minecraft.tileentity.TileEntity te, CallbackInfo ci) {
        if (BlockTickScheduler.isWorkerThread()) {
            OtherDeferredActionQueue.enqueue(() -> ((World)(Object)this).setTileEntity(pos.toImmutable(), te));
            ci.cancel();
        }
    }
}
