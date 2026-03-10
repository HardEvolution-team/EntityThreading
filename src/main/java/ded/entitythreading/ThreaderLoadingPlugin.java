
package ded.entitythreading;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.mixin.Mixins;

import javax.annotation.Nullable;
import java.util.Map;

public class ThreaderLoadingPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        System.out.println("[EntityThreading] Registering ASM transformers...");
        return new String[] {
                "ded.entitythreading.transform.WorldClassTransformer"
        };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        System.out.println("[EntityThreading] Registering mixin configs...");
        Mixins.addConfiguration("mixins.entity_threader.json");
        System.out.println("[EntityThreading] Parallel entity ticking loaded.");
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
