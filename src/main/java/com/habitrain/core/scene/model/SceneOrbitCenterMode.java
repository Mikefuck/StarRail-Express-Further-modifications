package com.habitrain.core.scene.model;

/**
 * 环绕旋转中心模式。
 */
public enum SceneOrbitCenterMode {
    /** 围绕世界中指定方块坐标旋转。 */
    WORLD_BLOCK,
    /** 围绕模型自身几何中心自转（根据选区尺寸、pivotLocal、固定朝向与摆放位置动态计算）。 */
    MODEL_CENTER
}
