package ded.entitythreading.asm.visitor;

import ded.entitythreading.EntityThreadingMod;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

import static org.objectweb.asm.Opcodes.ASM9;

/**
 * Visits World class to find and patch {@code updateEntities()}.
 * <p>
 * FIX: Upgraded from ASM5 to ASM9 for Java 25 compatibility.
 * FIX: Extracted magic strings into constants.
 */
public final class WorldVisitor extends ClassVisitor {

    private static final Set<String> UPDATE_ENTITIES_NAMES = Set.of(
            "updateEntities", "func_72939_s", "k"
    );
    private static final String UPDATE_ENTITIES_DESC = "()V";

    public WorldVisitor(ClassVisitor classVisitor) {
        super(ASM9, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
        if (UPDATE_ENTITIES_DESC.equals(desc) && UPDATE_ENTITIES_NAMES.contains(name)) {
            EntityThreadingMod.LOGGER.info("Found updateEntities() (name={}), patching...", name);
            return new UpdateEntitiesMethodVisitor(visitor);
        }
        return visitor;
    }

    /**
     * Rewrites individual {@code World.updateEntity(Entity)} calls to
     * {@code EntityTickScheduler.queueEntity(World, Entity)} and inserts
     * a {@code waitForFinish()} call before every RETURN.
     */
    private static final class UpdateEntitiesMethodVisitor extends MethodVisitor {

        private static final String SCHEDULER_OWNER = "ded/entitythreading/schedule/EntityTickScheduler";

        private static final String QUEUE_ENTITY_NAME = "queueEntity";
        private static final String QUEUE_ENTITY_DESC = "(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;)V";

        private static final String WAIT_NAME = "waitForFinish";
        private static final String WAIT_DESC = "()V";

        // Deobfuscated
        private static final String WORLD_INTERNAL = "net/minecraft/world/World";
        private static final Set<String> UPDATE_ENTITY_NAMES_DEOBF = Set.of("updateEntity", "func_72870_g");
        private static final String UPDATE_ENTITY_DESC_DEOBF = "(Lnet/minecraft/entity/Entity;)V";

        // Obfuscated (notch names for 1.12.2)
        private static final String WORLD_OBF = "amu";
        private static final String UPDATE_ENTITY_NAME_OBF = "h";
        private static final String UPDATE_ENTITY_DESC_OBF = "(Lvg;)V";

        UpdateEntitiesMethodVisitor(MethodVisitor methodVisitor) {
            super(ASM9, methodVisitor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if (opcode == Opcodes.INVOKEVIRTUAL && isUpdateEntityCall(owner, name, desc)) {
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        SCHEDULER_OWNER,
                        QUEUE_ENTITY_NAME,
                        QUEUE_ENTITY_DESC,
                        false
                );
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        SCHEDULER_OWNER,
                        WAIT_NAME,
                        WAIT_DESC,
                        false
                );
            }
            super.visitInsn(opcode);
        }

        private static boolean isUpdateEntityCall(String owner, String name, String desc) {
            if (WORLD_INTERNAL.equals(owner)
                    && UPDATE_ENTITY_NAMES_DEOBF.contains(name)
                    && UPDATE_ENTITY_DESC_DEOBF.equals(desc)) {
                return true;
            }
            return WORLD_OBF.equals(owner)
                    && UPDATE_ENTITY_NAME_OBF.equals(name)
                    && UPDATE_ENTITY_DESC_OBF.equals(desc);
        }
    }
}
