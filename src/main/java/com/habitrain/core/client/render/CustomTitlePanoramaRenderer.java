package com.habitrain.core.client.render;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.resources.ResourceLocation;

/** Renders the optional HabiTrain Core title-screen cube map. */
@Environment(EnvType.CLIENT)
public final class CustomTitlePanoramaRenderer {
    public static final String TEXTURE_BASE = "textures/gui/title/panorama/panorama";

    private static PanoramaRenderer renderer;

    private CustomTitlePanoramaRenderer() {
    }

    public static void render(GuiGraphics graphics, int width, int height, float alpha, float delta) {
        if (renderer == null) {
            renderer = new PanoramaRenderer(new CubeMap(HabiTrainCore.id(TEXTURE_BASE)));
        }
        renderer.render(graphics, width, height, alpha, delta);
    }

    /** Releases the 6 title-screen cube map textures when entering a world so they do not stay in VRAM. */
    public static void release() {
        if (renderer == null) {
            return;
        }
        renderer = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            ResourceLocation id = HabiTrainCore.id(TEXTURE_BASE + "_" + i + ".png");
            try {
                mc.getTextureManager().release(id);
            } catch (Exception ignored) {
            }
        }
    }
}
