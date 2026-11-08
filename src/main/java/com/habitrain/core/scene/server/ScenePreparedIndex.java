package com.habitrain.core.scene.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Durable index preparation is worker-only; commit does only two same-directory renames. */
final class ScenePreparedIndex implements AutoCloseable {
    private final Path target;
    private final Path previous;
    private final Path next;

    private ScenePreparedIndex(Path target, Path previous, Path next) {
        this.target = target;
        this.previous = previous;
        this.next = next;
    }

    static ScenePreparedIndex prepare(Path target, JsonObject before, JsonObject after, Gson gson) throws IOException {
        Files.createDirectories(target.getParent());
        Path previous = Files.createTempFile(target.getParent(), ".scene-index-before-", ".tmp");
        Path next = null;
        try {
            next = Files.createTempFile(target.getParent(), ".scene-index-after-", ".tmp");
            writeDurable(previous, gson.toJson(before));
            writeDurable(next, gson.toJson(after));
            return new ScenePreparedIndex(target, previous, next);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(previous);
            if (next != null) Files.deleteIfExists(next);
            throw failure;
        }
    }

    private static void writeDurable(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    void commit() throws IOException {
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        if (Files.exists(backup) && !Files.isRegularFile(backup))
            throw new IOException("Index backup is not a regular file: " + backup);
        // The backup represents the validated in-memory index, never an externally corrupted primary.
        move(previous, backup);
        move(next, target);
    }

    static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    @Override public void close() throws IOException {
        try { Files.deleteIfExists(previous); }
        finally { Files.deleteIfExists(next); }
    }
}
