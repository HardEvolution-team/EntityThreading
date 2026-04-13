package ded.entitythreading.mixin;

import net.minecraft.util.ClassInheritanceMultiMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces the backing collections of {@link ClassInheritanceMultiMap}
 * with concurrent-safe implementations to prevent CME during parallel entity ticking.
 *
 * CRITICAL FIX: CopyOnWriteArrayList has O(n) add — catastrophic for chunks with
 * hundreds of entities. Instead, we use Collections.synchronizedList(ArrayList)
 * which has O(1) amortized add and only synchronizes on access.
 *
 * The iteration pattern in entity ticking (for-each over the list) is safe because:
 * 1. Entity additions/removals during parallel ticking are deferred to main thread
 * 2. The synchronizedList's iterator must be manually synchronized — but since we
 *    snapshot to array before iteration in hot paths, this is acceptable
 */
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
        List<T> safeValues = Collections.synchronizedList(new ArrayList<>());
        this.values = safeValues;

        ConcurrentHashMap<Class<?>, List<T>> safeMap = new ConcurrentHashMap<>();
        safeMap.put(baseClassIn, safeValues);
        this.map = safeMap;
    }

    /**
     * Override addForClass to use computeIfAbsent with synchronized list creation.
     * This is called when entities are added to a chunk's entity list.
     */
    @Inject(method = "addForClass", at = @At("HEAD"), cancellable = true)
    private void onAddForClass(T value, Class<?> parentClass, CallbackInfo ci) {
        this.map.computeIfAbsent(parentClass, _ -> Collections.synchronizedList(new ArrayList<>()))
                .add(value);
        ci.cancel();
    }
}
