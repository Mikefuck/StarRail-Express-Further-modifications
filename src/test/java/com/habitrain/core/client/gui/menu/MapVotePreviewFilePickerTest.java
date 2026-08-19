package com.habitrain.core.client.gui.menu;

import com.habitrain.core.network.MapVoteProfilePayload;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapVotePreviewFilePickerTest {
    @Test
    void selectedScreenshotIsResizedIntoNetworkBudget() throws Exception {
        BufferedImage source = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, 0xFF000000 | (x * 31 + y * 17));
            }
        }

        MapVotePreviewFilePicker.PreparedPreview prepared =
                MapVotePreviewFilePicker.prepare(source, "screenshot.png");

        assertTrue(prepared.pngBytes().length <= MapVoteProfilePayload.MAX_PREVIEW_BYTES);
        assertTrue(prepared.width() <= 640);
        assertTrue(prepared.height() <= 360);
        assertEquals("screenshot.png", prepared.fileName());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(prepared.pngBytes())));
    }
}
