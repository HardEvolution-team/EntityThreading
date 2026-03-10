package ded.tileentitythreading.transform;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.ASM5;

/**
 * ASM MethodVisitor that patches World.updateEntities() for tile entity threading:
 * 1. Redirects ITickable.update() →
 *    TileEntityTickScheduler.queueTileEntity(World, ITickable)
 * 2. Injects TileEntityTickScheduler.waitForFinish() before every RETURN opcode
 */
public class TileEntityMethodTransformer extends MethodVisitor {

    private int matchCount = 0;

    TileEntityMethodTransformer(MethodVisitor methodVisitor) {
        super(ASM5, methodVisitor);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        // Debug: log ALL interface calls in updateEntities to help diagnose name mismatches
        if (opcode == Opcodes.INVOKEINTERFACE) {
            System.out.println("[TileEntityThreading-ASM] INVOKEINTERFACE: " + owner + "." + name + desc);
        }

        if (opcode == Opcodes.INVOKEINTERFACE && isTickableUpdateCall(owner, name, desc)) {
            matchCount++;
            System.out.println("[TileEntityThreading-ASM] >>> MATCHED ITickable.update() call #" + matchCount +
                    " (owner=" + owner + ", name=" + name + ")");
            // Stack currently has: [..., ITickable_instance]
            // Load 'this' (the World) onto the stack
            super.visitVarInsn(Opcodes.ALOAD, 0);
            // Stack: [..., ITickable_instance, World_instance]
            // Swap so World is first argument
            super.visitInsn(Opcodes.SWAP);
            // Stack: [..., World_instance, ITickable_instance]
            // Call our static method
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "shit/tileentitythreading/schedule/TileEntityTickScheduler",
                    "queueTileEntity",
                    "(Lnet/minecraft/world/World;Lnet/minecraft/util/ITickable;)V",
                    false);
            return;
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    @Override
    public void visitInsn(int opcode) {
        if (opcode == Opcodes.RETURN) {
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "shit/tileentitythreading/schedule/TileEntityTickScheduler",
                    "waitForFinish",
                    "()V",
                    false);
        }
        super.visitInsn(opcode);
    }

    @Override
    public void visitEnd() {
        System.out.println("[TileEntityThreading-ASM] Finished patching updateEntities(). " +
                "Matched " + matchCount + " ITickable.update() call(s).");
        if (matchCount == 0) {
            System.err.println("[TileEntityThreading-ASM] WARNING: No ITickable.update() calls found! " +
                    "The tile entity threading patch may NOT be working!");
        }
        super.visitEnd();
    }

    /**
     * Match ITickable.update()V — MCP (dev), SRG (production), and obfuscated names.
     */
    private boolean isTickableUpdateCall(String owner, String name, String desc) {
        if (!desc.equals("()V")) return false;

        // MCP deobfuscated (dev environment)
        if (owner.equals("net/minecraft/util/ITickable") && name.equals("update")) return true;

        // SRG name (production Forge environment) — THIS IS THE CRITICAL ONE
        if (owner.equals("net/minecraft/util/ITickable") && name.equals("func_73660_a")) return true;

        // Fully obfuscated (vanilla, no Forge)
        if (owner.equals("amg") && name.equals("e")) return true;

        return false;
    }
}

