package ded.entitythreading.transform.mixin;

import net.minecraft.util.ClassInheritanceMultiMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Replaces the thread-unsafe collections in ClassInheritanceMultiMap with
 * CopyOnWriteArrayList for lock-free, snapshot-based iteration.
 *
 * Why CopyOnWriteArrayList is correct here:
 * - Each ClassInheritanceMultiMap belongs to one CHUNK SECTION (Y=0..15)
 * - Per-section entity counts are small (typically 0-50), so copy-on-write is cheap
 * - Reads (getEntitiesOfTypeWithinAABB) vastly outnumber writes (entity add/remove)
 * - CopyOnWriteArrayList.iterator() returns a snapshot — ZERO chance of CME
 *
 * Also replaces the internal map with ConcurrentHashMap for safe concurrent access.
 */
@Mixin(ClassInheritanceMultiMap.class)
public abstract class MixinClassInheritanceMultiMap<T> {

    @Shadow
    private List<T> values;

    @Shadow
    private Map<Class<?>, List<T>> map;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(Class<T> baseClassIn, CallbackInfo ci) {
        // Replace default ArrayList with CopyOnWriteArrayList for thread-safe iteration
        this.values = new CopyOnWriteArrayList<>();

        // Replace HashMap with ConcurrentHashMap for thread-safe map operations
        ConcurrentHashMap<Class<?>, List<T>> safeMap = new ConcurrentHashMap<>();
        safeMap.put(baseClassIn, this.values);
        this.map = safeMap;
    }

    @Inject(method = "addForClass", at = @At("HEAD"), cancellable = true)
    private void onAddForClass(T value, Class<?> parentClass, CallbackInfo ci) {
        List<T> list = this.map.get(parentClass);
        if (list == null) {
            CopyOnWriteArrayList<T> newList = new CopyOnWriteArrayList<>();
            newList.add(value);
            this.map.put(parentClass, newList);
        } else {
            list.add(value);
        }
        ci.cancel();
    }
}
