package ded.entitythreading.mixin;

import ded.entitythreading.EntityThreadingMod;
import ded.entitythreading.schedule.DeferredActionQueue;
import ded.entitythreading.schedule.EntityTickScheduler;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.ASMEventHandler;
import net.minecraftforge.fml.common.eventhandler.Event;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ASMEventHandler.class, remap = false)
public abstract class ASMEventHandlerMixin {

    @Final
    @Shadow
    private ModContainer owner;

    /**
     * Defers blacklisted mod event invocations from entity worker threads
     * to the main thread to prevent concurrent modification issues.
     */
    @Inject(method = "invoke", at = @At("HEAD"), cancellable = true)
    private void onInvokeSandboxed(Event event, CallbackInfo ci) {
        if (!EntityTickScheduler.isEntityThread() || owner == null) {
            return;
        }

        String modId = owner.getModId();
        if (!EntityTickScheduler.isModEventBlacklisted(modId)) {
            return;
        }

        ASMEventHandler self = (ASMEventHandler) (Object) this;
        DeferredActionQueue.enqueue(() -> {
            try {
                self.invoke(event);
            } catch (Throwable t) {
                EntityThreadingMod.LOGGER.error("Deferred event invoke failed for mod '{}': {}",
                        modId, t.getMessage());
            }
        });

        ci.cancel();
    }
}
