package com.habitrain.core.scene.asset;

import net.minecraft.core.registries.BuiltInRegistries;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 稳定的方块注册表指纹，用于拒绝在不兼容模组集合上构建场景网格。 */
public final class SceneRegistryFingerprint {
    private SceneRegistryFingerprint() {}

    private static volatile String cached;

    public static String calculate() {
        String value = cached;
        if (value != null) return value;
        List<String> ids = new ArrayList<>();
        BuiltInRegistries.BLOCK.keySet().forEach(id -> ids.add(id.toString()));
        ids.sort(String::compareTo);
        value = SceneAssetCodec.calculateSha256(String.join("\n", ids).getBytes(StandardCharsets.UTF_8));
        cached = value;
        return value;
    }
}
