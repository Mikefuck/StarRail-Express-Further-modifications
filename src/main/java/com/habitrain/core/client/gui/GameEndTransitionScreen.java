package com.habitrain.core.client.gui;

import com.habitrain.core.client.mvp.MvpStillPose;
import com.habitrain.core.network.GameEndTransitionPayload;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * 对局结束转场：几张泛黄、撕缺、被火燎过的残票和一张纪念车票一起从屏幕上方左右摇摆着飘落；
 * 残票各自掉出屏幕底边（其中一张从镜头前擦过，见 {@link TicketScraps}），只有纪念车票越飘越近，
 * 最后底边先贴上屏幕、上半张随之铺平。
 *
 * <p>节奏刻意压得很慢、很静：落定后先是一阵寂静；随后胜方印章伴着一声心跳重重盖下，
 * 印泥顺着章框往下淌。若有 MVP，数据像打字机一样一行行敲出来；停顿片刻后，票面左下角的黑暗慢慢漫开，
 * 一个只剩轮廓光的人影从黑暗里一点点浮上来（{@link MvpStillPose} 固定姿势，头歪着盯住镜头）——
 * 浮到头时人影伴着心跳清清楚楚地显形，并缓缓向镜头靠近一点。
 * 交还时车票迅速做旧、泛黄起皱，随后从底边燃起，烧穿处露出大厅。退出时机见 {@link #tick()}。</p>
 *
 * <p>车票整张先画进离屏画布（{@link PaperCanvas}），再由 {@link PaperSheet} 以可弯曲的网格贴回屏幕；
 * 灯光、做旧、燃烧与人影浮现由 {@link TicketShaders} 的着色器逐像素完成，火星与纸灰见 {@link TicketFire}。
 * 只展示一名 MVP（得分最高者）。</p>
 */
public final class GameEndTransitionScreen extends Screen {
    private static final List<ResourceLocation> KILLER_KNIFE_IDS = List.of(
            ResourceLocation.fromNamespaceAndPath("trainmurdermystery", "knife"),
            ResourceLocation.fromNamespaceAndPath("starrailexpress", "knife"));
    private static final List<ResourceLocation> SHERIFF_REVOLVER_IDS = List.of(
            ResourceLocation.fromNamespaceAndPath("trainmurdermystery", "revolver"),
            ResourceLocation.fromNamespaceAndPath("starrailexpress", "revolver"));
    private static final List<ResourceLocation> NEUTRAL_CROWBAR_IDS = List.of(
            ResourceLocation.fromNamespaceAndPath("trainmurdermystery", "crowbar"),
            ResourceLocation.fromNamespaceAndPath("starrailexpress", "crowbar"));

    // ---- 时间轴（毫秒，相对打开时刻） ----
    /** 车票从屏幕上方摇摆着飘落、贴到屏幕上。 */
    private static final long FLIGHT_MILLIS = 2_600L;
    /** 贴上屏幕后纸面被压平前的余颤。 */
    private static final long SETTLE_MILLIS = 650L;
    /** 胜方印章盖下。 */
    private static final long STAMP_AT = FLIGHT_MILLIS + 1_150L;
    private static final long STAMP_SLAM_MILLIS = 160L;
    /** 印泥顺着章框往下淌。 */
    private static final long DRIP_AT = STAMP_AT + 260L;
    private static final long DRIP_MILLIS = 2_600L;
    /** 模式与票面其余文字。 */
    private static final long DETAILS_AT = STAMP_AT + 450L;
    /** MVP 数据开始打字的计划时刻（MVP 数据晚到则顺延）。 */
    private static final long MVP_DATA_AT = STAMP_AT + 900L;
    // ---- MVP 阶段（相对 MVP 数据开始打字） ----
    /** 打完字停顿片刻，左下角的黑暗开始漫开、人影开始浮现。 */
    private static final long FIGURE_AT = 2_300L;
    private static final long FIGURE_RISE_MILLIS = 2_200L;
    /** 人影浮到头时完全显形。 */
    private static final long REVEALED_AT = FIGURE_AT + FIGURE_RISE_MILLIS;
    /** 显形时人影平滑地靠近镜头、显影到位。 */
    private static final long REVEAL_EASE_MILLIS = 320L;
    private static final long FIGURE_HOLD_MILLIS = 2_400L;
    /** 没有 MVP 时，印章盖下后停留多久。 */
    private static final long NO_MVP_HOLD_MILLIS = 2_600L;
    /** 尚无 MVP 阶段时 mvpElapsed 的取值（远小于任何时刻，且相减不会溢出）。 */
    private static final long NO_MVP = Long.MIN_VALUE / 4L;
    // ---- 退场：做旧 → 燃烧（相对退场开始） ----
    private static final long AGE_MILLIS = 1_000L;
    private static final long BURN_AT = 800L;
    private static final long BURN_MILLIS = 2_600L;
    private static final long EXIT_MILLIS = BURN_AT + BURN_MILLIS + 600L;
    /** 退场最后这段时间里残余的火星与纸灰淡出。 */
    private static final long EMBER_FADE_MILLIS = 500L;
    /** 投票层/HUD 可能写入深度；转场整体抬到更高深度绘制。 */
    private static final float LAYER_Z = 400.0f;

    // ---- 配色 ----
    private static final int VOID = RailArt.NIGHT_0;
    private static final int GOLD_DARK = RailArt.BRASS_DIM;
    private static final int GOLD = RailArt.BRASS;
    private static final int GOLD_BRIGHT = RailArt.BRASS_LIGHT;
    private static final int IVORY = RailArt.CHAMPAGNE;
    /** 心跳时屏幕四周泛起的暗红。 */
    private static final int BLOOD = 0xFF7A0A10;

    private final long startedAtMillis = Util.getMillis();
    private final int serial = 100_000 + Math.floorMod((int) (Util.getMillis() / 1000L) * 7919, 900_000);
    private final float fireSeed = Math.floorMod(this.serial, 997) * 0.013f;
    private String winStatusName = "";
    private String modeId = "";
    private String customWinnerId = "";
    private int customWinnerColor = 0;
    private String customTitleJson = "";
    private List<GameEndTransitionPayload.MvpPlayer> mvpPlayers = List.of();
    private long mvpAvailableAtMillis;
    private final Map<UUID, AbstractClientPlayer> previewPlayers = new LinkedHashMap<>();
    private ClientLevel previewLevel;
    private final Map<Integer, ItemStack> victoryWeaponTemplates = new LinkedHashMap<>();

    // ---- 纸面 ----
    private final PaperCanvas ticketCanvas = new PaperCanvas();
    private final PaperCanvas ghostCanvas = new PaperCanvas();
    private final PaperSheet sheet = new PaperSheet();
    private final TicketFire fire = new TicketFire(this.fireSeed);
    private final TicketScraps scraps = new TicketScraps(this.fireSeed, this.serial);
    /** 车票在画布上的左上角（静止贴屏时即屏幕位置）。 */
    private float ticketX;
    private float ticketY;
    private boolean ghostReady;
    private long lastFrameMillis;

    /** 已经响过的音效节点。 */
    private final Set<String> cues = new HashSet<>();
    /** 本帧打字机已打出的字数，与上一次击键音时的字数。 */
    private int typedChars;
    private int typedHeard;
    private long lastTypeSoundMillis;
    private boolean environmentReady;
    private boolean exitStarted;
    private long exitStartAtMillis;
    private boolean completed;
    private boolean gameFinished;

    public GameEndTransitionScreen(GameEndTransitionPayload payload) {
        super(Component.translatable("gameend.habitrain_core.title"));
        this.applyPayload(payload);
    }

    @Override
    protected void init() {
        super.init();
        GameEndOverlayState.setActive(true);
        VoteLaunchOverlayState.scheduleGrace(0L);
    }

    private void applyPayload(GameEndTransitionPayload payload) {
        if (payload == null) {
            return;
        }
        if (payload.winStatusName() != null && !payload.winStatusName().isBlank()) {
            this.winStatusName = payload.winStatusName();
        }
        if (payload.modeId() != null && !payload.modeId().isBlank()) {
            this.modeId = payload.modeId();
        }
        if (payload.customWinnerId() != null && !payload.customWinnerId().isBlank()) {
            this.customWinnerId = payload.customWinnerId();
        }
        if (payload.customWinnerColor() != 0) {
            this.customWinnerColor = payload.customWinnerColor();
        }
        if (payload.customTitleJson() != null && !payload.customTitleJson().isBlank()) {
            this.customTitleJson = payload.customTitleJson();
        }
        if (payload.mvpPlayers() != null && !payload.mvpPlayers().isEmpty()) {
            if (this.mvpPlayers.isEmpty()) {
                this.mvpAvailableAtMillis = Util.getMillis();
            }
            this.mvpPlayers = List.copyOf(payload.mvpPlayers());
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
        this.applyPayload(payload);
    }

    @Override
    public void tick() {
        long now = Util.getMillis();
        if (!this.exitStarted) {
            long minExitAt = this.mvpPlayers.isEmpty()
                    ? this.startedAtMillis + STAMP_AT + NO_MVP_HOLD_MILLIS
                    : this.mvpStageStartMillis() + REVEALED_AT + FIGURE_HOLD_MILLIS;
            boolean normalRelease = this.environmentReady && this.gameFinished;
            boolean environmentFallback = this.gameFinished && now >= minExitAt + 3000L;
            boolean hardRelease = !this.environmentReady && now >= this.startedAtMillis + 30000L;
            if (now >= minExitAt && (normalRelease || environmentFallback) || hardRelease) {
                this.startExit(now);
            }
        } else if (this.exitProgress() >= 1.0f) {
            this.completeTransition();
        }
    }

    private void startExit(long now) {
        if (this.exitStarted) {
            return;
        }
        this.exitStarted = true;
        this.exitStartAtMillis = now;
    }

    // ==================== 绘制 ====================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        long elapsed = now - this.startedAtMillis;
        long mvpElapsed = this.mvpPlayers.isEmpty() ? NO_MVP : now - this.mvpStageStartMillis();
        float dt = this.lastFrameMillis == 0L ? 0.0f : Mth.clamp((now - this.lastFrameMillis) / 1000.0f, 0.0f, 0.05f);
        this.lastFrameMillis = now;
        long exitElapsed = this.exitStarted ? now - this.exitStartAtMillis : 0L;
        float age = this.exitStarted ? TransitionFx.easeInOutCubic(exitElapsed / (float) AGE_MILLIS) : 0.0f;
        float burn = this.exitStarted ? burnProgress(exitElapsed) : 0.0f;
        this.playSounds(elapsed, mvpElapsed, exitElapsed);
        float exposure = this.exposure(elapsed, mvpElapsed);

        float margin = Mth.clamp(Math.min(this.width, this.height) * 0.035f, 6.0f, 18.0f);
        float tw = this.width - margin * 2.0f;
        float th = this.height - margin * 2.0f;
        TicketArt.Shape shape = TicketArt.Shape.of(tw, th, 0.8f);
        this.ticketX = TicketArt.snap(margin);
        this.ticketY = TicketArt.snap(margin);

        // 1) 离屏：先画 MVP 人像，再画整张车票（人像合成进票面左下角）
        this.renderGhostCanvas(g, shape, mvpElapsed);
        this.typedChars = 0;
        this.ticketCanvas.begin(g);
        try {
            g.pose().pushPose();
            g.pose().translate(this.ticketX, this.ticketY, 0.0f);
            this.renderTicket(g, shape, elapsed, mvpElapsed);
            this.sealPaper(g, shape);
            g.pose().popPose();
        } finally {
            this.ticketCanvas.end(g);
        }
        this.playTyping(now);

        // 2) 背景：随车票飘近逐渐压暗；燃烧时随烧穿而消退，露出大厅
        float flight = TransitionFx.clamp01(elapsed / (float) FLIGHT_MILLIS);
        float cover = TransitionFx.easeInOutCubic(flight / 0.92f);
        if (this.exitStarted) {
            cover *= 1.0f - TransitionFx.easeInOutCubic((burn - 0.08f) / 0.72f);
        }
        g.pose().pushPose();
        g.pose().translate(0.0f, 0.0f, LAYER_Z);
        if (cover > 0.005f) {
            GuiGeo veil = GuiGeo.begin(g);
            veil.rect(0, 0, this.width, this.height, RailArt.a(VOID, cover * (0.94f + 0.06f * (1.0f - exposure))));
            veil.end();
            GuiGeo glow = GuiGeo.glow(g);
            glow.softGlow(this.width / 2.0f, this.height / 2.0f, this.width * 0.75f, this.height * 0.7f,
                    TransitionFx.mixRgb(0xFF1A1426, this.winColor(), 0.2f), Math.round(50 * cover * exposure));
            glow.end();
        }

        // 3) 纸面：飘落时摇摆、弯折、起波纹；做旧时起皱；燃烧处卷起。残票按远近夹在纪念车票前后
        float aspect = tw / th;
        PaperSheet.Pose pose = this.sheetPose(elapsed, mvpElapsed, age, this.ticketX + tw / 2.0f, this.ticketY + th / 2.0f);
        PaperSheet.Lift curl = burn > 0.0f ? (u, v) -> this.curl(u, v, aspect, burn) : null;
        this.sheet.layout(this.width, this.height, this.ticketX, this.ticketY, tw, th, pose, curl, this.serial);
        ShaderInstance shader = TicketShaders.paper();
        float seconds = (now % 600_000L) / 1000.0f;
        if (shader != null) {
            shader.safeGetUniform("TicketRect").set(PaperCanvas.u(this.ticketX), PaperCanvas.v(this.ticketY),
                    PaperCanvas.u(this.ticketX + tw), PaperCanvas.v(this.ticketY + th));
            shader.safeGetUniform("TicketSize").set(tw, th);
            shader.safeGetUniform("Time").set(seconds);
        }
        this.scraps.update(elapsed, this.width, this.height, th);
        this.scraps.draw(g, this.ticketCanvas.textureId(), shader, true, pose.depth(), cover,
                this.width, this.height, this.ticketX, this.ticketY, tw, th);
        float lift = (float) Math.pow(1.0f - flight, 0.8f);
        float shadowOffset = 4.0f + 30.0f * lift;
        // 投影只在背景压暗后才明显，免得在明亮的天空上拖出一块灰影
        float shadowAlpha = (0.55f * lift + 0.3f * (1.0f - lift)) * (0.2f + 0.8f * cover) * (1.0f - age);
        this.sheet.shadow(g, shadowOffset * 0.7f, shadowOffset, 0.01f + 0.05f * lift, shadowAlpha);
        if (shader != null) {
            float lampIn = TransitionFx.clamp01((elapsed - FLIGHT_MILLIS) / 700.0f) * (1.0f - 0.5f * age);
            shader.safeGetUniform("Age").set(age);
            shader.safeGetUniform("Burn").set(burn);
            shader.safeGetUniform("Front").set(TicketFire.front(burn));
            shader.safeGetUniform("Seed").set(this.fireSeed);
            // 纪念车票不撕角、火势全亮（残票会改这两项，这里每帧复位）
            shader.safeGetUniform("Tear").set(1.0f, 0.0f, 0.0f, 0.0f);
            shader.safeGetUniform("Smolder").set(1.0f);
            // 一盏吊灯挂在票面上方，随车厢缓缓晃动
            shader.safeGetUniform("Lamp").set(0.42f + 0.05f * Mth.sin(seconds * 0.8f), -0.15f, 1.1f, 0.6f * lampIn);
            shader.safeGetUniform("Exposure").set(exposure);
        }
        // 着色器不可用时退化为整体淡出
        float sheetAlpha = shader != null ? 1.0f : 1.0f - TransitionFx.easeInCubic(burn);
        this.sheet.draw(g, this.ticketCanvas.textureId(), shader, sheetAlpha);
        this.scraps.draw(g, this.ticketCanvas.textureId(), shader, false, pose.depth(), cover,
                this.width, this.height, this.ticketX, this.ticketY, tw, th);

        // 4) 心跳时屏幕四周泛起暗红
        this.renderPulse(g, this.pulse(elapsed, mvpElapsed) * (1.0f - age));

        // 5) 火光、纸灰与火星
        this.fire.update(dt, burn, this.exitStarted && shader != null, this.sheet, aspect, th / 400.0f);
        float embers = 1.0f - TransitionFx.clamp01((exitElapsed - (EXIT_MILLIS - EMBER_FADE_MILLIS)) / (float) EMBER_FADE_MILLIS);
        this.fire.render(g, Mth.sin(Mth.clamp(burn, 0.0f, 1.0f) * Mth.PI) * 0.6f + 0.4f, embers);
        g.pose().popPose();
    }

    /**
     * 纸面姿态：像落叶一样左右摆荡着下落、由远及近；滑翔时整张纸沿运动方向弯折，迎风边被压回、
     * 后缘甩起，摆到两端失速时拱起最明显，自由边上一直有细小的波纹。最后底边先贴上屏幕，
     * 上半张像被气垫托着一样一点点铺平，再轻颤两下压实。此后完全静止（与画布逐像素对齐），
     * 只在盖章与人影显形时随心跳震一下。做旧时整张起皱。
     */
    private PaperSheet.Pose sheetPose(long elapsed, long mvpElapsed, float age, float restX, float restY) {
        float crumple = 0.016f * age;
        if (elapsed >= FLIGHT_MILLIS + SETTLE_MILLIS) {
            PaperSheet.Pose rest = PaperSheet.Pose.rest(restX, restY).withCrumple(crumple);
            float shake = Math.max(shake(elapsed - STAMP_AT), shake(mvpElapsed - REVEALED_AT));
            if (shake <= 0.0f) {
                return rest;
            }
            int frame = (int) (elapsed / 30L);
            return rest.shifted(TicketArt.snap((TicketArt.hash(frame * 2 + 1) - 0.5f) * 2.0f * shake),
                    TicketArt.snap((TicketArt.hash(frame * 2 + 2) - 0.5f) * 2.0f * shake));
        }
        float w = this.width;
        float h = this.height;
        float t = TransitionFx.clamp01(elapsed / (float) FLIGHT_MILLIS);
        float rest = 1.0f - t;
        float s = 1.0f - (float) Math.pow(rest, 1.25f);
        float phase = -Mth.HALF_PI + Mth.PI * 2.3f * s;
        float sway = Mth.sin(phase);
        float swing = Mth.cos(phase);
        float cx = restX + w * 0.26f * (float) Math.pow(rest, 1.3f) * sway;
        float fall = 1.0f - rest * rest;
        float cy = Mth.lerp(fall, -h * 0.25f, restY) - h * 0.07f * rest * sway * sway;
        float depth = 1.0f + 2.4f * (float) Math.pow(rest, 1.5f);
        float roll = 0.38f * (float) Math.pow(rest, 1.2f) * swing;
        float yaw = -0.5f * (float) Math.pow(rest, 1.2f) * swing;
        // 远处时明显后仰（像从斜上方看一张往下飘的纸），弯折才看得出来
        float pitch = -(0.85f * (float) Math.pow(rest, 1.1f) + 0.25f * rest * Mth.sin(phase * 2.0f + 0.8f));
        float amp = (float) Math.pow(rest, 0.7f);
        float land = smoothstep(0.62f, 0.88f, t);
        float free = amp * (1.0f - land);
        // 滑翔时沿票宽弯折、方向随摆动微微转；落地时转为沿票高，从下往上铺平
        float bendAngle = Mth.lerp(land, 0.4f * Mth.sin(phase * 0.5f + 0.6f), -Mth.HALF_PI + 0.35f);
        float bend = free * (0.22f + 0.10f * Mth.sin(phase * 2.0f + 0.9f));
        float curlAmount = free * 0.22f * Mth.cos(phase - 0.55f);
        float ripple = 0.035f * (float) Math.sqrt(amp);
        float twist = free * 0.14f * Mth.sin(phase + 1.4f);
        float hinge = 0.45f * land;
        float hingeAt = Mth.lerp(smoothstep(0.72f, 1.0f, t), -1.0f, 1.0f);
        if (elapsed > FLIGHT_MILLIS) {
            float tau = (elapsed - FLIGHT_MILLIS) / 1000.0f;
            float decay = (float) Math.exp(-tau * 7.5f);
            bend += 0.06f * Mth.sin(tau * 24.0f) * decay;
            ripple += 0.02f * decay * (1.0f - (float) Math.exp(-tau * 30.0f));
        }
        return new PaperSheet.Pose(cx, cy, depth, roll, pitch, yaw, bendAngle, bend, curlAmount, ripple,
                elapsed * 0.011f, twist, hinge, hingeAt, crumple);
    }

    /** 心跳的一震：瞬间顶到 3.5 像素，随即衰减。 */
    private static float shake(long since) {
        if (since < 0L || since > 320L) {
            return 0.0f;
        }
        return 3.5f * (float) Math.exp(-since / 80.0f);
    }

    /** 燃烧前沿附近的纸受热卷起，越靠近火线翘得越高。 */
    private float curl(float u, float v, float aspect, float burn) {
        float d = this.fire.field(u, v, aspect) - TicketFire.front(burn);
        if (d >= 0.22f) {
            return 0.0f;
        }
        float k = 1.0f - Math.max(0.0f, d) / 0.22f;
        return 0.05f * k * k;
    }

    /** 起火后火势略微加快，整体接近匀速地烧过票面。 */
    private static float burnProgress(long exitElapsed) {
        float t = TransitionFx.clamp01((exitElapsed - BURN_AT) / (float) BURN_MILLIS);
        return t <= 0.0f ? 0.0f : (float) Math.pow(t, 1.15f);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = TransitionFx.clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0f - 2.0f * t);
    }

    // ==================== 灯光 / 心跳 ====================

    /**
     * 车厢灯的亮度：落定后一直有电压不稳的细微抖动；人影浮现时一点点暗下去，显形后略回亮一些。
     */
    private float exposure(long elapsed, long mvpElapsed) {
        if (elapsed < FLIGHT_MILLIS) {
            return 1.0f;
        }
        float e = 0.95f + 0.05f * TicketArt.hash((int) (elapsed / 70L) * 31 + 7);
        if (mvpElapsed >= FIGURE_AT) {
            if (mvpElapsed < REVEALED_AT) {
                e *= 1.0f - 0.3f * TransitionFx.clamp01((mvpElapsed - FIGURE_AT) / (float) FIGURE_RISE_MILLIS);
            } else {
                e *= Mth.lerp(TransitionFx.easeOutCubic((mvpElapsed - REVEALED_AT) / 220.0f), 0.7f, 0.9f);
            }
        }
        return e;
    }

    /** 心跳的暗红：盖章一下；人影显形时「咚—咚」两下。 */
    private float pulse(long elapsed, long mvpElapsed) {
        float p = beat(elapsed - STAMP_AT);
        p = Math.max(p, beat(mvpElapsed - REVEALED_AT));
        return Math.max(p, 0.7f * beat(mvpElapsed - REVEALED_AT - 300L));
    }

    private static float beat(long since) {
        if (since < 0L || since > 1200L) {
            return 0.0f;
        }
        return Math.min(1.0f, since / 40.0f) * (float) Math.exp(-since / 220.0f);
    }

    /** 屏幕四周的暗红晕边。 */
    private void renderPulse(GuiGraphics g, float pulse) {
        if (pulse <= 0.01f) {
            return;
        }
        int color = TransitionFx.mixRgb(BLOOD, this.winColor(), 0.2f);
        int strong = RailArt.a(color, 0.5f * pulse);
        int clear = RailArt.a(color, 0.0f);
        float ew = this.width * 0.2f;
        float eh = this.height * 0.24f;
        GuiGeo geo = GuiGeo.begin(g);
        geo.rectH(0, 0, ew, this.height, strong, clear);
        geo.rectH(this.width - ew, 0, this.width, this.height, clear, strong);
        geo.rectV(0, 0, this.width, eh, strong, clear);
        geo.rectV(0, this.height - eh, this.width, this.height, clear, strong);
        geo.end();
    }

    // ==================== 音效 ====================

    /** 节点到时只响一次。 */
    private boolean cue(String id, boolean due) {
        return due && this.cues.add(id);
    }

    /**
     * 飘落时几声纸响（残票擦过镜头时一声更低的）、落定；盖章的心跳、滴墨；人影浮现时的钟声与低语、显形的心跳；
     * 退场时揉皱、点燃、燃烧与熄灭。打字声见 {@link #playTyping}。
     */
    private void playSounds(long elapsed, long mvpElapsed, long exitElapsed) {
        if (!this.exitStarted) {
            float[] flutterAt = {0.04f, 0.3f, 0.62f};
            for (int i = 0; i < flutterAt.length; i++) {
                if (this.cue("flutter" + i, elapsed >= FLIGHT_MILLIS * flutterAt[i])) {
                    TicketSounds.flutter(0.78f + 0.06f * i);
                }
            }
            if (this.cue("scrap", elapsed >= 650L)) {
                TicketSounds.flutter(0.62f);
            }
            if (this.cue("land", elapsed >= FLIGHT_MILLIS - 40L)) {
                TicketSounds.land(0.85f);
            }
            if (this.cue("stamp", elapsed >= STAMP_AT)) {
                TicketSounds.stamp();
                TicketSounds.heartbeat(0.85f, 1.0f);
            }
            if (this.cue("drip0", elapsed >= DRIP_AT + 700L)) {
                TicketSounds.drip(0.55f);
            }
            if (this.cue("drip1", elapsed >= DRIP_AT + 1_700L)) {
                TicketSounds.drip(0.45f);
            }
            if (this.cue("toll", mvpElapsed >= FIGURE_AT)) {
                TicketSounds.toll();
            }
            if (this.cue("whisper", mvpElapsed >= FIGURE_AT + 700L)) {
                TicketSounds.whisper();
            }
            if (this.cue("revealed", mvpElapsed >= REVEALED_AT)) {
                TicketSounds.heartbeat(0.75f, 1.0f);
            }
            if (this.cue("revealed2", mvpElapsed >= REVEALED_AT + 300L)) {
                TicketSounds.heartbeat(0.75f, 0.75f);
            }
            return;
        }
        if (this.cue("crumple", true)) {
            TicketSounds.crumple();
        }
        if (this.cue("ignite", exitElapsed >= BURN_AT)) {
            TicketSounds.ignite();
        }
        if (this.cue("burn0", exitElapsed >= BURN_AT + 150L)) {
            TicketSounds.burn(1.0f);
        }
        if (this.cue("burn1", exitElapsed >= BURN_AT + 900L)) {
            TicketSounds.burn(1.2f);
        }
        if (this.cue("smother", exitElapsed >= BURN_AT + BURN_MILLIS - 150L)) {
            TicketSounds.smother();
        }
    }

    /** 打字机击键：打出新字时响一下，太密时合并。 */
    private void playTyping(long now) {
        if (this.typedChars <= this.typedHeard || this.exitStarted) {
            return;
        }
        if (now - this.lastTypeSoundMillis >= 45L) {
            this.lastTypeSoundMillis = now;
            this.typedHeard = this.typedChars;
            TicketSounds.type(1.7f + 0.4f * TicketArt.hash(this.typedChars * 13 + 5));
        }
    }

    /**
     * 画布上 GUI 的混合会把半透明叠层的 alpha 写进画布，在票面上留下「透明洞」；
     * 最后只写 alpha 通道，把整张票纸重新铺满为不透明。
     */
    private void sealPaper(GuiGraphics g, TicketArt.Shape s) {
        g.flush();
        RenderSystem.colorMask(false, false, false, true);
        TicketArt.paper(g, s, TicketArt.MIDNIGHT, TicketArt.Part.WHOLE, 1.0f);
        RenderSystem.colorMask(true, true, true, true);
    }

    /** 纪念车票：票纸 → 防伪底纹 → 票头 → 左下角的黑暗与人影 → 胜方印章与 MVP 数据 → 票根。 */
    private void renderTicket(GuiGraphics g, TicketArt.Shape s, long elapsed, long mvpElapsed) {
        TicketArt.Palette p = TicketArt.MIDNIGHT;
        TicketArt.paper(g, s, p, TicketArt.Part.WHOLE, 1.0f);
        TicketArt.details(g, s, p, TicketArt.Part.WHOLE, 1.0f, 2024, true);
        float unit = Mth.clamp(s.h() / 228.0f, 1.0f, 2.2f);
        float b = s.band();
        float in = s.inset();
        float left = in + 10.0f;
        float right = s.bodyRight() - 8.0f;
        float span = right - left;
        float small = TicketArt.crisp(0.5f * unit);
        float bandText = TicketArt.crisp(0.75f * unit);
        int win = this.winColor();

        // 票头：路徽 + 线路名 ／ 对局结束
        float emblemR = b * 0.27f;
        float emblemX = in + 5.0f + emblemR * 3.1f;
        RailArt.drawWingedWheel(g, emblemX, b / 2.0f, emblemR, 0.0f, 1.0f, 1.0f);
        float bandY = TicketArt.snap((b - 8.0f * bandText) / 2.0f + 0.5f);
        Component header = Component.translatable("gameend.habitrain_core.ticket.header").withStyle(ChatFormatting.BOLD);
        float headerX = emblemX + emblemR * 3.3f + 3.0f;
        TicketArt.drawScaled(g, this.font, header, headerX, bandY, bandText, p.bandInk());
        String headerSub = OptionVoteTexts.subtitle("gameend.habitrain_core.ticket.header");
        Component over = Component.translatable("gameend.habitrain_core.title");
        String overSub = OptionVoteTexts.subtitle("gameend.habitrain_core.title");
        float subDesired = 0.5f * unit;
        float headerEnd = headerX + this.font.width(header) * bandText;
        float overW = this.font.width(over) * bandText;
        // 票头放不下时先省略左侧英文，再省略右侧英文
        if (headerEnd + 5.0f + TicketArt.subWidth(this.font, headerSub, subDesired) + 10.0f
                > right - overW - 5.0f - TicketArt.subWidth(this.font, overSub, subDesired)) {
            headerSub = "";
        }
        if (headerEnd + 10.0f > right - overW - 5.0f - TicketArt.subWidth(this.font, overSub, subDesired)) {
            overSub = "";
        }
        TicketArt.drawSub(g, this.font, headerSub, headerEnd + 5.0f, bandY, bandText, subDesired,
                TicketArt.fade(p.bandInk(), 0.7f));
        float overSubW = overSub.isEmpty() ? 0.0f : TicketArt.subWidth(this.font, overSub, subDesired) + 5.0f;
        TicketArt.drawSub(g, this.font, overSub, right - overSubW + 5.0f, bandY, bandText, subDesired,
                TicketArt.fade(p.bandInk(), 0.7f));
        TicketArt.drawRight(g, this.font, over, right - overSubW, bandY, bandText, p.bandInk());
        // 票头下缘一道胜方色细线
        GuiGeo accent = GuiGeo.begin(g);
        accent.rectH(in, b, s.bodyRight(), b + 1.2f, RailArt.a(win, 0.95f), RailArt.a(win, 0.15f));
        accent.end();

        if (!this.mvpPlayers.isEmpty()) {
            this.renderFigureLayer(g, s, mvpElapsed);
            this.renderMvpLayout(g, s, p, left, right, unit, elapsed, mvpElapsed);
        } else {
            float area = s.h() - in - b;
            this.renderWinBlock(g, p, left + span / 2.0f, b + area * 0.42f, span, unit, elapsed, true);
            float thanksIn = TransitionFx.clamp01((elapsed - DETAILS_AT - 600L) / 700.0f);
            Component thanks = Component.translatable("gameend.habitrain_core.ticket.thanks");
            TicketArt.drawCentered(g, this.font, TicketArt.fit(this.font, thanks, span / small), left + span / 2.0f,
                    TicketArt.snap(b + area * 0.8f), small, TicketArt.fade(p.inkSoft(), thanksIn));
        }

        // 票面右下角：跳过提示
        float hintIn = TransitionFx.clamp01((elapsed - FLIGHT_MILLIS) / 400.0f);
        if (!this.exitStarted && hintIn > 0.02f) {
            Component skip = Component.translatable("gameend.habitrain_core.skip_hint");
            TicketArt.drawRight(g, this.font, skip, right, TicketArt.snap(s.h() - in - 4.0f - 8.0f * small), small,
                    TicketArt.fade(p.inkSoft(), 0.6f * hintIn));
        }

        this.renderStub(g, s, p, unit, elapsed, mvpElapsed);
    }

    // ==================== 胜方印章 ====================

    /**
     * 胜方印章：胜方色双线章框 + 粗体胜方文字 + 另一种语言的副标题；盖下时从大落到原位并溅出墨点，
     * 随后印泥顺着章框下缘往下淌。模式写在章框上方。
     */
    private void renderWinBlock(GuiGraphics g, TicketArt.Palette p, float cx, float cy, float maxWidth, float unit,
                                long elapsed, boolean large) {
        Component winText = this.winLine();
        float textScale = TicketArt.crisp((large ? 3.0f : 2.0f) * unit);
        float limit = maxWidth - 30.0f;
        while (textScale > TicketArt.crisp(1.0f) && this.font.width(winText) * textScale > limit) {
            textScale = TicketArt.crisp(textScale - 0.5f);
        }
        if (this.font.width(winText) * textScale > limit) {
            winText = TicketArt.fit(this.font, winText, limit / textScale);
        }
        String sub = this.winSubtitle();
        float[] box = TicketArt.stampBox(this.font, winText, sub, textScale);
        float modeIn = TransitionFx.clamp01((elapsed - DETAILS_AT) / 420.0f);
        Component mode = this.modeLine();
        if (modeIn > 0.02f && !mode.getString().isBlank()) {
            float modeScale = TicketArt.crisp(1.0f * unit);
            float modeY = TicketArt.snap(cy - box[1] / 2.0f - 9.0f - 8.0f * modeScale
                    - (1.0f - TransitionFx.easeOutCubic(modeIn)) * 3.0f);
            Component labelled = Component.translatable("gameend.habitrain_core.ticket.mode", mode);
            TicketArt.drawCentered(g, this.font, TicketArt.fit(this.font, labelled, maxWidth / modeScale), cx, modeY,
                    modeScale, TicketArt.fade(p.inkSoft(), modeIn));
        }
        float slam = TransitionFx.clamp01((elapsed - STAMP_AT) / (float) STAMP_SLAM_MILLIS);
        if (slam <= 0.0f) {
            return;
        }
        float sc = Mth.lerp(slam * slam, 2.4f, 1.0f);
        int ink = TransitionFx.mixRgb(this.winColor(), IVORY, 0.08f);
        TicketArt.stamp(g, this.font, cx, cy, winText, sub, textScale, sc, -4.0f, ink,
                Math.min(1.0f, slam * 1.5f), p.paperTop(), 31);
        TicketArt.inkSplash(g, cx, cy, 18.0f * textScale, ink, (elapsed - STAMP_AT - STAMP_SLAM_MILLIS) / 480.0f, 57);
        TicketArt.stampDrips(g, cx, cy, box[0], box[1], -4.0f, (large ? 26.0f : 20.0f) * unit, ink,
                (elapsed - DRIP_AT) / (float) DRIP_MILLIS, 31);
    }

    // ==================== MVP ====================

    /**
     * 人影区域（票面局部坐标）：票身左下角、票头之下直到底框。人影按半身像取景——
     * 头顶在票头下方不远，腰部落在底框附近，再往下沉进黑暗。
     */
    private record GhostFrame(float x0, float y0, float x1, float y1, float figureX, float feetY, float modelScale) {
        static GhostFrame of(TicketArt.Shape s) {
            float b = s.band();
            float in = s.inset();
            float left = in + 10.0f;
            float span = s.bodyRight() - 8.0f - left;
            float area = s.h() - in - b;
            return new GhostFrame(in + 1.5f, b + 1.5f, left + span * 0.4f, s.h() - in - 1.5f,
                    left + span * 0.17f, s.h() - in + area * 0.3f, area * 0.5f);
        }
    }

    /** 右上是胜方印章，右下是逐行打出的 MVP 数据；人影在左下角，见 {@link #renderFigureLayer}。 */
    private void renderMvpLayout(GuiGraphics g, TicketArt.Shape s, TicketArt.Palette p, float left, float right,
                                 float unit, long elapsed, long mvpElapsed) {
        float b = s.band();
        float in = s.inset();
        float span = right - left;
        float area = s.h() - in - b;
        this.renderWinBlock(g, p, left + span * 0.64f, b + area * 0.27f, span * 0.6f, unit, elapsed, false);
        if (mvpElapsed < 0L) {
            return;
        }
        GameEndTransitionPayload.MvpPlayer entry = this.mvpPlayers.get(0);
        float x = TicketArt.snap(left + span * 0.47f);
        float colW = right - x;
        String key = "gameend.habitrain_core.mvp.solo";
        float labelScale = TicketArt.crisp(0.75f * unit);
        float nameScale = TicketArt.crisp(1.5f * unit);
        float statScale = TicketArt.crisp(0.75f * unit);
        float scoreScale = TicketArt.crisp(1.25f * unit);
        Component label = Component.translatable(key).withStyle(ChatFormatting.BOLD);
        String name = entry.playerName().isBlank() ? "Player" : entry.playerName();
        Component nameText = TicketArt.fit(this.font, Component.literal(name).withStyle(ChatFormatting.BOLD), colW / nameScale);
        Component stats = TicketArt.fit(this.font, Component.translatable("gameend.habitrain_core.mvp.stats",
                entry.kills(), entry.survivalSeconds(), entry.itemUses()), colW / statScale);
        Component score = Component.translatable("gameend.habitrain_core.mvp.score", entry.score()).withStyle(ChatFormatting.BOLD);

        float y = TicketArt.snap(b + area * 0.56f);
        float nameY = TicketArt.snap(y + 8.0f * labelScale + 5.0f);
        float statsY = TicketArt.snap(nameY + 8.0f * nameScale + 6.0f);
        float scoreY = TicketArt.snap(statsY + 8.0f * statScale + 7.0f);
        long labelDone = this.typeLine(g, label, x, y, labelScale, GOLD_BRIGHT, mvpElapsed, 0L, 45L, 260L);
        float subIn = TransitionFx.clamp01((mvpElapsed - labelDone) / 300.0f);
        if (subIn > 0.0f) {
            TicketArt.drawSub(g, this.font, OptionVoteTexts.subtitle(key), x + this.font.width(label) * labelScale + 5.0f,
                    y, labelScale, 0.5f * unit, TicketArt.fade(GOLD, subIn * 0.8f));
        }
        long nameDone = this.typeLine(g, nameText, x, nameY, nameScale, IVORY, mvpElapsed, labelDone + 140L, 60L, 720L);
        long statsDone = this.typeLine(g, stats, x, statsY, statScale, p.inkSoft(), mvpElapsed, nameDone + 140L, 22L, 700L);
        this.typeLine(g, score, x, scoreY, scoreScale, GOLD, mvpElapsed, statsDone + 140L, 45L, 420L);
    }

    /**
     * 打字机：从 start 起逐字打出，返回这一行打完的时刻；正在打的行尾跟着光标，打完后再闪两下熄掉。
     */
    private long typeLine(GuiGraphics g, Component text, float x, float y, float scale, int color, long now,
                          long start, long perChar, long maxMillis) {
        String plain = text.getString();
        int total = plain.codePointCount(0, plain.length());
        long duration = Math.min(maxMillis, perChar * total);
        long end = start + duration;
        if (now < start || total == 0) {
            return end;
        }
        int shown = now >= end ? total : (int) Math.min(total, total * (now - start) / Math.max(1L, duration) + 1);
        this.typedChars += shown;
        Component part = Component.literal(plain.substring(0, plain.offsetByCodePoints(0, shown))).withStyle(text.getStyle());
        TicketArt.drawScaled(g, this.font, part, x, y, scale, color);
        boolean cursor = now < end || (now < end + 520L && ((now - end) / 130L & 1L) == 1L);
        if (cursor) {
            float cx = x + this.font.width(part) * scale + scale;
            GuiGeo caret = GuiGeo.begin(g);
            caret.rect(cx, y, cx + Math.max(1.0f, 0.9f * scale), y + 8.0f * scale, RailArt.a(color, 0.85f));
            caret.end();
        }
        return end;
    }

    /**
     * 左下角：黑暗先像墨一样从角落漫开，人影从黑暗里浮上来，以半透明底纹合成进票面。
     * 人像本身在 {@link #renderGhostCanvas} 里画进人像画布。
     */
    private void renderFigureLayer(GuiGraphics g, TicketArt.Shape s, long mvpElapsed) {
        float pool = TransitionFx.easeInOutCubic((mvpElapsed - FIGURE_AT + 500L) / 1_600.0f);
        if (pool <= 0.0f) {
            return;
        }
        float b = s.band();
        float in = s.inset();
        float area = s.h() - in - b;
        float span = s.bodyRight() - in;
        g.enableScissor(Math.round(this.ticketX + in), Math.round(this.ticketY + b + 1.2f),
                Math.round(this.ticketX + s.bodyRight()), Math.round(this.ticketY + s.h() - in));
        GuiGeo dark = GuiGeo.begin(g);
        dark.disc(in, s.h() - in, span * 0.7f * (0.4f + 0.6f * pool), area * 1.25f * (0.4f + 0.6f * pool),
                RailArt.a(VOID, 0.97f * pool), RailArt.a(VOID, 0.0f));
        dark.end();
        g.disableScissor();
        if (!this.ghostReady) {
            return;
        }
        GhostFrame f = GhostFrame.of(s);
        float u0 = PaperCanvas.u(this.ticketX + f.x0());
        float v0 = PaperCanvas.v(this.ticketY + f.y0());
        float u1 = PaperCanvas.u(this.ticketX + f.x1());
        float v1 = PaperCanvas.v(this.ticketY + f.y1());
        float rise = TransitionFx.clamp01((mvpElapsed - FIGURE_AT) / (float) FIGURE_RISE_MILLIS);
        float reveal = TransitionFx.easeInOutCubic(rise);
        float settle = TransitionFx.easeOutCubic((mvpElapsed - REVEALED_AT) / (float) REVEAL_EASE_MILLIS);
        ShaderInstance shader = TicketShaders.ghost();
        float alpha = 1.0f;
        if (shader != null) {
            Window window = Minecraft.getInstance().getWindow();
            int rim = TransitionFx.mixRgb(this.winColor(), BLOOD, 0.25f);
            shader.safeGetUniform("GhostRect").set(u0, v0, u1, v1);
            shader.safeGetUniform("TexelSize").set(1.0f / Math.max(1, window.getWidth()), 1.0f / Math.max(1, window.getHeight()));
            shader.safeGetUniform("Reveal").set(reveal);
            shader.safeGetUniform("Develop").set(Mth.lerp(settle, 0.1f + 0.12f * rise, 1.0f));
            shader.safeGetUniform("Glitch").set(this.glitch(mvpElapsed));
            shader.safeGetUniform("Time").set(mvpElapsed / 1000.0f);
            shader.safeGetUniform("Tint").set(0.8f, 0.86f, 1.0f, 0.7f);
            shader.safeGetUniform("Rim").set(((rim >> 16) & 0xFF) / 255.0f, ((rim >> 8) & 0xFF) / 255.0f,
                    (rim & 0xFF) / 255.0f, Mth.lerp(settle, 1.4f, 0.7f));
        } else {
            alpha = 0.7f * reveal;
        }
        PaperCanvas.blit(g, this.ghostCanvas.textureId(), shader, f.x0(), f.y0(), f.x1(), f.y1(), u0, v0, u1, v1, alpha);
    }

    /** 人影的横向撕裂：浮现途中偶尔抽一下，显形时一阵强烈的撕裂，之后极偶尔地抽动。 */
    private float glitch(long mvpElapsed) {
        long since = mvpElapsed - REVEALED_AT;
        float strength = since >= 0L && since < 420L ? 1.0f - since / 420.0f : 0.0f;
        long rising = mvpElapsed - FIGURE_AT;
        if (rising > 0L && since < 0L && TicketArt.hash((int) (rising / 80L) * 13 + 5) > 0.88f) {
            strength = Math.max(strength, 0.5f);
        }
        if (since > 600L && TicketArt.hash((int) (since / 90L) * 7 + 1) > 0.965f) {
            strength = Math.max(strength, 0.35f);
        }
        return strength;
    }

    /**
     * 把 MVP 人像画进人像画布（与票面同一坐标系）：固定姿势，自下而上缓缓升起；
     * 显形后人影平滑地向镜头靠近一点。画布就绪后 {@link #ghostReady} 为 true。
     */
    private void renderGhostCanvas(GuiGraphics g, TicketArt.Shape s, long mvpElapsed) {
        this.ghostReady = false;
        if (this.mvpPlayers.isEmpty() || mvpElapsed < FIGURE_AT) {
            return;
        }
        GameEndTransitionPayload.MvpPlayer entry = this.mvpPlayers.get(0);
        AbstractClientPlayer player = this.previewPlayer(entry.playerId(), entry.playerName());
        if (player == null) {
            return;
        }
        GhostFrame frame = GhostFrame.of(s);
        float area = s.h() - s.inset() - s.band();
        float rise = TransitionFx.easeOutCubic((mvpElapsed - FIGURE_AT) / (float) FIGURE_RISE_MILLIS);
        float closer = TransitionFx.easeOutCubic((mvpElapsed - REVEALED_AT) / (float) REVEAL_EASE_MILLIS);
        float scale = frame.modelScale() * (1.0f + 0.07f * closer);
        float feetY = frame.feetY() + (1.0f - rise) * area * 0.3f + closer * area * 0.04f;
        this.ghostCanvas.begin(g);
        try {
            g.pose().pushPose();
            g.pose().translate(this.ticketX, this.ticketY, 0.0f);
            this.drawModel(g, player, frame.figureX(), feetY, scale, this.victoryWeapon(entry.roleType()));
            g.pose().popPose();
        } finally {
            this.ghostCanvas.end(g);
        }
        this.ghostReady = true;
    }

    /**
     * 人物模型绘制：原版 {@code renderEntityInInventoryFollowsMouse} 自带的裁剪不适用于离屏画布，
     * 因此直接调用底层渲染并自己设置朝向。身体侧过一点，头由 {@link MvpStillPose} 转回来正对镜头。
     */
    private void drawModel(GuiGraphics g, AbstractClientPlayer player, float centerX, float feetY, float scale,
                           ItemStack held) {
        if (player == null || scale < 2.0f) {
            return;
        }
        ItemStack oldMain = player.getMainHandItem().copy();
        Pose oldPose = player.getPose();
        boolean oldInvisible = player.isInvisible();
        float oldBody = player.yBodyRot;
        float oldYRot = player.getYRot();
        float oldXRot = player.getXRot();
        float oldHeadO = player.yHeadRotO;
        float oldHead = player.yHeadRot;
        boolean pushed = false;
        try {
            player.setInvisible(false);
            player.setPose(Pose.STANDING);
            player.stopUsingItem();
            player.setItemSlot(EquipmentSlot.MAINHAND, held == null ? ItemStack.EMPTY : held.copy());
            player.walkAnimation.setSpeed(0.0f);
            float turn = MvpStillPose.BODY_TURN_DEGREES;
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf camera = new Quaternionf().rotateX(-3.0f * Mth.DEG_TO_RAD);
            pose.mul(camera);
            player.yBodyRot = 180.0f + turn;
            player.setYRot(180.0f + turn);
            player.setXRot(0.0f);
            player.yHeadRot = player.getYRot();
            player.yHeadRotO = player.getYRot();
            float entityScale = player.getScale();
            float offset = player.getBbHeight() / 2.0f + 0.0625f * entityScale;
            float renderScale = scale / entityScale;
            g.pose().pushPose();
            pushed = true;
            g.pose().translate(0.0f, 0.0f, 150.0f);
            InventoryScreen.renderEntityInInventory(g, centerX, feetY - offset * renderScale, renderScale,
                    new Vector3f(0.0f, offset, 0.0f), pose, camera, player);
        } catch (Throwable ignored) {
            // 预览模型渲染失败不影响结算转场本身
        } finally {
            if (pushed) {
                g.pose().popPose();
            }
            player.stopUsingItem();
            player.setItemSlot(EquipmentSlot.MAINHAND, oldMain);
            player.setPose(oldPose);
            player.setInvisible(oldInvisible);
            player.yBodyRot = oldBody;
            player.setYRot(oldYRot);
            player.setXRot(oldXRot);
            player.yHeadRotO = oldHeadO;
            player.yHeadRot = oldHead;
        }
    }

    // ==================== 票根 ====================

    /** 票根：纪念票字样、MVP 纪念章（或终点章）、编号与条码；结果揭晓后打孔。 */
    private void renderStub(GuiGraphics g, TicketArt.Shape s, TicketArt.Palette p, float unit, long elapsed,
                            long mvpElapsed) {
        float b = s.band();
        float in = s.inset();
        float left = s.stubLeft() + 2.0f;
        float right = s.w() - in - 3.0f;
        float cx = (left + right) / 2.0f;
        float small = TicketArt.crisp(0.5f * unit);
        float bandText = TicketArt.crisp(0.75f * unit);
        TicketArt.drawCentered(g, this.font, Component.translatable("gameend.habitrain_core.ticket.souvenir"), cx,
                TicketArt.snap((b - 8.0f * bandText) / 2.0f + 0.5f), bandText, p.bandInk());
        float area = s.h() - in - b;

        // 纪念章：黄铜圆环 + 中央胜方色星，背后一团暗淡的光
        float medalIn = TransitionFx.easeOutCubic((elapsed - DETAILS_AT) / 500.0f);
        float r = Math.min((right - left) * 0.36f, area * 0.18f);
        float my = b + area * 0.3f;
        if (medalIn > 0.01f && r > 4.0f) {
            float mr = r * (0.8f + 0.2f * medalIn);
            GuiGeo halo = GuiGeo.glow(g);
            halo.softGlow(cx, my, mr * 2.0f, mr * 2.0f, GOLD_DARK, Math.round(45 * medalIn));
            halo.end();
            GuiGeo medal = GuiGeo.begin(g);
            medal.disc(cx, my, mr, mr, RailArt.a(GOLD_BRIGHT, medalIn), RailArt.a(GOLD_DARK, medalIn));
            medal.disc(cx, my, mr * 0.8f, mr * 0.8f, RailArt.a(0xFF1D2A55, medalIn), RailArt.a(VOID, medalIn));
            medal.ring(cx, my, mr * 0.66f, mr * 0.7f, RailArt.a(GOLD, medalIn));
            medal.end();
            GuiGeo star = GuiGeo.glow(g);
            float sr = mr * 0.5f;
            star.diamond(cx, my, sr * 0.3f, sr, RailArt.a(this.winColor(), medalIn));
            star.diamond(cx, my, sr, sr * 0.3f, RailArt.a(this.winColor(), medalIn));
            star.diamond(cx, my, sr * 0.22f, sr * 0.22f, RailArt.a(IVORY, medalIn));
            star.end();
            boolean hasMvp = !this.mvpPlayers.isEmpty();
            Component caption = hasMvp ? Component.literal("MVP").withStyle(ChatFormatting.BOLD)
                    : Component.translatable("gameend.habitrain_core.ticket.terminus").withStyle(ChatFormatting.BOLD);
            float capScale = TicketArt.crisp(0.75f * unit);
            float capY = TicketArt.snap(my + mr + 5.0f);
            TicketArt.drawCentered(g, this.font, TicketArt.fit(this.font, caption, (right - left) / capScale), cx, capY,
                    capScale, TicketArt.fade(GOLD_BRIGHT, medalIn));
            if (hasMvp) {
                Component score = Component.translatable("gameend.habitrain_core.mvp.score", this.mvpPlayers.get(0).score());
                TicketArt.drawCentered(g, this.font, TicketArt.fit(this.font, score, (right - left) / small), cx,
                        TicketArt.snap(capY + 8.0f * capScale + 3.0f), small, TicketArt.fade(p.inkSoft(), medalIn));
            }
        }

        float serialY = TicketArt.snap(b + area * 0.66f);
        TicketArt.drawCentered(g, this.font, Component.literal("No."), cx, serialY, small, p.inkSoft());
        TicketArt.drawCentered(g, this.font, Component.literal(String.format(Locale.ROOT, "%06d", this.serial))
                        .withStyle(ChatFormatting.BOLD), cx, TicketArt.snap(serialY + 8.0f * small + 2.0f),
                TicketArt.crisp(0.75f * unit), p.serial());
        GuiGeo code = GuiGeo.begin(g);
        float codeH = Math.max(8.0f, area * 0.12f);
        TicketArt.barcode(code, left, s.h() - in - 4.0f - codeH, right - left, codeH, this.serial, TicketArt.fade(p.ink(), 0.9f));
        // 检票打孔：没有 MVP 时印章盖下后打孔；有 MVP 时在人影显形那一刻打孔
        boolean punched = this.mvpPlayers.isEmpty() ? elapsed >= DETAILS_AT + 300L : mvpElapsed >= REVEALED_AT;
        long punchSince = this.mvpPlayers.isEmpty() ? elapsed - DETAILS_AT - 300L : mvpElapsed - REVEALED_AT;
        if (punched) {
            float hole = TransitionFx.easeOutCubic(punchSince / 140.0f);
            TicketArt.punchHole(code, cx, b + 1.0f, 3.2f * unit * hole, RailArt.a(VOID, 1.0f), TicketArt.fade(p.edge(), 1.0f));
        }
        code.end();
    }

    // ==================== 文案 ====================

    /** 胜方副标题（另一种语言）：已知阵营取语言文件；自定义胜方用其 id 拼出。 */
    private String winSubtitle() {
        boolean custom = "CUSTOM".equals(this.winStatusName) || "CUSTOM_COMPONENT".equals(this.winStatusName);
        String id = custom
                ? shortOptionId(this.customWinnerId).toLowerCase(Locale.ROOT)
                : ("TIME".equals(this.winStatusName) ? "time" : this.upstreamWinnerId());
        Language language = Language.getInstance();
        String key = "gameend.habitrain_core.tag." + id;
        if (!id.isBlank() && language.has(key)) {
            return language.getOrDefault(key);
        }
        if (custom && !id.isBlank()) {
            return Component.translatable("gameend.habitrain_core.tag.custom",
                    id.replace('_', ' ').toUpperCase(Locale.ROOT)).getString();
        }
        return language.getOrDefault("gameend.habitrain_core.tag.unknown");
    }

    private String upstreamWinnerId() {
        return switch (this.winStatusName) {
            case "KILLERS" -> "killers";
            case "PASSENGERS", "TIME" -> "passengers";
            case "LOOSE_END" -> "loose_end";
            case "GAMBLER" -> "gambler";
            case "RECORDER" -> "recorder";
            case "NO_PLAYER" -> "noplayer";
            case "NONE" -> "none";
            case "NIAN_SHOU" -> "nianshou";
            case "LOVERS" -> "lovers";
            default -> "unknown";
        };
    }

    private Component winLine() {
        boolean customComponentWin = "CUSTOM_COMPONENT".equals(this.winStatusName);
        boolean customWin = "CUSTOM".equals(this.winStatusName) || customComponentWin;
        if (customComponentWin && !this.customTitleJson.isBlank()) {
            try {
                RegistryAccess access = Minecraft.getInstance().level != null
                        ? Minecraft.getInstance().level.registryAccess()
                        : RegistryAccess.EMPTY;
                MutableComponent parsed = Component.Serializer.fromJson(this.customTitleJson, (HolderLookup.Provider) access);
                if (parsed != null && !parsed.getString().isBlank()) {
                    return parsed;
                }
            } catch (Throwable ignored) {
                // 自定义标题解析失败时回落到 winnerId / 上游胜方文案
            }
        }
        if (customWin && !this.customWinnerId.isBlank()) {
            String winnerId = shortOptionId(this.customWinnerId).toLowerCase(Locale.ROOT);
            String winKey = "announcement.star.win." + winnerId;
            if (Language.getInstance().has(winKey)) {
                return Component.translatable(winKey);
            }
            String roleKey = "announcement.star.role." + winnerId;
            if (Language.getInstance().has(roleKey)) {
                return Component.translatable("gameend.habitrain_core.win.custom", Component.translatable(roleKey));
            }
            return Component.translatable("gameend.habitrain_core.win.custom", Component.literal(winnerId));
        }
        String upstreamWinnerId = this.upstreamWinnerId();
        String winKey = "announcement.star.win." + upstreamWinnerId;
        if (Language.getInstance().has(winKey)) {
            return Component.translatable(winKey);
        }
        return Component.translatable("gameend.habitrain_core.win.custom", Component.literal(upstreamWinnerId));
    }

    private int winColor() {
        if ("CUSTOM_COMPONENT".equals(this.winStatusName) || "CUSTOM".equals(this.winStatusName)) {
            return this.customWinnerColor != 0 ? this.customWinnerColor | 0xFF000000 : 0xFFFFE1A0;
        }
        return switch (this.winStatusName) {
            case "KILLERS" -> 0xFFE0383E;
            case "PASSENGERS" -> 0xFFFFE1A0;
            case "TIME" -> 0xFFD9AE59;
            case "LOOSE_END" -> 0xFFD02A2A;
            case "GAMBLER" -> 0xFFC266E0;
            case "RECORDER", "NO_PLAYER", "NONE" -> 0xFFC0C0C0;
            case "NIAN_SHOU" -> 0xFFFF6A2A;
            case "LOVERS" -> 0xFFF38AFF;
            default -> 0xFFB8A995;
        };
    }

    private Component modeLine() {
        if (this.modeId.isBlank()) {
            return Component.literal("");
        }
        String key = OptionVoteTexts.optionLangKey(this.modeId);
        if (Language.getInstance().has(key)) {
            return Component.translatable(key);
        }
        return Component.literal(shortOptionId(this.modeId));
    }

    // ==================== 模型 / 物品 ====================

    private AbstractClientPlayer previewPlayer(UUID id, String name) {
        Minecraft mc = Minecraft.getInstance();
        if (id == null || mc.level == null) {
            return null;
        }
        if (this.previewLevel != mc.level) {
            this.previewPlayers.clear();
            this.previewLevel = mc.level;
        }
        AbstractClientPlayer cached = this.previewPlayers.get(id);
        if (cached != null) {
            return cached;
        }
        String safeName = name == null || name.isBlank() ? "Player" : name;
        GameProfile profile = new GameProfile(id, safeName);
        AbstractClientPlayer source = mc.level.players().stream()
                .filter(player -> player.getUUID().equals(id)).findFirst().orElse(null);
        if (source != null) {
            profile.getProperties().putAll(source.getGameProfile().getProperties());
        }
        RemotePlayer created = new RemotePlayer(mc.level, profile) {
            @Override
            public boolean isModelPartShown(PlayerModelPart part) {
                return true;
            }
        };
        MvpStillPose.apply(created);
        this.previewPlayers.put(id, created);
        return created;
    }

    private ItemStack victoryWeapon(int roleType) {
        if (roleType == GameEndTransitionPayload.ROLE_TYPE_CIVILIAN) {
            return ItemStack.EMPTY;
        }
        ItemStack cached = this.victoryWeaponTemplates.get(roleType);
        if (cached != null) {
            return cached;
        }
        List<ResourceLocation> itemIds = switch (roleType) {
            case GameEndTransitionPayload.ROLE_TYPE_KILLER -> KILLER_KNIFE_IDS;
            case GameEndTransitionPayload.ROLE_TYPE_SHERIFF -> SHERIFF_REVOLVER_IDS;
            case GameEndTransitionPayload.ROLE_TYPE_NEUTRAL_PRIMARY, GameEndTransitionPayload.ROLE_TYPE_NEUTRAL_SECONDARY -> NEUTRAL_CROWBAR_IDS;
            default -> List.of();
        };
        for (ResourceLocation id : itemIds) {
            try {
                Item item = BuiltInRegistries.ITEM.get(id);
                if (item == null || item == Items.AIR) {
                    continue;
                }
                ItemStack result = new ItemStack(item);
                this.victoryWeaponTemplates.put(roleType, result);
                return result;
            } catch (Throwable ignored) {
                // 注册表缺失该物品时尝试下一个候选
            }
        }
        this.victoryWeaponTemplates.put(roleType, ItemStack.EMPTY);
        return ItemStack.EMPTY;
    }

    private long mvpStageStartMillis() {
        long planned = this.startedAtMillis + MVP_DATA_AT;
        return this.mvpAvailableAtMillis > 0L ? Math.max(planned, this.mvpAvailableAtMillis) : planned;
    }

    // ==================== 生命周期 / 输入 ====================

    private void completeTransition() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == this && !this.completed) {
            this.completed = true;
            GameEndOverlayState.scheduleGrace(4000L);
            mc.setScreen(null);
        }
    }

    @Override
    public void removed() {
        super.removed();
        if (!this.completed && GameEndOverlayState.isActive()) {
            GameEndOverlayState.scheduleGrace(0L);
        }
        this.previewPlayers.clear();
        this.previewLevel = null;
        this.ticketCanvas.close();
        this.ghostCanvas.close();
    }

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
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.exitStarted) {
                this.completeTransition();
            } else {
                this.startExit(Util.getMillis());
            }
            return true;
        }
        // 仅消费 ESC；其余按键放行给全局键（F2 截图/F3 调试/F11 全屏），
        // 否则约 7~14 秒的结算转场内全局键全部失效（review M11）。
        // shouldCloseOnEsc 已为 false，super 不会再触发关闭。
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private float exitProgress() {
        if (!this.exitStarted) {
            return 0.0f;
        }
        return Mth.clamp((Util.getMillis() - this.exitStartAtMillis) / (float) EXIT_MILLIS, 0.0f, 1.0f);
    }

    private static String shortOptionId(String optionId) {
        if (optionId == null) {
            return "";
        }
        int split = optionId.lastIndexOf(':');
        return split >= 0 && split + 1 < optionId.length() ? optionId.substring(split + 1) : optionId;
    }
}
