package com.habitrain.core.client.gui;

import com.habitrain.core.network.OptionVotePayload;
import com.mojang.math.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 开局转场：一叠车票从四周飘落、铺满整个屏幕，最后一张「登车凭证」落在正中。
 *
 * <p>全程由服务端权威信号 + 本地 {@link VoteLaunchSession} 驱动：</p>
 * <ol>
 *   <li><b>重置地图</b>：投票结束后上游重置地图期间不开本屏，只在屏幕顶部挂
 *       {@link MapResetProgressHud} 进度牌，玩家可自由操作。</li>
 *   <li><b>判定点 A</b>：地图重置完成（{@code MapVoteStartConfirmedPayload}），约 3 秒后服务端传送玩家。
 *       此时打开本屏：十几张车票（落选地图的车票）从屏幕外打着旋飘进来，赶在传送前铺满画面；
 *       登车凭证最后落定，票面印着终点站、模式、人数与线路进度。hide 已锁定。
 *       玩家无法隐藏或跳过本屏，输入一律吞掉。</li>
 *   <li><b>判定点 B</b>：环境就绪（{@link #confirmLaunch}）后，登车凭证上「啪」地盖下「对局开始」章、
 *       票根打孔。</li>
 *   <li><b>交还</b>：SRE 进入 ACTIVE（{@link #markGameActive()}）后，四周车票被风吹散，
 *       登车凭证沿撕票线撕开——票根落下、票身飞走，游戏世界随之露出。</li>
 * </ol>
 *
 * <p>{@link #openSafetyCover}：判定点 B 时若本屏意外不在（被别的界面顶掉），立即铺满车票重新盖住，
 * 若 session 已 launchConfirmed 再盖章。</p>
 *
 * <p>交还时 SRE 原版黑场与开场相机动画仍在宽限期内被屏蔽（见
 * {@code VoteLaunchFadeBlockMixin} / {@code VoteLaunchCameraBlockMixin}）。</p>
 */
public final class VoteLaunchTransitionScreen extends Screen {
    private static final long MIN_LOADING_MILLIS = 400L;
    /** 票堆飘落铺满屏幕、登车凭证落定的总时长（可见路径）。 */
    private static final long ENTER_MILLIS = 1_700L;
    /** 盖章到标题停留开始的时长。 */
    private static final long CONTENT_SWITCH_MILLIS = 700L;
    /** 「对局开始」章盖下后的停留时长，之后进入巡航氛围。 */
    private static final long TITLE_HOLD_MILLIS = 1_500L;
    /** 车票吹散、登车凭证撕开并飞走的总时长。 */
    private static final long EXIT_MILLIS = 1_300L;
    /** 巡航氛围过渡时长（毫秒）：等待提示淡入。 */
    private static final long CRUISE_TRANSITION_MILLIS = 1_500L;
    /**
     * 确认后仍未收到 ACTIVE 的极端兜底（毫秒）。
     *
     * <p>正常交还路径：成功由 {@link #markGameActive()}（ACTIVE）触发、失败由
     * {@link #markGameAborted()}（服务端开局中止信号）触发，二者都不应依赖此兜底。
     * 此值仅兜底"客户端丢了 ACTIVE 包/服务端状态异常"等极端情况。必须设得足够长，
     * 因为 SRE {@code initializeGame} 角色分配实测可耗时 30-34s（trueStartGame → ACTIVE）；
     * 收紧会导致动画在 ACTIVE 到达前被截断、SRE 相机 intro 时序错乱。</p>
     */
    private static final long GAME_ACTIVE_FALLBACK_MILLIS = 100_000L;
    /** 交还后仍屏蔽 SRE 相机 intro 的宽限期（毫秒）。相机 intro 默认 100 tick=5s，多留余量。 */
    private static final long CAMERA_BLOCK_GRACE_MILLIS = 7_000L;
    /**
     * 交还世界后继续阻止场景环境音启动的短宽限期。
     *
     * <p>开局时冒险模式、场景设置与传送位置不是一个原子客户端状态；给位置/区块判定
     * 额外半秒稳定下来，可避免旧大厅位置短暂满足“对局中且露天”而误启外部列车声。</p>
     */
    private static final long AMBIENT_SOUND_GRACE_MILLIS = 500L;
    /** 被覆盖的投票页含有带深度的文字；车票层整体抬到更高的深度绘制，并逐张递增。 */
    private static final float LAYER_Z = 400.0f;

    // ---- 登车凭证编排（相对飘落起点，可见路径时间轴） ----
    private static final long HERO_DELAY = 880L;
    private static final long HERO_FLIGHT = 800L;
    private static final long TICKET_STAGGER = 52L;
    private static final long STAMP_SLAM_MILLIS = 170L;

    private static final int VOID = RailArt.NIGHT_0;
    private static final int GOLD = RailArt.BRASS;
    private static final int GOLD_DARK = RailArt.BRASS_DIM;
    private static final int GOLD_BRIGHT = RailArt.BRASS_LIGHT;
    private static final int IVORY = RailArt.CHAMPAGNE;
    private static final int TEXT_MUTED = RailArt.TEXT_MUTED;

    /** 被车票覆盖的投票页，仅在飘落阶段作为背景渲染。 */
    private final Screen coveredScreen;
    /** 车票散去后真正交还的页面（已剥离 OptionVoteScreen）。 */
    private final Screen destination;
    private String winningMapId;
    private final long startedAtMillis = Util.getMillis();

    // 服务端加载信息（由 Session / MapVoteProgressPayload 更新）
    private int progress = 0;
    private int playerCount = 0;
    private int killerCount = 0;
    private String mapId = "";
    private String modeId = "";
    /** 平滑后的显示进度：服务端进度是阶梯式跳变，直接绘制会一顿一顿。 */
    private float displayedProgress = -1.0f;
    private long lastFrameMillis;

    // 阶段状态
    private boolean launchConfirmed;
    private long launchConfirmedAtMillis;
    private boolean exitStarted;
    private long exitStartAtMillis;
    private boolean completed;
    private boolean gameActive; // OnGameStartedClient 已触发

    /** 保险开屏：车票已铺满，跳过飘落。 */
    private final boolean skipEnterAnimation;

    private final List<FlyTicket> pile = new ArrayList<>();
    /** 音效只播一次；首帧把开屏前就已发生的节点标记为已播，避免保险开屏时补放一串。 */
    private boolean[] pileSounded = new boolean[0];
    private boolean soundsPrimed;
    private boolean heroSounded;
    private boolean stampSounded;
    private boolean tearSounded;
    private FlyTicket hero;
    private TicketArt.Shape heroShape;
    private final int serial;

    public VoteLaunchTransitionScreen(Screen destination, String winningMapId) {
        this(destination, winningMapId, false);
    }

    /** @param skipEnter true=车票直接铺满，不播飘落入场 */
    private VoteLaunchTransitionScreen(Screen destination, String winningMapId, boolean skipEnter) {
        super(OptionVoteTexts.transitionTitle());
        this.coveredScreen = destination;
        this.destination = unwrapVoteScreen(destination);
        this.winningMapId = winningMapId == null ? "" : winningMapId;
        this.mapId = this.winningMapId;
        this.skipEnterAnimation = skipEnter;
        this.serial = 100_000 + Math.floorMod(this.winningMapId.hashCode() * 31 + (int) (startedAtMillis / 1000L), 900_000);
        VoteLaunchOverlayState.setActive(true);
        pullFromSession();
    }

    /**
     * 本屏意外丢失时的保险全屏盖住（车票立即铺满）。若 session 已 launchConfirmed，再盖章。
     */
    public static VoteLaunchTransitionScreen openSafetyCover(Screen parent) {
        VoteLaunchTransitionScreen screen = new VoteLaunchTransitionScreen(
                parent,
                VoteLaunchSession.getWinningMapId(),
                true);
        if (VoteLaunchSession.isLaunchConfirmed()) {
            screen.confirmLaunch(VoteLaunchSession.getWinningMapId());
        }
        return screen;
    }

    @Override
    protected void init() {
        super.init();
        if (destination != null) {
            destination.resize(minecraft, width, height);
        }
        if (coveredScreen != null && coveredScreen != destination) {
            coveredScreen.resize(minecraft, width, height);
        }
        layoutTickets();
    }

    /** 服务端加载进度更新（MapVoteProgressPayload / Session）。 */
    public void updateProgress(int prog, int players, int killers, String map, String mode) {
        this.progress = Math.max(this.progress, Mth.clamp(prog, 0, 100));
        if (players > 0) this.playerCount = players;
        if (killers >= 0) this.killerCount = killers;
        if (map != null && !map.isBlank()) { this.mapId = map; this.winningMapId = map; }
        if (mode != null && !mode.isBlank()) this.modeId = mode;
    }

    public void pullFromSession() {
        if (!VoteLaunchSession.isActive()) return;
        updateProgress(
                VoteLaunchSession.getProgress(),
                VoteLaunchSession.getPlayerCount(),
                VoteLaunchSession.getKillerCount(),
                VoteLaunchSession.getMapId(),
                VoteLaunchSession.getModeId());
        if (VoteLaunchSession.isGameActive()) {
            gameActive = true;
        }
        if (VoteLaunchSession.isLaunchConfirmed() && !launchConfirmed) {
            confirmLaunch(VoteLaunchSession.getWinningMapId());
        }
    }

    /**
     * 服务端确认 SRE 与 API 的对局环境均已应用（判定点 B）。
     */
    public void confirmLaunch(String confirmedMapId) {
        if (confirmedMapId != null && !confirmedMapId.isBlank()) {
            winningMapId = confirmedMapId;
            mapId = confirmedMapId;
        }
        if (!launchConfirmed) {
            launchConfirmed = true;
            launchConfirmedAtMillis = Util.getMillis();
        }
    }

    /** 服务端对局进入 ACTIVE（OnGameStartedClient）后由接收器调用，交还画面。 */
    public void markGameActive() {
        gameActive = true;
    }

    /** 服务端确认开局中止（如参与人数不足）后由接收器调用：立即交还画面，不再等待 ACTIVE。 */
    public void markGameAborted() {
        if (!completed) {
            completeTransition();
        }
    }

    @Override
    public void tick() {
        pullFromSession();
        long now = Util.getMillis();

        // 加载期最短停留
        if (!launchConfirmed && now - startedAtMillis < MIN_LOADING_MILLIS) {
            return;
        }
        if (!launchConfirmed) {
            // 本屏在判定点 A 才打开，之后的传送 + 角色分配（trueStartGame → ACTIVE）可能要 30 多秒，
            // 兜底必须同样宽松，否则会在玩家还没进入对局时提前交还画面。
            if (now - startedAtMillis >= GAME_ACTIVE_FALLBACK_MILLIS) {
                completeTransition();
            }
            return;
        }
        if (!exitStarted) {
            long minExitAt = stampAtMillis() + CONTENT_SWITCH_MILLIS + TITLE_HOLD_MILLIS;
            if (now >= minExitAt && (gameActive
                    || now - launchConfirmedAtMillis >= GAME_ACTIVE_FALLBACK_MILLIS)) {
                startExit(now);
            }
        } else if (exitProgress() >= 1.0f) {
            completeTransition();
        }
    }

    private void startExit(long now) {
        if (exitStarted) return;
        exitStarted = true;
        exitStartAtMillis = now;
    }

    // ==================== 时间轴 ====================

    /** 飘落编排的本地时钟（毫秒）；保险开屏跳过入场时视为早已落定。 */
    private float enterClock(long now) {
        if (skipEnterAnimation) {
            return ENTER_MILLIS + 10_000.0f;
        }
        return now - startedAtMillis;
    }

    private long heroLandedAtMillis() {
        if (skipEnterAnimation) {
            return startedAtMillis - 10_000L;
        }
        return startedAtMillis + HERO_DELAY + HERO_FLIGHT;
    }

    /** 「对局开始」章落下的时刻：登车凭证落定且开局已确认之后。 */
    private long stampAtMillis() {
        return Math.max(launchConfirmedAtMillis, heroLandedAtMillis()) + 90L;
    }

    private float exitProgress() {
        if (!exitStarted) return 0.0f;
        return Mth.clamp((Util.getMillis() - exitStartAtMillis) / (float) EXIT_MILLIS, 0.0f, 1.0f);
    }

    // ==================== 车票编排 ====================

    /**
     * 生成票堆：3~4 列 × 4 行的网格（带抖动与小角度）保证铺满，再加几张大角度的散票打破规整，
     * 最后是正中的登车凭证。种子固定，同一屏尺寸下编排稳定。
     */
    private void layoutTickets() {
        pile.clear();
        Rng rng = new Rng(0x5EED_1234L + width * 31L + height * 17L);
        int cols = width > height * 2.1f ? 4 : 3;
        int rows = 4;
        float cellW = width / (float) cols;
        float cellH = height / (float) rows;
        float th = cellH * 1.75f;
        float tw = Math.max(cellW * 1.45f, th * 1.9f);
        List<String> names = pileNames();
        List<FlyTicket> grid = new ArrayList<>();
        int nameIndex = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                float tx = (col + 0.5f) * cellW + rng.range(-0.08f, 0.08f) * cellW;
                float ty = (row + 0.5f) * cellH + rng.range(-0.1f, 0.1f) * cellH;
                float rot = rng.range(-9.0f, 9.0f);
                grid.add(flyTicket(rng, tx, ty, rot, tw * rng.range(0.96f, 1.04f), th, names, nameIndex++));
            }
        }
        // 洗牌：飘落顺序随机，但外圈略早，整体由外向内铺满
        for (int i = grid.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            FlyTicket swap = grid.get(i);
            grid.set(i, grid.get(j));
            grid.set(j, swap);
        }
        pile.addAll(grid);
        for (int i = 0; i < 4; i++) {
            float tx = width * rng.range(0.12f, 0.88f);
            float ty = height * (i < 2 ? rng.range(0.1f, 0.35f) : rng.range(0.65f, 0.9f));
            pile.add(flyTicket(rng, tx, ty, rng.range(14.0f, 26.0f) * (rng.nextBoolean() ? 1 : -1),
                    tw * 0.9f, th * 0.9f, names, nameIndex++));
        }
        long delay = 0L;
        for (int i = 0; i < pile.size(); i++) {
            FlyTicket t = pile.get(i);
            pile.set(i, t.withTiming(delay, Math.round(rng.range(640.0f, 780.0f))));
            delay += TICKET_STAGGER;
        }
        // 退场：离中心越远越早被吹走
        float cx = width / 2.0f;
        float cy = height / 2.0f;
        float maxDist = Mth.sqrt(cx * cx + cy * cy);
        for (int i = 0; i < pile.size(); i++) {
            FlyTicket t = pile.get(i);
            float dist = Mth.sqrt((t.tx - cx) * (t.tx - cx) + (t.ty - cy) * (t.ty - cy));
            long exitDelay = Math.round((1.0f - Math.min(1.0f, dist / maxDist)) * 320.0f + rng.range(0.0f, 60.0f));
            pile.set(i, t.withExitDelay(exitDelay));
        }

        float heroW = Math.min(Math.min(width * 0.74f, 470.0f), height * 0.8f * 2.05f);
        float heroH = heroW / 2.05f;
        heroShape = TicketArt.Shape.of(heroW, heroH, 0.78f);
        float diag = Mth.sqrt(width * width + height * height);
        hero = new FlyTicket(width / 2.0f, height / 2.0f + 2.0f, 0.0f, heroW, heroH, TicketArt.IVORY,
                -width * 0.25f, height + heroH, -24.0f, width * 0.18f, height * 0.62f,
                1.1f, 0.6f, 7, "", HERO_DELAY, HERO_FLIGHT, 0.0f, -1.0f, 0.0f, 0L);
        hero = hero.withStartDistance(diag);
    }

    private FlyTicket flyTicket(Rng rng, float tx, float ty, float rot, float w, float h, List<String> names, int index) {
        float cx = width / 2.0f;
        float cy = height / 2.0f;
        float dx = tx - cx;
        float dy = ty - cy;
        float len = Mth.sqrt(dx * dx + dy * dy);
        if (len < 1.0f) {
            dx = rng.range(-1.0f, 1.0f);
            dy = -1.0f;
            len = Mth.sqrt(dx * dx + dy * dy);
        }
        dx /= len;
        dy /= len;
        // 向外方向随机偏转 ±40°，再略偏向上方：像被风从高处吹落
        float ang = (float) Math.atan2(dy, dx) + rng.range(-0.7f, 0.7f);
        float ox = Mth.cos(ang);
        float oy = Mth.sin(ang) - 0.45f;
        float ol = Mth.sqrt(ox * ox + oy * oy);
        ox /= ol;
        oy /= ol;
        float diag = Mth.sqrt(width * width + height * height);
        float distance = diag * rng.range(0.62f, 0.82f) + Math.max(w, h) * 0.5f;
        float sx = tx + ox * distance;
        float sy = ty + oy * distance;
        // 控制点：中点向一侧偏移，使轨迹成弧线
        float side = rng.nextBoolean() ? 1.0f : -1.0f;
        float ctrlX = (sx + tx) / 2.0f - oy * distance * 0.24f * side;
        float ctrlY = (sy + ty) / 2.0f + ox * distance * 0.24f * side;
        float startRot = rot + rng.range(28.0f, 70.0f) * (rng.nextBoolean() ? 1 : -1);
        TicketArt.Palette palette = TicketArt.PILE[rng.nextInt(TicketArt.PILE.length)];
        String name = names.isEmpty() ? "" : names.get(index % names.size());
        // 退场方向：沿中心向外，带些随机
        float exAng = (float) Math.atan2(ty - cy, tx - cx) + rng.range(-0.5f, 0.5f);
        return new FlyTicket(tx, ty, rot, w, h, palette, sx, sy, startRot, ctrlX, ctrlY,
                rng.range(1.2f, 2.2f), rng.range(0.0f, Mth.TWO_PI), rng.nextInt(10_000), name,
                0L, 700L, Mth.cos(exAng), Mth.sin(exAng), rng.range(30.0f, 90.0f) * (rng.nextBoolean() ? 1 : -1), 0L);
    }

    /** 票堆上印的站名：落选的候选地图；没有候选时用线路名。 */
    private List<String> pileNames() {
        List<String> names = new ArrayList<>();
        for (OptionVotePayload.Entry entry : OptionVoteState.getCandidates()) {
            if (!entry.optionId().equals(winningMapId)) {
                names.add(OptionVoteTexts.candidateLabel(entry.optionId(), entry.displayName()).getString());
            }
        }
        if (names.isEmpty()) {
            names.add(winnerLabel().getString());
        }
        return names;
    }

    // ==================== 绘制 ====================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (hero == null) {
            layoutTickets();
        }
        advanceDisplayedProgress();
        long now = Util.getMillis();
        float clock = enterClock(now);
        float exitClock = exitStarted ? now - exitStartAtMillis : -1.0f;
        playSounds(clock, now, exitClock);

        // 背景压暗：随车票铺开由透明变为不透明；退场时随车票散去而消退
        float cover = TransitionFx.easeInOutCubic(clock / 1300.0f);
        if (exitClock >= 0.0f) {
            cover *= 1.0f - TransitionFx.easeInOutCubic((exitClock - 60.0f) / 760.0f);
        }

        if (coveredScreen != null && !exitStarted && cover < 0.999f) {
            coveredScreen.render(g, mouseX, mouseY, partialTick);
        }
        g.flush();

        // 登车凭证落定时的一下轻震
        float landAge = now - heroLandedAtMillis();
        float shake = landAge >= 0.0f && landAge < 200.0f ? (1.0f - landAge / 200.0f) * 1.6f : 0.0f;
        g.pose().pushPose();
        g.pose().translate(shake * Mth.sin(landAge * 0.11f), shake * Mth.cos(landAge * 0.13f) * 0.6f, LAYER_Z);

        renderBackdrop(g, cover);
        for (int i = 0; i < pile.size(); i++) {
            g.pose().pushPose();
            g.pose().translate(0.0f, 0.0f, i * 2.0f);
            renderPileTicket(g, pile.get(i), clock, exitClock);
            g.pose().popPose();
        }

        // 车票铺满后压暗四周，把视线收向登车凭证
        float focus = TransitionFx.easeOutCubic((clock - HERO_DELAY - HERO_FLIGHT * 0.6f) / 600.0f);
        if (exitClock >= 0.0f) {
            focus *= 1.0f - TransitionFx.clamp01(exitClock / 400.0f);
        }
        if (focus > 0.01f) {
            GuiGeo dim = GuiGeo.begin(g);
            dim.rect(0, 0, width, height, RailArt.a(VOID, 0.42f * focus));
            dim.end();
            GuiGeo glow = GuiGeo.glow(g);
            glow.softGlow(hero.tx, hero.ty, hero.w * 0.85f, hero.h * 1.1f, GOLD_DARK, Math.round(60 * focus));
            glow.end();
        }

        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, pile.size() * 2.0f + 6.0f);
        renderHero(g, clock, exitClock, now);
        g.pose().popPose();
        g.pose().popPose();

    }

    /** 每三张飘落的车票响一声纸声；登车凭证落定、盖章与撕票各一声。 */
    private void playSounds(float clock, long now, float exitClock) {
        if (pileSounded.length != pile.size()) {
            pileSounded = new boolean[pile.size()];
            soundsPrimed = false;
        }
        boolean stampDue = launchConfirmed && now >= stampAtMillis();
        if (!soundsPrimed) {
            soundsPrimed = true;
            for (int i = 0; i < pile.size(); i++) {
                pileSounded[i] = clock > pile.get(i).delay + pile.get(i).duration + 150.0f;
            }
            heroSounded = clock > HERO_DELAY + HERO_FLIGHT + 150.0f;
            stampSounded = stampDue && now > stampAtMillis() + 150L;
        }
        for (int i = 0; i < pile.size(); i++) {
            FlyTicket t = pile.get(i);
            if (!pileSounded[i] && clock >= t.delay + t.duration * 0.9f) {
                pileSounded[i] = true;
                if (i % 3 == 0) {
                    TicketSounds.flutter(0.85f + TicketArt.hash(t.seed) * 0.5f);
                }
            }
        }
        if (!heroSounded && clock >= HERO_DELAY + HERO_FLIGHT) {
            heroSounded = true;
            TicketSounds.land(1.05f);
        }
        if (!stampSounded && stampDue) {
            stampSounded = true;
            TicketSounds.stamp();
        }
        if (!tearSounded && exitClock >= 400.0f) {
            tearSounded = true;
            TicketSounds.tear(0.6f);
        }
    }

    private void renderBackdrop(GuiGraphics g, float cover) {
        if (cover <= 0.005f) {
            return;
        }
        GuiGeo geo = GuiGeo.begin(g);
        geo.rect(0, 0, width, height, RailArt.a(VOID, cover));
        geo.end();
        GuiGeo warm = GuiGeo.glow(g);
        warm.softGlow(width / 2.0f, height / 2.0f, width * 0.7f, height * 0.6f, 0xFF2A3A6E, Math.round(70 * cover));
        warm.end();
    }

    /** 一张飘落中的票堆车票：贝塞尔弧线 + 打旋 + 纸片翻飘（横纵向轻微压扁）。 */
    private void renderPileTicket(GuiGraphics g, FlyTicket t, float clock, float exitClock) {
        float p = TransitionFx.clamp01((clock - t.delay) / t.duration);
        if (p <= 0.0f) {
            return;
        }
        float e = TransitionFx.easeOutCubic(p);
        float air = 1.0f - e;
        float x = bezier(t.sx, t.ctrlX, t.tx, e);
        float y = bezier(t.sy, t.ctrlY, t.ty, e);
        float rot = Mth.lerp(e, t.startRot, t.rot) + Mth.sin(p * 9.0f * t.flutter + t.phase) * 14.0f * air * air;
        float flipX = 1.0f - 0.55f * air * Math.abs(Mth.sin(p * Mth.PI * 1.7f * t.flutter + t.phase));
        float flipY = 1.0f - 0.22f * air * Math.abs(Mth.cos(p * Mth.PI * 1.3f + t.phase * 0.7f));
        float lift = air;
        if (exitClock >= 0.0f) {
            float q = TransitionFx.clamp01((exitClock - t.exitDelay) / 620.0f);
            if (q >= 1.0f) {
                return;
            }
            float qe = TransitionFx.easeInCubic(q);
            float reach = Mth.sqrt(width * width + height * height) * 0.95f + t.w;
            x += t.exDirX * reach * qe;
            y += t.exDirY * reach * qe - 30.0f * Mth.sin(q * Mth.PI);
            rot += t.exSpin * qe;
            flipX *= 1.0f - 0.35f * Math.abs(Mth.sin(q * Mth.PI * 2.2f + t.phase));
            lift = Math.max(lift, qe);
        }
        float scale = 1.0f + 0.12f * lift;
        TicketArt.Shape shape = TicketArt.Shape.of(t.w, t.h, 0.76f);
        drawShadow(g, shape, x, y, rot, scale * flipX, scale * flipY, lift);
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(rot));
        g.pose().scale(scale * flipX, scale * flipY, 1.0f);
        g.pose().translate(-t.w / 2.0f, -t.h / 2.0f, 0.0f);
        TicketArt.paper(g, shape, t.palette, TicketArt.Part.WHOLE, 1.0f);
        TicketArt.details(g, shape, t.palette, TicketArt.Part.WHOLE, 1.0f, t.seed, true);
        renderPileFace(g, shape, t);
        g.pose().popPose();
    }

    private void drawShadow(GuiGraphics g, TicketArt.Shape shape, float x, float y, float rot,
                            float sx, float sy, float lift) {
        drawShadow(g, shape, TicketArt.Part.WHOLE, x, y, rot, sx, sy, lift);
    }

    private void drawShadow(GuiGraphics g, TicketArt.Shape shape, TicketArt.Part part, float x, float y, float rot,
                            float sx, float sy, float lift) {
        g.pose().pushPose();
        g.pose().translate(x + 2.0f + 16.0f * lift, y + 3.0f + 22.0f * lift, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(rot));
        g.pose().scale(sx, sy, 1.0f);
        g.pose().translate(-shape.w() / 2.0f, -shape.h() / 2.0f, 0.0f);
        TicketArt.shadow(g, shape, part, 2.5f + 9.0f * lift, 0.62f - 0.32f * lift);
        g.pose().popPose();
    }

    /** 票堆车票的票面：线路名、终点站（落选地图）、座位与编号、竖排「ADMIT ONE」与条码。 */
    private void renderPileFace(GuiGraphics g, TicketArt.Shape s, FlyTicket t) {
        TicketArt.Palette p = t.palette;
        float b = s.band();
        float in = s.inset();
        float emblemR = b * 0.26f;
        RailArt.drawWingedWheel(g, in + 4.0f + emblemR * 3.1f, b / 2.0f, emblemR, 0.0f, 1.0f, 1.0f);
        float bandScale = TicketArt.crisp(Math.max(0.5f, b / 24.0f));
        float textX = in + 8.0f + emblemR * 6.2f;
        TicketArt.drawScaled(g, font, Component.translatable("vote.habitrain_core.transition.line"),
                textX, (b - 8.0f * bandScale) / 2.0f + 0.5f, bandScale, p.bandInk());
        TicketArt.drawRight(g, font, Component.literal(String.format(Locale.ROOT, "No.%05d", t.seed)),
                s.bodyRight() - 3.0f, (b - 8.0f * bandScale) / 2.0f + 0.5f, bandScale, fadeTo(p.bandInk(), 0.75f));

        float bodyTop = b + 4.0f;
        float bodyH = s.h() - b - in;
        float label = TicketArt.crisp(Math.max(0.5f, s.h() / 200.0f));
        TicketArt.drawScaled(g, font, Component.translatable("vote.habitrain_core.transition.destination_label"),
                in + 8.0f, bodyTop + bodyH * 0.08f, label, p.inkSoft());
        float nameScale = TicketArt.crisp(Mth.clamp(s.h() / 70.0f, 1.0f, 3.0f));
        Component name = TicketArt.fit(font, Component.literal(t.name).withStyle(ChatFormatting.BOLD),
                (s.bodyRight() - in - 16.0f) / nameScale);
        TicketArt.drawScaled(g, font, name, in + 8.0f, bodyTop + bodyH * 0.08f + 10.0f * label + 2.0f, nameScale, p.ink());
        Component seat = Component.translatable("vote.habitrain_core.transition.seat",
                1 + t.seed % 9, 1 + (t.seed / 9) % 24, (char) ('A' + (t.seed / 7) % 4));
        TicketArt.drawScaled(g, font, seat, in + 8.0f, s.h() - in - 6.0f - 8.0f * label, label, p.inkSoft());

        // 票根
        float stubL = s.stubLeft();
        float stubR = s.w() - in - 2.0f;
        float stubCx = (stubL + stubR) / 2.0f;
        TicketArt.drawCentered(g, font, Component.literal(String.format(Locale.ROOT, "%06d", t.seed * 37 % 1_000_000)),
                stubCx, b + 4.0f, label, p.serial());
        float admit = TicketArt.crispAscii(Math.max(0.5f, s.h() / 200.0f));
        g.pose().pushPose();
        g.pose().translate(stubCx - 4.0f * admit, b + 6.0f + 10.0f * label, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(90.0f));
        TicketArt.drawTrackedLeft(g, font, "ADMIT ONE", 0.0f, -8.0f * admit, admit, fadeTo(p.ink(), 0.8f));
        g.pose().popPose();
        GuiGeo code = GuiGeo.begin(g);
        float codeH = Math.max(5.0f, s.h() * 0.16f);
        TicketArt.barcode(code, stubL + 1.0f, s.h() - in - 3.0f - codeH, stubR - stubL - 2.0f, codeH, t.seed,
                fadeTo(p.ink(), 0.85f));
        code.end();
    }

    // ==================== 登车凭证 ====================

    /** 正中的登车凭证：飘落 → 落定扫光 → 盖章 → 撕开飞走。 */
    private void renderHero(GuiGraphics g, float clock, float exitClock, long now) {
        FlyTicket t = hero;
        TicketArt.Shape s = heroShape;
        float p = TransitionFx.clamp01((clock - t.delay) / t.duration);
        if (p <= 0.0f) {
            return;
        }
        float e = TransitionFx.easeOutCubic(p);
        float air = 1.0f - e;
        float x = bezier(t.sx, t.ctrlX, t.tx, e);
        float y = bezier(t.sy, t.ctrlY, t.ty, e);
        float rot = Mth.lerp(e, t.startRot, t.rot) + Mth.sin(p * 7.0f + t.phase) * 9.0f * air * air;
        float flipX = 1.0f - 0.4f * air * Math.abs(Mth.sin(p * Mth.PI * 1.5f + t.phase));
        float scale = 1.0f + 0.16f * air;
        if (p >= 1.0f) {
            x = TicketArt.snap(x - s.w() / 2.0f) + s.w() / 2.0f;
            y = TicketArt.snap(y - s.h() / 2.0f) + s.h() / 2.0f;
        }

        // 撕票退场：先轻轻一拽，再沿撕票线分开——票根坠落、票身飞走
        float bodyDx = 0.0f;
        float bodyDy = 0.0f;
        float bodyRot = 0.0f;
        float bodyLift = 0.0f;
        float stubDx = 0.0f;
        float stubDy = 0.0f;
        float stubRot = 0.0f;
        boolean torn = false;
        if (exitClock >= 0.0f) {
            float tug = TransitionFx.clamp01((exitClock - 260.0f) / 140.0f);
            stubDx = 2.0f * Mth.sin(tug * Mth.PI);
            stubRot = 2.5f * Mth.sin(tug * Mth.PI);
            if (exitClock > 400.0f) {
                torn = true;
                float fall = TransitionFx.clamp01((exitClock - 400.0f) / 760.0f);
                stubDx = 46.0f * fall;
                stubDy = (height * 0.85f + s.h()) * fall * fall;
                stubRot = 32.0f * TransitionFx.easeInCubic(fall);
                float fly = TransitionFx.clamp01((exitClock - 470.0f) / 760.0f);
                float fe = TransitionFx.easeInCubic(fly);
                bodyDx = -width * 0.32f * fe;
                bodyDy = -(height * 0.75f + s.h()) * fe;
                bodyRot = -12.0f * fe;
                bodyLift = fe;
            }
        }

        if (!torn) {
            drawShadow(g, s, x, y, rot, scale * flipX, scale, Math.max(air, bodyLift));
            g.pose().pushPose();
            g.pose().translate(x, y, 0.0f);
            g.pose().mulPose(Axis.ZP.rotationDegrees(rot));
            g.pose().scale(scale * flipX, scale, 1.0f);
            g.pose().translate(-s.w() / 2.0f, -s.h() / 2.0f, 0.0f);
            if (stubDx != 0.0f) {
                renderHeroPart(g, s, TicketArt.Part.BODY, now, clock);
                g.pose().pushPose();
                g.pose().translate(s.stubX() + stubDx, s.h(), 0.0f);
                g.pose().mulPose(Axis.ZP.rotationDegrees(stubRot));
                g.pose().translate(-s.stubX(), -s.h(), 0.0f);
                renderHeroPart(g, s, TicketArt.Part.STUB, now, clock);
                g.pose().popPose();
            } else {
                renderHeroPart(g, s, TicketArt.Part.WHOLE, now, clock);
            }
            g.pose().popPose();
            return;
        }

        // 票根（先画，被票身压在下面）
        float stubCx = x - s.w() / 2.0f + (s.stubX() + s.w()) / 2.0f + stubDx;
        float stubCy = y + stubDy;
        float stubLift = TransitionFx.clamp01(stubDy / (height * 0.3f));
        g.pose().pushPose();
        g.pose().translate(stubCx + 2.0f + 10.0f * stubLift, stubCy + 3.0f + 14.0f * stubLift, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(stubRot));
        g.pose().translate(-(s.stubX() + s.w()) / 2.0f, -s.h() / 2.0f, 0.0f);
        TicketArt.shadow(g, s, TicketArt.Part.STUB, 2.5f + 6.0f * stubLift, 0.55f - 0.25f * stubLift);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(stubCx, stubCy, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(stubRot));
        g.pose().translate(-(s.stubX() + s.w()) / 2.0f, -s.h() / 2.0f, 0.0f);
        renderHeroPart(g, s, TicketArt.Part.STUB, now, clock);
        g.pose().popPose();

        float bodyScale = 1.0f + 0.08f * bodyLift;
        drawShadow(g, s, TicketArt.Part.BODY, x + bodyDx, y + bodyDy, bodyRot, bodyScale, bodyScale, bodyLift);
        g.pose().pushPose();
        g.pose().translate(x + bodyDx, y + bodyDy, 2.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees(bodyRot));
        g.pose().scale(bodyScale, bodyScale, 1.0f);
        g.pose().translate(-s.w() / 2.0f, -s.h() / 2.0f, 0.0f);
        renderHeroPart(g, s, TicketArt.Part.BODY, now, clock);
        g.pose().popPose();
    }

    /** 登车凭证的一部分（票纸 + 票面内容），在票面局部坐标系中绘制。 */
    private void renderHeroPart(GuiGraphics g, TicketArt.Shape s, TicketArt.Part part, long now, float clock) {
        TicketArt.Palette p = TicketArt.IVORY;
        TicketArt.paper(g, s, p, part, 1.0f);
        TicketArt.details(g, s, p, part, 1.0f, 4242, true);
        if (part != TicketArt.Part.STUB) {
            renderHeroBody(g, s, p, now);
            // 落定后一道金色光泽扫过票面
            float sheen = (clock - HERO_DELAY - HERO_FLIGHT - 60.0f) / 760.0f;
            TicketArt.sheen(g, s, sheen, 1.0f);
        }
        if (part != TicketArt.Part.BODY) {
            renderHeroStub(g, s, p, now);
        }
    }

    private void renderHeroBody(GuiGraphics g, TicketArt.Shape s, TicketArt.Palette p, long now) {
        float b = s.band();
        float in = s.inset();
        float left = in + 9.0f;
        float right = s.bodyRight() - 7.0f;
        float span = right - left;
        float top = b;
        float area = s.h() - in - b;
        float unit = Mth.clamp(s.h() / 160.0f, 1.0f, 1.6f);
        float small = TicketArt.crisp(0.5f * unit);

        // 票头：路徽 + 线路名 ／ 登车凭证
        float emblemR = b * 0.27f;
        float emblemX = in + 5.0f + emblemR * 3.1f;
        RailArt.drawWingedWheel(g, emblemX, b / 2.0f, emblemR, 0.0f, 1.0f, 1.0f);
        float bandText = TicketArt.crisp(0.75f * unit);
        float bandY = TicketArt.snap((b - 8.0f * bandText) / 2.0f + 0.5f);
        Component line = Component.translatable("vote.habitrain_core.transition.line").withStyle(ChatFormatting.BOLD);
        float lineX = emblemX + emblemR * 3.3f + 3.0f;
        Component pass = Component.translatable("vote.habitrain_core.transition.boarding_pass");
        String lineSub = OptionVoteTexts.subtitle("vote.habitrain_core.transition.line");
        String passSub = OptionVoteTexts.subtitle("vote.habitrain_core.transition.boarding_pass");
        // 票头两组双语字样放不下时，先省略左侧英文，再省略右侧英文
        float lineW = font.width(line) * bandText;
        float passW = font.width(pass) * bandText;
        float subDesired = 0.5f * unit;
        if (lineX + lineW + 5.0f + TicketArt.subWidth(font, lineSub, subDesired) + 10.0f
                > right - passW - 5.0f - TicketArt.subWidth(font, passSub, subDesired)) {
            lineSub = "";
        }
        if (lineX + lineW + 10.0f > right - passW - 5.0f - TicketArt.subWidth(font, passSub, subDesired)) {
            passSub = "";
        }
        TicketArt.drawScaled(g, font, line, lineX, bandY, bandText, p.bandInk());
        TicketArt.drawSub(g, font, lineSub, lineX + lineW + 5.0f, bandY, bandText, subDesired, fadeTo(p.bandInk(), 0.7f));
        float passSubW = passSub.isEmpty() ? 0.0f : TicketArt.subWidth(font, passSub, subDesired) + 5.0f;
        TicketArt.drawSub(g, font, passSub, right - passSubW + 5.0f, bandY, bandText, subDesired, fadeTo(p.bandInk(), 0.7f));
        TicketArt.drawRight(g, font, pass, right - passSubW, bandY, bandText, p.bandInk());

        // 终点站
        float labelY = TicketArt.snap(top + area * 0.07f);
        drawLabel(g, "vote.habitrain_core.transition.destination_label", left, labelY, 0.5f * unit, p, span);
        float nameScale = TicketArt.crisp(2.0f * unit);
        Component winner = winnerLabel().copy().withStyle(ChatFormatting.BOLD);
        while (nameScale > TicketArt.crisp(1.0f) && font.width(winner) * nameScale > span * 0.92f) {
            nameScale = TicketArt.crisp(nameScale - 0.5f);
        }
        Component fittedName = TicketArt.fit(font, winner, span / nameScale);
        float nameY = TicketArt.snap(labelY + 8.0f * small + 3.0f);
        TicketArt.drawScaled(g, font, fittedName, left, nameY, nameScale, p.ink());
        float ruleY = TicketArt.snap(nameY + 8.0f * nameScale + 4.0f);
        GuiGeo rule = GuiGeo.begin(g);
        rule.rectH(left, ruleY, left + span * 0.62f, ruleY + 0.8f, RailArt.a(p.trim(), 1.0f), RailArt.a(p.trim(), 0));
        rule.end();

        // 信息栏：模式 / 人数 / 杀手
        float fieldY = TicketArt.snap(ruleY + 5.0f);
        String[] keys = {
                "vote.habitrain_core.transition.mode",
                "vote.habitrain_core.transition.players",
                "vote.habitrain_core.transition.killers"};
        Component[] values = {
                modeLabel(),
                Component.literal(playerCount > 0 ? String.valueOf(playerCount) : "—"),
                Component.literal(playerCount > 0 ? String.valueOf(killerCount) : "—")};
        float[] widths = {0.5f, 0.25f, 0.25f};
        float valueScale = TicketArt.crisp(1.0f * unit);
        float colX = left;
        GuiGeo sep = GuiGeo.begin(g);
        for (int i = 1; i < 3; i++) {
            float sx = left + span * (i == 1 ? widths[0] : widths[0] + widths[1]) - 4.0f;
            sep.rect(sx, fieldY, sx + 0.7f, fieldY + 8.0f * small + 3.0f + 8.0f * valueScale, RailArt.a(p.trim(), 0.8f));
        }
        sep.end();
        for (int i = 0; i < 3; i++) {
            float colW = span * widths[i] - 10.0f;
            drawLabel(g, keys[i], colX, fieldY, 0.5f * unit, p, colW);
            Component value = TicketArt.fit(font, values[i].copy().withStyle(ChatFormatting.BOLD), colW / valueScale);
            TicketArt.drawScaled(g, font, value, colX, TicketArt.snap(fieldY + 8.0f * small + 3.0f), valueScale, p.ink());
            colX += span * widths[i];
        }

        // 线路进度：五站，小火车沿线驶向终点
        boolean stamped = launchConfirmed && now >= stampAtMillis();
        float shown = stamped ? 1.0f : Math.max(0.0f, displayedProgress) / 100.0f;
        float railY = TicketArt.snap(top + area * 0.80f);
        float statusY = TicketArt.snap(railY - 12.0f - 8.0f * small);
        String statusKey = stamped ? "vote.habitrain_core.transition.boarding" : "vote.habitrain_core.transition.loading";
        drawLabel(g, statusKey, left, statusY, 0.5f * unit, p, span * 0.75f);
        Component percent = Component.literal(Math.round(shown * 100.0f) + "%").withStyle(ChatFormatting.BOLD);
        TicketArt.drawRight(g, font, percent, right, statusY - 1.0f, TicketArt.crisp(0.75f * unit), p.serial());
        float railL = left + 2.0f;
        float railR = right - 2.0f;
        float headX = railL + (railR - railL) * shown;
        GuiGeo route = GuiGeo.begin(g);
        for (float sx = railL; sx < railR; sx += 4.0f) {
            route.rect(sx, railY - 0.8f, sx + 1.2f, railY + 1.8f, TicketArt.fade(p.inkSoft(), 0.45f));
        }
        route.rect(railL, railY, railR, railY + 0.9f, TicketArt.fade(p.inkSoft(), 0.8f));
        if (headX > railL + 0.5f) {
            route.rect(railL, railY - 0.2f, headX, railY + 1.1f, p.trim());
        }
        for (int i = 0; i <= 4; i++) {
            float frac = i / 4.0f;
            float sx = railL + (railR - railL) * frac;
            boolean reached = shown >= frac - 0.001f;
            route.disc(sx, railY + 0.4f, 3.0f, p.paperTop());
            route.ring(sx, railY + 0.4f, 2.0f, 3.0f, reached ? p.trim() : TicketArt.fade(p.inkSoft(), 0.8f));
            if (reached) {
                route.disc(sx, railY + 0.4f, 1.2f, p.serial());
            }
        }
        route.end();
        float time = (now - startedAtMillis) / 1000.0f;
        RailArt.drawTrain(g, Math.max(railL + 20.0f, headX), railY, 0.62f * unit, time, 1.0f, time * 9.0f);

        // 底部小字：加载期是票面说明，开局确认后换成等待提示
        float fineY = TicketArt.snap(s.h() - in - 4.0f - 8.0f * small);
        float cruise = 0.0f;
        if (stamped) {
            cruise = TransitionFx.easeOutCubic((now - stampAtMillis() - CONTENT_SWITCH_MILLIS - TITLE_HOLD_MILLIS)
                    / (float) CRUISE_TRANSITION_MILLIS);
        }
        if (cruise > 0.02f && !exitStarted) {
            Component wait = waitStatusLine(now - launchConfirmedAtMillis);
            TicketArt.drawScaled(g, font, TicketArt.fit(font, wait, span / small), left, fineY, small,
                    fadeTo(p.ink(), cruise));
        } else {
            Component fine = Component.translatable("vote.habitrain_core.transition.fine_print");
            TicketArt.drawScaled(g, font, TicketArt.fit(font, fine, span / small), left, fineY, small,
                    fadeTo(p.inkSoft(), 1.0f - cruise));
        }

        // 「对局开始」章
        if (launchConfirmed) {
            float slam = TransitionFx.clamp01((now - stampAtMillis()) / (float) STAMP_SLAM_MILLIS);
            if (slam > 0.0f) {
                float sc = Mth.lerp(slam * slam, 2.3f, 1.0f);
                // 盖在终点站名右侧的空白处，尽量不压住人数/杀手等数值
                float stampX = left + span * 0.73f;
                float stampY = top + area * 0.2f;
                TicketArt.stamp(g, font, stampX, stampY,
                        Component.translatable("vote.habitrain_core.transition.go_title"),
                        OptionVoteTexts.subtitle("vote.habitrain_core.transition.go_title"),
                        TicketArt.crisp(1.25f * unit), sc, -9.0f, p.serial(), Math.min(1.0f, slam * 1.4f), p.paperTop(), 77);
                float splash = (now - stampAtMillis() - STAMP_SLAM_MILLIS) / 420.0f;
                TicketArt.inkSplash(g, stampX, stampY, s.h() * 0.32f, p.serial(), splash, 91);
            }
        }
    }

    private void renderHeroStub(GuiGraphics g, TicketArt.Shape s, TicketArt.Palette p, long now) {
        float b = s.band();
        float in = s.inset();
        float left = s.stubLeft() + 2.0f;
        float right = s.w() - in - 3.0f;
        float cx = (left + right) / 2.0f;
        float unit = Mth.clamp(s.h() / 160.0f, 1.0f, 1.6f);
        float small = TicketArt.crisp(0.5f * unit);
        float bandText = TicketArt.crisp(0.75f * unit);
        TicketArt.drawCentered(g, font, Component.translatable("vote.habitrain_core.transition.gate"),
                cx, TicketArt.snap((b - 8.0f * bandText) / 2.0f + 0.5f), bandText, p.bandInk());

        float area = s.h() - in - b;
        float y = TicketArt.snap(b + area * 0.08f);
        TicketArt.drawCentered(g, font, Component.literal("No."), cx, y, small, p.inkSoft());
        float serialScale = TicketArt.crisp(0.75f * unit);
        TicketArt.drawCentered(g, font, Component.literal(String.format(Locale.ROOT, "%06d", serial)).withStyle(ChatFormatting.BOLD),
                cx, TicketArt.snap(y + 8.0f * small + 2.0f), serialScale, p.serial());
        float classY = TicketArt.snap(b + area * 0.36f);
        drawLabelCentered(g, "vote.habitrain_core.transition.class", cx, classY, small, p);
        TicketArt.drawCentered(g, font, Component.translatable("vote.habitrain_core.transition.class_first").withStyle(ChatFormatting.BOLD),
                cx, TicketArt.snap(classY + 8.0f * small + 3.0f), TicketArt.crisp(1.0f * unit), p.ink());

        GuiGeo code = GuiGeo.begin(g);
        float codeH = Math.max(8.0f, area * 0.2f);
        TicketArt.barcode(code, left, s.h() - in - 4.0f - codeH, right - left, codeH, serial, p.ink());
        // 盖章同时在票根打孔
        if (launchConfirmed && now >= stampAtMillis() + 40L) {
            float holeIn = TransitionFx.easeOutCubic((now - stampAtMillis() - 40L) / 120.0f);
            TicketArt.punchHole(code, cx, b + area * 0.62f, 3.4f * unit * holeIn,
                    RailArt.a(VOID, 0.92f), TicketArt.fade(p.edge(), 1.0f));
        }
        code.end();
    }

    /** 中文小标签 + 宽字距英文副标签；放不下时只留中文。desired 为期望缩放。 */
    private void drawLabel(GuiGraphics g, String key, float x, float y, float desired, TicketArt.Palette p, float maxWidth) {
        Component label = Component.translatable(key);
        float scale = TicketArt.crisp(desired);
        TicketArt.drawScaled(g, font, label, x, y, scale, p.inkSoft());
        String sub = OptionVoteTexts.subtitle(key);
        float labelW = font.width(label) * scale;
        if (!sub.isBlank() && labelW + 4.0f + TicketArt.subWidth(font, sub, desired) <= maxWidth) {
            TicketArt.drawSub(g, font, sub, x + labelW + 4.0f, y, scale, desired, fadeTo(p.inkSoft(), 0.7f));
        }
    }

    private void drawLabelCentered(GuiGraphics g, String key, float cx, float y, float scale, TicketArt.Palette p) {
        TicketArt.drawCentered(g, font, Component.translatable(key), cx, y, scale, p.inkSoft());
    }

    /** 「正在准备对局…」+ 已等待秒数，合并为一行，保持画面干净。 */
    private Component waitStatusLine(long elapsed) {
        long waitedSecs = Math.max(0, elapsed / 1000L);
        Component awaiting = Component.translatable("vote.habitrain_core.transition.awaiting");
        if (waitedSecs <= 0) {
            return awaiting;
        }
        return awaiting.copy().append(Component.literal(" · ").append(
                Component.translatable("vote.habitrain_core.transition.awaiting_elapsed", waitedSecs)));
    }

    /** 每帧把显示进度指数逼近服务端进度。 */
    private void advanceDisplayedProgress() {
        long now = Util.getMillis();
        float seconds = lastFrameMillis <= 0L ? 0.0f
                : Mth.clamp((now - lastFrameMillis) / 1000.0f, 0.0f, 0.1f);
        lastFrameMillis = now;
        if (displayedProgress < 0.0f || progress < displayedProgress) {
            displayedProgress = progress;
            return;
        }
        displayedProgress += (progress - displayedProgress)
                * (1.0f - (float) Math.exp(-seconds * 6.0f));
    }

    private Component modeLabel() {
        if (modeId == null || modeId.isBlank()) {
            return Component.literal("—");
        }
        String key = OptionVoteTexts.optionLangKey(modeId);
        if (net.minecraft.locale.Language.getInstance().has(key)) {
            return Component.translatable(key);
        }
        return Component.literal(shortOptionId(modeId));
    }

    private Component winnerLabel() {
        for (var entry : OptionVoteState.getCandidates()) {
            if (winningMapId.equals(entry.optionId())) {
                return OptionVoteTexts.candidateLabel(entry.optionId(), entry.displayName());
            }
        }
        return Component.literal(shortOptionId(winningMapId));
    }

    private void completeTransition() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == this && !completed) {
            completed = true;
            VoteLaunchSession.clear();
            // 交还后仍需屏蔽 SRE 相机 intro（ACTIVE 后立刻触发，默认 100 tick=5s），故用较长宽限期。
            VoteLaunchOverlayState.scheduleGrace(CAMERA_BLOCK_GRACE_MILLIS);
            // 相机需要覆盖完整 intro；场景音只需等待客户端传送位置/区块判定稳定。
            VoteLaunchOverlayState.scheduleAmbientSoundGrace(AMBIENT_SOUND_GRACE_MILLIS);
            mc.setScreen(destination);
        }
    }

    // 转场期间吞掉全部输入：本屏是服务端开局同步点前的遮罩，不能被 ESC/点击绕过。

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 工具 ====================

    private static float bezier(float a, float control, float b, float t) {
        float u = 1.0f - t;
        return u * u * a + 2.0f * u * t * control + t * t * b;
    }

    private static int fadeTo(int color, float factor) {
        return TicketArt.fade(color, factor);
    }

    private static String shortOptionId(String optionId) {
        if (optionId == null) return "";
        int split = optionId.lastIndexOf(':');
        return split >= 0 && split + 1 < optionId.length()
                ? optionId.substring(split + 1) : optionId;
    }

    private static Screen unwrapVoteScreen(Screen screen) {
        Screen destination = screen;
        while (destination instanceof OptionVoteScreen voteScreen) {
            destination = voteScreen.getParentScreen();
        }
        return destination;
    }

    /**
     * 一张飞行车票的编排参数。
     *
     * @param tx,ty,rot      落点中心与角度
     * @param sx,sy,startRot 起点（屏幕外）与起始角度
     * @param ctrlX,ctrlY    二次贝塞尔控制点
     * @param flutter,phase  翻飘频率与相位
     * @param delay,duration 飘落起始与时长（毫秒，可见路径时间轴）
     * @param exDirX,exDirY,exSpin,exitDelay 退场方向、旋转与延迟
     */
    private record FlyTicket(float tx, float ty, float rot, float w, float h, TicketArt.Palette palette,
                             float sx, float sy, float startRot, float ctrlX, float ctrlY,
                             float flutter, float phase, int seed, String name, long delay, long duration,
                             float exDirX, float exDirY, float exSpin, long exitDelay) {
        FlyTicket withTiming(long newDelay, long newDuration) {
            return new FlyTicket(tx, ty, rot, w, h, palette, sx, sy, startRot, ctrlX, ctrlY, flutter, phase, seed,
                    name, newDelay, newDuration, exDirX, exDirY, exSpin, exitDelay);
        }

        FlyTicket withExitDelay(long newExitDelay) {
            return new FlyTicket(tx, ty, rot, w, h, palette, sx, sy, startRot, ctrlX, ctrlY, flutter, phase, seed,
                    name, delay, duration, exDirX, exDirY, exSpin, newExitDelay);
        }

        /** 登车凭证：起点按屏幕对角线推远，保证从屏幕外飘入。 */
        FlyTicket withStartDistance(float diag) {
            float dx = sx - tx;
            float dy = sy - ty;
            float len = Math.max(1.0f, Mth.sqrt(dx * dx + dy * dy));
            float k = Math.max(1.0f, (diag * 0.75f + w * 0.5f) / len);
            return new FlyTicket(tx, ty, rot, w, h, palette, tx + dx * k, ty + dy * k, startRot, ctrlX, ctrlY,
                    flutter, phase, seed, name, delay, duration, exDirX, exDirY, exSpin, exitDelay);
        }
    }

    /** 固定种子的轻量随机数（编排可复现，不依赖全局 Random）。 */
    private static final class Rng {
        private long state;

        Rng(long seed) {
            this.state = seed ^ 0x9E3779B97F4A7C15L;
        }

        private int next(int bits) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return (int) (state >>> (64 - bits));
        }

        float nextFloat() {
            return next(24) / (float) (1 << 24);
        }

        float range(float min, float max) {
            return min + (max - min) * nextFloat();
        }

        int nextInt(int bound) {
            return Math.floorMod(next(31), Math.max(1, bound));
        }

        boolean nextBoolean() {
            return next(1) != 0;
        }
    }
}
