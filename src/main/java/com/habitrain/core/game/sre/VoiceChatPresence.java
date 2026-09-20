package com.habitrain.core.game.sre;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Simple Voice Chat 可选依赖探针（审核 B25）。
 *
 * <p>{@code fabric.mod.json} 只把 {@code voicechat} 声明为 {@code suggests}，但 core 的启动
 * 路径里 {@code SREGameModeState} 直接以 {@code de.maxhenkel.voicechat.api.Group} 声明字段，
 * 且 {@code SREGameModeBase} / {@code VoiceGroupService} 直接 import 该 API。当前缺 voicechat
 * 不崩，纯粹因为这些访问恰好全被 {@code try/catch} 包围——属「无保障的巧合」。</p>
 *
 * <p>所有触碰 voicechat 类型的入口都应当先过 {@link #isLoaded()} 守卫，
 * 使「没有 voicechat」成为确定性分支而不是依赖异常兜底。</p>
 */
public final class VoiceChatPresence {

    private static final String MOD_ID = "voicechat";

    private static volatile Boolean loaded;

    private VoiceChatPresence() {}

    /** Simple Voice Chat 是否已加载。首次调用后缓存（模组列表在运行期不变）。 */
    public static boolean isLoaded() {
        Boolean cached = loaded;
        if (cached != null) {
            return cached;
        }
        boolean present;
        try {
            present = FabricLoader.getInstance().isModLoaded(MOD_ID);
        } catch (Throwable t) {
            present = false;
        }
        loaded = present;
        return present;
    }
}
