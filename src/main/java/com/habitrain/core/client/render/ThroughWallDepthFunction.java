package com.habitrain.core.client.render;

import java.util.function.IntConsumer;

/** Explicit depth-function transition for the through-wall overlay pass. */
final class ThroughWallDepthFunction {

    static final int ALWAYS = 0x0207;
    static final int LEQUAL = 0x0203;

    private ThroughWallDepthFunction() {
    }

    static void apply(IntConsumer depthFunctionSetter) {
        depthFunctionSetter.accept(ALWAYS);
    }

    static void restore(IntConsumer depthFunctionSetter) {
        depthFunctionSetter.accept(LEQUAL);
    }
}
