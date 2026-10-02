package com.habitrain.core.role.capability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleVoiceAdapterBindingTest {
    private final AtomicInteger binds = new AtomicInteger();

    @BeforeEach
    void setUp() {
        RoleVoiceAdapterBinding.resetForTest(binds::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        RoleVoiceAdapterBinding.resetForTest(null);
    }

    @Test
    void voicechatBeforeCoreDefersUntilCoreReady() {
        RoleVoiceAdapterBinding.requestFromVoicechat();
        assertEquals(0, binds.get(), "must not touch the capability API before core installs the SPI");
        RoleVoiceAdapterBinding.markCoreReady();
        assertEquals(1, binds.get());
    }

    @Test
    void coreBeforeVoicechatBindsImmediately() {
        RoleVoiceAdapterBinding.markCoreReady();
        assertEquals(0, binds.get(), "core alone must not claim voice support");
        RoleVoiceAdapterBinding.requestFromVoicechat();
        assertEquals(1, binds.get());
    }

    @Test
    void bindsOnlyOnce() {
        RoleVoiceAdapterBinding.requestFromVoicechat();
        RoleVoiceAdapterBinding.markCoreReady();
        RoleVoiceAdapterBinding.requestFromVoicechat();
        RoleVoiceAdapterBinding.markCoreReady();
        assertEquals(1, binds.get());
    }
}
