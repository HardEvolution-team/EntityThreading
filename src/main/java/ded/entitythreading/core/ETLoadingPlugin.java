package ded.entitythreading.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import javax.annotation.Nullable;
import java.util.Map;

public final class ETLoadingPlugin implements IFMLLoadingPlugin {

    private static final String[] ASM_TRANSFORMERS = {
            "ded.entitythreading.asm.transformer.WorldTransformer"
    };

    @Override
    public String[] getASMTransformerClass() {
        return ASM_TRANSFORMERS;
    }

    @Override
    public @Nullable String getModContainerClass() {
        return null;
    }

    @Override
    public @Nullable String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> map) {
        // No injection data needed
    }

    @Override
    public @Nullable String getAccessTransformerClass() {
        return null;
    }
}
