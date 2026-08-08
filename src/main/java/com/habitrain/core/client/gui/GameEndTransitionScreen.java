package com.habitrain.core.client.gui;

import com.habitrain.core.network.GameEndTransitionPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

/**
 * 对局结束结算画面（2D）。
 * <p>
 * 时间轴：扫屏入场 → 标题进入/停留/淡出 → 胜负文字进入/停留 → 退出。
 * 由 {@link GameEndTransitionPayload} 驱动，服务端在 {@code GameStatus.STOPPING}
 * 时广播，环境就绪后二次广播推进。
 */
public final class GameEndTransitionScreen extends Screen {

    private static final long SWEEP_DURATION_MILLIS = 1150L;
    private static final long TITLE_ENTER_MILLIS = 650L;
    private static final long TITLE_HOLD_MILLIS = 1200L;
    private static final long TITLE_FADE_MILLIS = 650L;
    private static final long WIN_GAP_MILLIS = 120L;
    private static final long WIN_ENTER_MILLIS = 900L;
    private static final long WIN_HOLD_MILLIS = 1500L;
    private static final long EXIT_MILLIS = 900L;
    private static final long END_CLEAR_GRACE_MILLIS = 4000L;

    private static final int FEATHER_WIDTH = 26;
    private static final int VOID = 0xFF0B0706;
    private static final int INK = 0xFF191A10;
    private static final int GOLD_DARK = 0xFF8E8A8E;
    private static final int GOLD = 0xFFD9D9E9;
    private static final int GOLD_BRIGHT = 0xFFFFE0A0;
    private static final int IVORY = 0xFFF7EFAF;
    private static final int TEXT = 0xFFF4F3F6;
    private static final int TEXT_MUTED = 0xFFB8B8B5;
    private static final int KILLERS_COLOR = 0xFFB3A8A8;
    private static final float RESULT_TITLE_SCALE = 3.25f;

    private final long startedAtMillis;
    private String winStatusName = "";
    private String modeId = "";
    private String customWinnerId = "";
    private int customWinnerColor = 0;
    private String customTitleJson = "";
    private boolean environmentReady = false;
    private boolean exitStarted = false;
    private long exitStartAtMillis = 0;
    private boolean completed = false;
    private boolean gameFinished = false;

    public GameEndTransitionScreen(GameEndTransitionPayload payload) {
        super(Component.translatable("gameend.habitrain_core.title"));
        this.startedAtMillis = System.currentTimeMillis();
        applyPayload(payload);
        GameEndOverlayState.setActive(true);
    }

    private void applyPayload(GameEndTransitionPayload payload) {
        if (payload == null) return;
        if (payload.winStatusName() != null && !payload.winStatusName().isBlank()) {
            this.winStatusName = payload.winStatusName();
        }
        if (payload.modeId() != null && !payload.modeId().isBlank()) {
            this.modeId = payload.modeId();
        }
        if (payload.customWinnerId() != null) {
            this.customWinnerId = payload.customWinnerId();
        }
        this.customWinnerColor = payload.customWinnerColor();
        if (payload.customTitleJson() != null) {
            this.customTitleJson = payload.customTitleJson();
        }
        if (payload.environmentReady()) {
            this.environmentReady = true;
            this.gameFinished = true;
        }
    }

    public void markGameFinished() {
        this.gameFinished = true;
    }

    public void update(GameEndTransitionPayload payload) {
        applyPayload(payload);
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if (!exitStarted) {
            long total = startedAtMillis + SWEEP_DURATION_MILLIS + TITLE_ENTER_MILLIS + TITLE_HOLD_MILLIS
                    + TITLE_FADE_MILLIS + WIN_GAP_MILLIS + WIN_ENTER_MILLIS + WIN_HOLD_MILLIS;
            if (now >= total && environmentReady && gameFinished) {
                startExit(now);
            }
        } else if (exitProgress() >= 1f) {
            completeTransition();
        }
    }

    private void startExit(long now) {
        if (exitStarted) return;
        exitStarted = true;
        exitStartAtMillis = now;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        if (exitStarted) {
            renderExit(g, mouseX, mouseY, partialTicks);
        } else {
            renderLaunch(g, mouseX, mouseY, partialTicks);
        }
    }

    private void renderLaunch(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        float sweep = sweepProgress();
        float eased = easeInOutCubic(sweep);
        float offset = width * (1f - eased);
        g.pose().pushPose();
        g.pose().translate(offset, 0, 0);
        renderComposition(g, partialTicks);
        g.pose().popPose();
        if (sweep < 1f) {
            renderSlideEdge(g, offset, eased, 1);
        }
    }

    private void renderComposition(GuiGraphics g, float partialTicks) {
        long elapsed = System.currentTimeMillis() - startedAtMillis;

        float titleEnter = Mth.clamp((elapsed - SWEEP_DURATION_MILLIS) / (float) TITLE_ENTER_MILLIS, 0f, 1f);
        float titleEnterBack = easeOutBack(titleEnter);
        float titleEnterCubic = easeOutCubic(titleEnter);

        long titleFadeBase = SWEEP_DURATION_MILLIS + TITLE_ENTER_MILLIS + TITLE_HOLD_MILLIS;
        float titleFade = Mth.clamp((elapsed - titleFadeBase) / (float) TITLE_FADE_MILLIS, 0f, 1f);
        float titleFadeEased = easeInOutCubic(titleFade);

        long winBase = titleFadeBase + TITLE_FADE_MILLIS + WIN_GAP_MILLIS;
        float winEnter = Mth.clamp((elapsed - winBase) / (float) WIN_ENTER_MILLIS, 0f, 1f);
        float winEnterBack = easeOutBack(winEnter);
        float winEnterCubic = easeOutCubic(winEnter);

        // 背景渐变
        g.fillGradient(0, 0, width, height, withAlpha(0x110B07, 255), withAlpha(VOID, 255));

        int cx = width / 2;
        int cy = height / 2 - 18;
        float panelAlpha = 1f - 0.25f * winEnterCubic;
        int panelW = Math.min(600, width - 40);
        int panelH = 230;
        int panelTop = cy - panelH / 2;

        // 面板描边 + 填充
        g.fillGradient(cx - panelW / 2, panelTop, cx + panelW / 2, cy,
                withAlpha(GOLD_DARK, Math.round(30f * panelAlpha)),
                withAlpha(GOLD_DARK, Math.round(8f * panelAlpha)));
        g.fillGradient(cx - panelW / 2, panelTop, cx + panelW / 2, panelTop + panelH,
                withAlpha(GOLD_DARK, Math.round(8f * panelAlpha)),
                withAlpha(GOLD_DARK, 0));

        renderFlowingLines(g);
        renderParticles(g);

        // 徽章
        float emblemT = Mth.clamp((partialTicks - 0.3f) / 0.25f, 0f, 1f);
        float emblemCubic = easeOutCubic(emblemT);
        if (emblemCubic > 0f) {
            drawEmblem(g, cx, cy - 98,
                    Math.round(230f * emblemCubic * (1f - 0.65f * titleFadeEased)), 1);
        }

        // 标题
        int titleAlpha = Math.round(255f * titleEnterCubic * (1f - titleFadeEased));
        MutableComponent title = Component.translatable("gameend.habitrain_core.title")
                .withStyle(ChatFormatting.GOLD);
        if (titleAlpha > 0.5f) {
            float scale = RESULT_TITLE_SCALE * (0.84f + 0.16f * titleEnterBack)
                    * (1f + 0.04f * titleFadeEased);
            drawScaledCentered(g, title, cx, cy - 18f * titleFadeEased, scale,
                    withAlpha(GOLD_BRIGHT, titleAlpha));
        }

        // 胜负文字
        int winAlpha = Math.round(255f * winEnterCubic);
        if (winAlpha > 0.5f) {
            Component winText = winLine();
            float winY = cy + 8f + 34f * (1f - winEnterCubic);
            float winScale = fittedScale(winText) * (0.82f + 0.18f * winEnterBack);
            drawScaledCentered(g, winText, cx, winY, winScale, withAlpha(winColor(), winAlpha));
        }

        // 模式文字
        float modeT = Mth.clamp((winEnter - 0.45f) / 0.55f, 0f, 1f);
        float modeCubic = easeOutCubic(modeT);
        if (modeCubic > 0.01f) {
            drawScaledCentered(g, modeLine(), cx, cy + 58, 0.9f,
                    withAlpha(TEXT_MUTED, Math.round(205f * modeCubic)));
        }
    }

    private Component winLine() {
        boolean isCustomComponent = "CUSTOM_COMPONENT".equals(winStatusName);
        boolean isCustom = isCustomComponent || "CUSTOM".equals(winStatusName);

        if (isCustomComponent && !customTitleJson.isBlank()) {
            try {
                Component parsed = Component.Serializer.fromJson(customTitleJson,
                        Minecraft.getInstance().level != null
                                ? Minecraft.getInstance().level.registryAccess()
                                : net.minecraft.core.RegistryAccess.EMPTY);
                if (parsed != null && !parsed.getString().isBlank()) {
                    return parsed;
                }
            } catch (Throwable ignored) {}
        }

        if (isCustom && !customWinnerId.isBlank()) {
            String shortId = shortOptionId(customWinnerId).toLowerCase(java.util.Locale.ROOT);
            String key = "gameend.habitrain_core.win." + shortId;
            if (net.minecraft.client.resources.language.I18n.exists(key)) {
                return Component.translatable(key);
            }
            String fallback = "gameend.habitrain_core.win.custom";
            if (net.minecraft.client.resources.language.I18n.exists(fallback)) {
                return Component.translatable(fallback, Component.literal(shortId));
            }
            return Component.translatable(fallback, Component.literal(shortId));
        }

        String segment;
        switch (winStatusName) {
            case "KILLERS" -> segment = "killers";
            case "PASSENGERS", "TIME" -> segment = "passengers";
            case "LOOSE_END" -> segment = "loose_end";
            case "GAMBLER" -> segment = "gambler";
            case "RECORDER" -> segment = "recorder";
            case "NO_PLAYER" -> segment = "noplayer";
            case "NONE" -> segment = "none";
            case "NIAN_SHOU" -> segment = "nianshou";
            case "LOVERS" -> segment = "lovers";
            default -> segment = "unknown";
        }
        String key = "gameend.habitrain_core.win." + segment;
        if (net.minecraft.client.resources.language.I18n.exists(key)) {
            return Component.translatable(key);
        }
        return Component.translatable("gameend.habitrain_core.win.custom", Component.literal(segment));
    }

    private int winColor() {
        if ("CUSTOM_COMPONENT".equals(winStatusName) || "CUSTOM".equals(winStatusName)) {
            return customWinnerColor != 0 ? customWinnerColor : GOLD_BRIGHT;
        }
        return switch (winStatusName) {
            case "KILLERS" -> KILLERS_COLOR;
            case "PASSENGERS" -> GOLD_BRIGHT;
            case "TIME" -> GOLD;
            case "LOOSE_END" -> 0xFF9F6F00;
            case "GAMBLER" -> 0xFF800080;
            case "RECORDER", "NO_PLAYER", "NONE" -> 0xFFC0C0C0;
            case "NIAN_SHOU" -> 0xFFFF4500;
            case "LOVERS" -> 0xFFF38AFF;
            default -> TEXT_MUTED;
        };
    }

    private Component modeLine() {
        if (modeId.isBlank()) return Component.literal("");
        String key = com.habitrain.core.client.gui.OptionVoteTexts.optionLangKey(modeId);
        if (net.minecraft.client.resources.language.I18n.exists(key)) {
            return Component.translatable(key);
        }
        return Component.literal(shortOptionId(modeId));
    }

    private float fittedScale(Component text) {
        float maxWidth = Math.min(560, width - 120);
        int textWidth = font.width(text);
        if (textWidth <= 0) return RESULT_TITLE_SCALE;
        return Math.max(0.8f, Math.min(RESULT_TITLE_SCALE, maxWidth / (float) textWidth));
    }

    private void renderExit(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        float exit = exitProgress();
        float eased = easeInOutCubic(exit);
        float offset = -width * eased;
        g.pose().pushPose();
        g.pose().translate(offset, 0, 0);
        renderComposition(g, 1f);
        g.pose().popPose();
        if (exit < 1f) {
            renderSlideEdge(g, offset, eased, -1);
        }
    }

    private void renderFlowingLines(GuiGraphics g) {
        float t = System.currentTimeMillis() / 1000f;
        int baseY = height / 2 - 18;
        int[] ys = {baseY - 166, baseY - 136, baseY - 106, baseY + 98, baseY + 132, baseY + 166};
        int[] speeds = {96, 152, 112, 176, 130, 92};

        for (int i = 0; i < ys.length; i++) {
            int y = ys[i];
            float alpha = 0.75f + 0.25f * Mth.sin(t * 0.7f + i * 2.1f);
            g.fill(0, y, width, y + 1, withAlpha(GOLD_DARK, Math.round(26f * alpha)));

            int phase = 220 + i * 34;
            for (int k = 0; k < 3; k++) {
                float pos = t * speeds[i] + (k * (phase + 320)) + i * 97f;
                int x = Math.floorMod(Math.round(pos), width + phase) - width;
                renderStreak(g, x, y, phase, alpha, i, (i + k) % 2 == 0);
            }
        }

        int bandPhase = 190;
        int bandW = width + bandPhase * 2 + 60;
        float bandPos = t * bandW / 9f;
        float bandX = Math.floorMod(Math.round(bandPos), bandW) - (width + bandPhase + 30);
        renderLightBand(g, bandX);
    }

    private void renderStreak(GuiGraphics g, float x, int y, int phase, float alpha, int i, boolean bright) {
        int half = phase / 2;
        int seg = 12;
        int step = Math.max(1, half / seg);
        for (int k = 1; k <= seg; k++) {
            float p = k / (float) seg;
            int a = Math.round((4f + 44f * p * p) * alpha);
            int sx = (int) x - half + (seg - k) * step;
            g.fill(sx, y, sx + step, y + 1, withAlpha(bright ? GOLD_BRIGHT : GOLD, a));
        }
        g.fill((int) x - 1, y, (int) x + 2, y + 1, withAlpha(GOLD_BRIGHT, Math.round(175f * alpha)));
        for (int k = 0; k < seg; k++) {
            float p = 1f - k / (float) seg;
            int a = Math.round((4f + 30f * p * p) * alpha);
            int sx = (int) x + 1 + k * step;
            g.fill(sx, y, sx + step, y + 1, withAlpha(GOLD, a));
        }
    }

    private void renderLightBand(GuiGraphics g, float x) {
        int bandX = Mth.clamp(Math.round(x), 0, Math.max(0, width - 1));
        g.fill(bandX, 0, bandX + 1, height, withAlpha(GOLD_BRIGHT, 34));
        for (int i = 1; i <= 16; i++) {
            float p = 1f - i / 16f;
            int a = Math.round(26f * p * p);
            int left = Math.max(0, bandX - i);
            int right = Math.min(width - 1, bandX + i);
            g.fill(left, 0, left + 1, height, withAlpha(GOLD, a));
            g.fill(right, 0, right + 1, height, withAlpha(GOLD, a));
        }
    }

    private void renderParticles(GuiGraphics g) {
        float t = System.currentTimeMillis() / 1000f;
        int span = width + 60;
        for (int i = 0; i < 12; i++) {
            float speed = 30 + (i % 5) * 9;
            float x = Math.floorMod(Math.round(t * speed + i * 149f), span) - width;
            int y = 24 + Math.floorMod(i * 83 + 37, Math.max(48, height - 48));
            float alpha = 0.5f + 0.5f * Mth.sin(t * 2.3f + i * 1.9f);
            int color = i % 4 == 0 ? GOLD_BRIGHT : GOLD;
            g.fill((int) x, y, (int) x + (i % 3 == 0 ? 1 : 0), y + 1, withAlpha(color, Math.round(70f * alpha)));
        }
    }

    private void renderSlideEdge(GuiGraphics g, float offset, float eased, int dir) {
        if (offset < 0 || offset > width) return;
        float wave = Mth.sin(Mth.clamp(eased, 0f, 1f) * (float) Math.PI);
        for (int i = 1; i <= 26; i++) {
            int x = Mth.clamp(Math.round(offset) + dir * i, 0, Math.max(0, width - 1));
            float p = 1f - i / 26f;
            g.fill(x, 0, x + 1, height, withAlpha(INK, Math.round(150f * p * (0.25f + 0.75f * wave))));
        }
        int edge = Mth.clamp(Math.round(offset), 0, Math.max(0, width - 1));
        g.fill(edge - 1, 0, Math.min(width - 1, edge + 1), height,
                withAlpha(GOLD_BRIGHT, Math.round(150f + 100f * wave)));
        if (dir > 0) {
            g.fill(edge - 3, 0, edge - 1, height, withAlpha(GOLD, Math.round(70f * wave)));
        } else {
            g.fill(edge + 1, 0, Math.min(width - 1, edge + 3), height, withAlpha(GOLD, Math.round(70f * wave)));
        }
    }

    private void drawEmblem(GuiGraphics g, int cx, int cy, int alpha, float scale) {
        if (alpha <= 0) return;
        int r = 24;
        for (int i = 0; i < 40; i++) {
            double a = i * 2.0 * Math.PI / 40.0;
            int x = cx + (int) Math.round(Math.cos(a) * r);
            int y = cy + (int) Math.round(Math.sin(a) * r);
            g.fill(x, y, x + 1, y + 1, withAlpha(GOLD_DARK, Math.round(alpha * 0.55f)));
        }
        drawDiamond(g, cx, cy, 15, withAlpha(GOLD_DARK, alpha));
        drawDiamond(g, cx, cy, 11, withAlpha(GOLD, alpha));
        drawDiamond(g, cx, cy, 6, withAlpha(GOLD_BRIGHT, alpha));
        drawDiamond(g, cx, cy, 2, withAlpha(IVORY, Math.round(alpha * (0.6f + 0.4f * scale))));
    }

    private void completeTransition() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != this || completed) return;
        completed = true;
        GameEndOverlayState.scheduleGrace(END_CLEAR_GRACE_MILLIS);
        mc.setScreen(null);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) { return true; }

    @Override
    public boolean mouseReleased(double mx, double my, int button) { return true; }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) { return true; }

    @Override
    public boolean isPauseScreen() { return false; }

    private float sweepProgress() {
        return Mth.clamp((System.currentTimeMillis() - startedAtMillis) / (float) SWEEP_DURATION_MILLIS, 0f, 1f);
    }

    private float exitProgress() {
        if (!exitStarted) return 0f;
        return Mth.clamp((System.currentTimeMillis() - exitStartAtMillis) / (float) EXIT_MILLIS, 0f, 1f);
    }

    private void drawScaledCentered(GuiGraphics g, Component text, float x, float y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawCenteredString(font, text, 0, 0, color);
        g.pose().popPose();
    }

    private static void drawDiamond(GuiGraphics g, int cx, int cy, int size, int color) {
        for (int dy = -size; dy <= size; dy++) {
            int half = size - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    private static float easeInOutCubic(float t) {
        t = Mth.clamp(t, 0f, 1f);
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static float easeOutBack(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static String shortOptionId(String id) {
        if (id == null) return "";
        int idx = id.lastIndexOf(':');
        if (idx >= 0 && idx + 1 < id.length()) {
            return id.substring(idx + 1);
        }
        return id;
    }
}
