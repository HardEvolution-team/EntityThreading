package ded.entitythreading.transform.mixin;

import ded.entitythreading.schedule.DeferredActionQueue;
import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.ASMEventHandler;
import net.minecraftforge.fml.common.eventhandler.Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ASMEventHandler.class, remap = false)
public class MixinASMEventHandler {

    @Shadow private ModContainer owner;

    @Inject(method = "invoke", at = @At("HEAD"), cancellable = true)
    private void onInvokeSandboxed(Event event, CallbackInfo ci) {
        if (EntityTickScheduler.isEntityThread() && this.owner != null) {
            if (EntityTickScheduler.isModEventBlacklisted(this.owner.getModId())) {
                DeferredActionQueue.enqueue(() -> {
                    try { ((ASMEventHandler)(Object)this).invoke(event); } catch (Throwable ignored) {}
                });
                ci.cancel();
            }
        }
    }
}
