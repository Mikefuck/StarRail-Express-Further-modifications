package com.habitrain.core.client.gui;

import com.habitrain.core.client.BlackoutKeyHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * 投票结束后的开局转场：同一块全屏面板滑入、原地更新内容，最后继续向左滑出。
 *
 * <p>全程只有一个画面，由服务端权威信号 + 本地 {@link VoteLaunchSession} 驱动：</p>
 * <ol>
 *   <li><b>滑入并加载</b>：投票结束后，加载面板从右向左覆盖投票页，显示地图、模式和进度。
 *       此阶段玩家可 ESC / × / V 隐藏；隐藏后进度仍写入 Session，可用 V 重开（不清除 sticky 隐藏意图）。</li>
 *   <li><b>判定点 A</b>：地图重置完成（{@code MapVoteStartConfirmedPayload}）。
 *       若 sticky 隐藏意图 → 左→右补盖并直接显示「对局开始」（遮住随后 TP）；
 *       若未隐藏 → 锁定 hide，继续加载面板。</li>
 *   <li><b>判定点 B / 原地切换</b>：环境就绪（{@link #confirmLaunch}）后，可见路径在当前面板内
 *       让加载内容淡出并显示「对局开始」；补盖路径仅同步 mapId，不重播动画。</li>
 *   <li><b>交还</b>：SRE 进入 ACTIVE（{@link #markGameActive()}）后，开场画面整体继续向左
 *       滑出，游戏世界从画面右缘直接露出。</li>
 * </ol>
 *
 * <p>工厂契约：</p>
 * <ul>
 *   <li>{@link #reopenLoading} — V 偷看，仍是加载内容（skipEnter）</li>
 *   <li>{@link #openRecover} — 补盖：左→右 + 「对局开始」（绝非加载标题）</li>
 *   <li>{@link #openSafetyCover} — 可见路径保险：加载模式，仅当 session 已 launchConfirmed 时切标题</li>
 * </ul>
 *
 * <p>交还时 SRE 原版黑场与开场相机动画仍在宽限期内被屏蔽（见
 * {@code VoteLaunchFadeBlockMixin} / {@code VoteLaunchCameraBlockMixin}）。</p>
 */
public final class VoteLaunchTransitionScreen extends Screen {
    private static final long MIN_LOADING_MILLIS = 400L;
    /** 加载面板从右向左覆盖投票页的时长；补盖左→右复用同一时长。 */
    private static final long ENTER_MILLIS = 900L;
    /** 同一面板内从加载内容切换到「对局开始」的时长（仅可见路径）。 */
    private static final long CONTENT_SWITCH_MILLIS = 900L;
    /** 标题时刻停留时长（毫秒），之后进入巡航氛围。 */
    private static final long TITLE_HOLD_MILLIS = 1_500L;
    /** 开场画面左向滑出、露出游戏世界的时长（毫秒）。 */
    private static final long EXIT_MILLIS = 900L;
    /** 巡航氛围过渡时长（毫秒）：标题柔和降亮、等待提示淡入。 */
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
    /** 确认前（进度包缺失）的兜底：避免加载视图永久停留。 */
    private static final long LAUNCH_CONFIRM_FALLBACK_MILLIS = 30_000L;
    /** 交还后仍屏蔽 SRE 相机 intro 的宽限期（毫秒）。相机 intro 默认 100 tick=5s，多留余量。 */
    private static final long CAMERA_BLOCK_GRACE_MILLIS = 7_000L;
    /**
     * 交还世界后继续阻止场景环境音启动的短宽限期。
     *
     * <p>开局时冒险模式、场景设置与传送位置不是一个原子客户端状态；给位置/区块判定
     * 额外半秒稳定下来，可避免旧大厅位置短暂满足“对局中且露天”而误启外部列车声。</p>
     */
    private static final long AMBIENT_SOUND_GRACE_MILLIS = 500L;
    /** 滑动边缘羽化宽度（像素）。 */
    private static final int FEATHER_WIDTH = 26;
    /** 投票页可能含有带自身深度的文字/控件；滑入面板必须作为独立前景层绘制。 */
    private static final float PANEL_FOREGROUND_Z = 500.0f;

    private static final int VOID = 0xFF08070A;
    private static final int INK = 0xFF1A1110;
    private static final int GOLD_DARK = 0xFF8E682E;
    private static final int GOLD = 0xFFD9AE59;
    private static final int GOLD_BRIGHT = 0xFFFFE1A0;
    private static final int IVORY = 0xFFF7EBCF;
    private static final int TEXT = 0xFFF4EFE6;
    private static final int TEXT_MUTED = 0xFFB8A995;

    /** 被加载面板覆盖的投票页，仅在滑入阶段作为背景渲染。 */
    private final Screen coveredScreen;
    /** 面板滑出后真正交还的页面（已剥离 OptionVoteScreen）。 */
    private final Screen destination;
    private String winningMapId;
    private final long startedAtMillis = Util.getMillis();

    // 服务端加载信息（由 Session / MapVoteProgressPayload 更新）
    private int progress = 0;
    private int playerCount = 0;
    private int killerCount = 0;
    private String mapId = "";
    private String modeId = "";

    // 阶段状态
    private boolean launchConfirmed;
    private long launchConfirmedAtMillis;
    private boolean exitStarted;
    private long exitStartAtMillis;
    private boolean completed;
    private boolean gameActive; // OnGameStartedClient 已触发

    /** 补盖路径：左→右入场中。 */
    private boolean recoverEnter;
    private long recoverEnterAtMillis;
    /** 构造时是否为补盖模式（左→右 +「对局开始」）；与瞬时 recoverEnter 区分。 */
    private final boolean recoverMode;
    /** hide 后再开：跳过完整右→左滑入，直接满屏加载。 */
    private final boolean skipEnterAnimation;

    private Rect closeBounds = new Rect(0, 0, 0, 0);

    public VoteLaunchTransitionScreen(Screen destination, String winningMapId) {
        this(destination, winningMapId, false, false);
    }

    /**
     * @param skipEnter  true=已看过入场，再开加载页时直接满屏
     * @param recover    true=判定点 A 补盖：左→右 + 「对局开始」
     */
    public VoteLaunchTransitionScreen(Screen destination, String winningMapId,
                                      boolean skipEnter, boolean recover) {
        super(OptionVoteTexts.transitionTitle());
        this.coveredScreen = destination;
        this.destination = unwrapVoteScreen(destination);
        this.winningMapId = winningMapId == null ? "" : winningMapId;
        this.mapId = this.winningMapId;
        this.skipEnterAnimation = skipEnter;
        this.recoverMode = recover;
        this.recoverEnter = recover;
        if (recover) {
            this.recoverEnterAtMillis = Util.getMillis();
            this.launchConfirmed = true;
            this.launchConfirmedAtMillis = Util.getMillis();
        }
        VoteLaunchOverlayState.setActive(true);
        pullFromSession();
    }

    /** 从 Session 重开加载页（玩家 V 键）。仍是加载内容，不清除 sticky 隐藏意图。 */
    public static VoteLaunchTransitionScreen reopenLoading(Screen parent) {
        return new VoteLaunchTransitionScreen(
                parent,
                VoteLaunchSession.getWinningMapId(),
                VoteLaunchSession.isEnterCompletedOnce(),
                false);
    }

    /**
     * 判定点 A/B 补盖开屏：左→右 + 「对局开始」。
     * 绝不能用于可见路径保险——那会错误显示加载标题。
     */
    public static VoteLaunchTransitionScreen openRecover(Screen parent) {
        return new VoteLaunchTransitionScreen(
                parent,
                VoteLaunchSession.getWinningMapId(),
                true,
                true);
    }

    /**
     * 判定点 A/B 可见路径屏丢失时的保险全屏盖住（加载模式）。
     * 若 session 已 launchConfirmed（含 B 迟到），再切到标题。
     */
    public static VoteLaunchTransitionScreen openSafetyCover(Screen parent) {
        VoteLaunchTransitionScreen screen = new VoteLaunchTransitionScreen(
                parent,
                VoteLaunchSession.getWinningMapId(),
                true,
                false);
        if (VoteLaunchSession.isLaunchConfirmed()) {
            screen.confirmLaunch(VoteLaunchSession.getWinningMapId());
        }
        return screen;
    }

    /** 本屏是否以补盖（「对局开始」）呈现，而非加载内容。 */
    public boolean isRecoverPresentation() {
        return recoverMode || (VoteLaunchSession.isRecoverPath() && launchConfirmed);
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
        closeBounds = new Rect(Math.max(0, width - 21), 7, 13, 13);
    }

    /** 滑出/隐藏后交还的目标屏（已剥离 OptionVoteScreen）。 */
    public Screen getDestinationOrNull() {
        return destination;
    }

    /** 服务端加载进度更新（MapVoteProgressPayload / Session）。 */
    public void updateProgress(int prog, int players, int killers, String map, String mode) {
        this.progress = Mth.clamp(prog, 0, 100);
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
     * 补盖路径已在判定点 A 显示标题，此处只同步 mapId。
     */
    public void confirmLaunch(String confirmedMapId) {
        if (confirmedMapId != null && !confirmedMapId.isBlank()) {
            winningMapId = confirmedMapId;
            mapId = confirmedMapId;
        }
        // 补盖路径：已在 A 点 launchConfirmed，不重置计时、不重播 content switch
        if (VoteLaunchSession.isRecoverPath() || recoverEnter) {
            if (!launchConfirmed) {
                launchConfirmed = true;
                launchConfirmedAtMillis = Util.getMillis();
            }
            return;
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

    /**
     * 玩家主动隐藏加载页（仅 {@link VoteLaunchSession#canHide()} 时有效）。
     * 与完整交还区分：不调度相机 grace，Session 保留进度。
     */
    public void hideByUser() {
        if (!VoteLaunchSession.canHide()) return;
        VoteLaunchSession.markHiddenByUser();
        VoteLaunchSession.markEnterCompletedOnce();
        VoteLaunchOverlayState.deactivateForUserHide();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == this) {
            mc.setScreen(destination);
        }
    }

    @Override
    public void tick() {
        pullFromSession();
        long now = Util.getMillis();

        // 补盖入场完成后标记 enter 完成
        if (recoverEnter && recoverEnterProgress() >= 1.0f) {
            recoverEnter = false;
            VoteLaunchSession.markEnterCompletedOnce();
        }

        // 加载期最短停留（补盖路径跳过：已在 launchConfirmed）
        if (!launchConfirmed && now - startedAtMillis < MIN_LOADING_MILLIS) {
            return;
        }
        if (!launchConfirmed) {
            if (now - startedAtMillis >= LAUNCH_CONFIRM_FALLBACK_MILLIS) {
                completeTransition();
            }
            return;
        }
        if (!exitStarted) {
            long minExitAt;
            if (VoteLaunchSession.isRecoverPath()) {
                // 补盖：入场结束 + 标题停留后，等 ACTIVE
                long recoverDoneAt = recoverEnterAtMillis + ENTER_MILLIS;
                minExitAt = Math.max(launchConfirmedAtMillis, recoverDoneAt) + TITLE_HOLD_MILLIS;
            } else {
                // 可见路径：内容切换 + 标题时刻
                minExitAt = launchConfirmedAtMillis + CONTENT_SWITCH_MILLIS + TITLE_HOLD_MILLIS;
            }
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (exitStarted) {
            renderExit(g, mouseX, mouseY, partialTick);
            return;
        }
        // 补盖路径：左→右入场，完成后满屏「对局开始」
        if (recoverEnter) {
            renderRecoverEntry(g, mouseX, mouseY, partialTick);
            return;
        }
        if (VoteLaunchSession.isRecoverPath() && launchConfirmed) {
            renderPanelBackground(g);
            renderLaunchContent(g, 1.0f, !gameActive);
            return;
        }
        // 可见路径：加载 → 原地切标题
        if (!launchConfirmed) {
            renderLoadingEntry(g, mouseX, mouseY, partialTick);
            if (VoteLaunchSession.canHide()) {
                renderHideChrome(g, mouseX, mouseY);
            }
            return;
        }
        renderLaunch(g, mouseX, mouseY, partialTick);
    }

    // ==================== 阶段一：同一块加载面板滑入覆盖投票页 ====================

    private void renderLoadingEntry(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float enter = skipEnterAnimation ? 1.0f : enterProgress();
        float local = easeInOutCubic(enter);
        float edgeX = width * (1.0f - local);

        if (coveredScreen != null && edgeX > 0.5f) {
            coveredScreen.render(g, mouseX, mouseY, partialTick);
        }

        // 先提交投票页的文字/控件批次，再在更高的固定深度画整块不透明面板。
        // 仅依赖调用顺序会让字体 RenderType 的深度与背景 fill 竞争，出现文字残留穿透。
        g.flush();
        g.pose().pushPose();
        g.pose().translate(edgeX, 0.0f, PANEL_FOREGROUND_Z);
        renderPanelBackground(g);
        renderLoadingContent(g, 1.0f);
        g.pose().popPose();

        if (enter < 1.0f) {
            g.pose().pushPose();
            g.pose().translate(0.0f, 0.0f, PANEL_FOREGROUND_Z + 1.0f);
            renderSlideEdge(g, edgeX, local, +1);
            g.pose().popPose();
        } else {
            VoteLaunchSession.markEnterCompletedOnce();
        }
        g.flush();
    }

    /**
     * 补盖入场：面板从左向右覆盖世界，内容直接是「对局开始」。
     * 镜像 {@link #renderLoadingEntry} 的几何。
     */
    private void renderRecoverEntry(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float enter = recoverEnterProgress();
        float local = easeInOutCubic(enter);
        // 面板右缘从 0 → width
        float edgeX = width * local;

        // 世界在边缘右侧露出（无 coveredScreen 时保持透明，让世界底色透出）
        g.flush();
        g.pose().pushPose();
        // 面板整体：左缘 = edgeX - width，右缘 = edgeX
        g.pose().translate(edgeX - width, 0.0f, PANEL_FOREGROUND_Z);
        renderPanelBackground(g);
        renderLaunchContent(g, 1.0f, !gameActive);
        g.pose().popPose();

        if (enter < 1.0f) {
            g.pose().pushPose();
            g.pose().translate(0.0f, 0.0f, PANEL_FOREGROUND_Z + 1.0f);
            // 羽化落在面板一侧（边缘左侧）
            renderSlideEdge(g, edgeX, local, -1);
            g.pose().popPose();
        }
        g.flush();
    }

    /**
     * 加载内容层（无边框简洁视图）。背景由同一块面板统一绘制；确认后只淡出内容，
     * 不会再创建或滑入第二个页面。
     */
    private void renderLoadingContent(GuiGraphics g, float alphaMul) {
        if (alphaMul <= 0.01f) return;

        int cx = width / 2;
        int cy = height / 2 - 12;
        // 徽章呼吸
        float breathe = 0.5f + 0.5f * Mth.sin((Util.getMillis() % 2_600L) / 2_600f * (float) Math.PI * 2.0f);
        drawEmblem(g, cx, cy - 66, Math.round((140 + 90 * breathe) * alphaMul), breathe);

        Component title = Component.translatable("vote.habitrain_core.transition.loading")
                .copy().withStyle(ChatFormatting.BOLD);
        drawScaledCentered(g, title, cx, cy - 30, 1.7f, withAlpha(IVORY, Math.round(255 * alphaMul)));
        g.drawCenteredString(font, OptionVoteTexts.transitionPreparing(), cx, cy - 2,
                withAlpha(TEXT_MUTED, Math.round(255 * alphaMul)));

        Component info = Component.literal(playerCount + " 人 · 杀手 " + killerCount + " · "
                + shortOptionId(modeId) + " · " + shortOptionId(mapId));
        g.drawCenteredString(font, info, cx, cy + 20, withAlpha(TEXT, Math.round(255 * alphaMul)));

        // 细进度线（无边框）+ 前缘柔光
        int barW = Math.min(320, Math.max(140, width - 200));
        int barY = cy + 42;
        g.fill(cx - barW / 2, barY, cx + barW / 2, barY + 2,
                withAlpha(GOLD_DARK, Math.round(100 * alphaMul)));
        int fillW = Math.round(barW * progress / 100.0f);
        if (fillW > 0) {
            g.fillGradient(cx - barW / 2, barY, cx - barW / 2 + fillW, barY + 2,
                    withAlpha(GOLD, Math.round(255 * alphaMul)),
                    withAlpha(GOLD_BRIGHT, Math.round(255 * alphaMul)));
            int headX = Math.min(width - 1, cx - barW / 2 + fillW);
            for (int i = 1; i <= 5; i++) {
                g.fill(headX + i - 1, barY - 1, Math.min(width, headX + i), barY + 3,
                        withAlpha(GOLD, Math.round(80 * (1.0f - i / 5.0f) * alphaMul)));
            }
        }
        g.drawCenteredString(font,
                Component.literal(progress + "%").withStyle(ChatFormatting.BOLD),
                cx, barY + 8, withAlpha(GOLD, Math.round(255 * alphaMul)));
    }

    /** 加载期右上角隐藏提示 + ×。 */
    private void renderHideChrome(GuiGraphics g, int mouseX, int mouseY) {
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, PANEL_FOREGROUND_Z + 2.0f);
        Component hideTip = OptionVoteTexts.hideHintWithBoundKey();
        int hideTipRight = closeBounds.x() - 7;
        int hideTipX = hideTipRight - font.width(hideTip);
        g.drawString(font, hideTip, Math.max(8, hideTipX), 10, TEXT_MUTED, false);
        boolean hover = closeBounds.contains(mouseX, mouseY);
        int cx = closeBounds.x();
        int cy = closeBounds.y();
        int s = closeBounds.w();
        g.fill(cx, cy, cx + s, cy + s, withAlpha(INK, hover ? 220 : 160));
        int pad = 3;
        g.fill(cx + pad, cy + pad, cx + s - pad, cy + s - pad, withAlpha(GOLD_DARK, hover ? 200 : 120));
        // 简易 ×
        int x0 = cx + 3;
        int y0 = cy + 3;
        int x1 = cx + s - 3;
        int y1 = cy + s - 3;
        for (int i = 0; i < s - 6; i++) {
            g.fill(x0 + i, y0 + i, x0 + i + 1, y0 + i + 1, withAlpha(IVORY, 230));
            g.fill(x1 - 1 - i, y0 + i, x1 - i, y0 + i + 1, withAlpha(IVORY, 230));
        }
        g.pose().popPose();
    }

    // ==================== 阶段二：同一面板内原地切换为「对局开始」 ====================

    private void renderLaunch(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float content = contentSwitchProgress();
        float loadingAlpha = 1.0f - easeOutCubic(Mth.clamp(content / 0.45f, 0.0f, 1.0f));

        renderPanelBackground(g);
        if (loadingAlpha > 0.01f) {
            renderLoadingContent(g, loadingAlpha);
        }
        renderLaunchContent(g, content, !gameActive);
    }

    /** 同一面板的动态背景，加载、开场标题和滑出阶段共用。 */
    private void renderPanelBackground(GuiGraphics g) {
        g.fillGradient(0, 0, width, height, withAlpha(0x110B07, 255), withAlpha(VOID, 255));
        renderFlowingLines(g);
        renderParticles(g);
    }

    /** 「对局开始」内容层；不再绘制或滑入第二个全屏页面。 */
    private void renderLaunchContent(GuiGraphics g, float content, boolean showWaitStatus) {
        long elapsed = Util.getMillis() - launchConfirmedAtMillis;
        // 巡航系数：标题时刻结束后，画面进入平静氛围（标题柔和降亮、等待提示淡入）
        float cruise = easeOutCubic(Mth.clamp(
                (elapsed - CONTENT_SWITCH_MILLIS - TITLE_HOLD_MILLIS) / (float) CRUISE_TRANSITION_MILLIS,
                0.0f, 1.0f));
        if (VoteLaunchSession.isRecoverPath()) {
            // 补盖无 content switch，巡航从 TITLE_HOLD 后起算
            cruise = easeOutCubic(Mth.clamp(
                    (elapsed - TITLE_HOLD_MILLIS) / (float) CRUISE_TRANSITION_MILLIS,
                    0.0f, 1.0f));
        }

        int cx = width / 2;
        int cy = height / 2 - 18;

        // 中部柔光：上下渐变的暖色晕染，为标题提供干净的视觉重心（巡航期减半）
        float glowScale = 1.0f - 0.5f * cruise;
        int glowW = Math.min(600, width - 40);
        int glowH = 230;
        int gy = cy - glowH / 2;
        g.fillGradient(cx - glowW / 2, gy, cx + glowW / 2, cy,
                withAlpha(GOLD_DARK, Math.round(30 * glowScale)),
                withAlpha(GOLD_DARK, Math.round(8 * glowScale)));
        g.fillGradient(cx - glowW / 2, cy, cx + glowW / 2, gy + glowH,
                withAlpha(GOLD_DARK, Math.round(8 * glowScale)),
                withAlpha(GOLD_DARK, 0));

        // 徽章与标题只做内容层级联入场，面板本身不再重复滑动。
        float emblemT = easeOutCubic(Mth.clamp((content - 0.30f) / 0.25f, 0.0f, 1.0f));
        if (emblemT > 0.0f) {
            drawEmblem(g, cx, cy - 98, Math.round(230 * emblemT), 1.0f);
        }

        // 大标题：弹入，巡航期柔和降亮
        float titleT = easeOutBack(Mth.clamp((content - 0.42f) / 0.40f, 0.0f, 1.0f));
        float titleAlpha = 255.0f * easeOutCubic(Mth.clamp((content - 0.38f) / 0.45f, 0.0f, 1.0f))
                * (1.0f - 0.45f * cruise);
        Component title = Component.translatable("vote.habitrain_core.transition.go_title")
                .copy().withStyle(ChatFormatting.BOLD);
        drawScaledCentered(g, title, cx, cy, 3.25f * (0.84f + 0.16f * titleT),
                withAlpha(GOLD_BRIGHT, Math.round(titleAlpha)));

        // 目的地 + 对局信息：紧随浮现
        float lineT = easeOutCubic(Mth.clamp((content - 0.56f) / 0.34f, 0.0f, 1.0f));
        float lineAlpha = 255.0f * lineT * (1.0f - 0.5f * cruise);
        if (lineAlpha > 0.5f) {
            Component dest = OptionVoteTexts.transitionDestination(winnerLabel());
            drawScaledCentered(g, dest, cx, cy + 44, 1.15f, withAlpha(GOLD, Math.round(lineAlpha)));
            Component info = Component.literal(playerCount + " 人 · 杀手 " + killerCount + " · "
                    + shortOptionId(modeId));
            g.drawCenteredString(font, info, cx, cy + 64,
                    withAlpha(TEXT, Math.round(lineAlpha * 0.85f)));
        }

        // 等待提示：巡航期淡入（秒数递增证明未卡死，服务端仍在推进）
        if (showWaitStatus && cruise > 0.01f) {
            g.drawCenteredString(font, waitStatusLine(elapsed), cx, cy + 104,
                    withAlpha(TEXT_MUTED, Math.round(175 * cruise)));
        }
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

    // ==================== 阶段三：左向滑出交还（露出游戏世界） ====================

    /** 开场画面整体向左滑出，游戏世界从画面右缘露出，流动线条保持运动连续。 */
    private void renderExit(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float exit = exitProgress();
        float local = easeInOutCubic(exit);
        float edgeX = width * (1.0f - local); // 开场画面右缘：width → 0

        g.pose().pushPose();
        g.pose().translate(-width * local, 0.0f, 0.0f);
        renderPanelBackground(g);
        renderLaunchContent(g, 1.0f, false);
        g.pose().popPose();

        if (exit < 1.0f) {
            renderSlideEdge(g, edgeX, local, -1);
        }
    }

    /** 流畅线条：上下多条细基准线 + 自右向左流动的光带 + 缓慢扫过的柔和光幕。 */
    private void renderFlowingLines(GuiGraphics g) {
        float time = Util.getMillis() / 1000.0f;
        int cy = height / 2 - 18;
        int[] rows = { cy - 166, cy - 136, cy - 106, cy + 98, cy + 132, cy + 166 };
        int[] speeds = { 96, 152, 112, 176, 130, 92 };

        for (int r = 0; r < rows.length; r++) {
            int y = rows[r];
            // 基准线随行缓慢明暗呼吸
            float breathe = 0.75f + 0.25f * Mth.sin(time * 0.7f + r * 2.1f);
            g.fill(0, y, width, y + 1, withAlpha(GOLD_DARK, Math.round(26 * breathe)));

            // 三条流动光带（右→左，方向与滑入一致）
            int streakW = 220 + r * 34;
            for (int s = 0; s < 3; s++) {
                float phase = time * speeds[r] + s * (streakW + 320) + r * 97.0f;
                int span = width + streakW;
                float center = width - Math.floorMod(Math.round(phase), span);
                renderStreak(g, center, y, streakW, breathe, (r + s) % 2 == 0);
            }
        }

        // 柔和光幕：整屏高度的一条金色柔光带，缓慢自右向左扫过（约 9s 一趟）
        int bandHalf = 190;
        int span = width + bandHalf * 2 + 60;
        float bandPhase = time * span / 9.0f;
        float bandCenter = (width + bandHalf + 30) - Math.floorMod(Math.round(bandPhase), span);
        renderLightBand(g, bandCenter);
    }

    /** 一条带渐入渐出光尾的光带。 */
    private void renderStreak(GuiGraphics g, float center, int y, int streakW,
                              float rowBreathe, boolean bright) {
        int half = streakW / 2;
        int steps = 12;
        int stepW = Math.max(1, half / steps);
        // 左尾（向核心渐亮）
        for (int i = 1; i <= steps; i++) {
            float frac = i / (float) steps;
            int alpha = Math.round((4 + 44 * frac * frac) * rowBreathe);
            int x0 = (int) center - half + (steps - i) * stepW;
            g.fill(x0, y, x0 + stepW, y + 1, withAlpha(bright ? GOLD_BRIGHT : GOLD, alpha));
        }
        // 核心亮带
        g.fill((int) center - 1, y, (int) center + 2, y + 1,
                withAlpha(GOLD_BRIGHT, Math.round(175 * rowBreathe)));
        // 右尾（向远端渐灭）
        for (int i = 0; i < steps; i++) {
            float frac = 1.0f - i / (float) steps;
            int alpha = Math.round((4 + 30 * frac * frac) * rowBreathe);
            int x0 = (int) center + 1 + i * stepW;
            g.fill(x0, y, x0 + stepW, y + 1, withAlpha(GOLD, alpha));
        }
    }

    /** 一条整屏高度的柔和光带（余弦衰减），缓慢自右向左扫过，增强前进感。 */
    private void renderLightBand(GuiGraphics g, float centerX) {
        int core = Mth.clamp(Math.round(centerX), 0, Math.max(0, width - 1));
        g.fill(core, 0, core + 1, height, withAlpha(GOLD_BRIGHT, 34));
        for (int i = 1; i <= 16; i++) {
            float frac = 1.0f - i / 16.0f;
            int alpha = Math.round(26 * frac * frac);
            int xr = Math.min(width - 1, core + i);
            int xl = Math.max(0, core - i);
            g.fill(xl, 0, xl + 1, height, withAlpha(GOLD, alpha));
            g.fill(xr, 0, xr + 1, height, withAlpha(GOLD, alpha));
        }
    }

    /** 星点尘埃：缓慢右→左漂移，明暗呼吸。 */
    private void renderParticles(GuiGraphics g) {
        float time = Util.getMillis() / 1000.0f;
        int span = width + 60;
        for (int i = 0; i < 12; i++) {
            float speed = 30 + (i % 5) * 9;
            float x = width - Math.floorMod(Math.round(time * speed + i * 149.0f), span);
            int y = 24 + Math.floorMod(i * 83 + 37, Math.max(1, height - 48));
            float pulse = 0.5f + 0.5f * Mth.sin(time * 2.3f + i * 1.9f);
            int size = 1 + (i % 3 == 0 ? 1 : 0);
            g.fill((int) x, y, (int) x + size, y + size,
                    withAlpha(i % 4 == 0 ? GOLD_BRIGHT : GOLD, Math.round(70 * pulse)));
        }
    }

    /**
     * 滑动边缘：柔和羽化 + 金色核心亮线。
     *
     * @param featherDir +1 表示开场画面在边缘右侧（滑入），-1 表示在左侧（滑出/补盖）；
     *                   羽化与辅线落在开场画面一侧，另一侧保持干净露出。
     */
    private void renderSlideEdge(GuiGraphics g, float edgeX, float localProgress, int featherDir) {
        if (edgeX < 0 || edgeX > width) return;
        float velocity = Mth.sin(Mth.clamp(localProgress, 0.0f, 1.0f) * Mth.PI);
        // 开场画面一侧的羽化过渡
        for (int i = 1; i <= FEATHER_WIDTH; i++) {
            int x = Mth.clamp(Math.round(edgeX) + featherDir * i, 0, width - 1);
            float strength = 1.0f - i / (float) FEATHER_WIDTH;
            g.fill(x, 0, x + 1, height,
                    withAlpha(INK, Math.round(150 * strength * (0.25f + 0.75f * velocity))));
        }
        // 核心亮线
        int coreX = Mth.clamp(Math.round(edgeX), 0, Math.max(0, width - 1));
        g.fill(coreX - 1, 0, Math.min(width, coreX + 1), height,
                withAlpha(GOLD_BRIGHT, Math.round(150 + 100 * velocity)));
        if (featherDir > 0) {
            g.fill(coreX - 3, 0, coreX - 1, height, withAlpha(GOLD, Math.round(70 * velocity)));
        } else {
            g.fill(coreX + 1, 0, Math.min(width, coreX + 3), height,
                    withAlpha(GOLD, Math.round(70 * velocity)));
        }
    }

    private void drawEmblem(GuiGraphics g, int cx, int cy, int alpha, float breathe) {
        if (alpha <= 0) return;
        // 外环
        int ringR = 24;
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2.0 / 40;
            int x = cx + (int) Math.round(Math.cos(a) * ringR);
            int y = cy + (int) Math.round(Math.sin(a) * ringR);
            g.fill(x, y, x + 1, y + 1, withAlpha(GOLD_DARK, Math.round(alpha * 0.55f)));
        }
        // 菱形层
        drawDiamond(g, cx, cy, 15, withAlpha(GOLD_DARK, alpha));
        drawDiamond(g, cx, cy, 11, withAlpha(GOLD, alpha));
        drawDiamond(g, cx, cy, 6, withAlpha(GOLD_BRIGHT, alpha));
        drawDiamond(g, cx, cy, 2, withAlpha(IVORY, Math.round(alpha * (0.6f + 0.4f * breathe))));
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && VoteLaunchSession.canHide() && closeBounds.contains(mouseX, mouseY)) {
            hideByUser();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ESCAPE
                || BlackoutKeyHandler.matchesOpenVoteKey(keyCode, scanCode))
                && VoteLaunchSession.canHide()) {
            hideByUser();
            return true;
        }
        // 锁定后 / 非 hide 键：吞掉输入，避免 ESC 绕过服务端开局同步点
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // ESC 走 hideByUser 或被吞掉
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 时间轴 ====================

    private float enterProgress() {
        return Mth.clamp((Util.getMillis() - startedAtMillis) / (float) ENTER_MILLIS,
                0.0f, 1.0f);
    }

    private float recoverEnterProgress() {
        if (recoverEnterAtMillis <= 0L) return 1.0f;
        return Mth.clamp((Util.getMillis() - recoverEnterAtMillis) / (float) ENTER_MILLIS,
                0.0f, 1.0f);
    }

    private float contentSwitchProgress() {
        if (!launchConfirmed) return 0.0f;
        if (VoteLaunchSession.isRecoverPath()) return 1.0f;
        return Mth.clamp((Util.getMillis() - launchConfirmedAtMillis) / (float) CONTENT_SWITCH_MILLIS,
                0.0f, 1.0f);
    }

    private float exitProgress() {
        if (!exitStarted) return 0.0f;
        return Mth.clamp((Util.getMillis() - exitStartAtMillis) / (float) EXIT_MILLIS, 0.0f, 1.0f);
    }

    // ==================== 绘制工具 ====================

    private void drawScaledCentered(GuiGraphics g, Component text, float x, float y,
                                    float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        g.drawCenteredString(font, text, 0, 0, color);
        g.pose().popPose();
    }

    private static void drawDiamond(GuiGraphics g, int centerX, int centerY,
                                    int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int halfWidth = radius - Math.abs(dy);
            g.fill(centerX - halfWidth, centerY + dy,
                    centerX + halfWidth + 1, centerY + dy + 1, color);
        }
    }

    private static float easeInOutCubic(float value) {
        float t = Mth.clamp(value, 0.0f, 1.0f);
        return t < 0.5f ? 4.0f * t * t * t
                : 1.0f - (float) Math.pow(-2.0f * t + 2.0f, 3.0) / 2.0f;
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0f - value;
        return 1.0f - inverse * inverse * inverse;
    }

    /** 带轻微回弹的缓出：用于标题弹入。 */
    private static float easeOutBack(float value) {
        float t = Mth.clamp(value, 0.0f, 1.0f);
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        return 1.0f + c3 * (float) Math.pow(t - 1.0f, 3.0)
                + c1 * (float) Math.pow(t - 1.0f, 2.0);
    }

    private static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
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

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + w && my < y + h;
        }
    }
}
