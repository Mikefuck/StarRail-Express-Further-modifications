package com.habitrain.core.client.gui;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;
class PreviewImageLimitsTest {
    private byte[] header(int w, int h) {
        return ByteBuffer.allocate(33).putLong(0x89504e470d0a1a0aL).putInt(13).putInt(0x49484452).putInt(w).putInt(h).array();
    }
    @Test void acceptsTheServersFullLegalImageRange() {
        assertTrue(PreviewImageLimits.accepts(header(1024,1024)));
        assertTrue(PreviewImageLimits.accepts(header(1,1)));
    }
    @Test void rejectsAllocationAmplificationBeforeDecode() {
        for (int size : new int[]{0,-1,1025,Integer.MAX_VALUE}) assertFalse(PreviewImageLimits.accepts(header(size,1024)));
        assertFalse(PreviewImageLimits.accepts(header(1024,Integer.MAX_VALUE)));
        byte[] forged=header(16,16); forged[11]=12;
        assertFalse(PreviewImageLimits.accepts(forged));
        assertFalse(PreviewImageLimits.accepts(new byte[128*1024+1]));
        assertFalse(PreviewImageLimits.accepts(new byte[32]));
    }
}
