package com.habitrain.core.scene.server;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;

/** IO-thread-owned, bounded handles for immutable content-addressed files. */
final class SceneFileReader implements AutoCloseable {
    private final int capacity;
    private final LinkedHashMap<Path, FileChannel> channels = new LinkedHashMap<>(16, .75f, true);
    private int opened;

    SceneFileReader(int capacity) { this.capacity = Math.max(1, capacity); }

    byte[] read(File file, long expectedLength, long offset, int length) throws IOException {
        if (file == null) throw new IOException("Scene file is missing");
        Path path = file.toPath().toAbsolutePath().normalize();
        FileChannel channel = channels.get(path);
        try {
            if (channel == null) {
                if (channels.size() >= capacity) {
                    Path eldest = channels.keySet().iterator().next();
                    channels.remove(eldest).close();
                }
                channel = FileChannel.open(path, StandardOpenOption.READ);
                opened++;
                channels.put(path, channel);
            }
            if (channel.size() != expectedLength || offset < 0 || length <= 0
                    || length > expectedLength || offset > expectedLength - length) throw new IOException("Scene file range/size changed");
            byte[] bytes = new byte[length];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int count = channel.read(buffer, offset + buffer.position());
                if (count < 0) throw new IOException("Truncated scene file");
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Scene read cancelled");
            }
            if (offset + length == expectedLength) channels.remove(path).close();
            return bytes;
        } catch (IOException failure) {
            FileChannel invalid = channels.remove(path);
            if (invalid != null) try { invalid.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    int openedHandles() { return opened; }
    int retainedHandles() { return channels.size(); }

    public void close() throws IOException {
        IOException error = null;
        for (FileChannel channel : channels.values()) try { channel.close(); }
        catch (IOException failure) { error = failure; }
        channels.clear();
        if (error != null) throw error;
    }
}
