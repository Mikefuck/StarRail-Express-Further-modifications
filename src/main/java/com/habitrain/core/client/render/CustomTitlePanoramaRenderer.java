package com.habitrain.core.client.render;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.PanoramaRenderer;

/** Renders the optional HabiTrain Core title-screen cube map. */
@Environment(EnvType.CLIENT)
public final class CustomTitlePanoramaRenderer {
    public static final String TEXTURE_BASE = "textures/gui/title/panorama/panorama";

    private static final PanoramaRenderer RENDERER = new PanoramaRenderer(
            new CubeMap(HabiTrainCore.id(TEXTURE_BASE)));

    private CustomTitlePanoramaRenderer() {
    }

    public static void render(GuiGraphics graphics, int width, int height, float alpha, float delta) {
        RENDERER.render(graphics, width, height, alpha, delta);
    }
}
