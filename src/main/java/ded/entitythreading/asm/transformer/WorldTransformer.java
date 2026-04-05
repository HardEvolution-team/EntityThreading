package ded.entitythreading.asm.transformer;

import ded.entitythreading.EntityThreadingMod;
import ded.entitythreading.asm.visitor.WorldVisitor;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

public class WorldTransformer implements IClassTransformer {
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (transformedName.equals("net.minecraft.world.World")) {
            EntityThreadingMod.LOGGER.info("Patching World.updateEntities()...");
            ClassReader reader = new ClassReader(basicClass);
            ClassWriter writer = new ClassWriter(0);
            CheckClassAdapter checkClassAdapter = new CheckClassAdapter(writer);
            WorldVisitor visitor = new WorldVisitor(checkClassAdapter);
            reader.accept(visitor, 0);
            basicClass = writer.toByteArray();
            EntityThreadingMod.LOGGER.info("World patched successfully.");
        }
        return basicClass;
    }
}
