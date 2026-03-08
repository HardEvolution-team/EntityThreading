package ded.entitythreading.transform.mixin;

import ded.entitythreading.schedule.DeferredActionQueue;
import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillageCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents ArrayList corruption (resulting in NPEs) when multiple villager threads
 * simultaneously attempt to add their presence to the village collection.
 */
@Mixin(VillageCollection.class)
public abstract class MixinVillageCollection {

    @org.spongepowered.asm.mixin.Shadow
    protected abstract void addDoorsAround(BlockPos central);

    @Inject(method = "addToVillagerPositionList", at = @At("HEAD"), cancellable = true)
    private void onAddToVillagerPositionList(BlockPos pos, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((VillageCollection)(Object)this).addToVillagerPositionList(p));
            ci.cancel();
        }
    }
    
    @Inject(method = "addDoorsAround", at = @At("HEAD"), cancellable = true)
    private void onAddDoorsAround(BlockPos central, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = central == null ? null : central.toImmutable();
            DeferredActionQueue.enqueue(() -> this.addDoorsAround(p));
            ci.cancel();
        }
    }
}
