package com.habitrain.core.api.scene.asset;

import java.io.IOException;
import java.io.OutputStream;

/** Counts actual inflated wire bytes before they enter the compressor. */
final class SceneWriteLimit extends OutputStream {
    private final OutputStream delegate;
    private final long limit;
    private long count;
    SceneWriteLimit(OutputStream delegate, long limit) { this.delegate = delegate; this.limit = limit; }
    private void reserve(int amount) throws IOException {
        if (amount < 0 || amount > limit - count) throw new IOException("Scene encoded stream exceeds inflated byte budget");
        count += amount;
    }
    @Override public void write(int value) throws IOException { reserve(1); delegate.write(value); }
    @Override public void write(byte[] bytes, int offset, int length) throws IOException { reserve(length); delegate.write(bytes, offset, length); }
    @Override public void flush() throws IOException { delegate.flush(); }
    @Override public void close() throws IOException { delegate.close(); }
}
