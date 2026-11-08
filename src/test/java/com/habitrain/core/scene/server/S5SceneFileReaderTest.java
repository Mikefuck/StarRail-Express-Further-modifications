package com.habitrain.core.scene.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class S5SceneFileReaderTest {
    @TempDir Path directory;

    @Test void interleavedAssetsAndResumeReadExactBytesWithoutReopeningEachChunk() throws Exception {
        byte[] bytes = new byte[8 * 1024 * 1024];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + i / 65536);
        var a = directory.resolve("a.hscene");
        var b = directory.resolve("b.hscene");
        Files.write(a, bytes); Files.write(b, bytes);
        try (var reader = new SceneFileReader(2)) {
            for (int offset = 65536; offset < bytes.length; offset += 65536) {
                assertArrayEquals(Arrays.copyOfRange(bytes, offset, offset + 65536), reader.read(a.toFile(), bytes.length, offset, 65536));
                assertArrayEquals(Arrays.copyOfRange(bytes, offset, offset + 65536), reader.read(b.toFile(), bytes.length, offset, 65536));
            }
            assertEquals(2, reader.openedHandles());
            assertEquals(0, reader.retainedHandles());
        }
    }

    @Test void truncationAndInvalidRangesFailAndCanRetryAfterRepair() throws Exception {
        var file = directory.resolve("asset.hscene"); Files.write(file, new byte[16]);
        try (var reader = new SceneFileReader(1)) {
            assertThrows(IOException.class, () -> reader.read(file.toFile(), 32, 0, 16));
            assertThrows(IOException.class, () -> reader.read(file.toFile(), 16, Long.MAX_VALUE, 8));
            Files.write(file, new byte[32]);
            assertEquals(32, reader.read(file.toFile(), 32, 0, 32).length);
            assertEquals(0, reader.retainedHandles());
        }
    }
}
