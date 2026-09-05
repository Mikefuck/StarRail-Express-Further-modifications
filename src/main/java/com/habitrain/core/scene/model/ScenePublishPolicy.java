package com.habitrain.core.scene.model;

/**
 * 移动场景第三方方块兼容性发布策略。
 */
public enum ScenePublishPolicy {
    /**
     * 严格模式（默认）：出现缺失材质、缺少适配器或不支持着色器时，终止发布并保留上一份正常资产。
     */
    STRICT,

    /**
     * 跳过并警告模式：对缺少适配器或不支持的第三方方块，跳过该方块并记录在诊断报告中，
     * 允许管理员二次确认后提升；但严重的缺失纹理与无效图集永远阻断。
     */
    SKIP_AND_WARN;

    public static ScenePublishPolicy fromString(String str) {
        if (str == null) return STRICT;
        for (ScenePublishPolicy policy : values()) {
            if (policy.name().equalsIgnoreCase(str.trim())) {
                return policy;
            }
        }
        return STRICT;
    }
}
