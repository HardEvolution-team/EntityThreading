package ded.entitythreading.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.ASM5;

public class WorldClassVisitor extends ClassVisitor {
    public WorldClassVisitor(ClassVisitor classVisitor) {
        super(ASM5, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = cv.visitMethod(access, name, desc, signature, exceptions);
        if (desc.equals("()V") && (name.equals("updateEntities") || name.equals("func_72939_s") || name.equals("k"))) {
            System.out.println("[EntityThreading] Found updateEntities() (name=" + name + "), patching...");
            return new WorldMethodTransformer(visitor);
        }
        return visitor;
    }
}
