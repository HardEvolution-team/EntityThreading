package ded.entitythreading.transform;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.objectweb.asm.Opcodes.ASM5;

/**
 * ASM MethodVisitor that patches World.updateEntities():
 * 1. Redirects World.updateEntity(Entity) -> EntityTickScheduler.queueEntity(World, Entity)
 * 2. Injects EntityTickScheduler.waitForFinish() before every RETURN opcode
 */
public class WorldMethodTransformer extends MethodVisitor {

    WorldMethodTransformer(MethodVisitor methodVisitor) {
        super(ASM5, methodVisitor);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        // Redirect World.updateEntity(Entity) -> EntityTickScheduler.queueEntity(World, Entity)
        if (opcode == Opcodes.INVOKEVIRTUAL && isUpdateEntityCall(owner, name, desc)) {
            System.out.println("[EntityThreading] Redirecting updateEntity() -> EntityTickScheduler.queueEntity()");
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "ded/entitythreading/schedule/EntityTickScheduler",
                "queueEntity",
                "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;)V",
                false
            );
            return;
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    @Override
    public void visitInsn(int opcode) {
        // Inject waitForFinish() before every RETURN in updateEntities()
        if (opcode == Opcodes.RETURN) {
            System.out.println("[EntityThreading] Inserting waitForFinish() before RETURN");
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "ded/entitythreading/schedule/EntityTickScheduler",
                "waitForFinish",
                "()V",
                false
            );
        }
        super.visitInsn(opcode);
    }

    private boolean isUpdateEntityCall(String owner, String name, String desc) {
        // Deobfuscated / SRG
        boolean deobf = owner.equals("net/minecraft/world/World")
            && (name.equals("updateEntity") || name.equals("func_72870_g"))
            && desc.equals("(Lnet/minecraft/entity/Entity;)V");
        // Obfuscated (notch)
        boolean obf = owner.equals("amu")
            && name.equals("h")
            && desc.equals("(Lvg;)V");
        return deobf || obf;
    }
}
