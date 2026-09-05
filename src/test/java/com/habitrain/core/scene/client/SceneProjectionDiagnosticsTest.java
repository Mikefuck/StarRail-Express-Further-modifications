package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneProjectionDiagnosticsTest {
    @Test
    void extractsFarPlaneFromStandardPerspectiveCoefficients() {
        double near = 0.05;
        double far = 768.0;
        double m22 = -(far + near) / (far - near);
        double m32 = -(2.0 * far * near) / (far - near);

        assertEquals(far, SceneProjectionDiagnostics.estimatePerspectiveFarPlane(m22, m32), 1.0e-4);
        assertTrue(Double.isNaN(SceneProjectionDiagnostics.estimatePerspectiveFarPlane(-1.0, -0.1)));
    }
}
