/*
 * Copyright (c) 2020  DemonScythe45
 *
 * This file is part of EntityThreading
 *
 *     EntityThreading is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation; version 3 only
 *
 *     EntityThreading is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with EntityThreading.  If not, see <https://www.gnu.org/licenses/>
 */

package demonscythe.entitythreading.transform;

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
