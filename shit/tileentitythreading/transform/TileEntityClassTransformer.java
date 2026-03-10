package ded.tileentitythreading.transform;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

public class TileEntityClassTransformer implements IClassTransformer {
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (transformedName.equals("net.minecraft.world.World")) {
            System.out.println("[TileEntityThreading] Patching World.updateEntities() for tile entity threading...");
            ClassReader reader = new ClassReader(basicClass);
            ClassWriter writer = new ClassWriter(0);
            CheckClassAdapter checkClassAdapter = new CheckClassAdapter(writer);
            TileEntityClassVisitor visitor = new TileEntityClassVisitor(checkClassAdapter);
            reader.accept(visitor, 0);
            basicClass = writer.toByteArray();
            System.out.println("[TileEntityThreading] World patched successfully for tile entity threading.");
        }
        return basicClass;
    }
}
