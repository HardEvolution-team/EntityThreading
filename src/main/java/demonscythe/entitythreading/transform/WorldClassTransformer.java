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
