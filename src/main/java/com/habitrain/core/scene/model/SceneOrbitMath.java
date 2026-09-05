package com.habitrain.core.scene.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 环绕旋转运动数学核心（坐标基底、角度进度、多副本分布、模型中心计算与单实例变换）。
 */
public final class SceneOrbitMath {
    private static final double EPSILON = 1.0e-6;

    private SceneOrbitMath() {}

    /**
     * 角度归一化至 [0.0, 360.0)。
     */
    public static double normalizeAngle(double angle) {
        if (Double.isNaN(angle) || Double.isInfinite(angle)) return 0.0;
        double a = angle % 360.0;
        if (a < 0.0) a += 360.0;
        return a;
    }

    /**
     * 浮点数取模，结果落在 [0.0, mod)。
     */
    public static double floorMod(double value, double mod) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        if (mod <= 0.0) return 0.0;
        return value - Math.floor(value / mod) * mod;
    }

    /**
     * 连续往返函数，在 [min, max] 区间内平滑往返且不发生瞬移。
     */
    public static double pingPong(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return min;
        if (max <= min) return min;
        double range = max - min;
        double cycle = 2.0 * range;
        double mod = floorMod(value, cycle);
        if (mod <= range) {
            return min + mod;
        } else {
            return min + (cycle - mod);
        }
    }

    /**
     * 旋转轴单位向量 A。
     */
    public static double[] unitAxis(SceneOrbitAxis axis) {
        if (axis == null) axis = SceneOrbitAxis.Y;
        return switch (axis) {
            case X -> new double[]{1.0, 0.0, 0.0};
            case Y -> new double[]{0.0, 1.0, 0.0};
            case Z -> new double[]{0.0, 0.0, 1.0};
        };
    }

    /**
     * 旋转平面 0° 基底向量 U。
     * <ul>
     *   <li>Y 轴（水平绕转）：0° 指向北侧 (0, 0, -1)</li>
     *   <li>X 轴（前后立转）：0° 指向上方 (0, 1, 0)</li>
     *   <li>Z 轴（左右立转）：0° 指向上方 (0, 1, 0)</li>
     * </ul>
     */
    public static double[] basisU(SceneOrbitAxis axis) {
        if (axis == null) axis = SceneOrbitAxis.Y;
        return switch (axis) {
            case X -> new double[]{0.0, 1.0, 0.0};  // Up (+Y)
            case Y -> new double[]{0.0, 0.0, -1.0}; // North (-Z)
            case Z -> new double[]{0.0, 1.0, 0.0};  // Up (+Y)
        };
    }

    /**
     * 旋转平面 90° 基底向量 V。
     * <ul>
     *   <li>Y 轴（水平绕转）：90° 指向东侧 (1, 0, 0)</li>
     *   <li>X 轴（前后立转）：90° 指向南侧 (0, 0, 1)</li>
     *   <li>Z 轴（左右立转）：90° 指向东侧 (1, 0, 0)</li>
     * </ul>
     */
    public static double[] basisV(SceneOrbitAxis axis) {
        if (axis == null) axis = SceneOrbitAxis.Y;
        return switch (axis) {
            case X -> new double[]{0.0, 0.0, 1.0};  // South (+Z)
            case Y -> new double[]{1.0, 0.0, 0.0};  // East (+X)
            case Z -> new double[]{1.0, 0.0, 0.0};  // East (+X)
        };
    }

    /**
     * 旋转平面在指定角度下的单位半径向量 B(θ)。
     */
    public static double[] unitRadiusVector(SceneOrbitAxis axis, double angleDegrees) {
        double rad = Math.toRadians(angleDegrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        if (axis == null) axis = SceneOrbitAxis.Y;
        return switch (axis) {
            case X -> new double[]{0.0, cos, sin};
            case Y -> new double[]{sin, 0.0, -cos};
            case Z -> new double[]{sin, cos, 0.0};
        };
    }

    /**
     * 从平面向量反算角度（以对应轴的 U/V 基底为基准，返回 [0, 360) 度数）。
     */
    public static double angleFromVector(SceneOrbitAxis axis, double[] vectorInPlane) {
        if (vectorInPlane == null || vectorInPlane.length < 3) return 0.0;
        double vx = vectorInPlane[0];
        double vy = vectorInPlane[1];
        double vz = vectorInPlane[2];
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < EPSILON) return 0.0;

        if (axis == null) axis = SceneOrbitAxis.Y;
        double rad = switch (axis) {
            case X -> Math.atan2(vz, vy);
            case Y -> Math.atan2(vx, -vz);
            case Z -> Math.atan2(vx, vy);
        };
        return normalizeAngle(Math.toDegrees(rad));
    }

    /**
     * 绕指定正交轴旋转向量。
     */
    public static double[] rotateAroundAxis(SceneOrbitAxis axis, double[] vector, double angleDegrees) {
        if (vector == null || vector.length < 3) return new double[]{0.0, 0.0, 0.0};
        double x = vector[0], y = vector[1], z = vector[2];
        if (Math.abs(angleDegrees) < 1e-8) return new double[]{x, y, z};

        if (axis == null) axis = SceneOrbitAxis.Y;
        return switch (axis) {
            case X -> {
                double rad = Math.toRadians(angleDegrees);
                double cos = Math.cos(rad);
                double sin = Math.sin(rad);
                yield new double[]{x, y * cos - z * sin, y * sin + z * cos};
            }
            case Y -> {
                double rad = Math.toRadians(-angleDegrees);
                double cos = Math.cos(rad);
                double sin = Math.sin(rad);
                yield new double[]{x * cos + z * sin, y, -x * sin + z * cos};
            }
            case Z -> {
                double rad = Math.toRadians(-angleDegrees);
                double cos = Math.cos(rad);
                double sin = Math.sin(rad);
                yield new double[]{x * cos - y * sin, x * sin + y * cos, z};
            }
        };
    }

    /**
     * 依次应用 Roll(Z) -> Pitch(X) -> Yaw(Y) 欧拉角固定旋转。
     */
    public static double[] rotateEuler(double[] vector, SceneRotation rotation) {
        if (vector == null || vector.length < 3) return new double[]{0.0, 0.0, 0.0};
        double x = vector[0], y = vector[1], z = vector[2];
        if (rotation == null || rotation.isZero()) return new double[]{x, y, z};

        // 1. Roll (around +Z)
        double radR = Math.toRadians(rotation.rollDegrees());
        double cosR = Math.cos(radR);
        double sinR = Math.sin(radR);
        double rx = x * cosR - y * sinR;
        double ry = x * sinR + y * cosR;
        double rz = z;

        // 2. Pitch (around +X)
        double radP = Math.toRadians(rotation.pitchDegrees());
        double cosP = Math.cos(radP);
        double sinP = Math.sin(radP);
        double px = rx;
        double py = ry * cosP - rz * sinP;
        double pz = ry * sinP + rz * cosP;

        // 3. Yaw (around +Y)
        double radY = Math.toRadians(rotation.yawDegrees());
        double cosY = Math.cos(radY);
        double sinY = Math.sin(radY);
        double yx = px * cosY + pz * sinY;
        double yy = py;
        double yz = -px * sinY + pz * cosY;

        return new double[]{yx, yy, yz};
    }

    /**
     * 计算模型自身几何中心的世界坐标 C = P0 + F(L)。
     * <pre>
     * 局部中心 L = (sizeX/2, sizeY/2, sizeZ/2)
     * 固定朝向变换 F(Q) = pivotLocal + Rfixed × (Q - pivotLocal)
     * 世界模型中心 C = 场景摆放位置 P0 + F(L)
     * </pre>
     */
    public static double[] calculateModelCenter(SceneBounds bounds, double[] displayOrigin,
                                                double[] pivotLocal, SceneRotation rotation) {
        double[] localCenter = calculateFixedLocalCenter(bounds, pivotLocal, rotation);
        double ox = displayOrigin != null && displayOrigin.length >= 1 ? displayOrigin[0] : 0.0;
        double oy = displayOrigin != null && displayOrigin.length >= 2 ? displayOrigin[1] : 0.0;
        double oz = displayOrigin != null && displayOrigin.length >= 3 ? displayOrigin[2] : 0.0;

        return new double[]{ox + localCenter[0], oy + localCenter[1], oz + localCenter[2]};
    }

    /**
     * 计算局部几何中心经过固定枢轴与初始朝向后的局部坐标 F(L)。
     */
    public static double[] calculateFixedLocalCenter(SceneBounds bounds,
                                                     double[] pivotLocal, SceneRotation rotation) {
        double sx = bounds != null ? bounds.sizeX() : 0.0;
        double sy = bounds != null ? bounds.sizeY() : 0.0;
        double sz = bounds != null ? bounds.sizeZ() : 0.0;
        double lx = sx / 2.0;
        double ly = sy / 2.0;
        double lz = sz / 2.0;

        double px = pivotLocal != null && pivotLocal.length >= 1 ? pivotLocal[0] : 0.0;
        double py = pivotLocal != null && pivotLocal.length >= 2 ? pivotLocal[1] : 0.0;
        double pz = pivotLocal != null && pivotLocal.length >= 3 ? pivotLocal[2] : 0.0;

        double[] q = new double[]{lx - px, ly - py, lz - pz};
        double[] rotated = rotateEuler(q, rotation);
        double fx = px + rotated[0];
        double fy = py + rotated[1];
        double fz = pz + rotated[2];
        return new double[]{fx, fy, fz};
    }

    /**
     * 解析当前生效的绕转中心方块坐标。
     */
    public static double resolveRadius(SceneProfile profile) {
        if (profile == null) return 0.0;
        SceneOrbitSettings orbit = profile.getOrbit();
        SceneOrbitAxis axis = orbit.getAxis();
        double[] a = unitAxis(axis);
        double[] c = resolveOrbitCenter(profile);
        double[] p0 = profile.getDisplayOrigin();
        double dx = p0[0] - c[0];
        double dy = p0[1] - c[1];
        double dz = p0[2] - c[2];
        double h = dx * a[0] + dy * a[1] + dz * a[2];
        double vx = dx - a[0] * h;
        double vy = dy - a[1] * h;
        double vz = dz - a[2] * h;
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    public static double[] resolveOrbitCenter(SceneProfile profile) {
        if (profile == null) return new double[]{0.0, 0.0, 0.0};
        SceneOrbitSettings orbit = profile.getOrbit();
        if (orbit.getCenterMode() == SceneOrbitCenterMode.MODEL_CENTER) {
            return calculateModelCenter(profile.getSourceBounds(), profile.getDisplayOrigin(),
                    profile.getPivotLocal(), profile.getRotationDegrees());
        } else {
            return orbit.getCenterWorld();
        }
    }

    /**
     * 根据中心和摆放位置反算起始方向 θ0。
     */
    public static double deriveStartAngle(double[] center, double[] displayOrigin, SceneOrbitAxis axis) {
        if (center == null || displayOrigin == null) return 0.0;
        double[] a = unitAxis(axis);
        double dx = displayOrigin[0] - center[0];
        double dy = displayOrigin[1] - center[1];
        double dz = displayOrigin[2] - center[2];
        double h = dx * a[0] + dy * a[1] + dz * a[2];
        double vx = dx - a[0] * h;
        double vy = dy - a[1] * h;
        double vz = dz - a[2] * h;
        return angleFromVector(axis, new double[]{vx, vy, vz});
    }

    /**
     * 解析起始方向 θ0（MODEL_CENTER 模式下动态反算，WORLD_BLOCK 模式下采用管理员配置）。
     */
    public static double resolveStartAngle(SceneProfile profile) {
        if (profile == null) return 0.0;
        SceneOrbitSettings orbit = profile.getOrbit();
        if (orbit.getCenterMode() == SceneOrbitCenterMode.MODEL_CENTER) {
            double[] center = resolveOrbitCenter(profile);
            return deriveStartAngle(center, profile.getDisplayOrigin(), orbit.getAxis());
        }
        return orbit.getStartAngleDegrees();
    }

    /**
     * 计算单轮角度行程 a(t)。
     */
    public static double calculateAngleProgress(double elapsedSeconds, double angularSpeed, double sweepDegrees) {
        double time = Math.max(0.0, elapsedSeconds);
        double speed = Math.max(0.0, angularSpeed);
        double sweep = Math.max(0.0, Math.min(360.0, sweepDegrees));
        if (sweep >= 360.0) {
            return floorMod(speed * time, 360.0);
        } else {
            return pingPong(speed * time, 0.0, sweep);
        }
    }

    /**
     * 计算实例 i 在圆周上的角度分布偏移 δi。
     */
    public static double instanceOffsetAngle(int instanceIndex, int totalInstances, double spreadDegrees) {
        if (totalInstances <= 1 || instanceIndex <= 0) return 0.0;
        double spread = Math.max(0.0, Math.min(360.0, spreadDegrees));
        if (Math.abs(spread - 360.0) < EPSILON) {
            return (instanceIndex * 360.0) / totalInstances;
        } else {
            return (instanceIndex * spread) / (totalInstances - 1);
        }
    }

    /**
     * 计算浮动位移（正弦波连续往返）。
     */
    public static double calculateBob(double amplitude, double cyclesPerSecond, double elapsedSeconds) {
        if (amplitude <= 0.0 || cyclesPerSecond <= 0.0) return 0.0;
        return amplitude * Math.sin(2.0 * Math.PI * cyclesPerSecond * Math.max(0.0, elapsedSeconds));
    }

    /**
     * 计算单个实例在时刻 t 的完整几何变换状态。
     */
    public static SceneInstanceTransform calculateInstanceTransform(SceneProfile profile, double elapsedSeconds, int instanceIndex) {
        if (profile == null) {
            return new SceneInstanceTransform(instanceIndex, new double[]{0, 0, 0}, 0, 0, SceneOrbitAxis.Y,
                    new double[]{0, 1, 0}, true, 0, 0, 0);
        }
        SceneOrbitSettings orbit = profile.getOrbit();
        SceneOrbitAxis axis = orbit.getAxis();
        double[] a = unitAxis(axis);
        double[] c = resolveOrbitCenter(profile);
        double[] p0 = profile.getDisplayOrigin();

        // 轴向偏移与平面初始向量
        double dx = p0[0] - c[0];
        double dy = p0[1] - c[1];
        double dz = p0[2] - c[2];
        double h = dx * a[0] + dy * a[1] + dz * a[2];
        double vx = dx - a[0] * h;
        double vy = dy - a[1] * h;
        double vz = dz - a[2] * h;
        double r = Math.sqrt(vx * vx + vy * vy + vz * vz);

        // 起始方向 θ0
        double theta0;
        if (orbit.getCenterMode() == SceneOrbitCenterMode.MODEL_CENTER) {
            theta0 = angleFromVector(axis, new double[]{vx, vy, vz});
        } else {
            theta0 = orbit.getStartAngleDegrees();
        }

        // 角度行程与副本分布
        double sign = orbit.isClockwise() ? 1.0 : -1.0;
        double at = calculateAngleProgress(elapsedSeconds, orbit.getAngularSpeedDegreesPerSecond(), orbit.getSweepDegrees());
        int totalInstances = Math.max(1, orbit.getInstanceCount());
        double deltaI = instanceOffsetAngle(instanceIndex, totalInstances, orbit.getInstanceSpreadDegrees());

        double dynamicDelta = sign * (at + deltaI);
        double currentAngle = normalizeAngle(theta0 + dynamicDelta);

        // 浮动位移
        double verticalBob = calculateBob(orbit.getVerticalBobAmplitudeBlocks(), orbit.getBobCyclesPerSecond(), elapsedSeconds);
        double radialBob = calculateBob(orbit.getRadialBobAmplitudeBlocks(), orbit.getBobCyclesPerSecond(), elapsedSeconds);
        double effectiveRadius = r + radialBob;

        // 半径单位向量与当前锚点位置
        double[] b = unitRadiusVector(axis, currentAngle);
        double px = c[0] + a[0] * h + b[0] * effectiveRadius;
        double py = c[1] + a[1] * h + b[1] * effectiveRadius + verticalBob;
        double pz = c[2] + a[2] * h + b[2] * effectiveRadius;

        boolean rotateModel = orbit.isRotateModelWithOrbit();

        return new SceneInstanceTransform(
                instanceIndex,
                new double[]{px, py, pz},
                currentAngle,
                dynamicDelta,
                axis,
                a,
                rotateModel,
                effectiveRadius,
                verticalBob,
                radialBob
        );
    }

    /**
     * 计算所有实例在时刻 t 的变换状态列表。
     */
    public static List<SceneInstanceTransform> calculateAllInstanceTransforms(SceneProfile profile, double elapsedSeconds) {
        List<SceneInstanceTransform> list = new ArrayList<>();
        if (profile == null) return list;
        int count = Math.max(1, profile.getOrbit().getInstanceCount());
        for (int i = 0; i < count; i++) {
            list.add(calculateInstanceTransform(profile, elapsedSeconds, i));
        }
        return list;
    }

    /**
     * 计算实例变换后的世界空间包围球。球心遵循正式渲染矩阵顺序：
     * T(instancePosition) * Rorbit * F；半径为模型局部 AABB 的半对角线。
     */
    public static SceneInstanceBounds calculateInstanceBounds(SceneProfile profile,
                                                               SceneInstanceTransform transform) {
        if (profile == null || transform == null) {
            return new SceneInstanceBounds(0.0, 0.0, 0.0, 0.0);
        }
        SceneBounds bounds = profile.getSourceBounds();
        double sx = bounds != null ? Math.max(0.0, bounds.sizeX()) : 0.0;
        double sy = bounds != null ? Math.max(0.0, bounds.sizeY()) : 0.0;
        double sz = bounds != null ? Math.max(0.0, bounds.sizeZ()) : 0.0;
        double radius = 0.5 * Math.sqrt(sx * sx + sy * sy + sz * sz);

        double[] localCenter = calculateFixedLocalCenter(
                bounds, profile.getPivotLocal(), profile.getRotationDegrees());
        if (transform.rotateModelWithOrbit()) {
            localCenter = rotateAroundAxis(transform.axis(), localCenter, transform.deltaAngleDegrees());
        }
        double[] position = transform.position();
        return new SceneInstanceBounds(
                position[0] + localCenter[0],
                position[1] + localCenter[1],
                position[2] + localCenter[2],
                radius
        );
    }
}
