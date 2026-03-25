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

    @Inject(method = "addToVillagerPositionList", at = @At("HEAD"), cancellable = true)
    private void onAddToVillagerPositionList(BlockPos pos, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos p = pos.toImmutable();
            DeferredActionQueue.enqueue(() -> ((VillageCollection)(Object)this).addToVillagerPositionList(p));
            ci.cancel();
        }
    }
}
