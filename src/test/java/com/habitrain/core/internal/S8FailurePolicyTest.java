package com.habitrain.core.internal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class S8FailurePolicyTest {
    @Test void fatalErrorsEscapeWithTheirOriginalIdentity() {
        var linkage=new NoClassDefFoundError("missing binary");
        assertSame(linkage,assertThrows(LinkageError.class,()->FailurePolicy.rethrowIfFatal(linkage)));
        var vm=new OutOfMemoryError("synthetic, no allocation pressure");
        assertSame(vm,assertThrows(VirtualMachineError.class,()->FailurePolicy.rethrowIfFatal(vm)));
        var death=new ThreadDeath();
        assertSame(death,assertThrows(ThreadDeath.class,()->FailurePolicy.rethrowIfFatal(death)));
    }
    @Test void nonFatalAddonFailuresCanStillBeIsolated() {
        assertDoesNotThrow(()->FailurePolicy.rethrowIfFatal(new IllegalStateException("addon")));
        assertDoesNotThrow(()->FailurePolicy.rethrowIfFatal(new AssertionError("addon assertion")));
    }
}
