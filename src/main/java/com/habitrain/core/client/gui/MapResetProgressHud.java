package com.habitrain.core.client.gui;

import com.habitrain.core.client.RepairModeClientState;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * 「重置地图中」进度牌：上游重置地图期间挂在屏幕上缘正中的小号发车牌（与投票面板同一套视觉）。
 *
 * <p>它是 HUD 而不是 Screen——不抢鼠标、不吞按键，玩家在整备期间可以照常走动、聊天、开背包。
 * 真正要把玩家传送进对局时（判定点 A，{@code MapVoteStartConfirmedPayload}）才打开全屏的
 * {@link VoteLaunchTransitionScreen}，进度牌随之收回。</p>
 *
 * <p>数据源：有投票会话时用服务端 {@code MapVoteProgressPayload}（{@link VoteLaunchSession}）；
 * 没有时（指令开局、切换地图、中途加入）用上游 actionbar 推导的 {@link MapResetProgressTracker}。
 * 上游那条显示在屏幕中下方的「重置地图中 xx%」actionbar 一律在客户端拦下，不再显示。</p>
 */
public final class MapResetProgressHud {
    private static final String RESETTING_KEY = "message.sre.reseting";
    private static final String STARTING_KEY = "message.sre.starting";

    private static final int MAX_PANEL_WIDTH = 300;
    private static final int SIDE = 10;
    private static final int HEADER_Y = 5;
    private static final int RAIL_Y = 31;
    private static final int FOOTER_Y = 37;
    private static final int PANEL_HEIGHT = FOOTER_Y + 13;

    private static final long PANEL_DROP_MILLIS = 560L;
    private static final long PANEL_RETRACT_MILLIS = 260L;
    /** 投票面板收回（220ms）后再落下，两块牌子一收一放而不是叠在一起。 */
    private static final long AFTER_VOTE_DELAY_MILLIS = 200L;

    private static final int GOLD_DARK = RailArt.BRASS_DIM;
    private static final int GOLD = RailArt.BRASS;
    private static final int GOLD_BRIGHT = RailArt.BRASS_LIGHT;
    private static final int IVORY = RailArt.CHAMPAGNE;
    private static final int TEXT = RailArt.TEXT;
    private static final int TEXT_MUTED = RailArt.TEXT_MUTED;

    private static final MapResetProgressTracker TRACKER = new MapResetProgressTracker();
    private static final RailArt.FlapBoard PERCENT_BOARD = new RailArt.FlapBoard();

    private static boolean registered;
    private static boolean shown;
    private static long shownAtMillis;
    private static long dropDelayMillis;
    private static long hiddenAtMillis = Long.MIN_VALUE / 2;
    private static long lastFrameMillis;
    private static float displayedProgress = -1.0f;

    // 收起动画期间沿用最后一帧的内容
    private static int targetProgress;
    private static MapResetProgressTracker.Stage stage = MapResetProgressTracker.Stage.CARRIAGES;
    private static Component destination = Component.empty();

    private MapResetProgressHud() {}

    /** 注册 actionbar 拦截。HUD 绘制由 {@code HudRegistrar} 统一挂到 HudRenderCallback。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
                !overlay || allowOverlayMessage(message));
    }

    /**
     * 上游 actionbar 过滤：「重置地图中 xx%」改由进度牌显示，拦下不画；
     * 「游戏开始！」照常显示，同时结束本轮进度。
     */
    static boolean allowOverlayMessage(Component message) {
        if (message == null || !(message.getContents() instanceof TranslatableContents contents)) {
            return true;
        }
        long now = Util.getMillis();
        if (RESETTING_KEY.equals(contents.getKey())) {
            int percent = parsePercent(contents.getArgs());
            if (percent >= 0) {
                TRACKER.observe(percent, isFixtureStage(message), now);
            }
            return false;
        }
        if (STARTING_KEY.equals(contents.getKey())) {
            TRACKER.finish(now);
        }
        return true;
    }

    /** 上游整车复制用黄色、复位机关方块用金色。 */
    private static boolean isFixtureStage(Component message) {
        TextColor color = message.getStyle().getColor();
        return color != null && color.equals(TextColor.fromLegacyFormat(ChatFormatting.GOLD));
    }

    private static int parsePercent(Object[] args) {
        if (args == null || args.length == 0) {
            return -1;
        }
        Object arg = args[0];
        String text = arg instanceof Component component ? component.getString() : String.valueOf(arg);
        try {
            return Mth.clamp((int) Math.round(Double.parseDouble(text.trim())), 0, 100);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** 换世界/断线时清空。 */
    public static void reset() {
        TRACKER.reset();
        shown = false;
        hiddenAtMillis = Long.MIN_VALUE / 2;
        displayedProgress = -1.0f;
        targetProgress = 0;
        destination = Component.empty();
    }

    /** 进度牌正在显示或收起中（供大厅顶部文字让位）。 */
    public static boolean isOccupyingTop() {
        return shown || Util.getMillis() - hiddenAtMillis < PANEL_RETRACT_MILLIS;
    }

    // ==================== 状态 ====================

    private static boolean sessionResetting() {
        return VoteLaunchSession.isActive()
                && !VoteLaunchSession.isStartConfirmed()
                && !VoteLaunchSession.isLaunchConfirmed()
                && !RepairModeClientState.isLocalRepairer();
    }

    /** 本帧是否应显示进度牌，并刷新进度/阶段/终点站。 */
    private static boolean refresh(long now) {
        boolean session = sessionResetting();
        boolean upstream = TRACKER.isVisible(now)
                && !VoteLaunchSession.isStartConfirmed()
                && !VoteLaunchSession.isLaunchConfirmed();
        if (!session && !upstream) {
            return false;
        }
        if (session) {
            targetProgress = VoteLaunchSession.getProgress();
            if (TRACKER.isRecent(now)) {
                stage = TRACKER.stage();
            } else {
                // 服务端进度：0-70 整车复制、70-90 复位机关、90 以后等待发车
                stage = targetProgress >= 90 ? MapResetProgressTracker.Stage.DEPARTING
                        : targetProgress >= 70 ? MapResetProgressTracker.Stage.FIXTURES
                        : MapResetProgressTracker.Stage.CARRIAGES;
            }
            destination = mapLabel(VoteLaunchSession.getWinningMapId());
        } else {
            targetProgress = TRACKER.progress();
            stage = TRACKER.stage();
            destination = Component.empty();
        }
        return true;
    }

    private static Component mapLabel(String mapId) {
        if (mapId == null || mapId.isBlank()) {
            return Component.empty();
        }
        for (var entry : OptionVoteState.getCandidates()) {
            if (mapId.equals(entry.optionId())) {
                return OptionVoteTexts.candidateLabel(entry.optionId(), entry.displayName());
            }
        }
        return OptionVoteTexts.candidateLabel(mapId, mapId);
    }

    // ==================== 绘制 ====================

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        long now = Util.getMillis();
        boolean wanted = refresh(now);
        if (wanted && !shown) {
            shown = true;
            shownAtMillis = now;
            // 投票面板正在收回时稍等一下再落下；收起途中又要显示则直接重新落下
            dropDelayMillis = mc.screen instanceof OptionVoteScreen ? AFTER_VOTE_DELAY_MILLIS : 0L;
        } else if (!wanted && shown) {
            shown = false;
            hiddenAtMillis = now;
        }
        if (!shown && now - hiddenAtMillis >= PANEL_RETRACT_MILLIS) {
            displayedProgress = -1.0f;
            return;
        }
        if (mc.options.hideGui) {
            return;
        }

        float dt = frameSeconds(now);
        if (displayedProgress < 0.0f || targetProgress < displayedProgress) {
            displayedProgress = targetProgress;
        } else {
            displayedProgress += (targetProgress - displayedProgress) * (1.0f - (float) Math.exp(-dt * 5.0f));
        }

        int screenW = g.guiWidth();
        int w = Math.min(MAX_PANEL_WIDTH, Math.max(Math.min(screenW - 8, 200), screenW - 24));
        w = Math.max(120, Math.min(w, screenW));
        int x = (screenW - w) / 2;
        float top = panelTop(now);
        if (top + PANEL_HEIGHT <= 0.0f) {
            return;
        }

        Font font = mc.font;
        float time = (now - shownAtMillis) / 1000.0f;
        RailArt.drawCeilingShade(g, screenW, top, PANEL_HEIGHT);
        RailArt.drawHangingPanel(g, x, w, top, PANEL_HEIGHT);
        int y = Math.round(top) + HEADER_Y;
        int[] titleBounds = renderHeader(g, font, x, w, y, time);
        renderTitle(g, font, x + w / 2.0f, y, titleBounds[0], titleBounds[1]);

        boolean departing = stage == MapResetProgressTracker.Stage.DEPARTING;
        float blink = 0.55f + 0.45f * Mth.sin(time * 5.0f);
        int signal = departing ? GOLD_BRIGHT : RailArt.a(RailArt.LAMP, 0.45f + 0.55f * blink);
        RailArt.drawRouteRail(g, x + SIDE, x + w - SIDE, top + RAIL_Y, displayedProgress / 100.0f,
                signal, RailArt.LAMP, time);

        renderFooter(g, font, x, w, Math.round(top) + FOOTER_Y);
    }

    /** 面板上缘的 y：落下时带一次轻微回弹；收起时向上滑出。 */
    private static float panelTop(long now) {
        float hidden = -(PANEL_HEIGHT + 14.0f);
        if (!shown) {
            float t = TransitionFx.clamp01((now - hiddenAtMillis) / (float) PANEL_RETRACT_MILLIS);
            return hidden * TransitionFx.easeInCubic(t);
        }
        float t = TransitionFx.clamp01((now - shownAtMillis - dropDelayMillis) / (float) PANEL_DROP_MILLIS);
        return hidden * (1.0f - easeOutBack(t, 1.15f));
    }

    /**
     * 标题行两端：左侧阶段铭牌（闪烁的信号菱形），右侧翻牌百分比。
     *
     * @return 标题可用的 {左边界, 右边界}
     */
    private static int[] renderHeader(GuiGraphics g, Font font, int x, int w, int y, float time) {
        Component phase = Component.translatable("vote.habitrain_core.reset_hud.phase");
        int chipX = x + SIDE;
        int chipW = font.width(phase) + 16;
        float[] chip = RailArt.chamfer(chipX, y - 1, chipW, 13, 3.0f);
        float blink = 0.5f + 0.5f * Mth.sin(time * 5.0f);
        GuiGeo geo = GuiGeo.begin(g);
        geo.convexV(chip, y - 1, y + 12, RailArt.a(0xFF22335F, 0.95f), RailArt.a(RailArt.NIGHT_2, 0.95f));
        geo.outline(chip, 0.8f, RailArt.a(GOLD_DARK, 1.0f));
        geo.diamond(chipX + 6.0f, y + 5.5f, 2.2f, 2.2f, RailArt.a(RailArt.LAMP, 0.4f + 0.6f * blink));
        geo.end();
        g.drawString(font, phase, chipX + 11, y + 2, TEXT, false);
        int leftEdge = chipX + chipW + 6;

        int shownPercent = Mth.clamp(Math.round(displayedProgress), 0, 100);
        PERCENT_BOARD.set(String.format(Locale.ROOT, "%02d", shownPercent));
        Component unit = Component.literal("%");
        int right = x + w - SIDE;
        int unitW = font.width(unit);
        g.drawString(font, unit, right - unitW, y + 2, GOLD, false);
        int tileW = 8;
        int boardW = Math.round(PERCENT_BOARD.tileWidth(tileW, 2));
        int boardX = right - unitW - 3 - boardW;
        PERCENT_BOARD.render(g, font, boardX, y - 1, tileW, 12, 2, IVORY,
                RailArt.NIGHT_4, RailArt.NIGHT_1, 1.0f);
        return new int[] {leftEdge, boardX - 6};
    }

    /** 中：双语标题「重置地图中 · RESETTING MAP」，放不下时省略副标题。 */
    private static void renderTitle(GuiGraphics g, Font font, float center, int y, int leftEdge, int rightEdge) {
        String key = "vote.habitrain_core.reset_hud.title";
        Component heading = Component.translatable(key).withStyle(ChatFormatting.BOLD);
        String sub = OptionVoteTexts.subtitle(key);
        float half = Math.min(center - leftEdge, rightEdge - center);
        if (half <= 10.0f) {
            return;
        }
        float subScale = TicketArt.crispFor(sub, 0.62f);
        float pairW = TransitionFx.inlinePairWidth(font, heading, sub, subScale);
        if (pairW > half * 2.0f) {
            sub = "";
            pairW = font.width(heading);
        }
        if (pairW <= half * 2.0f) {
            TransitionFx.drawInlinePair(g, font, heading, sub, center, y + 2, subScale, IVORY, GOLD, true);
            GuiGeo wings = GuiGeo.begin(g);
            float gap = pairW / 2.0f + 7.0f;
            float wing = Math.min(22.0f, half - gap - 2.0f);
            if (wing > 6.0f) {
                RailArt.drawTitleWings(wings, center, y + 5.5f, gap, wing, GOLD_DARK, 0.85f);
            }
            wings.end();
        } else {
            Component fitted = TicketArt.fit(font, heading, half * 2.0f);
            g.drawString(font, fitted, Math.round(center - font.width(fitted) / 2.0f), y + 2, IVORY, true);
        }
    }

    /** 提示行：左侧终点站，右侧当前整备阶段。 */
    private static void renderFooter(GuiGraphics g, Font font, int x, int w, int y) {
        int left = x + SIDE + 2;
        int right = x + w - SIDE - 2;
        int span = right - left;
        Component stageText = Component.translatable(switch (stage) {
            case CARRIAGES -> "vote.habitrain_core.reset_hud.stage_carriages";
            case FIXTURES -> "vote.habitrain_core.reset_hud.stage_fixtures";
            case DEPARTING -> "vote.habitrain_core.reset_hud.stage_departing";
        });
        Component status = destination.getString().isBlank()
                ? Component.translatable("vote.habitrain_core.reset_hud.free_move").withStyle(ChatFormatting.GRAY)
                : Component.literal("▸ ").withStyle(ChatFormatting.GOLD)
                        .append(Component.translatable("vote.habitrain_core.reset_hud.destination",
                                destination.copy().withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.GRAY));
        int stageW = Math.min(font.width(stageText), span * 2 / 5);
        int statusRoom = span - stageW - 12;
        if (statusRoom > 30) {
            g.drawString(font, TicketArt.fit(font, status, statusRoom), left, y, TEXT, false);
        }
        Component fittedStage = TicketArt.fit(font, stageText, stageW);
        g.drawString(font, fittedStage, right - font.width(fittedStage), y, TEXT_MUTED, false);
    }

    // ==================== 工具 ====================

    private static float frameSeconds(long now) {
        if (lastFrameMillis <= 0L) {
            lastFrameMillis = now;
            return 1.0f / 60.0f;
        }
        float seconds = Mth.clamp((now - lastFrameMillis) / 1000.0f, 0.0f, 0.1f);
        lastFrameMillis = now;
        return seconds;
    }

    /** 带回弹的缓出：overshoot 越大，越过终点再回落得越明显。 */
    private static float easeOutBack(float value, float overshoot) {
        float t = TransitionFx.clamp01(value) - 1.0f;
        return 1.0f + t * t * ((overshoot + 1.0f) * t + overshoot);
    }
}
