package ded.entitythreading.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import javax.annotation.Nullable;
import java.util.Map;

public class ETLoadingPlugin implements IFMLLoadingPlugin {
    @Override
    public @Nullable String[] getASMTransformerClass() {
        return new String[]{
                "ded.entitythreading.asm.transformer.WorldTransformer",
        };
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

    }

    @Override
    public @Nullable String getAccessTransformerClass() {
        return null;
    }
}
