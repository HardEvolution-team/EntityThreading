package ded.entitythreading.mixin;

import net.minecraft.util.ClassInheritanceMultiMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Mixin(ClassInheritanceMultiMap.class)
public abstract class ClassInheritanceMultiMapMixin<T> {

    @Mutable
    @Final
    @Shadow
    private List<T> values;

    @Mutable
    @Final
    @Shadow
    private Map<Class<?>, List<T>> map;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(Class<T> baseClassIn, CallbackInfo ci) {
        this.values = new CopyOnWriteArrayList<>();
        ConcurrentHashMap<Class<?>, List<T>> safeMap = new ConcurrentHashMap<>();
        safeMap.put(baseClassIn, this.values);
        this.map = safeMap;
    }

    @Inject(method = "addForClass", at = @At("HEAD"), cancellable = true)
    private void onAddForClass(T value, Class<?> parentClass, CallbackInfo ci) {
        this.map.computeIfAbsent(parentClass, _ -> new CopyOnWriteArrayList<>()).add(value);
        ci.cancel();
    }
}
