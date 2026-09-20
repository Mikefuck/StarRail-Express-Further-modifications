package com.habitrain.core.api;

import java.util.Locale;

/**
 * Canonical short game-mode ids and mapping from registry / SRE identifiers.
 *
 * <p>Registry keys are {@code habitrain_core:<shortId>} (see
 * {@link GameModeRegistry#register}). Upstream SRE blackout is {@code sre:blackout}
 * and canonicalizes to {@link #BLACKOUT}.
 */
public final class GameModeIds {
    /**
     * 停电模式的规范短 ID。
     *
     * <p>审核 B8：本常量曾被标为 {@code @Deprecated}（"this mode is no longer registered
     * or playable"），但它同时是 {@link #canonical} 的活跃返回值之一，也是
     * {@link MatchSettlement#modeId()} 的合法取值——「已废弃的常量」与「当前对外返回值」
     * 自相矛盾，按文档避开它的第三方会漏判停电局。现在恢复为活跃语义：
     * <b>本模组不再注册/不可玩该模式，但历史与上游对局仍然会产生这个 ID。</b></p>
     */
    public static final String BLACKOUT = "habitrain:blackout";
    public static final String MURDER = "sre:murder";
    public static final String REPAIR = "sre:repair";

    public static final String REGISTRY_BLACKOUT = "habitrain_core:habitrain:blackout";
    public static final String REGISTRY_MURDER = "habitrain_core:sre:murder";
    public static final String REGISTRY_REPAIR = "habitrain_core:sre:repair";

    public static final String SRE_BLACKOUT = "sre:blackout";
    public static final String SRE_REPAIR_ESCAPE = "canyuesama:repair_escape";

    private GameModeIds() {}

    /**
     * Maps any registry / SRE / class-name guess to a canonical short id.
     *
     * <h2>审核 A-15：{@code null} / 空白 的语义</h2>
     * <p>旧实现把 {@code null} 与空白静默映射为 {@link #MURDER}，且<b>没有任何文档说明</b>——
     * 调用方无法区分「上游告诉我这是谋杀模式」与「我读不到模式 id」，
     * 而后者恰恰是最需要走保守分支的情形。
     *
     * <p>返回值语义保持不变（{@code MURDER}）以维持二进制兼容，但现在
     * <b>显式文档化</b>：{@code null} / 空白是「未知模式」，本方法按历史约定回落到
     * {@link #MURDER}。<b>需要区分「未知」的调用方请用
     * {@link #canonicalOrNull(String)}</b>——它只做映射，不做回落。
     *
     * @param raw 上游模式 id（{@code sre:murder} / {@code sre:blackout} /
     *            {@code canyuesama:repair_escape} / 类名片段 / 注册表 fullId）
     * @return 规范短 id；{@code null} 或空白按 {@link #MURDER} 处理（见上）
     */
    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return MURDER;
        }
        // 审核 B9：Locale.ROOT，避免 tr/az 默认 Locale 让 "REPAIR"/"MURDER" 漏判。
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (isBlackout(lower)) {
            return BLACKOUT;
        }
        if (isRepair(lower)) {
            return REPAIR;
        }
        if (isMurder(lower)) {
            return MURDER;
        }
        int slash = lower.lastIndexOf('.');
        if (slash >= 0 && slash + 1 < lower.length()) {
            return canonical(lower.substring(slash + 1));
        }
        return raw.trim();
    }

    /**
     * 与 {@link #canonical(String)} 相同的映射，但<b>不做</b> {@code MURDER} 回落
     * （审核 A-15）：{@code null} / 空白返回 {@link Optional#empty()}，
     * 让调用方能区分「未知模式」与「谋杀模式」。
     */
    public static java.util.Optional<String> canonicalOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(canonical(raw));
    }

    /**
     * 与 {@link #canonical(String)} 相同，但 {null} / 空白返回 {@code null}
     * （便于放进已有 {@code @Nullable} 语义的字段）。
     */
    public static @org.jetbrains.annotations.Nullable String canonicalOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : canonical(raw);
    }

    public static boolean isBlackout(String raw) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("blackout")
                || BLACKOUT.equals(lower)
                || REGISTRY_BLACKOUT.equals(lower)
                || SRE_BLACKOUT.equals(lower);
    }

    public static boolean isRepair(String raw) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("repair")
                || REPAIR.equals(lower)
                || REGISTRY_REPAIR.equals(lower)
                || SRE_REPAIR_ESCAPE.equals(lower)
                || lower.contains("repair_escape");
    }

    public static boolean isMurder(String raw) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return MURDER.equals(lower)
                || REGISTRY_MURDER.equals(lower)
                || lower.contains("sre:murder")
                || (lower.contains("murder") && !isBlackout(lower) && !isRepair(lower));
    }
}
