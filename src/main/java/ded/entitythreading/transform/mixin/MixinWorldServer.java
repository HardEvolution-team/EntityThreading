package ded.entitythreading.transform.mixin;

import ded.entitythreading.schedule.DeferredActionQueue;
import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts thread-unsafe WorldServer methods called from entity worker
 * threads.
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    @Inject(method = "scheduleUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleUpdate(BlockPos pos, Block blockIn, int delay, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((WorldServer) (Object) this).scheduleUpdate(p, blockIn, delay));
            ci.cancel();
        }
    }

    @Inject(method = "updateBlockTick", at = @At("HEAD"), cancellable = true)
    private void onUpdateBlockTick(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue
                    .enqueue(() -> ((WorldServer) (Object) this).updateBlockTick(p, blockIn, delay, priority));
            ci.cancel();
        }
    }

    @Inject(method = "scheduleBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleBlockUpdate(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue
                    .enqueue(() -> ((WorldServer) (Object) this).scheduleBlockUpdate(p, blockIn, delay, priority));
            ci.cancel();
        }
    }

    @Inject(method = "addWeatherEffect", at = @At("HEAD"), cancellable = true)
    private void onAddWeatherEffect(Entity entityIn, CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> {
                try {
                    ((WorldServer) (Object) this).addWeatherEffect(entityIn);
                } catch (Exception e) {
                    // Weather entity might already be added — safe to ignore
                }
            });
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setEntityState", at = @At("HEAD"), cancellable = true)
    private void onSetEntityState(Entity entityIn, byte state, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> {
                try {
                    ((WorldServer) (Object) this).setEntityState(entityIn, state);
                } catch (Exception e) {
                    // Entity state might be stale — safe to ignore
                }
            });
            ci.cancel();
        }
    }

    @Inject(method = "addBlockEvent", at = @At("HEAD"), cancellable = true)
    private void onAddBlockEvent(BlockPos pos, Block blockIn, int eventID, int eventParam, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue
                    .enqueue(() -> ((WorldServer) (Object) this).addBlockEvent(p, blockIn, eventID, eventParam));
            ci.cancel();
        }
    }
}
