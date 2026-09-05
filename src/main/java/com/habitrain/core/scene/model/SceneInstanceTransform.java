package com.habitrain.core.scene.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * 单个场景模型副本在特定时刻的几何变换状态。
 */
public record SceneInstanceTransform(
        int instanceIndex,
        double[] position,
        double orbitAngleDegrees,
        double deltaAngleDegrees,
        SceneOrbitAxis axis,
        double[] rotationAxis,
        boolean rotateModelWithOrbit,
        double effectiveRadius,
        double verticalBob,
        double radialBob
) {
    public SceneInstanceTransform {
        position = position != null ? Arrays.copyOf(position, 3) : new double[]{0.0, 0.0, 0.0};
        rotationAxis = rotationAxis != null ? Arrays.copyOf(rotationAxis, 3) : new double[]{0.0, 1.0, 0.0};
        axis = axis != null ? axis : SceneOrbitAxis.Y;
    }

    @Override
    public double[] position() {
        return Arrays.copyOf(position, 3);
    }

    @Override
    public double[] rotationAxis() {
        return Arrays.copyOf(rotationAxis, 3);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneInstanceTransform that)) return false;
        return instanceIndex == that.instanceIndex &&
                Double.compare(orbitAngleDegrees, that.orbitAngleDegrees) == 0 &&
                Double.compare(deltaAngleDegrees, that.deltaAngleDegrees) == 0 &&
                rotateModelWithOrbit == that.rotateModelWithOrbit &&
                Double.compare(effectiveRadius, that.effectiveRadius) == 0 &&
                Double.compare(verticalBob, that.verticalBob) == 0 &&
                Double.compare(radialBob, that.radialBob) == 0 &&
                axis == that.axis &&
                Arrays.equals(position, that.position) &&
                Arrays.equals(rotationAxis, that.rotationAxis);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(instanceIndex, orbitAngleDegrees, deltaAngleDegrees, axis,
                rotateModelWithOrbit, effectiveRadius, verticalBob, radialBob);
        result = 31 * result + Arrays.hashCode(position);
        result = 31 * result + Arrays.hashCode(rotationAxis);
        return result;
    }
}
