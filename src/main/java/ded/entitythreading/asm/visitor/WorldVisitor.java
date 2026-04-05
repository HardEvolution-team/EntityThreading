package ded.entitythreading.asm.visitor;

import ded.entitythreading.EntityThreadingMod;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.ASM5;

public class WorldVisitor extends ClassVisitor {
    public WorldVisitor(ClassVisitor classVisitor) {
        super(ASM5, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = cv.visitMethod(access, name, desc, signature, exceptions);
        if (desc.equals("()V") && (name.equals("updateEntities") || name.equals("func_72939_s") || name.equals("k"))) {
            EntityThreadingMod.LOGGER.info("Found updateEntities() (name={}), patching...", name);
            return new WorldMethodVisitor(visitor);
        }
        return visitor;
    }

    static class WorldMethodVisitor extends MethodVisitor {

        WorldMethodVisitor(MethodVisitor methodVisitor) {
            super(ASM5, methodVisitor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (opcode == Opcodes.INVOKEVIRTUAL && isUpdateEntityCall(owner, name, desc)) {
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "ded/entitythreading/schedule/EntityTickScheduler",
                        "queueEntity",
                        "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;)V",
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
                        "ded/entitythreading/schedule/EntityTickScheduler",
                        "waitForFinish",
                        "()V",
                        false);
            }
            super.visitInsn(opcode);
        }

        private boolean isUpdateEntityCall(String owner, String name, String desc) {
            boolean deobf = owner.equals("net/minecraft/world/World")
                    && (name.equals("updateEntity") || name.equals("func_72870_g"))
                    && desc.equals("(Lnet/minecraft/entity/Entity;)V");
            boolean obf = owner.equals("amu")
                    && name.equals("h")
                    && desc.equals("(Lvg;)V");
            return deobf || obf;
        }
    }
}
