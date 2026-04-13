package ded.entitythreading.mixin;

import ded.entitythreading.schedule.DeferredActionQueue;
import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.village.Village;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Village.class)
public abstract class VillageMixin {

    private Village self() {
        return (Village) (Object) this;
    }

    @Inject(method = "addOrRenewAgressor", at = @At("HEAD"), cancellable = true)
    private void onAddOrRenewAgressor(EntityLivingBase entitylivingbaseIn, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            Village village = self();
            DeferredActionQueue.enqueue(() -> village.addOrRenewAgressor(entitylivingbaseIn));
            ci.cancel();
        }
    }

    @Inject(method = "endMatingSeason", at = @At("HEAD"), cancellable = true)
    private void onEndMatingSeason(CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            Village village = self();
            DeferredActionQueue.enqueue(village::endMatingSeason);
            ci.cancel();
        }
    }

    @Inject(method = "setDefaultPlayerReputation", at = @At("HEAD"), cancellable = true)
    private void onSetDefaultPlayerReputation(int defaultReputation, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread()) {
            Village village = self();
            DeferredActionQueue.enqueue(() -> village.setDefaultPlayerReputation(defaultReputation));
            ci.cancel();
        }
    }

    @Inject(method = "modifyPlayerReputation", at = @At("HEAD"), cancellable = true)
    private void onModifyPlayerReputation(String playerName, int reputation,
                                          CallbackInfoReturnable<Integer> cir) {
        if (EntityTickScheduler.isEntityThread()) {
            Village village = self();
            DeferredActionQueue.enqueue(() -> village.modifyPlayerReputation(playerName, reputation));
            cir.setReturnValue(0);
        }
    }
}
