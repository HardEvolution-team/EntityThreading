
package ded.entitythreading.transform;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

public class WorldClassTransformer implements IClassTransformer {
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (transformedName.equals("net.minecraft.world.World")) {
            System.out.println("World class found");
            System.out.println("Obfuscated name is: " + name);
            ClassReader reader = new ClassReader(basicClass);
            ClassWriter writer = new ClassWriter(0);

            //TraceClassVisitor traceClassVisitor = new TraceClassVisitor(visitor, new PrintWriter(System.out));
            CheckClassAdapter checkClassAdapter = new CheckClassAdapter(writer);
            WorldClassVisitor visitor = new WorldClassVisitor(checkClassAdapter);
            reader.accept(visitor, 0);
            basicClass = writer.toByteArray();
        }

        return basicClass;
    }
}
