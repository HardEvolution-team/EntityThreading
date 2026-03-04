
package ded.entitythreading.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.ASM5;

/**
 * ASM ClassVisitor that intercepts the World.updateEntities() method.
 *
 * In the RFG/GTNH toolchain, the transformer receives deobfuscated (SRG) class names.
 * So we match on the SRG name "updateEntities" with descriptor "()V".
 * Fallback to obfuscated name "k" is included for compatibility.
 */
public class WorldClassVisitor extends ClassVisitor {
    public WorldClassVisitor(ClassVisitor classVisitor) {
        super(ASM5, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = cv.visitMethod(access, name, desc, signature, exceptions);
        // Match updateEntities()V — try deobfuscated name first, then obfuscated
        if (desc.equals("()V") && (name.equals("updateEntities") || name.equals("func_72939_s") || name.equals("k"))) {
            System.out.println("[EntityThreading] Found updateEntities() method (name=" + name + ")! Patching...");
            return new WorldMethodTransformer(visitor);
        }
        return visitor;
    }
}
