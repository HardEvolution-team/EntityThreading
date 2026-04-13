package ded.entitythreading.mixin;

import ded.entitythreading.EntityThreadingMod;
import ded.entitythreading.schedule.DeferredActionQueue;
import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldServer.class)
public abstract class WorldServerMixin {

    @Unique
    private WorldServer self() {
        return (WorldServer) (Object) this;
    }

    @Unique
    private static void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            EntityThreadingMod.LOGGER.debug("Deferred WorldServer action failed: {}", e.getMessage());
        }
    }

    @Inject(method = "onEntityAdded", at = @At("HEAD"), cancellable = true)
    private void onEntityAddedServerSync(Entity entityIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            WorldServer ws = self();
            DeferredActionQueue.enqueue(() -> ws.onEntityAdded(entityIn));
            ci.cancel();
        }
    }

    @Inject(method = "onEntityRemoved", at = @At("HEAD"), cancellable = true)
    private void onEntityRemovedServerSync(Entity entityIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            WorldServer ws = self();
            DeferredActionQueue.enqueue(() -> ws.onEntityRemoved(entityIn));
            ci.cancel();
        }
    }

    @Inject(method = "scheduleUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleUpdate(BlockPos pos, Block blockIn, int delay, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            WorldServer ws = self();
            DeferredActionQueue.enqueue(() -> ws.scheduleUpdate(p, blockIn, delay));
            ci.cancel();
        }
    }

    @Inject(method = "updateBlockTick", at = @At("HEAD"), cancellable = true)
    private void onUpdateBlockTick(BlockPos pos, Block blockIn, int delay, int priority,
                                   CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            WorldServer ws = self();
            DeferredActionQueue.enqueue(() -> ws.updateBlockTick(p, blockIn, delay, priority));
            ci.cancel();
        }
    }

    @Inject(method = "scheduleBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleBlockUpdate(BlockPos pos, Block blockIn, int delay, int priority,
                                       CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            WorldServer ws = self();
            DeferredActionQueue.enqueue(() -> ws.scheduleBlockUpdate(p, blockIn, delay, priority));
            ci.cancel();
        }
    }

    @Inject(method = "addWeatherEffect", at = @At("HEAD"), cancellable = true)
    private void onAddWeatherEffect(Entity entityIn, CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            WorldServer ws = self();
            DeferredActionQueue.enqueue(() -> safeRun(() -> ws.addWeatherEffect(entityIn)));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setEntityState", at = @At("HEAD"), cancellable = true)
    private void onSetEntityState(Entity entityIn, byte state, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            WorldServer ws = self();
            DeferredActionQueue.enqueue(() -> safeRun(() -> ws.setEntityState(entityIn, state)));
            ci.cancel();
        }
    }

    @Inject(method = "addBlockEvent", at = @At("HEAD"), cancellable = true)
    private void onAddBlockEvent(BlockPos pos, Block blockIn, int eventID, int eventParam,
                                 CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            WorldServer ws = self();
            DeferredActionQueue.enqueue(() -> ws.addBlockEvent(p, blockIn, eventID, eventParam));
            ci.cancel();
        }
    }

    @Inject(method = "isChunkLoaded", at = @At("HEAD"), cancellable = true)
    private void onIsChunkLoaded(int x, int z, boolean allowEmpty,
                                 CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            cir.setReturnValue(EntityTickScheduler.getChunkFromSnapshot(x, z) != null);
        }
    }
}
