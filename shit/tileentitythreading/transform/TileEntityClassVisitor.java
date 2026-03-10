package ded.tileentitythreading.transform;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.ASM5;

/**
 * ASM ClassVisitor that intercepts the World.updateEntities() method
 * for tile entity threading patches.
 * Matches on SRG name "updateEntities" and obfuscated fallbacks.
 */
public class TileEntityClassVisitor extends ClassVisitor {
    public TileEntityClassVisitor(ClassVisitor classVisitor) {
        super(ASM5, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = cv.visitMethod(access, name, desc, signature, exceptions);
        if (desc.equals("()V") && (name.equals("updateEntities") || name.equals("func_72939_s") || name.equals("k"))) {
            System.out.println("[TileEntityThreading] Found updateEntities() (name=" + name + "), patching tile entity ticking...");
            return new TileEntityMethodTransformer(visitor);
        }
        return visitor;
    }
}
