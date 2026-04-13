package ded.entitythreading.mixin;

import ded.entitythreading.schedule.DeferredActionQueue;
import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillageCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VillageCollection.class)
public abstract class VillageCollectionMixin {

    @Inject(method = "addToVillagerPositionList", at = @At("HEAD"), cancellable = true)
    private void onAddToVillagerPositionList(BlockPos pos, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            BlockPos immutablePos = pos.toImmutable();
            VillageCollection self = (VillageCollection) (Object) this;
            DeferredActionQueue.enqueue(() -> self.addToVillagerPositionList(immutablePos));
            ci.cancel();
        }
    }
}
