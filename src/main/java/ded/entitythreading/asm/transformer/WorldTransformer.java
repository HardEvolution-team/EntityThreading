package ded.entitythreading.asm.transformer;

import ded.entitythreading.EntityThreadingMod;
import ded.entitythreading.asm.visitor.WorldVisitor;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * ASM transformer that patches {@code World.updateEntities()} to redirect
 * individual entity update calls through {@code EntityTickScheduler}.
 * <p>
 * FIX: Removed {@link org.objectweb.asm.util.CheckClassAdapter} from production —
 * it is a debugging tool that adds overhead and can mask real errors.
 */
public final class WorldTransformer implements IClassTransformer {

    private static final String TARGET_CLASS = "net.minecraft.world.World";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET_CLASS.equals(transformedName)) {
            return basicClass;
        }

        EntityThreadingMod.LOGGER.info("Patching World.updateEntities()...");

        ClassReader reader = new ClassReader(basicClass);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        WorldVisitor visitor = new WorldVisitor(writer);
        reader.accept(visitor, 0);

        EntityThreadingMod.LOGGER.info("World patched successfully.");
        return writer.toByteArray();
    }
}
