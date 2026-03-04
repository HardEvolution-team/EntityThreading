package demonscythe.entitythreading.transform.mixin;

import demonscythe.entitythreading.schedule.DeferredActionQueue;
import demonscythe.entitythreading.schedule.EntityTickScheduler;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts unsafe WorldServer methods called from entity worker threads.
 * Prevents the TickNextTick TreeSet CME and other server-side state corruption.
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServer {

    // === TickNextTick schedule (FallingBlock, Redstone, Liquids) ===
    @Inject(method = "scheduleUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleUpdate(BlockPos pos, Block blockIn, int delay, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            WorldServer world = (WorldServer) (Object) this;
            BlockPos immutable = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> world.scheduleUpdate(immutable, blockIn, delay));
            ci.cancel();
        }
    }

    // === TickNextTick with priority ===
    @Inject(method = "updateBlockTick", at = @At("HEAD"), cancellable = true)
    private void onUpdateBlockTick(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            WorldServer world = (WorldServer) (Object) this;
            BlockPos immutable = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> world.updateBlockTick(immutable, blockIn, delay, priority));
            ci.cancel();
        }
    }

    // === Another alias for tick scheduling used by some mods/vanilla ===
    @Inject(method = "scheduleBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void onScheduleBlockUpdate(BlockPos pos, Block blockIn, int delay, int priority, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            WorldServer world = (WorldServer) (Object) this;
            BlockPos immutable = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> world.scheduleBlockUpdate(immutable, blockIn, delay, priority));
            ci.cancel();
        }
    }

    // === Weather entity spawning (lightning) ===
    @Inject(method = "addWeatherEffect", at = @At("HEAD"), cancellable = true)
    private void onAddWeatherEffect(Entity entityIn, CallbackInfoReturnable<Boolean> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            WorldServer world = (WorldServer) (Object) this;
            DeferredActionQueue.enqueue(() -> world.addWeatherEffect(entityIn));
            cir.setReturnValue(true);
        }
    }

    // === Entity tracking updates ===
    @Inject(method = "updateEntityWithOptionalForce", at = @At("HEAD"), cancellable = true)
    private void onUpdateEntityWithOptionalForce(Entity entityIn, boolean forceUpdate, CallbackInfo ci) {
        // This is only dangerous if called from a worker thread trying to update
        // a different entity's tracking state. Let it pass through normally
        // since our scheduler already handles the entity update calls.
    }
}
