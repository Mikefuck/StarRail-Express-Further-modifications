package com.habitrain.core.client.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThroughWallDepthFunctionTest {

    @Test
    void overlayUsesAlwaysAndThenRestoresNormalDepthComparison() {
        List<Integer> appliedFunctions = new ArrayList<>();

        ThroughWallDepthFunction.apply(appliedFunctions::add);
        ThroughWallDepthFunction.restore(appliedFunctions::add);

        assertEquals(List.of(0x0207, 0x0203), appliedFunctions);
    }
}
