package com.habitrain.core.scene.client;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * {@link ScenePartFile} 的默认实现。
 *
 * <p>刻意不用 {@code "rwd"}：每片都 fsync 会把写盘重新变回主线程瓶颈。进程崩溃或断线由
 * OS page cache 兜住；断电丢掉的那一段会被续传时的前缀校验挡住，最坏只是少续一点。</p>
 */
public final class RandomAccessPartFile implements ScenePartFile {
    private final Path path;
    private final RandomAccessFile raf;
    private final FileChannel channel;

    public RandomAccessPartFile(Path path) throws IOException {
        this.path = path;
        this.raf = new RandomAccessFile(path.toFile(), "rw");
        this.channel = raf.getChannel();
    }

    @Override
    public void writeAt(long offset, byte[] data, int length) throws IOException {
        if (length <= 0) return;
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        long position = offset;
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer, position);
            if (written <= 0) {
                throw new IOException("场景部分文件写入停滞: " + path + ", offset=" + position);
            }
            position += written;
        }
    }

    @Override
    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public void truncate(long length) throws IOException {
        channel.truncate(length);
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
