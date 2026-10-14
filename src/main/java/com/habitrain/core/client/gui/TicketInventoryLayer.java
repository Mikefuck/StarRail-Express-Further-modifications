package com.habitrain.core.client.gui;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.gui.menu.MenuPermissions;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import io.wifi.starrailexpress.cca.ParticipationComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

/**
 * 背包「乘车凭证」页：替换上游等待开局时的背包面板。
 *
 * <p>正中是一张深蓝车票（照片窗里是跟随鼠标的玩家模型、旅客信息、候车路线、本局参与打孔、随身行李
 * 快捷栏；副券上是翻牌时钟、检票戳与条码）。上游的便捷菜单项被做成齿孔邮票，一半插在票的上沿后、
 * 一半插在下沿后：悬停抽出、点击盖邮戳后执行原动作。两侧背景是由飞散线条拼合成型的线框机车与星穹仪。
 * 车票、衬纸与邮票位置不随鼠标移动；只有选中或操作邮票时才给予交互反馈。</p>
 *
 * <p>整页按设计单位（DU）排版，再乘布局缩放 {@code k}；k 取到「k × GUI 缩放」为整数，
 * 保证像素字与物品贴图清晰。菜单项来自上游按钮列表，新增项会自动多出一枚邮票，
 * 一页放不下时分页（滚轮切换）。</p>
 */
public final class TicketInventoryLayer implements GuiEventListener, NarratableEntry {
    // ---- 票面（DU）----
    private static final float TICKET_W = 324.0f;
    private static final float TICKET_H = 152.0f;
    private static final float STUB_X = 238.0f;
    private static final float PHOTO_X = 14.0f;
    private static final float PHOTO_Y = 28.0f;
    private static final float PHOTO_W = 50.0f;
    private static final float PHOTO_H = 76.0f;
    private static final float INFO_X = 74.0f;
    private static final float BODY_RIGHT = 226.0f;
    private static final float SLOT_X0 = 29.0f;
    private static final float SLOT_Y = 125.0f;
    private static final float SLOT_PITCH = 20.0f;
    private static final float CHECK_Y = 84.0f;
    // ---- 邮票（DU）----
    private static final float STAMP_W = 48.0f;
    private static final float STAMP_H = 60.0f;
    private static final float PEEK = 36.0f;
    private static final float PEEK_HOVER = 54.0f;
    private static final float ROW_MARGIN = 14.0f;
    private static final float REST_GAP = 7.0f;
    /** 不压叠：同排最多放到邮票不重叠为止，其余分页。 */
    private static final float MIN_PITCH = 49.0f;
    // ---- 时间轴（毫秒）----
    private static final long ENTER_MS = 540L;
    private static final long STAMP_START_MS = 300L;
    private static final long STAMP_STAGGER_MS = 42L;
    private static final long STAMP_IN_MS = 360L;
    private static final long ITEM_START_MS = 470L;
    private static final long ITEM_STAGGER_MS = 30L;
    private static final long ITEM_DROP_MS = 180L;
    private static final long NAME_START_MS = 360L;
    private static final long CLOCK_AT_MS = 540L;
    private static final long CHECK_AT_MS = 700L;
    private static final long WIRE_START_MS = 60L;
    private static final long WIRE_MS = 1500L;
    private static final long ACTION_DELAY_MS = 200L;
    private static final long REENTRY_MS = 260L;
    private static final long PUNCH_MS = 420L;

    private static final TicketArt.Palette PALETTE = TicketArt.SAPPHIRE;
    private static final String TASK_SETTINGS_KEY = "screen.habitrain_core.task_settings";
    private static final String MAP_ROTATION_KEY = "screen.limited_inventory.menu.map_rotation";
    private static final String PARTICIPATE_HINT_KEY = "screen.limited_inventory.button.participate.hover_text";

    private static final Wire3D.Style LOCO_STYLE = new Wire3D.Style(RailArt.BRASS_LIGHT, 0.5f, 2.4f, 0.1f, 0.72f);
    private static final Wire3D.Style SKY_STYLE = new Wire3D.Style(0xFFCFE0FF, 0.5f, 2.4f, 0.1f, 0.65f);

    private final Minecraft mc = Minecraft.getInstance();
    private final TicketPerformanceButton modeButton;
    private final List<Stamp> stamps = new ArrayList<>();
    /** Source order is independent of the visual order used for rendering and keyboard navigation. */
    private final List<Stamp> sourceOrder = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();
    private final RailArt.FlapBoard clock = new RailArt.FlapBoard();
    private final TicketClockText clockText = new TicketClockText();
    private final TicketTextCache texts = new TicketTextCache();
    private final GuiGeo.CachedMesh paperMesh = new GuiGeo.CachedMesh();
    private final GuiGeo.CachedMesh stillWires = new GuiGeo.CachedMesh();
    private final int[][] slotPositions = new int[9][2];
    private final boolean[] slotVisible = new boolean[9];
    private boolean lightweight;
    private UUID passengerId;
    private String passengerName;
    private String serialDigits = "";
    private Component serialLabel, serialNumber, carLabel, seatLabel, gateLabel, nameLabel, revealedName;
    private int revealedCharacters = -1;
    private String dateString;
    private Component dateLabel;
    private final Wire3D.Mesh motion = new Wire3D.Mesh();
    private final Wire3D.Mesh sky = new Wire3D.Mesh();
    private final Wire3D.Mesh smoke = new Wire3D.Mesh();
    private final Wire3D.View view = new Wire3D.View();
    private final float[] point = new float[3];
    private TicketArt.Shape shape = TicketArt.Shape.of(TICKET_W, TICKET_H, STUB_X / TICKET_W);
    private Button participation;

    private long openedAt = -1L;
    private long reentryAt = -1L;
    private long lastFrameAt = -1L;
    private long stampIntroAt = -1L;
    private boolean failed;
    private boolean showing;
    private boolean focused;

    // 布局（GUI 坐标）
    private int width;
    private int height;
    private float k = 1.0f;
    private float centerX;
    private float centerY;
    private int ticketX;
    private int ticketY;
    private float ticketRot;
    private float ticketAlpha = 1.0f;
    private float motionScale = 1.0f;

    // 邮票分页与交互
    private int page;
    private int pages = 1;
    private int perPage = 1;
    private int hovered = -1;
    private int keyboardIndex = -1;
    private int previousMouseX = Integer.MIN_VALUE;
    private int previousMouseY = Integer.MIN_VALUE;
    private long lastSlideSoundAt;
    private Stamp pending;
    private long pendingAt;
    private final float[] pager = new float[6];

    // 本局参与
    private Boolean participating;
    private long participationChangedAt = -1L;
    private long participationRequestedAt = -1L;
    private int participationHover = -1;
    private final float[][] participationBoxes = new float[2][4];

    // ==================== 生命周期 ====================

    public TicketInventoryLayer(TicketPerformanceButton modeButton) {
        this.modeButton = modeButton;
    }

    /** 屏幕 init 时调用：按上游按钮重建邮票。保留已盖过邮戳的记录，返回时仍是「用过的」邮票。 */
    public void rebuild(List<? extends Button> menu, Button participation) {
        releaseResources();
        this.participation = participation;
        List<Stamp> previous = new ArrayList<>(stamps);
        stamps.clear();
        List<Button> buttons = new ArrayList<>(menu);
        buttons.add(modeButton);
        int number = 0;
        for (Button button : buttons) {
            if (button == null) {
                continue;
            }
            Component label = button.getMessage();
            String key = button == modeButton ? TicketPerformanceButton.KEY : keyOf(label);
            Stamp stamp = new Stamp(button, label, key, iconFor(key), inkFor(key, number), ++number);
            for (Stamp old : previous) {
                if (old.key.equals(key)) {
                    stamp.yaw = old.yaw;
                    stamp.clickedAt = old.clickedAt;
                    break;
                }
            }
            stamps.add(stamp);
        }
        sourceOrder.clear();
        sourceOrder.addAll(stamps);
        pending = null;
        hovered = -1;
        keyboardIndex = -1;
        if (openedAt >= 0L) {
            reentryAt = Util.getMillis();
            stampIntroAt = reentryAt + 40L;
        }
    }

    /** Pick up entries added/relabelled by other mods after this screen's init callback. */
    public void sync(List<? extends Button> menu, Button participation) {
        boolean changed = this.participation != participation || menu.size() + 1 != sourceOrder.size();
        for (int i = 0; !changed && i < menu.size(); i++) {
            Button source = menu.get(i);
            changed = source != sourceOrder.get(i).source || !source.getMessage().equals(sourceOrder.get(i).label);
        }
        if (changed) {
            rebuild(menu, participation);
            layout(width, height);
        }
        // Updating the local mode must not replay every stamp's entrance or discard keyboard focus.
        Stamp mode = sourceOrder.getLast();
        if (!mode.label.equals(modeButton.getMessage())) {
            mode.label = modeButton.getMessage();
            mode.face = null;
            mode.art.close();
        }
    }

    /** init / resize 时按屏幕尺寸重新排版。 */
    public void layout(int width, int height) {
        releaseResources();
        this.width = width;
        this.height = height;
        double gs = mc.getWindow().getGuiScale();
        k = layoutScale(width, height, 1.6f);
        centerX = (float) (Math.round(width / 2.0 * gs) / gs);
        centerY = (float) (Math.round(height / 2.0 * gs) / gs);
        shape = TicketArt.Shape.of(TICKET_W, TICKET_H, STUB_X / TICKET_W);
        float rowWidth = TICKET_W - ROW_MARGIN * 2.0f;
        int perRow = Math.max(1, (int) ((rowWidth - STAMP_W) / MIN_PITCH) + 1);
        perPage = perRow * 2;
        stamps.clear();
        stamps.addAll(sourceOrder);
        TicketUiGeometry.sortStampRows(stamps, perPage, stamp -> stamp.restricted);
        pages = TicketUiGeometry.pages(stamps.size(), perPage);
        page = Mth.clamp(page, 0, pages - 1);
        for (Stamp stamp : stamps) {
            stamp.face = null;
        }
    }

    /**
     * 布局缩放：为完全抽出的邮票与底部导航预留 470×300 的画幅，并优先对齐物理像素，
     * 这样像素字与物品贴图的每个像素都落在整数个屏幕像素上。
     */
    static float layoutScale(int width, int height, float cap) {
        return TicketUiGeometry.scale(width, height, Minecraft.getInstance().getWindow().getGuiScale(), cap);
    }

    /** 本帧是否由车票页接管（等待开局状态）；对局中鼠标事件全部放行给上游。 */
    public void setShowing(boolean showing) {
        this.showing = showing;
        if (!showing) {
            pending = null;
            hovered = -1;
            keyboardIndex = -1;
            focused = false;
        }
    }

    public boolean usable() {
        return !failed;
    }

    public void setLightweight(boolean lightweight) {
        if (this.lightweight == lightweight) return;
        this.lightweight = lightweight;
        pending = null;
        releaseResources();
    }

    /** May be called repeatedly, including before returning from a child screen. */
    public void releaseResources() {
        paperMesh.close();
        stillWires.close();
        for (Stamp stamp : stamps) stamp.art.close();
    }

    /** 渲染出错时退回上游面板，避免整个背包无法使用。 */
    public void fail(Throwable error) {
        if (!failed) {
            failed = true;
            releaseResources();
            HabiTrainCore.LOGGER.error("车票背包页渲染失败，已退回上游背包面板", error);
        }
    }

    /** 屏幕 tick：执行盖完邮戳后延迟的菜单动作。 */
    public void tick() {
        if (showing && !failed && pending != null && Util.getMillis() >= pendingAt) {
            Stamp stamp = pending;
            pending = null;
            // Permission may change during the brief postmark animation.
            if (!isLocked(stamp)) stamp.source.onPress();
        }
    }

    // ==================== 快捷栏槽位 ====================

    /** 第 i 个快捷栏槽位物品左上角的 GUI 坐标；入场时物品逐个落入，未落入前返回 null。 */
    public int[] slotPosition(int i) {
        return i >= 0 && i < 9 && slotVisible[i] ? slotPositions[i] : null;
    }

    private void updateSlotPositions() {
        for (int i = 0; i < 9; i++) updateSlotPosition(i);
    }

    private void updateSlotPosition(int i) {
        slotVisible[i] = false;
        long now = lastFrameAt;
        if (openedAt < 0L || ticketRot != 0.0f) return;
        int x = ticketX + Math.round((SLOT_X0 + 1.0f + i * SLOT_PITCH) * k);
        int y = ticketY + Math.round((SLOT_Y + 1.0f) * k);
        if (reentryAt < 0L && openedAt >= 0L && motionScale > 0.01f) {
            long t = now - openedAt - ITEM_START_MS - i * ITEM_STAGGER_MS;
            if (t < 0L) {
                return;
            }
            if (t < ITEM_DROP_MS) {
                float p = t / (float) ITEM_DROP_MS;
                y -= Math.round(bounceDrop(p) * 9.0f * k);
            }
        }
        slotPositions[i][0] = x;
        slotPositions[i][1] = y;
        slotVisible[i] = true;
    }

    public float slotScale() {
        return k;
    }

    // ==================== 渲染 ====================

    /** 背景层：背景、线框模型、邮票、车票与槽位框。物品由上游随后画在 {@link #slotPosition} 处。 */
    public void render(GuiGraphics g, int mouseX, int mouseY) {
        long now = Util.getMillis();
        if (openedAt < 0L) {
            openedAt = now;
            stampIntroAt = now + STAMP_START_MS;
            TicketSounds.slide(1.05f);
        }
        float dt = lastFrameAt < 0L ? 0.0f : Math.min(0.1f, (now - lastFrameAt) / 1000.0f);
        lastFrameAt = now;
        Font font = mc.font;
        motionScale = lightweight ? 0 : mc.options.screenEffectScale().get().floatValue();
        clockText.update(System.currentTimeMillis());
        if (texts.refreshLanguage()) {
            for (Stamp stamp : stamps) {
                stamp.face = null;
                stamp.art.close();
            }
        }
        updatePassengerText();

        if (mouseX != previousMouseX || mouseY != previousMouseY) keyboardIndex = -1;
        previousMouseX = mouseX;
        previousMouseY = mouseY;

        placeTicket(now);
        updateSlotPositions();
        updateParticipation(now);
        layoutStamps(now, dt, mouseX, mouseY);

        drawBackdrop(g, now);
        if (!lightweight) {
            drawWireModels(g, now);
            drawBackingSheets(g, now);
        }
        drawStamps(g, font, now);
        drawTicket(g, font, now, mouseX, mouseY);
        drawSlotFrames(g, now, mouseX, mouseY);
        drawNavigationHint(g, font);
        drawPager(g, font);
    }

    /** 最上层：被截断的邮票全名、无权限提示与参与按钮说明。 */
    public void renderOverlay(GuiGraphics g, int mouseX, int mouseY) {
        Font font = mc.font;
        int selected = keyboardIndex >= 0 ? keyboardIndex : hovered;
        if (selected >= 0 && selected < stamps.size()) {
            Stamp stamp = stamps.get(selected);
            int tipX = keyboardIndex >= 0 ? Math.round(stamp.gx + STAMP_W * k / 2.0f) : mouseX;
            int tipY = keyboardIndex >= 0 ? Math.round(stamp.top ? stamp.gy : stamp.gy + STAMP_H * k) : mouseY;
            if (stamp.source == modeButton) {
                g.renderTooltip(font, font.split(modeButton.description(), 200), tipX, tipY);
                return;
            }
            List<Component> lines = new ArrayList<>();
            if (stamp.truncated) {
                lines.add(stamp.label);
            }
            if (stamp.locked) {
                lines.add(texts.lockedHint());
            }
            if (!lines.isEmpty()) {
                g.renderComponentTooltip(font, lines, tipX, tipY);
            }
        } else if (participationHover >= 0) {
            g.renderTooltip(font, font.split(texts.label(PARTICIPATE_HINT_KEY), 200), mouseX, mouseY);
        }
    }

    // ---------- 票面位置 ----------

    private void placeTicket(long now) {
        float animY;
        float rot;
        float alpha;
        if (reentryAt >= 0L) {
            float e = TransitionFx.clamp01((now - reentryAt) / (float) REENTRY_MS);
            animY = (1.0f - TransitionFx.easeOutCubic(e)) * 12.0f;
            rot = 0.0f;
            alpha = 0.35f + 0.65f * TransitionFx.easeOutCubic(e);
        } else {
            float e = TransitionFx.clamp01((now - openedAt) / (float) ENTER_MS);
            animY = (1.0f - easeOutBack(e)) * 48.0f;
            rot = (1.0f - TransitionFx.easeOutCubic(e)) * -4.5f;
            alpha = TransitionFx.clamp01((now - openedAt) / 170.0f);
        }
        animY *= motionScale;
        rot *= motionScale;
        if (motionScale <= 0.01f) alpha = 1.0f;
        ticketX = Math.round(centerX - TICKET_W * k / 2.0f);
        ticketY = Math.round(centerY - TICKET_H * k / 2.0f + animY * k);
        ticketRot = Math.abs(rot) < 0.02f ? 0.0f : rot;
        ticketAlpha = alpha;
    }

    // ---------- 背景 ----------

    /** Two stationary paper layers bridge the ticket and the stamps. */
    private void drawBackingSheets(GuiGraphics g, long now) {
        float enter = motionScale <= 0.01f ? 1.0f
                : TransitionFx.easeOutCubic(TransitionFx.clamp01((now - openedAt) / 500.0f));
        for (int layer = 0; layer < 2; layer++) {
            float direction = layer == 0 ? -1.0f : 1.0f;
            float x = ticketX + direction * 4.0f * k;
            float y = ticketY + direction * (5.0f + 3.0f * enter) * k;
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(k, k, 1);
            GuiGeo geo = GuiGeo.begin(g);
            float[] paper = RailArt.chamfer(0, 0, TICKET_W, TICKET_H, 6.0f);
            geo.convexV(paper, 0, TICKET_H, RailArt.a(layer == 0 ? 0xFF20374D : 0xFF352B40, ticketAlpha),
                    RailArt.a(RailArt.NIGHT_0, ticketAlpha));
            geo.outline(paper, 0.6f, RailArt.a(RailArt.BRASS_DIM, ticketAlpha * 0.7f));
            float edgeY = layer == 0 ? 3.0f : TICKET_H - 3.0f;
            geo.line(12, edgeY, TICKET_W - 12, edgeY, 0.45f,
                    RailArt.a(RailArt.BRASS_LIGHT, ticketAlpha * 0.3f));
            geo.end();
            g.pose().popPose();
        }
    }

    private void drawBackdrop(GuiGraphics g, long now) {
        if (lightweight) {
            g.fill(0, 0, width, height, 0xAA0A1022);
            return;
        }
        float fade = TransitionFx.clamp01((now - openedAt) / 260.0f);
        float w = width;
        float h = height;
        GuiGeo geo = GuiGeo.begin(g);
        geo.rect(0, 0, w, h, RailArt.a(RailArt.NIGHT_1, 0.42f * fade));
        geo.rectV(0, 0, w, h * 0.42f, RailArt.a(RailArt.NIGHT_0, 0.55f * fade), RailArt.a(RailArt.NIGHT_0, 0));
        geo.rectV(0, h * 0.58f, w, h, RailArt.a(RailArt.NIGHT_0, 0), RailArt.a(RailArt.NIGHT_0, 0.62f * fade));
        geo.rectH(0, 0, w * 0.28f, h, RailArt.a(RailArt.NIGHT_0, 0.5f * fade), RailArt.a(RailArt.NIGHT_0, 0));
        geo.rectH(w * 0.72f, 0, w, h, RailArt.a(RailArt.NIGHT_0, 0), RailArt.a(RailArt.NIGHT_0, 0.5f * fade));
        geo.end();

        float time = (now - openedAt) / 1000.0f * motionScale;
        float hw = w / 2.0f / k;
        float hh = h / 2.0f / k;
        g.pose().pushPose();
        g.pose().translate(centerX, centerY, 0.0f);
        g.pose().scale(k, k, 1.0f);
        GuiGeo glow = GuiGeo.glow(g);
        glow.softGlow(0, 6, TICKET_W * 0.82f, TICKET_H * 1.05f, RailArt.LAMP, Math.round(30 * fade));
        // 星空：用大小与亮度区分远近，位置保持固定
        for (int i = 0; i < 96; i++) {
            float depth = 0.3f + TicketArt.hash(i * 71 + 5) * 0.7f;
            float sx = (TicketArt.hash(i * 13 + 1) * 2.0f - 1.0f) * hw;
            float sy = (TicketArt.hash(i * 29 + 7) * 2.0f - 1.0f) * hh;
            float twinkle = 0.55f + 0.45f * Mth.sin(time * (0.8f + TicketArt.hash(i * 3) * 2.2f) + i);
            float size = 0.35f + depth * 0.65f;
            float a = fade * twinkle * (0.25f + 0.55f * depth);
            glow.diamond(sx, sy, size, size, RailArt.a(i % 7 == 0 ? RailArt.BRASS_LIGHT : 0xFFDCE6FF, a));
            if (i % 11 == 0) {
                glow.softGlow(sx, sy, 4.0f * depth, 4.0f * depth, 0xFFDCE6FF, Math.round(40 * a));
            }
        }
        drawDipper(glow, -hw * 0.66f, -hh * 0.62f, Math.min(hw, hh) * 0.36f, time, fade);
        drawShootingStar(glow, time, hw, hh);
        glow.end();
        g.pose().popPose();
    }

    /** 北斗七星：开场后连线逐段描出。 */
    private static void drawDipper(GuiGeo geo, float x, float y, float s, float time, float fade) {
        float[] pts = {0.0f, 0.0f, 0.06f, 0.36f, 0.44f, 0.46f, 0.5f, 0.14f, 0.84f, 0.06f, 1.14f, -0.02f, 1.5f, 0.2f};
        int[] links = {0, 1, 1, 2, 2, 3, 3, 0, 3, 4, 4, 5, 5, 6};
        float draw = TransitionFx.clamp01((time - 0.7f) / 1.4f) * (links.length / 2);
        for (int i = 0; i < links.length / 2; i++) {
            float p = TransitionFx.clamp01(draw - i);
            if (p <= 0.0f) {
                break;
            }
            int a = links[i * 2];
            int b = links[i * 2 + 1];
            float x0 = x + pts[a * 2] * s;
            float y0 = y + pts[a * 2 + 1] * s;
            float x1 = x + pts[b * 2] * s;
            float y1 = y + pts[b * 2 + 1] * s;
            geo.line(x0, y0, Mth.lerp(p, x0, x1), Mth.lerp(p, y0, y1), 0.5f, RailArt.a(0xFFBFD2FF, 0.22f * fade));
        }
        for (int i = 0; i < 7; i++) {
            float px = x + pts[i * 2] * s;
            float py = y + pts[i * 2 + 1] * s;
            float tw = 0.7f + 0.3f * Mth.sin(time * 1.7f + i * 1.3f);
            geo.diamond(px, py, 1.1f, 1.1f, RailArt.a(0xFFEFF4FF, 0.85f * fade * tw));
            geo.softGlow(px, py, 5.0f, 5.0f, 0xFFBFD2FF, Math.round(70 * fade * tw));
        }
    }

    /** 每 9 秒左右划过一颗流星。 */
    private static void drawShootingStar(GuiGeo geo, float time, float hw, float hh) {
        float period = 9.0f;
        int cycle = (int) Math.floor((time + 4.0f) / period);
        float local = (time + 4.0f) - cycle * period;
        float life = 0.75f;
        if (local > life) {
            return;
        }
        float p = local / life;
        float sx = (TicketArt.hash(cycle * 97 + 3) * 1.2f - 0.6f) * hw;
        float sy = -hh * (0.92f - TicketArt.hash(cycle * 31 + 9) * 0.25f);
        float dx = 150.0f;
        float dy = 62.0f;
        float hx = sx + dx * p;
        float hy = sy + dy * p;
        float tail = 0.28f;
        float fade = Mth.sin(p * Mth.PI);
        geo.line(hx - dx * tail, hy - dy * tail, hx, hy, 0.8f, RailArt.a(0xFFDCE6FF, 0), RailArt.a(0xFFFFFFFF, 0.8f * fade));
        geo.softGlow(hx, hy, 4.0f, 4.0f, 0xFFFFFFFF, Math.round(110 * fade));
    }

    // ---------- 线框模型 ----------

    private void drawWireModels(GuiGraphics g, long now) {
        boolean still = motionScale <= 0.01f;
        float time = (now - openedAt) / 1000.0f * motionScale;
        float assemble = reentryAt >= 0L || motionScale <= 0.01f ? 1.0f
                : TransitionFx.clamp01((now - openedAt - WIRE_START_MS) / (float) WIRE_MS);
        float after = (now - openedAt - WIRE_START_MS - WIRE_MS) / 450.0f;
        float pulse = !still && reentryAt < 0L && after > 0.0f && after < 1.0f ? Mth.sin(after * Mth.PI) : 0.0f;
        float hw = width / 2.0f / k;
        float side = Math.max(0.0f, hw - TICKET_W / 2.0f);

        g.pose().pushPose();
        g.pose().translate(centerX, centerY, 0.0f);
        g.pose().scale(k, k, 1.0f);
        if (still && stillWires.ready()) {
            stillWires.draw(g, 1, true);
            g.pose().popPose();
            return;
        }
        GuiGeo geo = still ? GuiGeo.record() : GuiGeo.glow(g);

        // 左：原地奔跑的蒸汽机车
        // 车头朝左前方（远离车票），煤水车尾藏到票后
        float locoSize = Mth.clamp(side * 0.56f, 40.0f, 100.0f);
        float locoX = -(TICKET_W / 2.0f + side * 0.52f);
        float locoY = 18.0f;
        float yaw = Mth.PI - 0.6f + 0.07f * Mth.sin(time * 0.37f);
        float pitch = -0.3f;
        view.orient(yaw, pitch, 0.0f)
                .origin(WireModels.LOCO_CENTER_X, WireModels.LOCO_CENTER_Y, 0.0f)
                .at(locoX, locoY, locoSize / WireModels.LOCO_RADIUS, WireModels.LOCO_RADIUS * 3.0f,
                        WireModels.LOCO_RADIUS);
        float locoAlpha = 0.6f * (1.0f + pulse * 0.6f);
        Wire3D.draw(geo, WireModels.LOCOMOTIVE, view, LOCO_STYLE, locoAlpha, assemble, 11);
        float travel = time * 2.4f;
        motion.clear();
        WireModels.locomotiveMotion(motion, -travel / 0.82f, travel);
        Wire3D.draw(geo, motion, view, LOCO_STYLE, locoAlpha * 0.85f, assemble, 23);
        // 烟圈
        for (int i = 0; i < 4; i++) {
            float age = (time * 0.42f + i / 4.0f) % 1.0f;
            smoke.clear();
            WireModels.smokeRing(smoke, age, time * 1.3f + i * 2.0f);
            float a = (float) Math.pow(1.0f - age, 1.4f) * 0.5f * assemble;
            Wire3D.draw(geo, smoke, view, LOCO_STYLE, a, 1.0f, 31 + i);
        }
        // 车头灯与光束
        float lampVisible = TransitionFx.clamp01((assemble - 0.85f) / 0.15f);
        if (lampVisible > 0.0f) {
            view.project(WireModels.LAMP_X + 0.05f, WireModels.LAMP_Y - 0.05f, 0.0f);
            float lx = view.px;
            float ly = view.py;
            view.project(WireModels.LAMP_X + 7.5f, WireModels.LAMP_Y - 1.6f, 0.0f);
            float fx = view.px;
            float fy = view.py;
            float dx = fx - lx;
            float dy = fy - ly;
            float len = Mth.sqrt(dx * dx + dy * dy);
            if (len > 0.5f) {
                float nx = -dy / len;
                float ny = dx / len;
                float spread = len * 0.22f;
                int bright = RailArt.a(RailArt.LAMP, 0.2f * lampVisible);
                int clear = RailArt.a(RailArt.LAMP, 0);
                geo.quad(lx + nx * 0.8f, ly + ny * 0.8f, bright, fx + nx * spread, fy + ny * spread, clear,
                        fx - nx * spread, fy - ny * spread, clear, lx - nx * 0.8f, ly - ny * 0.8f, bright);
            }
            geo.softGlow(lx, ly, 7.0f, 7.0f, RailArt.LAMP, Math.round(150 * lampVisible));
        }

        // 右：星穹仪
        float skySize = Mth.clamp(side * 0.6f, 40.0f, 88.0f);
        float skyX = TICKET_W / 2.0f + side * 0.5f;
        float skyY = -4.0f;
        float spin = time * 0.35f;
        view.orient(-0.5f + time * 0.06f, -0.24f, 0.12f)
                .origin(0.0f, -0.35f, 0.0f)
                .at(skyX, skyY, skySize / WireModels.ARMILLARY_RADIUS, WireModels.ARMILLARY_RADIUS * 3.4f,
                        WireModels.ARMILLARY_RADIUS);
        sky.clear();
        WireModels.armillary(sky, spin);
        Wire3D.draw(geo, sky, view, SKY_STYLE, 0.55f * (1.0f + pulse * 0.6f), assemble, 47);
        if (assemble >= 1.0f) {
            int[] planets = {RailArt.LAMP, RailArt.RUBY, RailArt.CHAMPAGNE};
            float[] speeds = {0.9f, 0.52f, 0.27f};
            for (int i = 0; i < planets.length; i++) {
                WireModels.eclipticPoint(spin, time * speeds[i] + i * 2.1f, point);
                view.project(point[0], point[1], point[2]);
                float light = view.depthLight(view.pz, 0.6f);
                geo.softGlow(view.px, view.py, 5.0f, 5.0f, planets[i], Math.round(150 * light));
                geo.disc(view.px, view.py, 1.1f, RailArt.a(planets[i], 0.9f * light));
            }
            view.project(0, 0, 0);
            geo.softGlow(view.px, view.py, 9.0f, 9.0f, RailArt.BRASS_LIGHT,
                    Math.round(90 + 30 * Mth.sin(time * 2.0f)));
        }
        if (still) {
            stillWires.upload(geo);
            stillWires.draw(g, 1, true);
        } else {
            geo.end();
        }
        g.pose().popPose();
    }

    // ---------- 邮票 ----------

    /** 按当前页计算每枚邮票本帧的位置、悬停与动画量。 */
    private void layoutStamps(long now, float dt, int mouseX, int mouseY) {
        int start = page * perPage;
        int end = Math.min(stamps.size(), start + perPage);
        int count = Math.max(0, end - start);
        // Prefer the lower row for an odd final entry, including a page containing only the mode stamp.
        int topCount = count / 2;
        for (int i = 0; i < stamps.size(); i++) {
            stamps.get(i).shown = i >= start && i < end;
        }
        int selected = keyboardIndex >= 0 ? keyboardIndex : hovered;
        float hoveredAmount = selected >= 0 && selected < stamps.size() ? stamps.get(selected).hover : 0.0f;
        int hoveredOrder = selected >= start && selected < end ? selected - start : -1;
        for (int i = start; i < end; i++) {
            Stamp s = stamps.get(i);
            int order = i - start;
            boolean top = order < topCount;
            int rowIndex = top ? order : order - topCount;
            int rowCount = top ? topCount : count - topCount;
            float pitch = rowPitch(rowCount);
            float rowW = STAMP_W + pitch * (rowCount - 1);
            float x = (TICKET_W - rowW) / 2.0f + rowIndex * pitch;
            // 悬停的邮票把同排邻居轻轻推开
            if (hoveredOrder >= 0 && hoveredOrder != order) {
                boolean sameRow = (hoveredOrder < topCount) == top;
                if (sameRow) {
                    int hoveredRow = top ? hoveredOrder : hoveredOrder - topCount;
                    int d = rowIndex - hoveredRow;
                    float push = Math.max(0.0f, (STAMP_W + 4.0f - pitch)) * 0.55f + 3.0f;
                    x += Math.signum(d) * push * hoveredAmount / Math.max(1, Math.abs(d));
                }
            }
            float intro = motionScale <= 0.01f ? 1.0f : TransitionFx.clamp01((now - stampIntroAt - order * (reentryAt >= 0L ? 22L : STAMP_STAGGER_MS))
                    / (float) STAMP_IN_MS);
            float target = i == selected ? 1.0f : 0.0f;
            s.hover += (target - s.hover) * (dt <= 0.0f ? 1.0f : 1.0f - (float) Math.exp(-dt * 15.0f));
            s.yaw += dt * 3.0f * s.hover * motionScale;
            float out = lightweight ? PEEK : PEEK * easeOutBack(intro) + (PEEK_HOVER - PEEK) * s.hover;
            if (s.clickedAt >= 0L) {
                float c = (now - s.clickedAt) / 260.0f;
                if (c >= 0.0f && c < 1.0f) {
                    out += Mth.sin(c * Mth.PI) * 5.0f;
                }
            }
            float shake = 0.0f;
            if (s.deniedAt >= 0L) {
                float d = (now - s.deniedAt) / 360.0f;
                if (d < 1.0f) {
                    shake = Mth.sin(d * 34.0f) * 2.4f * (1.0f - d);
                }
            }
            float yDu = top ? -out : TICKET_H + out - STAMP_H;
            s.top = top;
            s.gx = ticketX + (x + shake) * k;
            s.gy = ticketY + yDu * k;
            s.order = order;
            s.intro = intro;
        }

        // 命中检测：可点区域只算露在票外的部分
        int previous = hovered;
        hovered = showing ? stampIndexAt(mouseX, mouseY) : -1;
        if (hovered >= 0 && hovered != previous && now - lastSlideSoundAt > 70L) {
            lastSlideSoundAt = now;
            TicketSounds.slide(1.5f + TicketArt.hash(hovered * 7) * 0.3f);
        }
        participationHover = showing && hovered < 0 ? participationPartAt(mouseX, mouseY) : -1;
        if (keyboardIndex >= stamps.size()) participationHover = keyboardIndex - stamps.size();
    }

    private static float rowPitch(int rowCount) {
        if (rowCount <= 1) {
            return 0.0f;
        }
        float rowWidth = TICKET_W - ROW_MARGIN * 2.0f;
        return Math.min(STAMP_W + REST_GAP, (rowWidth - STAMP_W) / (rowCount - 1));
    }

    private int stampIndexAt(double mx, double my) {
        if (hovered >= 0 && hovered < stamps.size() && stampContains(stamps.get(hovered), mx, my)) {
            return hovered;
        }
        for (int i = stamps.size() - 1; i >= 0; i--) {
            Stamp s = stamps.get(i);
            if (s.shown && stampContains(s, mx, my)) {
                return i;
            }
        }
        return -1;
    }

    private boolean stampContains(Stamp s, double mx, double my) {
        if (!s.shown || s.intro < 0.6f || ticketRot != 0.0f) {
            return false;
        }
        if (s.top ? my >= ticketY : my < ticketY + TICKET_H * k) return false;
        return TicketUiGeometry.contains(mx, my, s.gx, s.gy, STAMP_W * k, STAMP_H * k,
                lightweight ? 1 : 1.0f + 0.05f * s.hover, lightweight ? 0 : s.tilt * (1.0f - s.hover));
    }

    private void drawStamps(GuiGraphics g, Font font, long now) {
        Stamp lifted = null;
        for (int i = page * perPage; i < Math.min(stamps.size(), (page + 1) * perPage); i++) {
            Stamp s = stamps.get(i);
            if (!s.shown) {
                continue;
            }
            if (i == (keyboardIndex >= 0 ? keyboardIndex : hovered)) {
                lifted = s;
                continue;
            }
            drawStamp(g, font, s, now);
        }
        if (lifted != null) {
            drawStamp(g, font, lifted, now);
        }
    }

    private void drawStamp(GuiGraphics g, Font font, Stamp s, long now) {
        if (s.intro <= 0.0f) {
            return;
        }
        if (s.face == null) {
            s.face = buildFace(font, s);
        }
        s.locked = isLocked(s);
        if (s.locked != s.face.locked()) s.face = s.face.withLocked(s.locked);
        StampArt.Face face = s.face;
        float scale = lightweight ? 1 : 1.0f + 0.05f * s.hover;
        float tilt = lightweight ? 0 : s.tilt * (1.0f - s.hover);
        // The buried part of a stamp is not interactive and must not render either. In particular,
        // its shadow, postmark and buffered text must never leak into the ticket's foreground.
        g.flush();
        int edge = Mth.clamp(s.top ? ticketY : Mth.ceil(ticketY + TICKET_H * k), 0, height);
        g.enableScissor(0, s.top ? 0 : edge, width, s.top ? edge : height);
        g.pose().pushPose();
        try {
            g.pose().translate(s.gx + STAMP_W * k / 2.0f, s.gy + STAMP_H * k / 2.0f, 0.0f);
            if (Math.abs(tilt) > 0.01f) {
                g.pose().mulPose(Axis.ZP.rotationDegrees(tilt));
            }
            g.pose().scale(k * scale, k * scale, 1.0f);
            g.pose().translate(-STAMP_W / 2.0f, -STAMP_H / 2.0f, 0.0f);
            StampArt.draw(g, font, face, STAMP_W, STAMP_H, !s.top, s.yaw, s.hover, ticketAlpha, s.number * 37, k * scale,
                    s.art, lightweight);
            if (s.clickedAt >= 0L || visited.contains(s.key)) {
                float slam = lightweight || s.clickedAt < 0L ? 1.0f : TransitionFx.clamp01((now - s.clickedAt) / 150.0f);
                float markY = s.top ? STAMP_H * 0.34f : STAMP_H * 0.6f;
                StampArt.postmark(g, font, STAMP_W * 0.36f, markY, 11.5f, clockText.stamp(), -14.0f + s.tilt * 2.0f,
                        ticketAlpha * (s.clickedAt >= 0L && now - s.clickedAt < 1200L ? 1.0f : 0.7f), slam, s.number,
                        k * scale);
                if (s.clickedAt >= 0L && slam < 1.0f) {
                    TicketArt.inkSplash(g, STAMP_W * 0.36f, markY, 16.0f, StampArt.POSTMARK, slam, s.number * 3);
                }
            }
        } finally {
            try {
                // GuiGraphics may defer text until a later batch; submit it before removing the clip.
                g.flush();
            } finally {
                g.pose().popPose();
                g.disableScissor();
            }
        }
    }

    private StampArt.Face buildFace(Font font, Stamp s) {
        float avail = STAMP_W - 4.2f * 2.0f - 3.0f;
        String plain = s.label.getString();
        Component display = Component.literal(plain);
        // 先尽量单行（可略缩小），再考虑折成两行，最后截断并在悬停时给出全名
        float[][] tries = {{1.0f, 1}, {0.75f, 1}, {1.0f, 2}, {0.75f, 2}, {0.5f, 1}, {0.5f, 2}};
        for (float[] attempt : tries) {
            float scale = StampArt.crispDu(plain, attempt[0], k);
            List<FormattedCharSequence> split = font.split(display, Math.max(1, Math.round(avail / scale)));
            if (split.size() <= attempt[1]) {
                s.truncated = false;
                return new StampArt.Face(split, scale, s.icon, s.ink, s.number, false, s.restricted);
            }
        }
        float scale = StampArt.crispDu(plain, 0.5f, k);
        List<FormattedCharSequence> split = font.split(display, Math.max(1, Math.round(avail / scale)));
        s.truncated = split.size() > 2;
        List<FormattedCharSequence> kept = new ArrayList<>(split.subList(0, Math.min(2, split.size())));
        return new StampArt.Face(kept, scale, s.icon, s.ink, s.number, false, s.restricted);
    }

    private static boolean isLocked(Stamp s) {
        return isLockedKey(s.key);
    }

    /** 地图轮换与任务点设置只对 OP2 开放；延迟执行动作时也会重新检查。 */
    static boolean isLockedKey(String key) {
        return (TASK_SETTINGS_KEY.equals(key) || MAP_ROTATION_KEY.equals(key))
                && !MenuPermissions.canAccessTaskSettings();
    }

    private void drawPager(GuiGraphics g, Font font) {
        if (pages <= 1) {
            pager[0] = -1;
            return;
        }
        float x = ticketX + (TICKET_W - 8.0f) * k;
        float y = ticketY + (TICKET_H + PEEK_HOVER + 8.0f) * k;
        Component text = Component.literal((page + 1) + " / " + pages);
        float scale = StampArt.crispDu("0", 1.0f, k) * k;
        float tw = font.width(text) * scale;
        float arrow = 8.0f * k;
        float right = x;
        float left = right - arrow * 2.0f - tw - 6.0f * k;
        pager[0] = left;
        pager[1] = y - 2.0f * k;
        pager[2] = left + arrow + 2.0f * k;
        pager[3] = right - arrow - 2.0f * k;
        pager[4] = right;
        pager[5] = y + 10.0f * k;
        GuiGeo geo = GuiGeo.begin(g);
        float cy = y + 4.0f * k;
        int on = RailArt.a(RailArt.BRASS_LIGHT, 0.95f);
        int off = RailArt.a(RailArt.TEXT_FAINT, 0.8f);
        geo.tri(left + arrow * 0.25f, cy, page > 0 ? on : off, left + arrow * 0.85f, cy - 3.5f * k, page > 0 ? on : off,
                left + arrow * 0.85f, cy + 3.5f * k, page > 0 ? on : off);
        geo.tri(right - arrow * 0.25f, cy, page < pages - 1 ? on : off, right - arrow * 0.85f, cy + 3.5f * k,
                page < pages - 1 ? on : off, right - arrow * 0.85f, cy - 3.5f * k, page < pages - 1 ? on : off);
        geo.end();
        TicketArt.drawScaled(g, font, text, left + arrow + 3.0f * k, y, scale, RailArt.a(RailArt.CHAMPAGNE, 0.9f));
    }

    private void changePage(int delta) {
        int next = Mth.clamp(page + delta, 0, pages - 1);
        if (next != page) {
            releaseResources();
            page = next;
            stampIntroAt = Util.getMillis();
            hovered = -1;
            keyboardIndex = -1;
            pending = null;
            for (Stamp stamp : stamps) {
                stamp.shown = false;
                stamp.hover = 0.0f;
            }
            TicketSounds.slide(0.9f);
        }
    }

    private void drawNavigationHint(GuiGraphics g, Font font) {
        Component hint = texts.label(pages > 1
                ? "inventory.habitrain_core.ticket.controls_pages" : "inventory.habitrain_core.ticket.controls");
        float scale = cs(hint.getString(), 0.5f) * k;
        TicketArt.drawScaled(g, font, hint, ticketX + 8.0f * k,
                ticketY + (TICKET_H + PEEK_HOVER + 8.0f) * k, scale,
                RailArt.a(RailArt.TEXT_MUTED, ticketAlpha * 0.8f));
    }

    // ---------- 车票 ----------

    private void drawTicket(GuiGraphics g, Font font, long now, int mouseX, int mouseY) {
        float alpha = ticketAlpha;
        float time = (now - openedAt) / 1000.0f * motionScale;
        g.pose().pushPose();
        g.pose().translate(ticketX, ticketY, 0.0f);
        if (ticketRot != 0.0f) {
            g.pose().translate(TICKET_W * k / 2.0f, TICKET_H * k / 2.0f, 0.0f);
            g.pose().mulPose(Axis.ZP.rotationDegrees(ticketRot));
            g.pose().translate(-TICKET_W * k / 2.0f, -TICKET_H * k / 2.0f, 0.0f);
        }
        g.pose().scale(k, k, 1.0f);

        g.pose().pushPose();
        g.pose().translate(2.5f, 4.0f, 0.0f);
        if (!lightweight) TicketArt.shadow(g, shape, 5.0f, alpha);
        g.pose().popPose();
        if (!paperMesh.ready()) {
            GuiGeo paper = GuiGeo.record();
            TicketArt.paper(paper, shape, PALETTE, TicketArt.Part.WHOLE, 1);
            if (!lightweight) TicketArt.details(paper, shape, PALETTE, TicketArt.Part.WHOLE, 0.85f, 7, true);
            else paper.rectOutline(5, 5, TICKET_W - 5, TICKET_H - 5, 0.8f, PALETTE.trim());
            paperMesh.upload(paper);
        }
        paperMesh.draw(g, alpha, false);

        drawHeader(g, font, time, alpha);
        drawPhoto(g, mouseX, mouseY, time, alpha);
        drawPassengerInfo(g, font, now, alpha);
        if (alpha >= 0.98f) {
            // 路线小火车不接受透明度，票面淡入完成后才画
            drawRoute(g, font, time, alpha);
        }
        drawParticipation(g, font, now, alpha);
        drawCarryOnLabel(g, font, alpha);
        drawStub(g, font, now, alpha);

        g.pose().popPose();
        drawClock(g, font, now);
    }

    private void drawHeader(GuiGraphics g, Font font, float time, float alpha) {
        float band = shape.band();
        if (!lightweight) RailArt.drawWingedWheel(g, 15.0f, band / 2.0f, 3.4f, time * 1.6f, 1.0f, alpha);
        Component title = texts.label("inventory.habitrain_core.ticket.title");
        float s = cs(title.getString(), 1.0f);
        float ty = snap((band - 8.0f * s) / 2.0f);
        TicketArt.drawScaled(g, font, title, 30.0f, ty, s, RailArt.a(PALETTE.bandInk(), alpha));
        Component serial = serialLabel;
        float ss = cs("No.", 0.75f);
        String subtitle = texts.subtitle("inventory.habitrain_core.ticket.title");
        float subX = 30.0f + font.width(title) * s + 5.0f;
        if (subX + TicketArt.trackedWidth(font, subtitle, cs(subtitle, 0.5f))
                < shape.bodyRight() - font.width(serial) * ss - 6.0f) {
            drawSub(g, font, subtitle, subX, ty, s, 0.5f, RailArt.a(PALETTE.inkSoft(), alpha * 0.9f));
        }
        TicketArt.drawRight(g, font, serial, shape.bodyRight() - 2.0f, snap((band - 8.0f * ss) / 2.0f), ss,
                RailArt.a(PALETTE.serial(), alpha));
        // 副券票头
        Component stub = texts.label("inventory.habitrain_core.ticket.stub");
        float stubCx = (shape.stubLeft() + TICKET_W - shape.inset()) / 2.0f;
        String stubSub = secondaryLabels() ? texts.subtitle("inventory.habitrain_core.ticket.stub") : "";
        float subW = stubSub.isBlank() ? 0 : TicketArt.trackedWidth(font, stubSub, cs(stubSub, 0.5f)) + 4.0f;
        float mainW = font.width(stub) * s;
        float left = snap(stubCx - (mainW + subW) / 2.0f);
        TicketArt.drawScaled(g, font, stub, left, ty, s, RailArt.a(PALETTE.bandInk(), alpha));
        drawSub(g, font, stubSub, left + mainW + 4.0f, ty, s,
                0.5f, RailArt.a(PALETTE.inkSoft(), alpha * 0.9f));
    }

    private void drawPhoto(GuiGraphics g, int mouseX, int mouseY, float time, float alpha) {
        float x0 = PHOTO_X;
        float y0 = PHOTO_Y;
        float x1 = PHOTO_X + PHOTO_W;
        float y1 = PHOTO_Y + PHOTO_H;
        GuiGeo geo = GuiGeo.begin(g);
        geo.convexV(RailArt.chamfer(x0, y0, PHOTO_W, PHOTO_H, 3.0f), y0, y1, RailArt.a(0xFF34528F, alpha),
                RailArt.a(0xFF15244A, alpha));
        for (float y = y0 + 2.0f; !lightweight && y < y1 - 1.0f; y += 2.0f) {
            geo.rect(x0 + 1.0f, y, x1 - 1.0f, y + 0.3f, RailArt.a(0xFFFFFFFF, 0.035f * alpha));
        }
        geo.end();
        if (!lightweight) {
            GuiGeo glow = GuiGeo.glow(g);
            glow.softGlow((x0 + x1) / 2.0f, y0 + PHOTO_H * 0.42f, PHOTO_W * 0.62f, PHOTO_H * 0.5f, RailArt.BRASS_LIGHT,
                    Math.round(46 * alpha));
            glow.end();
        }

        LocalPlayer player = mc.player;
        if (player != null && alpha > 0.6f) {
            if (lightweight) PlayerFaceRenderer.draw(g, player.getSkin().texture(), (int) PHOTO_X + 5, (int) PHOTO_Y + 16, 40);
            else drawPassengerModel(g, player, mouseX, mouseY);
        }

        GuiGeo frame = GuiGeo.begin(g);
        int trim = RailArt.a(PALETTE.trim(), alpha);
        frame.outline(RailArt.chamfer(x0, y0, PHOTO_W, PHOTO_H, 3.0f), 0.9f, trim);
        // 相角
        float c = 5.0f;
        int corner = RailArt.a(RailArt.BRASS_LIGHT, alpha * 0.9f);
        frame.line(x0 + 2.0f, y0 + 2.0f + c, x0 + 2.0f, y0 + 2.0f, 0.7f, corner);
        frame.line(x0 + 2.0f, y0 + 2.0f, x0 + 2.0f + c, y0 + 2.0f, 0.7f, corner);
        frame.line(x1 - 2.0f, y1 - 2.0f - c, x1 - 2.0f, y1 - 2.0f, 0.7f, corner);
        frame.line(x1 - 2.0f, y1 - 2.0f, x1 - 2.0f - c, y1 - 2.0f, 0.7f, corner);
        // 照片角上的钢印
        float sx = x1 - 3.0f;
        float sy = y1 - 4.0f;
        int seal = RailArt.a(RailArt.BRASS_LIGHT, alpha * 0.55f);
        frame.ring(sx, sy, 7.2f, 7.8f, seal);
        frame.ring(sx, sy, 5.0f, 5.4f, seal);
        for (int i = 0; i < 16; i++) {
            float a = Mth.TWO_PI * i / 16.0f + time * 0.25f;
            frame.line(sx + Mth.cos(a) * 5.6f, sy + Mth.sin(a) * 5.6f, sx + Mth.cos(a) * 6.9f, sy + Mth.sin(a) * 6.9f,
                    0.4f, seal);
        }
        frame.diamond(sx, sy, 1.6f, 1.6f, seal);
        frame.end();
    }

    /** 照片窗里的旅客：完整身形，随鼠标转头。模型写深度，画完清掉深度，后续的票面元素按画家顺序叠上。 */
    private void drawPassengerModel(GuiGraphics g, LocalPlayer player, int mouseX, int mouseY) {
        float cx = PHOTO_X + PHOTO_W / 2.0f;
        float cy = PHOTO_Y + PHOTO_H / 2.0f + 4.0f;
        float guiCx = ticketX + cx * k;
        float guiEyeY = ticketY + (PHOTO_Y + PHOTO_H * 0.3f) * k;
        float yawF = (float) Math.atan((guiCx - mouseX) / 40.0f);
        float pitchF = (float) Math.atan((guiEyeY - mouseY) / 40.0f);
        Quaternionf pose = new Quaternionf().rotateZ(Mth.PI);
        Quaternionf camera = new Quaternionf().rotateX(pitchF * 20.0f * Mth.DEG_TO_RAD);
        pose.mul(camera);
        float bodyRot = player.yBodyRot;
        float yRot = player.getYRot();
        float xRot = player.getXRot();
        float headRotO = player.yHeadRotO;
        float headRot = player.yHeadRot;
        player.yBodyRot = 180.0f + yawF * 20.0f;
        player.setYRot(180.0f + yawF * 40.0f);
        player.setXRot(-pitchF * 20.0f);
        player.yHeadRot = player.getYRot();
        player.yHeadRotO = player.getYRot();
        try {
            float entityScale = player.getScale();
            Vector3f translate = new Vector3f(0.0f, player.getBbHeight() / 2.0f, 0.0f);
            InventoryScreen.renderEntityInInventory(g, cx, cy, 29.0f / entityScale, translate, pose, camera, player);
        } finally {
            player.yBodyRot = bodyRot;
            player.setYRot(yRot);
            player.setXRot(xRot);
            player.yHeadRotO = headRotO;
            player.yHeadRot = headRot;
        }
        g.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    private void drawPassengerInfo(GuiGraphics g, Font font, long now, float alpha) {
        LocalPlayer player = mc.player;
        String name = passengerName;
        int ink = RailArt.a(PALETTE.ink(), alpha);
        int soft = RailArt.a(PALETTE.inkSoft(), alpha * 0.9f);
        labelPair(g, font, "inventory.habitrain_core.ticket.passenger", INFO_X, 28.0f, soft, alpha);
        // 姓名：打字机逐字印出
        long since = lightweight || reentryAt >= 0L ? Long.MAX_VALUE : now - openedAt - NAME_START_MS;
        int chars = since == Long.MAX_VALUE ? name.length() : (int) Mth.clamp(since / 32L, 0L, name.length());
        float maxW = BODY_RIGHT - INFO_X;
        Component fullName = nameLabel;
        float desired = Math.min(2.0f, maxW / Math.max(1.0f, font.width(fullName)));
        float ns = Math.min(cs(name, desired), desired);
        if (chars != revealedCharacters) {
            revealedCharacters = chars;
            revealedName = chars == name.length() ? nameLabel : Component.literal(name.substring(0, chars)).withStyle(ChatFormatting.BOLD);
        }
        TicketArt.drawScaled(g, font, revealedName, INFO_X, 39.0f, ns, ink);
        if (chars < name.length() && chars > 0) {
            float cursorX = INFO_X + font.width(revealedName) * ns + 1.0f;
            GuiGeo geo = GuiGeo.begin(g);
            geo.rect(cursorX, 39.0f, cursorX + ns, 39.0f + 8.0f * ns, RailArt.a(RailArt.BRASS_LIGHT, alpha));
            geo.end();
        }
        // 车厢 · 座位 · 席别
        float x = INFO_X;
        float y = 60.0f;
        x = field(g, font, "inventory.habitrain_core.ticket.car", carLabel, x, y, alpha) + 9.0f;
        x = field(g, font, "inventory.habitrain_core.ticket.seat", seatLabel, x, y, alpha) + 9.0f;
        field(g, font, "inventory.habitrain_core.ticket.class",
                texts.label("inventory.habitrain_core.ticket.class.first"), x, y, alpha);
    }

    /** 「标签 值」一组，返回结束 x。 */
    private float field(GuiGraphics g, Font font, String key, Component value, float x, float y, float alpha) {
        Component label = texts.label(key);
        float s = cs(label.getString(), 1.0f);
        TicketArt.drawScaled(g, font, label, x, y, s, RailArt.a(PALETTE.inkSoft(), alpha * 0.85f));
        float vx = x + font.width(label) * s + 3.0f;
        float vs = cs(value.getString(), 1.0f);
        TicketArt.drawScaled(g, font, value, vx, y, vs, RailArt.a(RailArt.BRASS_LIGHT, alpha));
        return vx + font.width(value) * vs;
    }

    private void drawRoute(GuiGraphics g, Font font, float time, float alpha) {
        float left = INFO_X;
        float right = BODY_RIGHT - 2.0f;
        float railY = 82.0f;
        float shuttle = 0.5f - 0.5f * Mth.cos(time * Mth.TWO_PI / 16.0f);
        boolean ready = participating == null || participating;
        int signal = ready ? 0xFF7CFFB0 : RailArt.RUBY;
        int halo = ready ? 0xFF3CE08A : 0xFFFF3040;
        if (lightweight) {
            GuiGeo rail = GuiGeo.begin(g);
            rail.line(left, railY, right, railY, 1, PALETTE.trim());
            rail.diamond(right, railY, 2, 2, signal);
            rail.end();
        } else {
            RailArt.drawRouteRail(g, left, right, railY, 0.06f + 0.88f * TransitionFx.easeInOutCubic(shuttle),
                    RailArt.a(signal, alpha), halo, time);
        }
        int soft = RailArt.a(PALETTE.inkSoft(), alpha * 0.8f);
        Component from = texts.label("inventory.habitrain_core.ticket.platform");
        Component to = texts.label("inventory.habitrain_core.ticket.next");
        float s = cs(from.getString() + to.getString(), 1.0f);
        TicketArt.drawScaled(g, font, from, left, railY + 4.0f, s, soft);
        TicketArt.drawRight(g, font, to, right, railY + 4.0f, s, soft);
    }

    private void drawParticipation(GuiGraphics g, Font font, long now, float alpha) {
        float y = 98.0f;
        labelPair(g, font, "inventory.habitrain_core.ticket.this_run", INFO_X, y + 3.0f,
                RailArt.a(PALETTE.inkSoft(), alpha * 0.9f), alpha);
        Component join = texts.label("inventory.habitrain_core.ticket.join");
        Component out = texts.label("inventory.habitrain_core.ticket.sit_out");
        float s = cs(join.getString() + out.getString(), 1.0f);
        float boxH = 14.0f;
        float joinW = Math.max(40.0f, font.width(join) * s + 20.0f);
        float outW = Math.max(40.0f, font.width(out) * s + 20.0f);
        float x1 = BODY_RIGHT - outW;
        float x0 = x1 - 5.0f - joinW;
        boolean on = participating == null || participating;
        drawFareBox(g, font, join, x0, y, joinW, boxH, s, on, participationHover == 0, now, alpha, 0);
        drawFareBox(g, font, out, x1, y, outW, boxH, s, !on, participationHover == 1, now, alpha, 1);
        participationBoxes[0][0] = x0; participationBoxes[1][0] = x1;
        participationBoxes[0][1] = participationBoxes[1][1] = y;
        participationBoxes[0][2] = joinW; participationBoxes[1][2] = outW;
        participationBoxes[0][3] = participationBoxes[1][3] = boxH;
    }

    /** 一格「票价框」：选中的那格被打了孔，并描金。 */
    private void drawFareBox(GuiGraphics g, Font font, Component text, float x, float y, float w, float h, float s,
                             boolean active, boolean hover, long now, float alpha, int index) {
        boolean waiting = participationRequestedAt >= 0L && !active;
        float pulse = waiting ? 0.5f + 0.5f * Mth.sin((now - participationRequestedAt) / 90.0f) : 0.0f;
        GuiGeo geo = GuiGeo.begin(g);
        if (active) {
            geo.convexV(RailArt.chamfer(x, y, w, h, 2.5f), y, y + h, RailArt.a(RailArt.BRASS, 0.28f * alpha),
                    RailArt.a(RailArt.BRASS_DIM, 0.18f * alpha));
        } else if (hover || waiting) {
            geo.convexV(RailArt.chamfer(x, y, w, h, 2.5f), y, y + h, RailArt.a(0xFFFFFFFF, (0.08f + 0.08f * pulse) * alpha),
                    RailArt.a(0xFFFFFFFF, 0.03f * alpha));
        }
        int edge = active ? RailArt.a(RailArt.BRASS_LIGHT, alpha)
                : RailArt.a(hover ? RailArt.TEXT_MUTED : RailArt.TEXT_FAINT, alpha * (hover ? 1.0f : 0.85f));
        geo.outline(RailArt.chamfer(x, y, w, h, 2.5f), active ? 0.9f : 0.6f, edge);
        // 打孔：选中格左侧一个圆孔，切换时孔从无到有，纸屑飞出
        float hx = x + 6.5f;
        float hy = y + h / 2.0f;
        if (active) {
            float p = lightweight || participationChangedAt < 0L ? 1.0f
                    : TransitionFx.clamp01((now - participationChangedAt) / (float) PUNCH_MS);
            float r = 2.6f * (p >= 1.0f ? 1.0f : easeOutBack(Math.min(1.0f, p * 2.2f)));
            TicketArt.punchHole(geo, hx, hy, r, RailArt.a(RailArt.NIGHT_0, alpha), RailArt.a(RailArt.BRASS_DIM, alpha));
            if (p < 1.0f) {
                float chipX = hx + p * 14.0f;
                float chipY = hy + p * p * 22.0f;
                geo.disc(chipX, chipY, 2.4f * (1.0f - p * 0.4f), 2.4f * (0.5f + 0.5f * Mth.cos(p * 14.0f)),
                        RailArt.a(PALETTE.paperTop(), (1.0f - p) * alpha), RailArt.a(PALETTE.paperBottom(), (1.0f - p) * alpha));
            }
        } else {
            geo.ring(hx, hy, 2.2f, 2.7f, RailArt.a(RailArt.TEXT_FAINT, alpha * 0.7f));
        }
        geo.end();
        int color = active ? RailArt.a(RailArt.CHAMPAGNE, alpha)
                : RailArt.a(hover ? RailArt.TEXT : RailArt.TEXT_MUTED, alpha);
        TicketArt.drawScaled(g, font, text, snap(x + 12.0f), snap(y + (h - 8.0f * s) / 2.0f), s, color);
    }

    private void drawCarryOnLabel(GuiGraphics g, Font font, float alpha) {
        labelPair(g, font, "inventory.habitrain_core.ticket.carry_on", SLOT_X0, 113.0f,
                RailArt.a(PALETTE.inkSoft(), alpha * 0.9f), alpha);
        // 行李架：槽位行两端的短黄铜挂杆
        GuiGeo geo = GuiGeo.begin(g);
        float y = SLOT_Y + 9.0f;
        int brass = RailArt.a(RailArt.BRASS_DIM, alpha * 0.8f);
        float rowRight = SLOT_X0 + SLOT_PITCH * 9.0f - 2.0f;
        geo.rect(PHOTO_X, y - 0.4f, SLOT_X0 - 3.0f, y + 0.4f, brass);
        geo.rect(rowRight + 3.0f, y - 0.4f, BODY_RIGHT, y + 0.4f, brass);
        geo.diamond(PHOTO_X, y, 1.4f, 1.4f, brass);
        geo.diamond(BODY_RIGHT, y, 1.4f, 1.4f, brass);
        geo.end();
    }

    private void drawStub(GuiGraphics g, Font font, long now, float alpha) {
        float left = shape.stubLeft();
        float right = TICKET_W - shape.inset() - 2.0f;
        float cx = (left + right) / 2.0f;
        Component time = texts.label("inventory.habitrain_core.ticket.time");
        float s = cs(time.getString(), 1.0f);
        TicketArt.drawCentered(g, font, time, snap(cx), 28.0f, s, RailArt.a(PALETTE.inkSoft(), alpha * 0.9f));
        String date = clockText.date();
        if (!date.equals(dateString)) {
            dateString = date;
            dateLabel = Component.literal(date);
        }
        float ds = cs(date, 0.5f);
        TicketArt.drawCentered(g, font, dateLabel, snap(cx), 55.0f, ds, RailArt.a(PALETTE.inkSoft(), alpha));

        drawCheckStamp(g, font, cx, CHECK_Y, now, alpha);

        Component gate = texts.label("inventory.habitrain_core.ticket.gate");
        float gs = cs(gate.getString(), 1.0f);
        Component gateNo = gateLabel;
        float total = font.width(gate) * gs + 3.0f + font.width(gateNo) * gs;
        float gx = snap(cx - total / 2.0f);
        TicketArt.drawScaled(g, font, gate, gx, 106.0f, gs, RailArt.a(PALETTE.inkSoft(), alpha * 0.9f));
        TicketArt.drawScaled(g, font, gateNo, gx + font.width(gate) * gs + 3.0f, 106.0f, gs,
                RailArt.a(RailArt.BRASS_LIGHT, alpha));

        GuiGeo geo = GuiGeo.begin(g);
        TicketArt.barcode(geo, left + 2.0f, 119.0f, right - left - 4.0f, 17.0f, serialHash(),
                RailArt.a(PALETTE.ink(), alpha * 0.8f));
        geo.end();
        String digits = serialDigits;
        float ss = cs(digits, 0.5f);
        TicketArt.drawCentered(g, font, serialNumber, snap(cx), 138.0f, ss,
                RailArt.a(PALETTE.inkSoft(), alpha * 0.8f));
    }

    /** 副券上的检票戳：参与本局时是红色圆戳「已检票」，旁观时是灰蓝色虚线圈。切换时重新盖下。 */
    private void drawCheckStamp(GuiGraphics g, Font font, float cx, float cy, long now, float alpha) {
        boolean on = participating == null || participating;
        long since = lightweight ? Long.MAX_VALUE : participationChangedAt >= 0L ? now - participationChangedAt
                : reentryAt >= 0L ? Long.MAX_VALUE : now - openedAt - CHECK_AT_MS;
        if (since < 0L) {
            return;
        }
        float slam = since == Long.MAX_VALUE ? 1.0f : TransitionFx.clamp01(since / 160.0f);
        float impact = 1.0f + (1.0f - TransitionFx.easeOutCubic(slam)) * 0.9f;
        float a = alpha * TransitionFx.clamp01(slam * 2.5f);
        int ink = on ? 0xFFE0473E : 0xFF8FA2C8;
        float r = 18.0f;
        Component main = texts.label(on ? "inventory.habitrain_core.ticket.checked"
                : "inventory.habitrain_core.ticket.watching");
        String sub = secondaryLabels() ? texts.subtitle(on ? "inventory.habitrain_core.ticket.checked"
                : "inventory.habitrain_core.ticket.watching") : "";
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(on ? -14.0f : 8.0f));
        g.pose().scale(impact, impact, 1.0f);
        GuiGeo geo = GuiGeo.begin(g);
        int c = RailArt.a(ink, 0.9f * a);
        if (on) {
            geo.ring(0, 0, r - 1.4f, r, c);
            geo.ring(0, 0, r - 3.2f, r - 2.6f, c);
            geo.diamond(0, r - 5.6f, 1.2f, 1.2f, c);
        } else {
            for (int i = 0; i < 20; i++) {
                float a0 = Mth.TWO_PI * i / 20.0f;
                geo.arc(0, 0, r - 1.0f, r, 1.0f, a0, a0 + Mth.TWO_PI / 40.0f, c, c);
            }
        }
        // 缺墨颗粒
        for (int i = 0; i < 18; i++) {
            float ang = TicketArt.hash(i * 7 + 3) * Mth.TWO_PI;
            float d = TicketArt.hash(i * 13 + 1) * r;
            float size = 0.4f + TicketArt.hash(i * 5) * 0.7f;
            geo.rect(Mth.cos(ang) * d, Mth.sin(ang) * d, Mth.cos(ang) * d + size, Mth.sin(ang) * d + size,
                    RailArt.a(PALETTE.paperTop(), 0.5f * a));
        }
        geo.end();
        float ms = Math.min(cs(main.getString(), 1.0f), (r * 2.0f - 5.0f) / Math.max(1, font.width(main)));
        TicketArt.drawCentered(g, font, main, 0.0f, -6.5f * ms + (sub.isBlank() ? 2.5f : 0.0f), ms, c);
        if (!sub.isBlank()) {
            float subScale = cs(sub, 0.5f);
            TicketArt.drawCentered(g, font, Component.literal(sub), 0.0f, 3.0f, subScale, c);
        }
        g.pose().popPose();
        if (on && slam < 1.0f && participationChangedAt >= 0L) {
            TicketArt.inkSplash(g, cx, cy, r * 1.4f, ink, slam, 5);
        }
    }

    /** 翻牌时钟：FlapBoard 内部用 scissor 裁切，必须在未缩放的 GUI 坐标里画；入场旋转结束后才出现。 */
    private void drawClock(GuiGraphics g, Font font, long now) {
        if (ticketRot != 0.0f) {
            return;
        }
        boolean due = reentryAt >= 0L || now - openedAt >= CLOCK_AT_MS;
        clock.set(due || lightweight ? clockText.time() : "");
        int tileH = Math.max(5, Math.round(11.0f * k));
        int tileW = Math.round(tileH * 0.78f);
        int gap = Math.max(1, Math.round(k));
        float cx = (shape.stubLeft() + TICKET_W - shape.inset() - 2.0f) / 2.0f;
        int x = Math.round(ticketX + cx * k - clock.tileWidth(tileW, gap) / 2.0f);
        int y = Math.round(ticketY + 39.0f * k + (13.0f * k - tileH) / 2.0f);
        clock.render(g, font, x, y, tileW, tileH, gap, RailArt.CHAMPAGNE, RailArt.NIGHT_3, RailArt.NIGHT_1, ticketAlpha,
                !lightweight && motionScale > 0.01f);
    }

    /** 快捷栏槽位框：按物品实际落点（取整后的 GUI 坐标）画，保证与物品严格对齐。 */
    private void drawSlotFrames(GuiGraphics g, long now, int mouseX, int mouseY) {
        if (ticketRot != 0.0f) {
            return;
        }
        LocalPlayer player = mc.player;
        int selected = player == null ? -1 : player.getInventory().selected;
        float size = 16.0f * k;
        float pad = 1.0f * k;
        GuiGeo geo = GuiGeo.begin(g);
        GuiGeo glow = null;
        for (int i = 0; i < 9; i++) {
            float x = ticketX + Math.round((SLOT_X0 + 1.0f + i * SLOT_PITCH) * k);
            float y = ticketY + Math.round((SLOT_Y + 1.0f) * k);
            boolean hover = mouseX >= x - pad && mouseX < x + size + pad && mouseY >= y - pad && mouseY < y + size + pad;
            boolean sel = i == selected;
            geo.rectV(x - pad, y - pad, x + size + pad, y + size + pad, RailArt.a(RailArt.NIGHT_0, 0.62f * ticketAlpha),
                    RailArt.a(RailArt.NIGHT_1, 0.5f * ticketAlpha));
            geo.rect(x - pad, y - pad, x + size + pad, y - pad + 1.5f * k, RailArt.a(0xFF000000, 0.35f * ticketAlpha));
            int edge = sel ? RailArt.a(RailArt.BRASS_LIGHT, ticketAlpha)
                    : RailArt.a(hover ? RailArt.BRASS : RailArt.BRASS_DIM, ticketAlpha * (hover ? 1.0f : 0.75f));
            geo.rectOutline(x - pad, y - pad, x + size + pad, y + size + pad, (sel ? 0.9f : 0.5f) * k, edge);
            if (sel) {
                geo.tri(x + size / 2.0f - 2.0f * k, y + size + pad + 2.6f * k, edge, x + size / 2.0f + 2.0f * k,
                        y + size + pad + 2.6f * k, edge, x + size / 2.0f, y + size + pad + 0.8f * k, edge);
            }
        }
        geo.end();
        Font font = mc.font;
        float tiny = StampArt.crispDu("0", 0.5f, k) * k;
        for (int i = 0; i < 9; i++) {
            float x = ticketX + Math.round((SLOT_X0 + 1.0f + i * SLOT_PITCH) * k);
            float y = ticketY + Math.round((SLOT_Y + 1.0f) * k);
            TicketArt.drawScaled(g, font, TicketTextCache.HOTBAR_NUMBERS[i], x + 0.5f * k, y + 0.5f * k, tiny,
                    RailArt.a(RailArt.TEXT_FAINT, 0.9f * ticketAlpha));
        }
        if (!lightweight && selected >= 0 && selected < 9) {
            glow = GuiGeo.glow(g);
            float x = ticketX + Math.round((SLOT_X0 + 1.0f + selected * SLOT_PITCH) * k);
            float y = ticketY + Math.round((SLOT_Y + 1.0f) * k);
            float breathe = 0.75f + 0.25f * Mth.sin((now - openedAt) / 420.0f);
            glow.softGlow(x + size / 2.0f, y + size / 2.0f, size * 0.9f, size * 0.9f, RailArt.BRASS_LIGHT,
                    Math.round(46 * breathe * ticketAlpha));
            glow.end();
        }
    }

    // ---------- 本局参与 ----------

    private void updateParticipation(long now) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        boolean current;
        try {
            current = ParticipationComponent.KEY.get(player.level()).isParticipating(player);
        } catch (RuntimeException e) {
            current = true;
        }
        if (participating != null && participating != current) {
            participationChangedAt = now;
            participationRequestedAt = -1L;
            if (current) {
                TicketSounds.postmark();
            } else {
                TicketSounds.punch();
            }
        }
        participating = current;
        if (participationRequestedAt >= 0L && now - participationRequestedAt > 2500L) {
            participationRequestedAt = -1L;
        }
    }

    private int participationPartAt(double mx, double my) {
        if (participation == null || ticketRot != 0.0f) {
            return -1;
        }
        for (int i = 0; i < 2; i++) {
            float[] b = participationBoxes[i];
            if (b[2] <= 0.0f) {
                continue;
            }
            float x0 = ticketX + b[0] * k;
            float y0 = ticketY + b[1] * k;
            if (mx >= x0 && mx < x0 + b[2] * k && my >= y0 && my < y0 + b[3] * k) {
                return i;
            }
        }
        return -1;
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!showing || failed || button != 0) {
            return false;
        }
        long now = Util.getMillis();
        keyboardIndex = -1;
        int index = stampIndexAt(mouseX, mouseY);
        if (index >= 0) {
            clickStamp(stamps.get(index), now);
            return true;
        }
        if (pager[0] >= 0 && mouseY >= pager[1] && mouseY < pager[5]) {
            if (mouseX >= pager[0] && mouseX < pager[2]) {
                changePage(-1);
                return true;
            }
            if (mouseX >= pager[3] && mouseX < pager[4]) {
                changePage(1);
                return true;
            }
        }
        int part = participationPartAt(mouseX, mouseY);
        if (part >= 0) {
            selectParticipation(part, now);
            return true;
        }
        return false;
    }

    private void selectParticipation(int part, long now) {
        boolean on = participating == null || participating;
        if (participation != null && (part == 0) != on && participationRequestedAt < 0L) {
            participationRequestedAt = now;
            TicketSounds.punch();
            participation.onPress();
        }
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (!showing || failed) return false;
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            changePage(key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1);
            keyboardIndex = Math.min(stamps.size() - 1, page * perPage);
            focused = true;
            return true;
        }
        boolean tab = key == GLFW.GLFW_KEY_TAB;
        boolean arrow = key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
                || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN;
        if (tab || focused && arrow) {
            int count = stamps.size() + (participation == null ? 0 : 2);
            if (count == 0) return false;
            int direction = (tab && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0)
                    || key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_UP ? -1 : 1;
            int next = keyboardIndex < 0 ? (direction < 0 ? count - 1 : page * perPage)
                    : Math.floorMod(keyboardIndex + direction, count);
            if (next < stamps.size() && next / perPage != page) changePage(next / perPage - page);
            keyboardIndex = next;
            focused = true;
            return true;
        }
        if (keyboardIndex >= 0 && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER
                || key == GLFW.GLFW_KEY_SPACE)) {
            if (keyboardIndex < stamps.size()) clickStamp(stamps.get(keyboardIndex), Util.getMillis());
            else selectParticipation(keyboardIndex - stamps.size(), Util.getMillis());
            return true;
        }
        return false;
    }

    private void clickStamp(Stamp stamp, long now) {
        if (pending != null) {
            return;
        }
        if (isLocked(stamp)) {
            stamp.deniedAt = now;
            TicketSounds.denied();
            MenuPermissions.showDeniedMessage();
            return;
        }
        stamp.clickedAt = now;
        visited.add(stamp.key);
        TicketSounds.postmark();
        pending = stamp;
        pendingAt = now + ACTION_DELAY_MS;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!showing || failed || pages <= 1 || scrollY == 0.0) {
            return false;
        }
        changePage(scrollY < 0.0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        // 整张票及上下两排邮票的范围：滚轮在这里翻邮票页（Screen 只把滚轮交给鼠标下的子元素）
        return showing && mouseX >= ticketX - 12.0f * k && mouseX < ticketX + (TICKET_W + 12.0f) * k
                && mouseY >= ticketY - (PEEK_HOVER + 6.0f) * k && mouseY < ticketY + (TICKET_H + PEEK_HOVER + 16.0f) * k;
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public NarrationPriority narrationPriority() {
        if (!showing) return NarrationPriority.NONE;
        return keyboardIndex >= 0 ? NarrationPriority.FOCUSED
                : hovered >= 0 ? NarrationPriority.HOVERED : NarrationPriority.NONE;
    }

    @Override
    public void updateNarration(NarrationElementOutput output) {
        int index = keyboardIndex >= 0 ? keyboardIndex : hovered;
        if (index >= 0 && index < stamps.size()) {
            output.add(NarratedElementType.TITLE, stamps.get(index).label);
        } else if (keyboardIndex >= stamps.size()) {
            output.add(NarratedElementType.TITLE, texts.label(keyboardIndex == stamps.size()
                    ? "inventory.habitrain_core.ticket.join" : "inventory.habitrain_core.ticket.sit_out"));
        }
        output.add(NarratedElementType.USAGE, texts.label(index >= 0 && index < stamps.size()
                && stamps.get(index).source == modeButton ? "inventory.habitrain_core.ticket.mode.hint"
                : "inventory.habitrain_core.ticket.controls_pages"));
    }

    // ==================== 工具 ====================

    /** 在已缩放 k 的票面坐标里，按文字内容取整到屏幕像素的缩放。 */
    private float cs(String text, float desired) {
        return StampArt.crispDu(text, desired, k);
    }

    /** 票面坐标对齐到屏幕像素。 */
    private float snap(float du) {
        double gs = mc.getWindow().getGuiScale();
        double unit = k * gs;
        return unit <= 0.0 ? du : (float) (Math.round(du * unit) / unit);
    }

    /** 小标签 + 另一种语言的宽字距副标签。 */
    private void labelPair(GuiGraphics g, Font font, String key, float x, float y, int color, float alpha) {
        Component label = texts.label(key);
        float s = cs(label.getString(), 1.0f);
        TicketArt.drawScaled(g, font, label, x, y, s, color);
        drawSub(g, font, texts.subtitle(key), x + font.width(label) * s + 3.0f, y, s, 0.5f,
                RailArt.a(PALETTE.inkSoft(), alpha * 0.6f));
    }

    private void drawSub(GuiGraphics g, Font font, String text, float x, float mainY, float mainScale, float desired,
                         int color) {
        if (!secondaryLabels() || text == null || text.isBlank()) {
            return;
        }
        float s = cs(text, desired);
        TicketArt.drawTrackedLeft(g, font, text, x, mainY + 8.0f * mainScale - 8.0f * s, s, color);
    }

    private boolean secondaryLabels() {
        return k * mc.getWindow().getGuiScale() >= 2.25;
    }

    private int serialHash() {
        LocalPlayer player = mc.player;
        return player == null ? 0 : player.getUUID().hashCode() * 31 + 17;
    }

    private void updatePassengerText() {
        LocalPlayer player = mc.player;
        UUID id = player == null ? null : player.getUUID();
        String name = player == null ? "" : player.getGameProfile().getName();
        if (Objects.equals(id, passengerId) && name.equals(passengerName)) return;
        passengerId = id;
        passengerName = name;
        int hash = id == null ? 0 : id.hashCode();
        serialDigits = String.format(Locale.ROOT, "%07d", Math.floorMod(serialHash(), 10_000_000));
        serialLabel = Component.literal("No." + serialDigits);
        serialNumber = Component.literal(serialDigits);
        carLabel = Component.literal(String.format(Locale.ROOT, "%02d", 1 + Math.floorMod(hash >>> 3, 8)));
        seatLabel = Component.literal((1 + Math.floorMod(hash >>> 7, 16)) + String.valueOf("ABCD".charAt(Math.floorMod(hash >>> 11, 4))));
        gateLabel = Component.literal(String.format(Locale.ROOT, "%02d", 1 + Math.floorMod(serialHash() >>> 5, 12)));
        nameLabel = Component.literal(name).withStyle(ChatFormatting.BOLD);
        revealedCharacters = -1;
    }

    private static float easeOutBack(float x) {
        float c1 = 1.5f;
        float c3 = c1 + 1.0f;
        float t = TransitionFx.clamp01(x) - 1.0f;
        return 1.0f + c3 * t * t * t + c1 * t * t;
    }

    /** 物品落入槽位：落下后轻弹一下。返回剩余高度比例（1 = 起点，0 = 落定）。 */
    private static float bounceDrop(float p) {
        if (p < 0.6f) {
            float t = p / 0.6f;
            return 1.0f - t * t;
        }
        float t = (p - 0.6f) / 0.4f;
        return Mth.sin(t * Mth.PI) * 0.18f;
    }

    static String keyOf(Component label) {
        ComponentContents contents = label.getContents();
        if (contents instanceof TranslatableContents translatable) {
            return translatable.getKey();
        }
        return label.getString();
    }

    /** 自有受限入口按键识别，其余管理员项沿用上游的红色标记。 */
    static boolean isRestricted(Component label) {
        String key = keyOf(label);
        if (TASK_SETTINGS_KEY.equals(key) || MAP_ROTATION_KEY.equals(key)) return true;
        TextColor color = label.getStyle().getColor();
        return color != null && color.equals(TextColor.fromLegacyFormat(ChatFormatting.RED));
    }

    /**
     * 按翻译键的最后一段挑图标（上游键都带 {@code limited_inventory} 前缀，不能对整个键做包含判断）；
     * 未知的新菜单项用水晶。
     */
    static WireModels.Icon iconFor(String key) {
        if (TicketPerformanceButton.KEY.equals(key)) return WireModels.Icon.GEAR;
        String full = key.toLowerCase(Locale.ROOT);
        String k = full.substring(full.lastIndexOf('.') + 1);
        if (k.contains("setting")) {
            return WireModels.Icon.GEAR;
        }
        if (k.equals("map_introduction")) {
            return WireModels.Icon.GLOBE;
        }
        if (k.equals("introduction")) {
            return WireModels.Icon.TOP_HAT;
        }
        if (k.contains("map")) {
            return WireModels.Icon.TURNTABLE;
        }
        if (k.contains("record")) {
            return WireModels.Icon.TROPHY;
        }
        if (k.contains("skin")) {
            return WireModels.Icon.DRESS_FORM;
        }
        if (k.contains("backpack") || k.contains("inventory") || k.contains("storage")) {
            return WireModels.Icon.SUITCASE;
        }
        if (k.contains("game_menu") || k.contains("manage")) {
            return WireModels.Icon.STOPWATCH;
        }
        if (k.contains("roster")) {
            return WireModels.Icon.CARDS;
        }
        if (k.contains("guess")) {
            return WireModels.Icon.MAGNIFIER;
        }
        if (k.contains("loot") || k.contains("lottery") || k.contains("gacha")) {
            return WireModels.Icon.GIFT;
        }
        if (k.contains("mail")) {
            return WireModels.Icon.ENVELOPE;
        }
        return WireModels.Icon.CRYSTAL;
    }

    static StampArt.Ink inkFor(String key, int index) {
        return switch (iconFor(key)) {
            case TOP_HAT -> StampArt.BURGUNDY;
            case GLOBE -> StampArt.SAPPHIRE;
            case TURNTABLE -> StampArt.TEAL;
            case TROPHY -> StampArt.OCHRE;
            case DRESS_FORM -> StampArt.PLUM;
            case SUITCASE -> StampArt.CHESTNUT;
            case GEAR -> StampArt.SLATE;
            case STOPWATCH -> StampArt.VERMILION;
            case CARDS -> StampArt.EMERALD;
            case MAGNIFIER -> StampArt.INDIGO;
            default -> StampArt.ROTATION[Math.floorMod(key.hashCode() + index, StampArt.ROTATION.length)];
        };
    }

    /** 一枚邮票：对应一个菜单动作（包括本地简洁模式）。 */
    private static final class Stamp {
        final Button source;
        Component label;
        final boolean restricted;
        final String key;
        final WireModels.Icon icon;
        final StampArt.Ink ink;
        final int number;
        final float tilt;
        StampArt.Face face;
        final StampArt.Cache art = new StampArt.Cache();
        boolean truncated;
        boolean locked;
        float hover;
        float yaw;
        long clickedAt = -1L;
        long deniedAt = -1L;
        // 本帧布局
        boolean shown;
        boolean top;
        int order;
        float intro;
        float gx;
        float gy;

        Stamp(Button source, Component label, String key, WireModels.Icon icon, StampArt.Ink ink, int number) {
            this.source = source;
            this.label = label;
            this.restricted = isRestricted(label);
            this.key = key;
            this.icon = icon;
            this.ink = ink;
            this.number = number;
            this.tilt = (TicketArt.hash(key.hashCode()) - 0.5f) * 5.0f;
            this.yaw = TicketArt.hash(key.hashCode() * 3) * Mth.TWO_PI;
        }
    }
}
