package ded.chunkgenthreading.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;
import ded.chunkgenthreading.ChunkGenThreadingConfig;

import java.util.Set;
import java.util.stream.Collectors;

public class ChunkGenGuiFactory implements IModGuiFactory {

    @Override
    public void initialize(Minecraft minecraftInstance) {
        // Nothing to do
    }

    @Override
    public boolean hasConfigGui() {
        return true;
    }

    @Override
    public GuiScreen createConfigGui(GuiScreen parentScreen) {
        // Gets all the config options defined in the specific @Config class
        return new GuiConfig(
                parentScreen, 
                ConfigElement.from(ChunkGenThreadingConfig.class).getChildElements(),
                "chunk_gen_threader", 
                false, 
                false, 
                "ChunkGenThreading Configuration"
        );
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return null; // Not needed
    }
}
