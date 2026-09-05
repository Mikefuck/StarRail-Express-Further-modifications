package com.habitrain.core.scene.model;

/**
 * 环绕旋转轴（正交基底定义）。
 */
public enum SceneOrbitAxis {
    /** 绕世界 X 轴旋转（在 Y-Z 垂直平面内运动）。 */
    X,
    /** 绕世界 Y 轴旋转（在 X-Z 水平平面内运动，默认水平绕转）。 */
    Y,
    /** 绕世界 Z 轴旋转（在 X-Y 垂直平面内运动）。 */
    Z
}
