package com.habitrain.core.internal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreLifecycleScopeTest {
    @Test void providerCallbacksSuspendPrivilegeEvenWhenTheyReenterAScope() {
        CoreLifecycleTestSupport.run(() -> {
            assertTrue(CoreLifecycleScope.isActive());
            CoreLifecycleScope.runUnprivileged(() -> {
                assertFalse(CoreLifecycleScope.isActive());
                assertFalse(com.habitrain.core.api.spi.CoreLifecycle.isActive());
                CoreLifecycleTestSupport.run(() -> assertFalse(CoreLifecycleScope.isActive()));
            });
            assertTrue(CoreLifecycleScope.isActive());
        });
        assertFalse(CoreLifecycleScope.isActive());
    }

    @Test void throwingProviderRestoresBootstrapPrivilegeAndFinallyLeavesTheScope() {
        CoreLifecycleTestSupport.run(() -> {
            assertThrows(IllegalStateException.class, () -> CoreLifecycleScope.runUnprivileged(() -> {
                assertFalse(CoreLifecycleScope.isActive());
                throw new IllegalStateException("provider failed");
            }));
            assertTrue(CoreLifecycleScope.isActive());
        });
        assertFalse(CoreLifecycleScope.isActive());
    }

    @Test void nestedScopeAndExceptionRestoreDepth() {
        assertFalse(CoreLifecycleScope.isActive());
        assertThrows(IllegalArgumentException.class, () -> CoreLifecycleTestSupport.run(() -> {
            assertTrue(CoreLifecycleScope.isActive());
            CoreLifecycleTestSupport.run(() -> assertTrue(CoreLifecycleScope.isActive()));
            assertTrue(CoreLifecycleScope.isActive());
            throw new IllegalArgumentException("injected");
        }));
        assertFalse(CoreLifecycleScope.isActive());
    }
    @Test void externalTestSourceAndUnknownSourceCannotEnterPublicPort() {
        assertTrue(CoreLifecycleScope.hasCoreSource(CoreLifecycleScope.class));
        assertFalse(CoreLifecycleScope.hasCoreSource(CoreLifecycleScopeTest.class));
        assertFalse(CoreLifecycleScope.hasCoreSource(String.class));
        assertThrows(IllegalStateException.class, () -> CoreLifecycleScope.run(() -> fail("entered")));
        assertFalse(CoreLifecycleScope.isActive());
    }
}
