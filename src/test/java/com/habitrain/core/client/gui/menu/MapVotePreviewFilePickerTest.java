package com.habitrain.core.client.gui.menu;

import com.habitrain.core.network.MapVoteProfilePayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapVotePreviewFilePickerTest {
    @Test
    void extremeHeadersAreRejectedBeforePixelRead() {
        ControlledReader wide = new ControlledReader(8193, 1);
        assertThrows(IOException.class, () -> MapVotePreviewFilePicker.readAndPrepare(wide, "wide.png"));
        assertEquals(0, wide.reads);
        ControlledReader excessivePixels = new ControlledReader(8192, 8192);
        assertThrows(IOException.class,
                () -> MapVotePreviewFilePicker.readAndPrepare(excessivePixels, "pixels.png"));
        assertEquals(0, excessivePixels.reads);
    }

    @Test
    void acceptedLargeHeaderUsesSubsamplingBeforeRead() throws Exception {
        ControlledReader reader = new ControlledReader(4096, 4096);
        MapVotePreviewFilePicker.PreparedPreview preview =
                MapVotePreviewFilePicker.readAndPrepare(reader, "large.png");
        assertEquals(1, reader.reads);
        assertTrue(reader.subsampling > 1);
        assertTrue(preview.width() <= 640 && preview.height() <= 360);
    }

    @Test
    void damagedAndTruncatedFilesReturnReadableFailure(@TempDir Path directory) throws Exception {
        Path broken = directory.resolve("broken.png");
        Files.write(broken, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        assertThrows(IOException.class, () -> MapVotePreviewFilePicker.readAndPrepare(broken));
        Path screenshot = directory.resolve("valid.png");
        ImageIO.write(new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB), "png", screenshot.toFile());
        assertNotNull(MapVotePreviewFilePicker.readAndPrepare(screenshot));
        byte[] png = Files.readAllBytes(screenshot);
        Files.write(screenshot, java.util.Arrays.copyOf(png, 40));
        assertThrows(IOException.class, () -> MapVotePreviewFilePicker.readAndPrepare(screenshot));
    }

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

    private static final class ControlledReader extends ImageReader {
        final int width;
        final int height;
        int reads;
        int subsampling;
        ControlledReader(int width, int height) { super(null); this.width = width; this.height = height; }
        public int getNumImages(boolean allowSearch) { return 1; }
        public int getWidth(int index) { return width; }
        public int getHeight(int index) { return height; }
        public Iterator<ImageTypeSpecifier> getImageTypes(int index) {
            return List.of(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB)).iterator();
        }
        public IIOMetadata getStreamMetadata() { return null; }
        public IIOMetadata getImageMetadata(int index) { return null; }
        public BufferedImage read(int index, ImageReadParam parameter) {
            reads++;
            subsampling = parameter.getSourceXSubsampling();
            return new BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB);
        }
    }
}
