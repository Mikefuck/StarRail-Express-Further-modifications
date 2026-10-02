package com.habitrain.core.client.gui;

import com.habitrain.core.client.VoteKeyHandler;
import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.network.MapVoteProfilePayload;
import com.habitrain.core.network.OptionVotePayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 通用选项投票界面（模式/地图等字符串选项）。
 *
 * <p>界面是一块挂在屏幕上边缘正中的「发车牌」：标题行（阶段 · 双语标题 · 翻牌倒计时）、
 * 倒计时轨道（小火车驶向终点站）、一排横向车票、底部提示行。面板以外不画任何背景，
 * 玩家始终能看到大厅。投票即「检票」——车票换成头等票配色、盖章并在票根打孔。</p>
 *
 * <p>服务端仍是倒计时与票数的唯一权威来源，客户端只平滑呈现收到的状态。</p>
 */
public class OptionVoteScreen extends Screen {
    private static final int INK = RailArt.NIGHT_0;
    private static final int PANEL_BOTTOM = RailArt.PANEL_BOTTOM;
    private static final int NIGHT_2 = RailArt.NIGHT_2;
    private static final int NIGHT_4 = RailArt.NIGHT_4;
    private static final int STEEL = RailArt.STEEL;
    private static final int GOLD_DARK = RailArt.BRASS_DIM;
    private static final int GOLD = RailArt.BRASS;
    private static final int GOLD_BRIGHT = RailArt.BRASS_LIGHT;
    private static final int IVORY = RailArt.CHAMPAGNE;
    private static final int TEXT = RailArt.TEXT;
    private static final int TEXT_MUTED = RailArt.TEXT_MUTED;
    private static final int DANGER = RailArt.RUBY;

    /** 面板自上缘落下（带轻微回弹）的时长。 */
    private static final long PANEL_DROP_MILLIS = 560L;
    /** 玩家隐藏时面板收回的时长。 */
    private static final long PANEL_RETRACT_MILLIS = 220L;
    /** 车票逐张翻下的时长与错峰。 */
    private static final long CARD_FLIP_MILLIS = 340L;
    private static final long CARD_FLIP_STAGGER = 55L;
    private static final long CARD_FLIP_START = 240L;
    /** 模式→地图衔接或快速重开时，新面板不再重播落下动画，只翻入车票。 */
    private static final long HANDOFF_WINDOW_MILLIS = 600L;
    private static final float STUB_FRACTION = 0.74f;

    /** 最近一次任意投票面板绘制的时刻，用于识别衔接。 */
    private static long lastRenderedAtMillis;

    private final Screen parent;
    private final long openedAtMillis = Util.getMillis();
    private final boolean handoff;
    private long closingAtMillis;

    private final Map<String, Float> cardEmphasis = new HashMap<>();
    private final Map<String, Float> cardSelected = new HashMap<>();
    private final Map<String, Float> cardShare = new HashMap<>();
    private final Map<String, Integer> lastVotes = new HashMap<>();
    private final Map<String, Long> voteBumpAtMillis = new HashMap<>();
    private final Map<String, List<FormattedCharSequence>> wrappedTextCache = new HashMap<>();
    /** 上次 wrap 时的 Language 实例，用于 F3+T 重载后失效缓存（review L8）。 */
    private net.minecraft.locale.Language wrappedLanguage;

    private List<CardHitbox> cardHitboxes = List.of();
    private Rect closeBounds = Rect.EMPTY;
    private Rect previousBounds = Rect.EMPTY;
    private Rect nextBounds = Rect.EMPTY;
    private Rect panelBounds = Rect.EMPTY;

    private int focusedIndex = -1;
    private String focusedOptionId = "";
    private float scroll = Float.NaN;
    private float displayedCountdown = -1.0f;
    private long lastFrameMillis;
    private int frameTotalVotes;

    private long lastAutoPickStepMillis;
    private String revealedAutoPickId = "";
    /** 随机抽选开始时刻：步进间隔随时间拉长，轮盘逐渐减速而非匀速跳动。 */
    private long autoPickStartedMillis;

    private String pulseOptionId = "";
    private long pulseAtMillis;
    /** 大脉冲：随机抽选揭晓时使用，附带闪光与更重的盖章。 */
    private boolean pulseBig;

    private final RailArt.FlapBoard countdownBoard = new RailArt.FlapBoard();

    public OptionVoteScreen(Screen parent) {
        super(OptionVoteTexts.titleFor(OptionVoteState.getVoteId()));
        this.parent = parent;
        this.handoff = Util.getMillis() - lastRenderedAtMillis < HANDOFF_WINDOW_MILLIS;
    }

    @Override
    protected void init() {
        super.init();
        syncFocus(OptionVoteState.getCandidates());
        lastFrameMillis = Util.getMillis();
    }

    @Override
    public void tick() {
        super.tick();
        if (closingAtMillis > 0L) {
            if (Util.getMillis() - closingAtMillis >= PANEL_RETRACT_MILLIS) {
                onClose();
            }
            return;
        }
        if (!OptionVoteState.isActive()) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    // ==================== 绘制 ====================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        lastRenderedAtMillis = now;
        float dt = frameSeconds(now);
        List<OptionVotePayload.Entry> candidates = OptionVoteState.getCandidates();
        syncFocus(candidates);
        updateAutoPickAnimation(now, candidates);
        frameTotalVotes = candidates.stream().mapToInt(OptionVotePayload.Entry::votes).sum();

        OptionVoteLayout.Panel layout = OptionVoteLayout.panel(width, height, candidates.size());
        float target = OptionVoteLayout.scrollTarget(layout, focusedIndex, candidates.size());
        if (Float.isNaN(scroll)) {
            scroll = target;
        }
        scroll += (target - scroll) * approachFactor(dt, 13.0f);

        float top = panelTop(layout, now);
        float elapsed = (now - openedAtMillis) / 1000.0f;
        renderScreenShade(g, layout, top);
        renderPanelBody(g, layout, top);
        renderHeader(g, layout, top, mouseX, mouseY, dt);
        renderRail(g, layout, top, elapsed);
        renderStrip(g, layout, top, candidates, mouseX, mouseY, dt, now);
        renderFooter(g, layout, top, candidates);
        if (OptionVoteState.isAutoPickAnimating()) {
            renderRoulettePill(g, layout, top);
        }
        // 本界面没有原版控件；不调用 super.render，避免 Screen.render 再画一层模糊背景。
    }

    /** 面板上缘的 y：落下时带一次轻微回弹；收回时向上滑出。 */
    private float panelTop(OptionVoteLayout.Panel layout, long now) {
        float hidden = -(layout.height() + 14.0f);
        if (closingAtMillis > 0L) {
            float t = TransitionFx.clamp01((now - closingAtMillis) / (float) PANEL_RETRACT_MILLIS);
            return hidden * TransitionFx.easeInCubic(t);
        }
        if (handoff) {
            return 0.0f;
        }
        float t = TransitionFx.clamp01((now - openedAtMillis) / (float) PANEL_DROP_MILLIS);
        return hidden * (1.0f - easeOutBack(t, 1.15f));
    }

    /** 屏幕上缘一道很淡的压暗，让面板像嵌在车站顶棚上，同时保证亮天空下的可读性。 */
    private void renderScreenShade(GuiGraphics g, OptionVoteLayout.Panel layout, float top) {
        RailArt.drawCeilingShade(g, width, top, layout.height());
    }

    /** 面板本体：切角深蓝底 + 下缘黄铜双线 + 两枚顶部吊扣 + 投影。 */
    private void renderPanelBody(GuiGraphics g, OptionVoteLayout.Panel layout, float top) {
        float bottom = top + layout.height();
        panelBounds = new Rect(layout.x(), 0, layout.width(), Math.max(0, Math.round(bottom)));
        RailArt.drawHangingPanel(g, layout.x(), layout.width(), top, layout.height());
    }

    private void renderHeader(GuiGraphics g, OptionVoteLayout.Panel layout, float top,
                              int mouseX, int mouseY, float dt) {
        String voteId = OptionVoteState.getVoteId();
        boolean active = OptionVoteState.isActive();
        int remaining = Math.max(0, OptionVoteState.getRemainingSeconds());
        int total = Math.max(1, OptionVoteState.getTotalSeconds());
        float targetCountdown = Mth.clamp(remaining / (float) total, 0.0f, 1.0f);
        if (displayedCountdown < 0.0f) {
            displayedCountdown = targetCountdown;
        }
        displayedCountdown += (targetCountdown - displayedCountdown) * approachFactor(dt, 7.0f);
        boolean urgent = active && remaining <= 5;
        int y = Math.round(top) + OptionVoteLayout.HEADER_Y;

        // ---- 左：阶段铭牌 ----
        Component phase = OptionVoteTexts.phaseFor(voteId);
        int chipX = layout.x() + OptionVoteLayout.SIDE;
        int chipW = font.width(phase) + 16;
        float[] chip = RailArt.chamfer(chipX, y - 1, chipW, 13, 3.0f);
        GuiGeo geo = GuiGeo.begin(g);
        geo.convexV(chip, y - 1, y + 12, RailArt.a(0xFF22335F, 0.95f), RailArt.a(NIGHT_2, 0.95f));
        geo.outline(chip, 0.8f, RailArt.a(GOLD_DARK, 1.0f));
        geo.diamond(chipX + 6.0f, y + 5.5f, 2.2f, 2.2f, urgent ? DANGER : GOLD);
        geo.end();
        drawText(g, phase, chipX + 11, y + 2, TEXT, false);
        int leftEdge = chipX + chipW + 6;

        // ---- 右：关闭 + 翻牌倒计时 ----
        closeBounds = new Rect(layout.right() - OptionVoteLayout.SIDE - 12, y - 1, 13, 13);
        renderClose(g, mouseX, mouseY);
        int rightEdge = closeBounds.x() - 6;
        if (active) {
            countdownBoard.set(remaining > 99 ? String.valueOf(remaining)
                    : String.format(Locale.ROOT, "%02d", remaining));
            int tileW = 8;
            int tileH = 12;
            int boardW = Math.round(countdownBoard.tileWidth(tileW, 2));
            int boardX = rightEdge - boardW;
            int digit = urgent ? GuiGeo.lerpColor(IVORY, DANGER, 0.85f) : IVORY;
            countdownBoard.render(g, font, boardX, y - 1, tileW, tileH, 2, digit,
                    urgent ? 0xFF3C1A26 : NIGHT_4, urgent ? 0xFF1A0A12 : RailArt.NIGHT_1, 1.0f);
            rightEdge = boardX - 4;
            Component unit = OptionVoteTexts.countdownUnit();
            int unitW = font.width(unit);
            if (rightEdge - unitW - 8 > leftEdge + 80) {
                drawText(g, unit, rightEdge - unitW, y + 2, urgent ? DANGER : TEXT_MUTED, false);
                rightEdge -= unitW + 6;
            }
        } else {
            Component ended = OptionVoteTexts.ended();
            drawText(g, ended, rightEdge - font.width(ended), y + 2, TEXT_MUTED, false);
            rightEdge -= font.width(ended) + 6;
        }

        // ---- 中：双语标题 ----
        Component heading = OptionVoteTexts.titleFor(voteId).copy().withStyle(ChatFormatting.BOLD);
        String sub = OptionVoteTexts.titleSubtitleFor(voteId);
        float center = layout.centerX();
        float half = Math.min(center - leftEdge, rightEdge - center);
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
            float wing = Math.min(28.0f, half - gap - 2.0f);
            if (wing > 6.0f) {
                RailArt.drawTitleWings(wings, center, y + 5.5f, gap, wing, GOLD_DARK, 0.85f);
            }
            wings.end();
        } else {
            Component fitted = fit(heading, Math.max(20, Math.round(half * 2.0f)));
            drawText(g, fitted, Math.round(center - font.width(fitted) / 2.0f), y + 2, IVORY, true);
        }
    }

    /** 倒计时轨道：枕木 + 钢轨，驶过的路段被黄铜点亮，终点站信号灯在最后 5 秒转红。 */
    private void renderRail(GuiGraphics g, OptionVoteLayout.Panel layout, float top, float time) {
        boolean urgent = OptionVoteState.isActive() && OptionVoteState.getRemainingSeconds() <= 5;
        float left = layout.x() + OptionVoteLayout.SIDE;
        float right = layout.right() - OptionVoteLayout.SIDE;
        RailArt.drawRouteRail(g, left, right, top + OptionVoteLayout.RAIL_Y, 1.0f - displayedCountdown,
                urgent ? DANGER : GOLD_BRIGHT, urgent ? DANGER : RailArt.LAMP, time);
    }

    // ==================== 车票带 ====================

    private void renderStrip(GuiGraphics g, OptionVoteLayout.Panel layout, float top,
                             List<OptionVotePayload.Entry> candidates, int mouseX, int mouseY,
                             float dt, long now) {
        int stripTop = Math.round(top) + OptionVoteLayout.STRIP_Y;
        int clipTop = stripTop - 4;
        int clipBottom = stripTop + layout.cardH() + 4;
        int clipLeft = layout.stripX();
        int clipRight = layout.stripX() + layout.stripW();
        if (candidates.isEmpty()) {
            cardHitboxes = List.of();
            previousBounds = Rect.EMPTY;
            nextBounds = Rect.EMPTY;
            Component text = OptionVoteTexts.noCandidates();
            drawText(g, text, layout.centerX() - font.width(text) / 2, stripTop + layout.cardH() / 2 - 4, TEXT_MUTED, false);
            return;
        }

        boolean mapPhase = "map".equals(OptionVoteState.getVoteId());
        List<CardHitbox> hitboxes = new ArrayList<>();
        g.flush();
        g.enableScissor(clipLeft, Math.max(0, clipTop), clipRight, Math.max(1, clipBottom));
        int step = layout.cardW() + OptionVoteLayout.GAP;
        for (int i = 0; i < candidates.size(); i++) {
            OptionVotePayload.Entry entry = candidates.get(i);
            float cardX = layout.stripX() - scroll + i * step;
            if (cardX + layout.cardW() < clipLeft - 2 || cardX > clipRight + 2) {
                continue;
            }
            String id = entry.optionId();
            Rect bounds = new Rect(Math.round(cardX), stripTop, layout.cardW(), layout.cardH());
            boolean hovered = closingAtMillis == 0L && bounds.contains(mouseX, mouseY)
                    && mouseX >= clipLeft && mouseX < clipRight;
            boolean focused = i == focusedIndex;
            boolean selected = OptionVoteState.isSelected(id);
            float emphasis = approach(cardEmphasis, id, focused ? 1.0f : (hovered ? 0.55f : 0.0f), dt, 12.0f);
            float selectedT = approach(cardSelected, id, selected ? 1.0f : 0.0f, dt, 9.0f);
            float shareTarget = frameTotalVotes <= 0 ? 0.0f : entry.votes() / (float) frameTotalVotes;
            float share = approach(cardShare, id, shareTarget, dt, 6.0f);

            // 入场：车票自上缘逐张翻下（像翻牌器一样绕顶边展开）
            int visibleOrder = Math.max(0, Math.round((cardX - clipLeft) / step));
            long flipStart = (handoff ? 60L : CARD_FLIP_START) + visibleOrder * CARD_FLIP_STAGGER;
            float flip = TransitionFx.clamp01((now - openedAtMillis - flipStart) / (float) CARD_FLIP_MILLIS);
            if (flip <= 0.0f) {
                continue;
            }
            renderCard(g, entry, i, cardX, stripTop, layout.cardW(), layout.cardH(),
                    easeOutBack(flip, 1.6f), emphasis, selectedT, share, hovered, focused, mapPhase, now);
            int hitLeft = Math.max(clipLeft, bounds.x());
            int hitRight = Math.min(clipRight, bounds.right());
            if (hitRight > hitLeft) {
                hitboxes.add(new CardHitbox(i, new Rect(hitLeft, bounds.y(), hitRight - hitLeft, bounds.height())));
            }
        }
        g.flush();
        g.disableScissor();
        cardHitboxes = hitboxes;

        // 带首尾的柔和渐隐，提示还能继续滚动
        if (layout.arrows()) {
            GuiGeo fade = GuiGeo.begin(g);
            fade.rectH(clipLeft, clipTop, clipLeft + 10, clipBottom, RailArt.a(PANEL_BOTTOM, 0.9f), RailArt.a(PANEL_BOTTOM, 0));
            fade.rectH(clipRight - 10, clipTop, clipRight, clipBottom, RailArt.a(PANEL_BOTTOM, 0), RailArt.a(PANEL_BOTTOM, 0.9f));
            fade.end();
            int arrowY = stripTop + layout.cardH() / 2 - OptionVoteLayout.ARROW / 2;
            previousBounds = new Rect(layout.x() + OptionVoteLayout.SIDE, arrowY, OptionVoteLayout.ARROW, OptionVoteLayout.ARROW);
            nextBounds = new Rect(layout.right() - OptionVoteLayout.SIDE - OptionVoteLayout.ARROW, arrowY,
                    OptionVoteLayout.ARROW, OptionVoteLayout.ARROW);
            renderArrow(g, previousBounds, -1, focusedIndex > 0, previousBounds.contains(mouseX, mouseY));
            renderArrow(g, nextBounds, 1, focusedIndex < candidates.size() - 1, nextBounds.contains(mouseX, mouseY));
        } else {
            previousBounds = Rect.EMPTY;
            nextBounds = Rect.EMPTY;
        }
    }

    /**
     * 单张候选车票：左侧票身写名称（地图有预览图时作为车窗风景），右侧票根是票数与占比。
     * 选中后由蓝票过渡为象牙头等票，票面盖「✓」章、票根打孔。
     */
    private void renderCard(GuiGraphics g, OptionVotePayload.Entry entry, int index, float x, float y,
                            int w, int h, float flip, float emphasis, float selectedT, float share,
                            boolean hovered, boolean focused, boolean mapPhase, long now) {
        String id = entry.optionId();
        TicketArt.Shape shape = TicketArt.Shape.of(w, h, STUB_FRACTION);
        TicketArt.Palette palette = TicketArt.Palette.mix(TicketArt.SAPPHIRE, TicketArt.IVORY, selectedT);
        float pulse = id.equals(pulseOptionId)
                ? TransitionFx.clamp01((now - pulseAtMillis) / (pulseBig ? 900.0f : 600.0f)) : 1.0f;
        float lift = Math.round(emphasis * 2.0f);

        g.pose().pushPose();
        g.pose().translate(x, y - lift, 0.0f);
        if (flip < 0.999f) {
            g.pose().scale(1.0f, Math.max(0.02f, flip), 1.0f);
        }
        if (emphasis > 0.02f || selectedT > 0.02f) {
            GuiGeo halo = GuiGeo.glow(g);
            halo.softGlow(w / 2.0f, h / 2.0f, w * 0.72f, h * 0.85f, GOLD,
                    Math.round(26 * emphasis + 30 * selectedT));
            halo.end();
        }
        g.pose().pushPose();
        g.pose().translate(1.5f, 2.5f + lift, 0.0f);
        TicketArt.shadow(g, shape, 2.0f, 0.5f);
        g.pose().popPose();

        TicketArt.paper(g, shape, palette, TicketArt.Part.WHOLE, 1.0f);
        TicketArt.details(g, shape, palette, TicketArt.Part.WHOLE, 1.0f, index * 7 + 3, false);

        float bodyL = shape.inset() + 1.5f;
        float bodyR = shape.bodyRight() - 0.5f;
        float bodyT = shape.band() + 2.0f;
        float bodyB = h - shape.inset() - 2.0f;
        // 编号与百分比都是 ASCII，可以比中文更小
        float small = TicketArt.crispAscii(0.5f);

        // 票头：编号
        TicketArt.drawScaled(g, font, Component.literal(String.format(Locale.ROOT, "No.%02d", index + 1)),
                shape.inset() + 3.0f, (shape.band() - 8.0f * small) / 2.0f + 0.5f, small, palette.bandInk());

        // 票身：地图预览（车窗风景）或名称
        Component label = OptionVoteTexts.candidateLabel(id, entry.displayName());
        boolean drewPreview = mapPhase && renderPreview(g, id, bodyL, bodyT, bodyR, bodyB);
        if (drewPreview) {
            GuiGeo veil = GuiGeo.begin(g);
            veil.rectV(bodyL, bodyB - 13.0f, bodyR, bodyB, RailArt.a(INK, 0), RailArt.a(INK, 0.78f));
            veil.outline(new float[] {bodyL, bodyT, bodyR, bodyT, bodyR, bodyB, bodyL, bodyB}, 0.7f,
                    RailArt.a(palette.trim(), 0.9f));
            veil.end();
            Component name = fit(label, Math.round(bodyR - bodyL - 4));
            g.drawString(font, name, Math.round((bodyL + bodyR) / 2.0f - font.width(name) / 2.0f),
                    Math.round(bodyB - 10.0f), IVORY, true);
        } else {
            int maxW = Math.round(bodyR - bodyL - 2);
            // 需要两行时按总宽对半折行（「经典列车 / 谋杀案」），避免第二行只剩一个字
            int textW = font.width(label);
            int wrapW = textW > maxW ? Math.min(maxW, (textW + 1) / 2 + 9) : maxW;
            List<FormattedCharSequence> lines = wrapped("card|" + id, label, wrapW);
            if (lines.size() > 2 && wrapW < maxW) {
                lines = wrapped("card|" + id, label, maxW);
            }
            int count = Math.min(2, lines.size());
            float blockH = count * 9.0f - 1.0f;
            float textTop = (bodyT + bodyB) / 2.0f - blockH / 2.0f;
            for (int line = 0; line < count; line++) {
                FormattedCharSequence seq = lines.get(line);
                if (line == 1 && lines.size() > 2) {
                    seq = fit(Component.literal(joinRemaining(lines, 1)), maxW).getVisualOrderText();
                }
                int lw = font.width(seq);
                g.drawString(font, seq, Math.round((bodyL + bodyR) / 2.0f - lw / 2.0f),
                        Math.round(textTop + line * 9.0f), palette.ink(), false);
            }
        }

        // 票根：票数 + 占比 + 细条
        float stubL = shape.stubLeft();
        float stubR = w - shape.inset() - 1.0f;
        float stubCx = (stubL + stubR) / 2.0f;
        int votes = entry.votes();
        Integer previous = lastVotes.put(id, votes);
        if (previous != null && previous != votes) {
            voteBumpAtMillis.put(id, now);
        }
        long bumpAt = voteBumpAtMillis.getOrDefault(id, 0L);
        float bump = bumpAt > 0L ? 1.0f - TransitionFx.clamp01((now - bumpAt) / 520.0f) : 0.0f;
        float countScale = TicketArt.crisp(h >= 56 ? 1.5f : 1.0f);
        Component countText = Component.literal(String.valueOf(votes)).withStyle(ChatFormatting.BOLD);
        float countY = shape.band() + (h - shape.band()) * 0.24f;
        int countColor = GuiGeo.lerpColor(palette.ink(), selectedT > 0.5f ? palette.serial() : GOLD_BRIGHT, bump);
        g.pose().pushPose();
        g.pose().translate(stubCx, countY + 4.0f * countScale, 0.0f);
        float pop = 1.0f + 0.3f * bump * bump;
        g.pose().scale(pop, pop, 1.0f);
        TicketArt.drawCentered(g, font, countText, 0.0f, -4.0f * countScale, countScale, countColor);
        g.pose().popPose();
        int percent = frameTotalVotes <= 0 ? 0 : Math.round(votes * 100.0f / frameTotalVotes);
        TicketArt.drawCentered(g, font, Component.literal(percent + "%"), stubCx,
                countY + 8.0f * countScale + 2.0f, small, palette.inkSoft());
        float barY = h - shape.inset() - 4.0f;
        GuiGeo bar = GuiGeo.begin(g);
        bar.rect(stubL + 1.0f, barY, stubR - 1.0f, barY + 1.2f, TicketArt.fade(palette.inkSoft(), 0.35f));
        float fillR = Mth.lerp(Mth.clamp(share, 0.0f, 1.0f), stubL + 1.0f, stubR - 1.0f);
        if (fillR > stubL + 1.4f) {
            bar.rect(stubL + 1.0f, barY, fillR, barY + 1.2f, selectedT > 0.5f ? palette.serial() : GOLD);
        }
        // 检票打孔
        if (selectedT > 0.5f) {
            float holeIn = TransitionFx.clamp01((selectedT - 0.5f) * 2.0f);
            TicketArt.punchHole(bar, stubCx, shape.band(), 2.2f * holeIn,
                    RailArt.a(INK, 1.0f), TicketArt.fade(palette.edge(), 1.0f));
        }
        bar.end();

        // 选中印章：脉冲期间从大落到原位（盖章），其余时刻静止
        if (selectedT > 0.02f) {
            float slam = pulse < 1.0f ? TransitionFx.clamp01(pulse / (pulseBig ? 0.3f : 0.24f)) : 1.0f;
            float stampScale = Mth.lerp(slam * slam, pulseBig ? 2.4f : 1.9f, 1.0f);
            float stampAlpha = selectedT * TransitionFx.clamp01(slam * 1.6f);
            renderCheckStamp(g, bodyR - 6.0f, bodyT + 5.0f, stampScale, stampAlpha, palette.serial());
        }

        // 焦点/悬停：黄铜描边；焦点额外在票下方挂一枚指示
        if (emphasis > 0.02f) {
            GuiGeo frame = GuiGeo.begin(g);
            frame.outline(RailArt.chamfer(-1.0f, -1.0f, w + 2.0f, h + 2.0f, shape.corner() + 1.0f), 1.0f,
                    RailArt.a(focused ? GOLD_BRIGHT : GOLD, emphasis));
            frame.end();
        }

        // 揭晓/检票闪光
        if (pulse < 1.0f) {
            float fadeOut = 1.0f - pulse;
            GuiGeo flash = GuiGeo.glow(g);
            flash.convexV(RailArt.chamfer(0, 0, w, h, shape.corner()), 0, h,
                    RailArt.a(IVORY, (pulseBig ? 0.45f : 0.25f) * fadeOut * fadeOut),
                    RailArt.a(GOLD, 0.1f * fadeOut * fadeOut));
            float grow = (pulseBig ? 7.0f : 4.0f) * TransitionFx.easeOutCubic(pulse);
            flash.outline(RailArt.chamfer(-grow, -grow, w + grow * 2.0f, h + grow * 2.0f, shape.corner() + grow), 1.0f,
                    RailArt.a(GOLD_BRIGHT, 0.75f * fadeOut * fadeOut));
            flash.end();
        }
        g.pose().popPose();
    }

    /** 选中车票的「✓」检票章：圆环 + 对勾，略微倾斜。 */
    private void renderCheckStamp(GuiGraphics g, float cx, float cy, float scale, float alpha, int color) {
        if (alpha <= 0.02f) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(-14.0f));
        g.pose().scale(scale, scale, 1.0f);
        int ink = RailArt.a(color, alpha * 0.92f);
        GuiGeo geo = GuiGeo.begin(g);
        geo.ring(0.0f, 0.0f, 4.6f, 5.8f, ink);
        geo.line(-2.4f, 0.2f, -0.6f, 2.1f, 1.3f, ink);
        geo.line(-0.6f, 2.1f, 2.8f, -2.2f, 1.3f, ink);
        geo.end();
        g.pose().popPose();
    }

    /** 地图预览：懒解码缓存；以 cover 方式铺进车窗区域。无图时返回 false。 */
    private boolean renderPreview(GuiGraphics g, String mapId, float x0, float y0, float x1, float y1) {
        MapVoteProfilePayload.MapProfile profile = OptionVoteState.getProfile(mapId);
        if (profile == null) {
            return false;
        }
        MapVotePreviewCache.Decoded decoded = MapVotePreviewCache.getOrDecode(mapId, profile.previewBytes());
        if (decoded == null) {
            return false;
        }
        int x = Math.round(x0);
        int y = Math.round(y0);
        int w = Math.round(x1 - x0);
        int h = Math.round(y1 - y0);
        if (w <= 2 || h <= 2) {
            return false;
        }
        float scale = Math.max(w / (float) decoded.width(), h / (float) decoded.height());
        int srcW = Math.max(1, Math.round(w / scale));
        int srcH = Math.max(1, Math.round(h / scale));
        int srcX = (decoded.width() - srcW) / 2;
        int srcY = (decoded.height() - srcH) / 2;
        ResourceLocation texture = decoded.texture();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(0.9f, 0.9f, 0.95f, 1.0f);
        g.blit(texture, x, y, w, h, srcX, srcY, srcW, srcH, decoded.width(), decoded.height());
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
        return true;
    }

    // ==================== 提示行 / 轮盘 / 控件 ====================

    private void renderFooter(GuiGraphics g, OptionVoteLayout.Panel layout, float top,
                              List<OptionVotePayload.Entry> candidates) {
        int y = Math.round(top) + layout.footerY();
        int left = layout.x() + OptionVoteLayout.SIDE + 2;
        int right = layout.right() - OptionVoteLayout.SIDE - 2;
        int span = right - left;

        Component status = Component.empty();
        if (focusedIndex >= 0 && focusedIndex < candidates.size()) {
            OptionVotePayload.Entry focused = candidates.get(focusedIndex);
            int percent = frameTotalVotes <= 0 ? 0 : Math.round(focused.votes() * 100.0f / frameTotalVotes);
            status = Component.literal("▸ ").withStyle(ChatFormatting.GOLD)
                    .append(OptionVoteTexts.candidateLabel(focused.optionId(), focused.displayName()).copy()
                            .withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("  ").append(OptionVoteTexts.voteShare(focused.votes(), percent))
                            .withStyle(ChatFormatting.GRAY));
        }
        Component hint = OptionVoteTexts.panelHint();
        int statusW = Math.min(font.width(status), span * 3 / 5);
        int hintRoom = span - statusW - 12;
        if (statusW > 0) {
            drawText(g, fit(status, statusW), left, y, TEXT, false);
        }
        if (hintRoom > 40) {
            Component fittedHint = fit(hint, hintRoom);
            drawText(g, fittedHint, right - font.width(fittedHint), y, RailArt.TEXT_FAINT, false);
        }
    }

    /** 随机抽选提示：挂在面板下方的红色描边小牌。 */
    private void renderRoulettePill(GuiGraphics g, OptionVoteLayout.Panel layout, float top) {
        Component roulette = OptionVoteTexts.randomSelecting();
        int pillW = font.width(roulette) + 22;
        int pillH = 13;
        int px = layout.centerX() - pillW / 2;
        int py = Math.round(top) + layout.height() + 8;
        float[] shape = RailArt.chamfer(px, py, pillW, pillH, 4.0f);
        GuiGeo geo = GuiGeo.begin(g);
        geo.line(px + 8.0f, py - 8.0f, px + 8.0f, py, 0.8f, RailArt.a(GOLD_DARK, 0.9f));
        geo.line(px + pillW - 8.0f, py - 8.0f, px + pillW - 8.0f, py, 0.8f, RailArt.a(GOLD_DARK, 0.9f));
        geo.convexV(shape, py, py + pillH, 0xF21A1426, 0xF40A0710);
        geo.outline(shape, 1.0f, RailArt.a(GuiGeo.lerpColor(GOLD, DANGER, 0.6f), 0.9f));
        geo.diamond(px + 7.0f, py + 6.5f, 2.2f, 2.2f, RailArt.a(DANGER, 0.86f));
        geo.diamond(px + pillW - 7.0f, py + 6.5f, 2.2f, 2.2f, RailArt.a(DANGER, 0.86f));
        geo.end();
        g.drawCenteredString(font, roulette, layout.centerX(), py + 3, GOLD_BRIGHT);
    }

    /** 关闭按钮：黄铜圆环 + 叉，悬停发光。 */
    private void renderClose(GuiGraphics g, int mouseX, int mouseY) {
        boolean hovered = closeBounds.contains(mouseX, mouseY);
        float cx = closeBounds.x() + closeBounds.width() / 2.0f;
        float cy = closeBounds.y() + closeBounds.height() / 2.0f;
        if (hovered) {
            GuiGeo glow = GuiGeo.glow(g);
            glow.softGlow(cx, cy, 12.0f, 12.0f, GOLD, 110);
            glow.end();
        }
        GuiGeo geo = GuiGeo.begin(g);
        geo.disc(cx, cy, 6.0f, RailArt.a(hovered ? NIGHT_4 : NIGHT_2, 0.95f));
        geo.ring(cx, cy, 5.1f, 6.1f, hovered ? GOLD_BRIGHT : GOLD_DARK);
        int cross = hovered ? IVORY : TEXT_MUTED;
        geo.line(cx - 2.2f, cy - 2.2f, cx + 2.2f, cy + 2.2f, 1.2f, cross);
        geo.line(cx - 2.2f, cy + 2.2f, cx + 2.2f, cy - 2.2f, 1.2f, cross);
        geo.end();
    }

    /** 翻页按钮：深色圆盘 + 黄铜环 + 箭头；悬停时提亮。 */
    private void renderArrow(GuiGraphics g, Rect bounds, int direction, boolean enabled, boolean hovered) {
        float cx = bounds.x() + bounds.width() / 2.0f;
        float cy = bounds.y() + bounds.height() / 2.0f;
        float r = bounds.width() / 2.0f;
        if (enabled && hovered) {
            GuiGeo glow = GuiGeo.glow(g);
            glow.softGlow(cx, cy, r * 2.0f, r * 2.0f, GOLD, 120);
            glow.end();
        }
        int ring = enabled ? (hovered ? GOLD_BRIGHT : GOLD_DARK) : STEEL;
        int chevron = enabled ? (hovered ? IVORY : GOLD) : RailArt.TEXT_FAINT;
        GuiGeo geo = GuiGeo.begin(g);
        geo.disc(cx, cy, r, r, RailArt.a(enabled ? RailArt.NIGHT_3 : RailArt.NIGHT_1, 0.95f),
                RailArt.a(RailArt.NIGHT_1, 0.95f));
        geo.ring(cx, cy, r - 1.0f, r, ring);
        float tipX = cx + direction * 2.2f;
        float tailX = cx - direction * 1.6f;
        geo.line(tailX, cy - 3.2f, tipX, cy, 1.4f, chevron);
        geo.line(tipX, cy, tailX, cy + 3.2f, 1.4f, chevron);
        geo.end();
    }

    /** 文字 alpha 过低时 MC 会按不透明绘制，统一在此跳过。 */
    private void drawText(GuiGraphics g, Component text, int x, int y, int color, boolean shadow) {
        if (((color >>> 24) & 0xFF) < 6) {
            return;
        }
        g.drawString(font, text, x, y, color, shadow);
    }

    // ==================== 焦点 / 投票 ====================

    /** Cycles the map deck during the final three seconds, then reveals the reported random pick. */
    private void updateAutoPickAnimation(long now, List<OptionVotePayload.Entry> candidates) {
        if (!"map".equals(OptionVoteState.getVoteId()) || candidates.isEmpty()) {
            return;
        }
        if (OptionVoteState.isAutoPickAnimating()) {
            if (autoPickStartedMillis == 0L) {
                autoPickStartedMillis = now;
            }
            // 55ms 起步，约 3 秒后放慢到 ~280ms 一格，像真正的轮盘在减速
            long stepInterval = 55L + Math.min(240L, (now - autoPickStartedMillis) * 3L / 40L);
            if (lastAutoPickStepMillis == 0L || now - lastAutoPickStepMillis >= stepInterval) {
                int next = focusedIndex < 0 ? 0 : (focusedIndex + 1) % candidates.size();
                setFocus(next, candidates, false);
                playUiSound(0.90f + (next % 4) * 0.08f);
                lastAutoPickStepMillis = now;
            }
            return;
        }
        autoPickStartedMillis = 0L;

        String autoPickedId = OptionVoteState.getAutoPickedOptionId();
        if (autoPickedId == null || autoPickedId.isBlank() || autoPickedId.equals(revealedAutoPickId)) {
            return;
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (autoPickedId.equals(candidates.get(i).optionId())) {
                setFocus(i, candidates, false);
                revealedAutoPickId = autoPickedId;
                startPulse(autoPickedId, true);
                playUiSound(1.45f);
                break;
            }
        }
    }

    private void syncFocus(List<OptionVotePayload.Entry> candidates) {
        if (candidates.isEmpty()) {
            focusedIndex = -1;
            focusedOptionId = "";
            return;
        }
        if (!focusedOptionId.isBlank()) {
            for (int i = 0; i < candidates.size(); i++) {
                if (focusedOptionId.equals(candidates.get(i).optionId())) {
                    focusedIndex = i;
                    return;
                }
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (OptionVoteState.isSelected(candidates.get(i).optionId())) {
                setFocus(i, candidates, false);
                return;
            }
        }
        // 从首项开始浏览；重新打开时仍优先定位已投候选。
        setFocus(0, candidates, false);
    }

    private void changeFocus(int delta) {
        List<OptionVotePayload.Entry> candidates = OptionVoteState.getCandidates();
        if (candidates.isEmpty()) {
            return;
        }
        int next = Mth.clamp(focusedIndex + delta, 0, candidates.size() - 1);
        if (next != focusedIndex) {
            setFocus(next, candidates, true);
        }
    }

    private void setFocus(int index, List<OptionVotePayload.Entry> candidates, boolean sound) {
        if (candidates.isEmpty()) {
            return;
        }
        int clamped = Mth.clamp(index, 0, candidates.size() - 1);
        boolean changed = focusedIndex != clamped;
        focusedIndex = clamped;
        focusedOptionId = candidates.get(clamped).optionId();
        if (sound && changed) {
            playUiSound(1.25f);
        }
    }

    private void startPulse(String optionId, boolean big) {
        pulseOptionId = optionId == null ? "" : optionId;
        pulseAtMillis = Util.getMillis();
        pulseBig = big;
    }

    private void castVote(int index) {
        if (!OptionVoteState.isActive()) {
            return;
        }
        List<OptionVotePayload.Entry> candidates = OptionVoteState.getCandidates();
        if (index < 0 || index >= candidates.size()) {
            return;
        }
        OptionVotePayload.Entry entry = candidates.get(index);
        setFocus(index, candidates, false);
        if ("map".equals(OptionVoteState.getVoteId())) {
            // 地图票只能改投、不能撤回（保证随机抽选前一定有明确意向）。
            boolean changed = !OptionVoteState.isSelected(entry.optionId());
            OptionVoteState.select(entry.optionId());
            if (changed) {
                PayloadSenders.sendOptionVoteCast(OptionVoteState.getVoteId(), entry.optionId());
                startPulse(entry.optionId(), false);
            }
            playUiSound(1.05f);
            return;
        }
        boolean wasSelected = OptionVoteState.isSelected(entry.optionId());
        OptionVoteState.toggleSelection(entry.optionId());
        if (OptionVoteState.isSelected(entry.optionId())) {
            PayloadSenders.sendOptionVoteCast(OptionVoteState.getVoteId(), entry.optionId());
            startPulse(entry.optionId(), false);
            playUiSound(1.05f);
        } else if (wasSelected) {
            PayloadSenders.sendOptionVoteCast(OptionVoteState.getVoteId(), null);
            playUiSound(0.82f);
        }
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closingAtMillis > 0L) {
            return true;
        }
        if (button == 0) {
            if (closeBounds.contains(mouseX, mouseY)) {
                hideByUser();
                return true;
            }
            List<OptionVotePayload.Entry> candidates = OptionVoteState.getCandidates();
            if (previousBounds.contains(mouseX, mouseY)) {
                changeFocus(-1);
                return true;
            }
            if (nextBounds.contains(mouseX, mouseY)) {
                changeFocus(1);
                return true;
            }
            if (OptionVoteState.isActive()) {
                for (CardHitbox hitbox : cardHitboxes) {
                    if (hitbox.bounds().contains(mouseX, mouseY) && hitbox.index() < candidates.size()) {
                        castVote(hitbox.index());
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (closingAtMillis == 0L && OptionVoteState.isActive() && scrollY != 0.0
                && panelBounds.contains(mouseX, mouseY)) {
            changeFocus(scrollY > 0.0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closingAtMillis > 0L) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_UP
                || keyCode == GLFW.GLFW_KEY_A || keyCode == GLFW.GLFW_KEY_W) {
            changeFocus(-1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_DOWN
                || keyCode == GLFW.GLFW_KEY_D || keyCode == GLFW.GLFW_KEY_S) {
            changeFocus(1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            castVote(focusedIndex);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || VoteKeyHandler.matchesOpenVoteKey(keyCode, scanCode)) {
            hideByUser();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 玩家主动隐藏：本轮 mode/map 投票页都不再自动弹出，直到手动重开或本轮结束。
     * 与 {@link #onClose()} 区分——阶段切换强制关屏不得写入隐藏偏好。面板先向上收回再关屏。
     */
    public void hideByUser() {
        OptionVoteState.markUiHiddenByUser();
        if (closingAtMillis == 0L) {
            closingAtMillis = Util.getMillis();
        }
    }

    /**
     * 地图投票结算：面板向上收回，把屏幕顶部让给「重置地图中」进度牌（{@link MapResetProgressHud}）。
     * 不写入隐藏偏好；收回后交还到打开投票页之前的页面。
     */
    public void retractForLaunch() {
        if (closingAtMillis == 0L) {
            closingAtMillis = Util.getMillis();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // ESC 走 hideByUser，写入本轮隐藏偏好
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        // 避免模式→地图自动重建时把多个投票界面叠成 parent 链。
        Screen next = parent;
        while (next instanceof OptionVoteScreen nested) {
            next = nested.parent;
        }
        mc.setScreen(next);
    }

    /** Parent screen for auto-open rebuild / close chain. */
    public Screen getParentScreen() {
        return parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 工具 ====================

    private float approach(Map<String, Float> values, String id, float target, float dt, float speed) {
        float value = values.getOrDefault(id, target);
        value += (target - value) * approachFactor(dt, speed);
        values.put(id, value);
        return value;
    }

    private float frameSeconds(long now) {
        if (lastFrameMillis <= 0L) {
            lastFrameMillis = now;
            return 1.0f / 60.0f;
        }
        float seconds = Mth.clamp((now - lastFrameMillis) / 1000.0f, 0.0f, 0.1f);
        lastFrameMillis = now;
        return seconds;
    }

    private void playUiSound(float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
    }

    private Component fit(Component text, int maxWidth) {
        return TicketArt.fit(font, text, maxWidth);
    }

    private List<FormattedCharSequence> wrapped(String key, Component text, int width) {
        // 语言/资源重载（F3+T）会替换 Language 实例：以实例身份检测变化并清空，
        // 避免旧语言文本滞留；同时给缓存一个上限（review L8）。
        net.minecraft.locale.Language language = net.minecraft.locale.Language.getInstance();
        if (language != wrappedLanguage) {
            wrappedLanguage = language;
            wrappedTextCache.clear();
        }
        if (wrappedTextCache.size() > 512) {
            wrappedTextCache.clear();
        }
        String cacheKey = key + '|' + width + '|' + text.getString();
        return wrappedTextCache.computeIfAbsent(cacheKey, ignored -> font.split(text, width));
    }

    /** 把第 from 行起的折行文本拼回一行（第二行放不下时整体截断）。 */
    private static String joinRemaining(List<FormattedCharSequence> lines, int from) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            lines.get(i).accept((index, style, codePoint) -> {
                builder.appendCodePoint(codePoint);
                return true;
            });
        }
        return builder.toString();
    }

    private static float approachFactor(float seconds, float speed) {
        return 1.0f - (float) Math.exp(-Math.max(0.0f, seconds) * speed);
    }

    /** 带回弹的缓出：overshoot 越大，越过终点再回落得越明显。 */
    private static float easeOutBack(float value, float overshoot) {
        float t = TransitionFx.clamp01(value) - 1.0f;
        return 1.0f + t * t * ((overshoot + 1.0f) * t + overshoot);
    }

    private record Rect(int x, int y, int width, int height) {
        private static final Rect EMPTY = new Rect(0, 0, 0, 0);

        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(double mouseX, double mouseY) {
            return width > 0 && height > 0
                    && mouseX >= x && mouseX < right()
                    && mouseY >= y && mouseY < bottom();
        }
    }

    private record CardHitbox(int index, Rect bounds) {}
}
