package dev.redstudio.alfheim.lighting;

/**
 * Mock class to satisfy the Mixin compiler.
 * At runtime, the real Alfheim LightingEngine class will be loaded and patched.
 */
public class LightingEngine {
    public void processLightUpdatesForType(net.minecraft.world.EnumSkyBlock type) {
    }
}
