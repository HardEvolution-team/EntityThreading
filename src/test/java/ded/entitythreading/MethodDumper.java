package ded.entitythreading;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import java.lang.reflect.Method;
import java.util.Arrays;

public class MethodDumper {
    public static void main(String[] args) {
        System.out.println("=== WorldServer Methods ===");
        for (Method m : WorldServer.class.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("tick") || m.getName().toLowerCase().contains("schedule") || m.getName().toLowerCase().contains("update")) {
                System.out.println(m.getName() + " " + Arrays.toString(m.getParameterTypes()));
            }
        }
        
        System.out.println("\n=== World Methods ===");
        for (Method m : World.class.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("tick") || m.getName().toLowerCase().contains("schedule") || m.getName().toLowerCase().contains("update")) {
                System.out.println(m.getName() + " " + Arrays.toString(m.getParameterTypes()));
            }
        }
    }
}
