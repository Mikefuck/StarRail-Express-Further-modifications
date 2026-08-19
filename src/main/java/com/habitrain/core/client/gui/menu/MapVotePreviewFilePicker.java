package com.habitrain.core.client.gui.menu;

import com.habitrain.core.network.MapVoteProfilePayload;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Native PNG chooser plus bounded client-side resizing for network upload. */
final class MapVotePreviewFilePicker {
    private static final long MAX_SOURCE_FILE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_SOURCE_DIMENSION = 8192;
    private static final int INITIAL_MAX_WIDTH = 640;
    private static final int INITIAL_MAX_HEIGHT = 360;
    private static final int MIN_WIDTH = 128;
    private static final int MIN_HEIGHT = 72;

    private MapVotePreviewFilePicker() {
    }

    static PreparedPreview chooseAndPrepare(String title) throws IOException {
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.png")).flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(
                    title, "", filters, "PNG image", false);
        }
        if (selected == null || selected.isBlank()) return null;

        Path path = Path.of(selected).toAbsolutePath().normalize();
        String fileName = path.getFileName() == null ? "preview.png" : path.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new IOException("请选择 PNG 图片");
        }
        if (!Files.isRegularFile(path)) throw new IOException("选择的图片不存在");
        long sourceSize = Files.size(path);
        if (sourceSize <= 0 || sourceSize > MAX_SOURCE_FILE_BYTES) {
            throw new IOException("原图必须小于 16 MiB");
        }

        BufferedImage source = ImageIO.read(path.toFile());
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            throw new IOException("无法读取该 PNG 图片");
        }
        if (source.getWidth() > MAX_SOURCE_DIMENSION || source.getHeight() > MAX_SOURCE_DIMENSION) {
            throw new IOException("图片宽高不能超过 8192 像素");
        }
        return prepare(source, fileName);
    }

    static PreparedPreview prepare(BufferedImage source, String fileName) throws IOException {
        double initialScale = Math.min(1.0, Math.min(
                INITIAL_MAX_WIDTH / (double) source.getWidth(),
                INITIAL_MAX_HEIGHT / (double) source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * initialScale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * initialScale));

        while (true) {
            BufferedImage normalized = resize(source, width, height);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(normalized, "png", output)) {
                throw new IOException("系统没有可用的 PNG 编码器");
            }
            byte[] png = output.toByteArray();
            if (png.length <= MapVoteProfilePayload.MAX_PREVIEW_BYTES) {
                return new PreparedPreview(png, fileName, width, height);
            }
            if (width <= MIN_WIDTH || height <= MIN_HEIGHT) {
                throw new IOException("图片压缩后仍超过 128 KiB，请选择内容更简单的截图");
            }
            width = Math.max(MIN_WIDTH, (int) Math.floor(width * 0.82));
            height = Math.max(MIN_HEIGHT, (int) Math.floor(height * 0.82));
        }
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height
                && source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    record PreparedPreview(byte[] pngBytes, String fileName, int width, int height) {
        int kibibytes() {
            return Math.max(1, (pngBytes.length + 1023) / 1024);
        }
    }
}
