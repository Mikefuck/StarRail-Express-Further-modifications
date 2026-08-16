/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.google.common.collect.Multimap
 *  com.habitrain.core.client.gui.GameEndOverlayState
 *  com.habitrain.core.client.gui.OptionVoteTexts
 *  com.habitrain.core.client.gui.VoteLaunchOverlayState
 *  com.mojang.authlib.GameProfile
 *  com.mojang.math.Axis
 *  net.minecraft.ChatFormatting
 *  net.minecraft.Util
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.client.gui.screens.inventory.InventoryScreen
 *  net.minecraft.client.multiplayer.ClientLevel
 *  net.minecraft.client.player.AbstractClientPlayer
 *  net.minecraft.client.player.RemotePlayer
 *  net.minecraft.core.HolderLookup$Provider
 *  net.minecraft.core.RegistryAccess
 *  net.minecraft.core.RegistryAccess$Frozen
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.locale.Language
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.Component$Serializer
 *  net.minecraft.network.chat.FormattedText
 *  net.minecraft.network.chat.MutableComponent
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.util.Mth
 *  net.minecraft.world.entity.EquipmentSlot
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.entity.Pose
 *  net.minecraft.world.entity.player.PlayerModelPart
 *  net.minecraft.world.item.Item
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.Items
 *  net.minecraft.world.level.ItemLike
 */
package com.habitrain.core.client.gui;

import com.google.common.collect.Multimap;
import com.habitrain.core.client.gui.GameEndOverlayState;
import com.habitrain.core.client.gui.OptionVoteTexts;
import com.habitrain.core.client.gui.VoteLaunchOverlayState;
import com.habitrain.core.network.GameEndTransitionPayload;
import com.mojang.authlib.GameProfile;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import org.lwjgl.glfw.GLFW;

public final class GameEndTransitionScreen
extends Screen {
    private static final long SWEEP_DURATION_MILLIS = 1150L;
    private static final long TITLE_ENTER_MILLIS = 650L;
    private static final long TITLE_HOLD_MILLIS = 1200L;
    private static final long TITLE_FADE_MILLIS = 650L;
    private static final long WIN_GAP_MILLIS = 120L;
    private static final long WIN_ENTER_MILLIS = 900L;
    private static final long WIN_HOLD_MILLIS = 1500L;
    private static final long SQUAD_HOLD_MILLIS = 5900L;
    private static final long SOLO_HOLD_MILLIS = 6900L;
    private static final long WIN_LIFT_DELAY_MILLIS = 520L;
    private static final long WIN_LIFT_MILLIS = 900L;
    private static final long MVP_STAGE_DELAY_MILLIS = 620L;
    private static final long SQUAD_WALK_MILLIS = 2350L;
    private static final long SOLO_WALK_MILLIS = 2500L;
    private static final long SOLO_SIT_MILLIS = 1150L;
    private static final long EXIT_MILLIS = 900L;
    private static final long ENVIRONMENT_FALLBACK_MILLIS = 3000L;
    private static final long HARD_RELEASE_MILLIS = 30000L;
    private static final long END_CLEAR_GRACE_MILLIS = 4000L;
    private static final int FEATHER_WIDTH = 26;
    private static final List<ResourceLocation> KILLER_KNIFE_IDS = List.of(ResourceLocation.fromNamespaceAndPath((String)"trainmurdermystery", (String)"knife"), ResourceLocation.fromNamespaceAndPath((String)"starrailexpress", (String)"knife"));
    private static final List<ResourceLocation> SHERIFF_REVOLVER_IDS = List.of(ResourceLocation.fromNamespaceAndPath((String)"trainmurdermystery", (String)"revolver"), ResourceLocation.fromNamespaceAndPath((String)"starrailexpress", (String)"revolver"));
    private static final List<ResourceLocation> NEUTRAL_CROWBAR_IDS = List.of(ResourceLocation.fromNamespaceAndPath((String)"trainmurdermystery", (String)"crowbar"), ResourceLocation.fromNamespaceAndPath((String)"starrailexpress", (String)"crowbar"));
    private static final int VOID = -16251126;
    private static final int INK = -15068912;
    private static final int GOLD_DARK = -7444434;
    private static final int GOLD = -2511271;
    private static final int GOLD_BRIGHT = -7776;
    private static final int IVORY = -529457;
    private static final int TEXT = -725018;
    private static final int TEXT_MUTED = -4675179;
    private static final int KILLERS_COLOR = -4970456;
    private static final float RESULT_TITLE_SCALE = 3.25f;
    private final long startedAtMillis = Util.getMillis();
    private String winStatusName = "";
    private String modeId = "";
    private String customWinnerId = "";
    private int customWinnerColor = 0;
    private String customTitleJson = "";
    private List<GameEndTransitionPayload.MvpPlayer> mvpPlayers = List.of();
    private long mvpAvailableAtMillis;
    private final Map<UUID, AbstractClientPlayer> previewPlayers = new LinkedHashMap<UUID, AbstractClientPlayer>();
    private ClientLevel previewLevel;
    private final Map<Integer, ItemStack> victoryWeaponTemplates = new LinkedHashMap<Integer, ItemStack>();
    private boolean environmentReady;
    private boolean exitStarted;
    private long exitStartAtMillis;
    private boolean completed;
    private boolean gameFinished;

    public GameEndTransitionScreen(GameEndTransitionPayload payload) {
        super((Component)Component.translatable((String)"gameend.habitrain_core.title"));
        this.applyPayload(payload);
    }

    protected void init() {
        super.init();
        GameEndOverlayState.setActive((boolean)true);
        VoteLaunchOverlayState.scheduleGrace((long)0L);
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

    public void tick() {
        long now = Util.getMillis();
        if (!this.exitStarted) {
            boolean hardRelease;
            long winEnterEndAt = this.startedAtMillis + 1150L + 650L + 1200L + 650L + 120L + 900L;
            long minExitAt = winEnterEndAt + this.resultHoldMillis();
            if (!this.mvpPlayers.isEmpty()) {
                minExitAt = Math.max(winEnterEndAt, this.mvpStageStartMillis()) + this.resultHoldMillis();
            }
            boolean normalRelease = this.environmentReady && this.gameFinished;
            boolean environmentFallback = this.gameFinished && now >= minExitAt + 3000L;
            boolean bl = hardRelease = !this.environmentReady && now >= this.startedAtMillis + 30000L;
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

    private long resultHoldMillis() {
        if (this.mvpPlayers.size() == 1) {
            return 6900L;
        }
        if (this.mvpPlayers.size() > 1) {
            return 5900L;
        }
        return 1500L;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (this.exitStarted) {
            this.renderExit(g, mouseX, mouseY, partialTick);
            return;
        }
        this.renderLaunch(g, mouseX, mouseY, partialTick);
        this.renderSkipHint(g);
    }

    /** 右上角纯文字提示：按 ESC 跳过（无底色）。 */
    private void renderSkipHint(GuiGraphics g) {
        if (this.completed) {
            return;
        }
        Component skipHint = Component.translatable((String)"gameend.habitrain_core.skip_hint");
        int hx = this.width - 12 - this.font.width((FormattedText)skipHint);
        g.drawString(this.font, skipHint, hx, 12, GameEndTransitionScreen.withAlpha(-4675179, 220), false);
    }

    private void renderLaunch(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float sweep = this.sweepProgress();
        float local = GameEndTransitionScreen.easeInOutCubic(sweep);
        float edgeX = (float)this.width * (1.0f - local);
        g.pose().pushPose();
        g.pose().translate(edgeX, 0.0f, 0.0f);
        this.renderComposition(g, sweep);
        g.pose().popPose();
        if (sweep < 1.0f) {
            this.renderSlideEdge(g, edgeX, local, 1);
        }
    }

    private void renderComposition(GuiGraphics g, float sweep) {
        float modeT;
        float winAlpha;
        float emblemT;
        long elapsed = Util.getMillis() - this.startedAtMillis;
        float titleEnterLinear = Mth.clamp((float)((float)(elapsed - 1150L) / 650.0f), (float)0.0f, (float)1.0f);
        float titleEnter = GameEndTransitionScreen.easeOutBack(titleEnterLinear);
        float titleEnterAlpha = GameEndTransitionScreen.easeOutCubic(titleEnterLinear);
        long titleFadeStart = 3000L;
        float titleFade = GameEndTransitionScreen.easeInOutCubic(Mth.clamp((float)((float)(elapsed - titleFadeStart) / 650.0f), (float)0.0f, (float)1.0f));
        long winEnterStart = titleFadeStart + 650L + 120L;
        float winLinear = Mth.clamp((float)((float)(elapsed - winEnterStart) / 900.0f), (float)0.0f, (float)1.0f);
        float winT = GameEndTransitionScreen.easeOutBack(winLinear);
        float winAlphaT = GameEndTransitionScreen.easeOutCubic(winLinear);
        float winLift = this.mvpPlayers.isEmpty() ? 0.0f : GameEndTransitionScreen.easeInOutCubic(Mth.clamp((float)((float)(elapsed - winEnterStart - 520L) / 900.0f), (float)0.0f, (float)1.0f));
        long mvpElapsed = Util.getMillis() - this.mvpStageStartMillis();
        float mvpStageT = this.mvpPlayers.isEmpty() ? 0.0f : GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)((float)mvpElapsed / 520.0f), (float)0.0f, (float)1.0f));
        g.fillGradient(0, 0, this.width, this.height, GameEndTransitionScreen.withAlpha(1116935, 255), GameEndTransitionScreen.withAlpha(-16251126, 255));
        int cx = this.width / 2;
        int cy = this.height / 2 - 18;
        float glowScale = 1.0f - 0.25f * winAlphaT;
        int glowW = Math.min(600, this.width - 40);
        int glowH = 230;
        int gy = cy - glowH / 2;
        g.fillGradient(cx - glowW / 2, gy, cx + glowW / 2, cy, GameEndTransitionScreen.withAlpha(-7444434, Math.round(30.0f * glowScale)), GameEndTransitionScreen.withAlpha(-7444434, Math.round(8.0f * glowScale)));
        g.fillGradient(cx - glowW / 2, cy, cx + glowW / 2, gy + glowH, GameEndTransitionScreen.withAlpha(-7444434, Math.round(8.0f * glowScale)), GameEndTransitionScreen.withAlpha(-7444434, 0));
        float ambientStrength = 1.0f - 0.72f * mvpStageT;
        this.renderFlowingLines(g, ambientStrength);
        this.renderParticles(g, ambientStrength);
        if (mvpStageT > 0.0f) {
            this.renderMvpBackdrop(g, mvpStageT, this.mvpPlayers.size() == 1);
            if (this.mvpPlayers.size() == 1) {
                this.renderSoloMvp(g, Math.max(0L, mvpElapsed), mvpStageT);
            } else {
                this.renderSquadMvp(g, Math.max(0L, mvpElapsed), mvpStageT);
            }
        }
        if ((emblemT = GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)((sweep - 0.3f) / 0.25f), (float)0.0f, (float)1.0f))) > 0.0f) {
            this.drawEmblem(g, cx, cy - 98, Math.round(230.0f * emblemT * (1.0f - 0.65f * titleFade) * (1.0f - mvpStageT)), 1.0f);
        }
        float titleAlpha = 255.0f * titleEnterAlpha * (1.0f - titleFade);
        MutableComponent title = Component.translatable((String)"gameend.habitrain_core.title").copy().withStyle(ChatFormatting.BOLD);
        if (titleAlpha > 0.5f) {
            this.drawScaledCentered(g, (Component)title, cx, (float)cy - 18.0f * titleFade, 3.25f * (0.84f + 0.16f * titleEnter) * (1.0f + 0.04f * titleFade), GameEndTransitionScreen.withAlpha(-7776, Math.round(titleAlpha)));
        }
        if ((winAlpha = 255.0f * winAlphaT) > 0.5f) {
            Component win = this.winLine();
            float centeredY = (float)cy + 8.0f + 34.0f * (1.0f - winAlphaT);
            float headerY = Math.max(14.0f, Math.min(30.0f, (float)this.height * 0.072f));
            float winY = Mth.lerp((float)winLift, (float)centeredY, (float)headerY);
            float centerScale = this.fittedScale(win) * (0.82f + 0.18f * winT);
            float headerScale = Math.max(0.82f, Math.min(1.75f, this.fittedScale(win) * 0.55f));
            float winScale = Mth.lerp((float)winLift, (float)centerScale, (float)headerScale);
            this.drawScaledCentered(g, win, cx, winY, winScale, GameEndTransitionScreen.withAlpha(this.winColor(), Math.round(winAlpha)));
        }
        if ((modeT = GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)((winLinear - 0.45f) / 0.55f), (float)0.0f, (float)1.0f))) > 0.01f) {
            float modeY = Mth.lerp((float)winLift, (float)((float)cy + 58.0f), (float)Math.max(38.0f, Math.min(54.0f, (float)this.height * 0.145f)));
            this.drawScaledCentered(g, this.modeLine(), cx, modeY, Mth.lerp((float)winLift, (float)0.9f, (float)0.78f), GameEndTransitionScreen.withAlpha(-4675179, Math.round(205.0f * modeT)));
        }
    }

    private Component winLine() {
        Object winKey;
        boolean customWin;
        boolean customComponentWin = "CUSTOM_COMPONENT".equals(this.winStatusName);
        boolean bl = customWin = "CUSTOM".equals(this.winStatusName) || customComponentWin;
        if (customComponentWin && !this.customTitleJson.isBlank()) {
            try {
                RegistryAccess access = Minecraft.getInstance().level != null
                        ? Minecraft.getInstance().level.registryAccess()
                        : RegistryAccess.EMPTY;
                MutableComponent parsed = Component.Serializer.fromJson((String)this.customTitleJson, (HolderLookup.Provider)access);
                if (parsed != null && !parsed.getString().isBlank()) {
                    return parsed;
                }
            }
            catch (Throwable access) {
                // empty catch block
            }
        }
        if (customWin && !this.customWinnerId.isBlank()) {
            String winnerId = GameEndTransitionScreen.shortOptionId(this.customWinnerId).toLowerCase(Locale.ROOT);
            winKey = "announcement.star.win." + winnerId;
            if (Language.getInstance().has((String)winKey)) {
                return Component.translatable((String)winKey);
            }
            String roleKey = "announcement.star.role." + winnerId;
            if (Language.getInstance().has(roleKey)) {
                return Component.translatable((String)"gameend.habitrain_core.win.custom", (Object[])new Object[]{Component.translatable((String)roleKey)});
            }
            return Component.translatable((String)"gameend.habitrain_core.win.custom", (Object[])new Object[]{Component.literal((String)winnerId)});
        }
        String upstreamWinnerId = switch (this.winStatusName) {
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
        winKey = "announcement.star.win." + upstreamWinnerId;
        if (Language.getInstance().has((String)winKey)) {
            return Component.translatable((String)winKey);
        }
        return Component.translatable((String)"gameend.habitrain_core.win.custom", (Object[])new Object[]{Component.literal((String)upstreamWinnerId)});
    }

    private int winColor() {
        if ("CUSTOM_COMPONENT".equals(this.winStatusName) || "CUSTOM".equals(this.winStatusName)) {
            return this.customWinnerColor != 0 ? this.customWinnerColor : -7776;
        }
        return switch (this.winStatusName) {
            case "KILLERS" -> -4970456;
            case "PASSENGERS" -> -7776;
            case "TIME" -> -2511271;
            case "LOOSE_END" -> -6356992;
            case "GAMBLER" -> -8388480;
            case "RECORDER", "NO_PLAYER", "NONE" -> -4144960;
            case "NIAN_SHOU" -> -47872;
            case "LOVERS" -> -816385;
            default -> -4675179;
        };
    }

    private Component modeLine() {
        if (this.modeId.isBlank()) {
            return Component.literal((String)"");
        }
        String key = OptionVoteTexts.optionLangKey((String)this.modeId);
        if (Language.getInstance().has(key)) {
            return Component.translatable((String)key);
        }
        return Component.literal((String)GameEndTransitionScreen.shortOptionId(this.modeId));
    }

    private float fittedScale(Component text) {
        float maxW = Math.min(560, this.width - 120);
        int textW = this.font.width((FormattedText)text);
        if (textW <= 0) {
            return 3.25f;
        }
        return Math.max(0.8f, Math.min(3.25f, maxW / (float)textW));
    }

    private void renderMvpBackdrop(GuiGraphics g, float alphaT, boolean solo) {
        int i;
        int alpha = Math.round(255.0f * Mth.clamp((float)alphaT, (float)0.0f, (float)1.0f));
        int stageTop = Math.max(54, Math.round((float)this.height * 0.2f));
        int horizon = Math.max(stageTop + 20, Math.round((float)this.height * 0.48f));
        g.fillGradient(0, stageTop, this.width, horizon, GameEndTransitionScreen.withAlpha(solo ? 2503224 : 3151892, Math.round((float)alpha * 0.82f)), GameEndTransitionScreen.withAlpha(solo ? 7427375 : 5908764, Math.round((float)alpha * 0.92f)));
        int panelCount = solo ? 3 : 6;
        int panelGap = Math.max(4, this.width / 90);
        int panelW = Math.max(18, Math.min(72, (this.width - 48) / panelCount - panelGap));
        int totalW = panelCount * panelW + (panelCount - 1) * panelGap;
        int panelX = (this.width - totalW) / 2;
        for (int i2 = 0; i2 < panelCount; ++i2) {
            float centerBias = 1.0f - Math.abs((float)i2 - (float)(panelCount - 1) * 0.5f) / Math.max(1.0f, (float)panelCount * 0.5f);
            int lightAlpha = Math.round((float)alpha * (0.13f + 0.2f * centerBias));
            int x = panelX + i2 * (panelW + panelGap);
            g.fillGradient(x, stageTop + 8, x + panelW, horizon - 4, GameEndTransitionScreen.withAlpha(-7776, lightAlpha), GameEndTransitionScreen.withAlpha(-7444434, Math.round((float)lightAlpha * 0.28f)));
            g.fill(x + panelW / 2, stageTop + 8, x + panelW / 2 + 1, horizon - 4, GameEndTransitionScreen.withAlpha(-15068912, Math.round((float)alpha * 0.28f)));
        }
        g.fillGradient(0, horizon, this.width, this.height, GameEndTransitionScreen.withAlpha(solo ? 3814184 : 2562326, Math.round((float)alpha * 0.96f)), GameEndTransitionScreen.withAlpha(-16251126, alpha));
        g.fill(0, horizon, this.width, horizon + 1, GameEndTransitionScreen.withAlpha(-7776, Math.round((float)alpha * 0.48f)));
        int vanishX = solo ? Math.round((float)this.width * 0.62f) : this.width / 2;
        for (i = -4; i <= 4; ++i) {
            int bottomX = this.width / 2 + i * Math.max(42, this.width / 8);
            GameEndTransitionScreen.drawLine(g, vanishX, horizon, bottomX, this.height, GameEndTransitionScreen.withAlpha(-7444434, Math.round((float)alpha * 0.24f)));
        }
        for (i = 1; i <= 4; ++i) {
            float t = (float)i / 4.0f;
            int y = Math.round(Mth.lerp((float)(t * t), (float)horizon, (float)this.height));
            g.fill(0, y, this.width, y + 1, GameEndTransitionScreen.withAlpha(-7444434, Math.round((float)alpha * (0.1f + t * 0.12f))));
        }
        MutableComponent label = Component.translatable((String)(solo ? "gameend.habitrain_core.mvp.solo" : "gameend.habitrain_core.mvp.best_squad")).copy().withStyle(ChatFormatting.BOLD);
        float labelY = Math.max(52.0f, Math.min(70.0f, (float)this.height * 0.19f));
        this.drawScaledCentered(g, (Component)label, (float)this.width / 2.0f, labelY, 0.82f, GameEndTransitionScreen.withAlpha(-529457, Math.round((float)alpha * 0.88f)));
    }

    private void renderSquadMvp(GuiGraphics g, long mvpElapsed, float stageT) {
        int count = Math.min(4, this.mvpPlayers.size());
        if (count <= 0) {
            return;
        }
        float stageWidth = Math.min(Math.max(220.0f, (float)this.width - 24.0f), 560.0f);
        float cellWidth = stageWidth / 4.0f;
        float left = ((float)this.width - stageWidth) * 0.5f;
        float finalBottom = (float)this.height - Math.max(8.0f, (float)this.height * 0.035f);
        this.renderSquadStageLighting(g, count, left, cellWidth, finalBottom, stageT, mvpElapsed);
        for (int i = 0; i < count; ++i) {
            GameEndTransitionPayload.MvpPlayer entry = this.mvpPlayers.get(i);
            int depthFromEdge = Math.min(i, count - 1 - i);
            long stagger = (long)depthFromEdge * 170L + (i >= (count + 1) / 2 ? 80L : 0L);
            float walkLinear = Mth.clamp((float)((float)(mvpElapsed - stagger) / 2350.0f), (float)0.0f, (float)1.0f);
            float walk = GameEndTransitionScreen.easeOutCubic(walkLinear);
            float finalX = left + cellWidth * ((float)i + 0.5f);
            boolean entersFromLeft = i < (count + 1) / 2;
            float slotBottom = finalBottom - (switch (i) {
                case 0 -> 3.0f;
                case 1 -> 0.0f;
                case 2 -> 1.0f;
                default -> 4.0f;
            });
            float finalScale = Mth.clamp((float)(cellWidth * 0.62f), (float)28.0f, (float)Math.min(62.0f, (float)this.height * 0.25f));
            float startX = entersFromLeft ? -finalScale * 0.95f : (float)this.width + (finalScale *= (switch (i) {
                case 0 -> 0.96f;
                case 1 -> 1.04f;
                case 2 -> 1.0f;
                default -> 0.94f;
            })) * 0.95f;
            float x = Mth.lerp((float)walk, (float)startX, (float)finalX);
            float scale = finalScale * (0.9f + 0.1f * walk) * (0.72f + 0.28f * stageT);
            float moving = 1.0f - GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)((walkLinear - 0.78f) / 0.22f), (float)0.0f, (float)1.0f));
            float bob = Mth.sin((float)(walkLinear * (float)Math.PI * 7.0f + (float)i * 0.8f)) * 2.4f * moving;
            float entranceAlpha = GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)(walkLinear / 0.16f), (float)0.0f, (float)1.0f)) * stageT;
            this.renderStageShadow(g, x, slotBottom, scale, entranceAlpha);
            this.renderEntranceDust(g, x, slotBottom, scale, moving * entranceAlpha, mvpElapsed, i, entersFromLeft);
            AbstractClientPlayer player = this.previewPlayer(entry.playerId(), entry.playerName());
            float lookOffset = (entersFromLeft ? 1.0f : -1.0f) * scale * 0.52f * moving;
            ItemStack heldItem = this.victoryWeapon(entry.roleType());
            boolean raiseKnife = entry.roleType() == GameEndTransitionPayload.ROLE_TYPE_KILLER && walkLinear >= 0.98f;
            this.renderPlayerModel(g, player, x, slotBottom + bob, Math.round(scale), moving, walkLinear * 10.0f + (float)i * 1.3f, false, lookOffset, heldItem, raiseKnife);
            float nameT = GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)((walkLinear - 0.52f) / 0.28f), (float)0.0f, (float)1.0f)) * stageT;
            if (!(nameT > 0.01f)) continue;
            float nameY = slotBottom - scale * 2.42f - 26.0f;
            this.renderNamePlate(g, entry, x, nameY, Math.max(50, Math.round(cellWidth - 8.0f)), nameT);
        }
    }

    private void renderSoloMvp(GuiGraphics g, long mvpElapsed, float stageT) {
        GameEndTransitionPayload.MvpPlayer entry = this.mvpPlayers.get(0);
        float walkLinear = Mth.clamp((float)((float)mvpElapsed / 2500.0f), (float)0.0f, (float)1.0f);
        float walk = GameEndTransitionScreen.easeOutCubic(walkLinear);
        float sitLinear = Mth.clamp((float)((float)(mvpElapsed - 2500L) / 1150.0f), (float)0.0f, (float)1.0f);
        float sit = GameEndTransitionScreen.easeInOutCubic(sitLinear);
        float finalBottom = (float)this.height - Math.max(9.0f, (float)this.height * 0.038f);
        float finalScale = Mth.clamp((float)Math.min((float)this.width * 0.16f, (float)this.height * 0.34f), (float)38.0f, (float)84.0f);
        float heroX = Mth.lerp((float)walk, (float)(-finalScale * 0.92f), (float)((float)this.width * 0.62f));
        float bottom = finalBottom + sit * Math.min(8.0f, (float)this.height * 0.032f);
        float scale = finalScale * (0.92f + 0.08f * walk) * (0.7f + 0.3f * stageT) * (1.0f + 0.2f * sit);
        float moving = (1.0f - sit) * (1.0f - GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)((walkLinear - 0.84f) / 0.16f), (float)0.0f, (float)1.0f)));
        float bob = Mth.sin((float)(walkLinear * (float)Math.PI * 8.0f)) * 2.8f * moving;
        float heroAlpha = GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)(walkLinear / 0.14f), (float)0.0f, (float)1.0f)) * stageT;
        this.renderSoloSpotlight(g, heroX, finalBottom, scale, heroAlpha, mvpElapsed);
        this.renderStageShadow(g, heroX, finalBottom, scale, heroAlpha);
        this.renderEntranceDust(g, heroX, finalBottom, scale, moving * heroAlpha, mvpElapsed, 7, true);
        float pileT = GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)((sitLinear + walkLinear - 1.1f) / 0.45f), (float)0.0f, (float)1.0f)) * stageT;
        if (pileT > 0.01f) {
            this.renderModelPile(g, entry.playerId(), mvpElapsed, pileT);
        }
        AbstractClientPlayer hero = this.previewPlayer(entry.playerId(), entry.playerName());
        float lookOffset = scale * 0.58f * moving;
        ItemStack heldItem = this.victoryWeapon(entry.roleType());
        boolean raiseKnife = entry.roleType() == GameEndTransitionPayload.ROLE_TYPE_KILLER && walkLinear >= 0.98f;
        this.renderPlayerModel(g, hero, heroX, bottom + bob, Math.round(scale), moving, walkLinear * 11.0f, sit > 0.38f, lookOffset, heldItem, raiseKnife);
        float cardT = GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)((sitLinear - 0.35f) / 0.5f), (float)0.0f, (float)1.0f)) * stageT;
        if (cardT > 0.01f) {
            this.renderSoloNameCard(g, entry, cardT);
        }
    }

    private void renderNamePlate(GuiGraphics g, GameEndTransitionPayload.MvpPlayer entry, float centerX, float y, int maxWidth, float alphaT) {
        String name = entry.playerName().isBlank() ? "Player" : entry.playerName();
        name = this.font.plainSubstrByWidth(name, Math.max(24, maxWidth - 12));
        String score = Component.translatable((String)"gameend.habitrain_core.mvp.score", (Object[])new Object[]{entry.score()}).getString();
        int w = Math.min(maxWidth, Math.max(this.font.width(name), this.font.width(score)) + 12);
        int x = Math.round(centerX - (float)w * 0.5f);
        int iy = Math.round(y - 3.0f * (1.0f - alphaT));
        int alpha = Math.round(220.0f * alphaT);
        g.fill(x, iy, x + w, iy + 22, GameEndTransitionScreen.withAlpha(-16251126, alpha));
        g.fill(x, iy, x + 2, iy + 22, GameEndTransitionScreen.withAlpha(this.winColor(), Math.round(240.0f * alphaT)));
        g.drawCenteredString(this.font, name, Math.round(centerX), iy + 3, GameEndTransitionScreen.withAlpha(-725018, Math.round(255.0f * alphaT)));
        g.drawCenteredString(this.font, score, Math.round(centerX), iy + 12, GameEndTransitionScreen.withAlpha(-2511271, Math.round(220.0f * alphaT)));
    }

    private void renderSoloNameCard(GuiGraphics g, GameEndTransitionPayload.MvpPlayer entry, float alphaT) {
        int cardW = Math.min(220, Math.max(145, Math.round((float)this.width * 0.34f)));
        int x = Math.max(14, Math.round((float)this.width * 0.055f - 18.0f * (1.0f - alphaT)));
        int cardH = 60;
        int maxY = Math.max(8, this.height - cardH - 8);
        int minY = Math.min(62, maxY);
        int y = Mth.clamp((int)Math.round((float)this.height * 0.7f), (int)minY, (int)maxY);
        int alpha = Math.round(218.0f * alphaT);
        g.fill(x, y, x + cardW, y + cardH, GameEndTransitionScreen.withAlpha(-16251126, alpha));
        g.fill(x, y, x + 3, y + cardH, GameEndTransitionScreen.withAlpha(this.winColor(), Math.round(255.0f * alphaT)));
        g.drawString(this.font, (Component)Component.translatable((String)"gameend.habitrain_core.mvp.solo").copy().withStyle(ChatFormatting.BOLD), x + 10, y + 6, GameEndTransitionScreen.withAlpha(-7776, Math.round(255.0f * alphaT)), false);
        String name = this.font.plainSubstrByWidth(entry.playerName(), cardW - 20);
        g.drawString(this.font, name, x + 10, y + 21, GameEndTransitionScreen.withAlpha(-725018, Math.round(255.0f * alphaT)), false);
        MutableComponent stats = Component.translatable((String)"gameend.habitrain_core.mvp.stats", (Object[])new Object[]{entry.kills(), entry.survivalSeconds(), entry.itemUses()});
        g.drawString(this.font, this.font.plainSubstrByWidth(stats.getString(), cardW - 20), x + 10, y + 36, GameEndTransitionScreen.withAlpha(-4675179, Math.round(230.0f * alphaT)), false);
        g.drawString(this.font, (Component)Component.translatable((String)"gameend.habitrain_core.mvp.score", (Object[])new Object[]{entry.score()}), x + 10, y + 49, GameEndTransitionScreen.withAlpha(-2511271, Math.round(240.0f * alphaT)), false);
    }

    private void renderModelPile(GuiGraphics g, UUID heroId, long mvpElapsed, float alphaT) {
        int baseY = this.height - 3;
        int centerX = Math.round((float)this.width * 0.59f);
        this.renderBloodPool(g, centerX, baseY, alphaT);
        this.renderBlockyPile(g, centerX, baseY, alphaT);
        Minecraft mc = Minecraft.getInstance();
        ArrayList<AbstractClientPlayer> pile = new ArrayList<AbstractClientPlayer>();
        if (mc.level != null) {
            for (AbstractClientPlayer visible : mc.level.players()) {
                if (visible.getUUID().equals(heroId)) continue;
                pile.add(this.previewPlayer(visible.getUUID(), visible.getGameProfile().getName()));
                if (pile.size() < 4) continue;
                break;
            }
        }
        float[] dx = new float[]{-62.0f, -22.0f, 28.0f, 62.0f};
        float[] angle = new float[]{-72.0f, 67.0f, -61.0f, 74.0f};
        for (int i = 0; i < pile.size(); ++i) {
            float scatterLinear = Mth.clamp((float)((alphaT - (float)i * 0.075f) / 0.7f), (float)0.0f, (float)1.0f);
            float scatter = GameEndTransitionScreen.easeOutCubic(scatterLinear);
            float px = Mth.lerp((float)scatter, (float)((float)centerX + ((float)i - 1.5f) * 7.0f), (float)((float)centerX + dx[i]));
            float finalY = (float)baseY - 3.0f - (float)(i % 2) * 6.0f;
            float py = Mth.lerp((float)scatter, (float)((float)baseY - 25.0f - (float)i * 4.0f), (float)finalY) - Mth.sin((float)(scatter * (float)Math.PI)) * (16.0f + (float)i * 3.0f);
            g.pose().pushPose();
            g.pose().translate(px, py, 120.0f + (float)i);
            g.pose().mulPose(Axis.ZP.rotationDegrees(angle[i] * scatter));
            g.pose().translate(-px, -py, 0.0f);
            int pileScale = Math.round((float)Math.max(22, Math.min(34, this.height / 9)) * (0.72f + 0.28f * scatter));
            this.renderPlayerModel(g, (AbstractClientPlayer)pile.get(i), px, py, pileScale, 0.0f, i, true, 0.0f, ItemStack.EMPTY, false);
            g.pose().popPose();
        }
        this.renderBloodParticles(g, centerX, baseY, mvpElapsed, alphaT);
    }

    private void renderBlockyPile(GuiGraphics g, int centerX, int baseY, float alphaT) {
        int[] xs = new int[]{centerX - 68, centerX - 32, centerX + 7, centerX + 43};
        int[] ys = new int[]{baseY - 17, baseY - 24, baseY - 15, baseY - 22};
        for (int i = 0; i < xs.length; ++i) {
            float localLinear = Mth.clamp((float)((alphaT - (float)i * 0.055f) / 0.72f), (float)0.0f, (float)1.0f);
            float local = GameEndTransitionScreen.easeOutCubic(localLinear);
            int x = Math.round(Mth.lerp((float)local, (float)((float)centerX - 8.0f + (float)i * 5.0f), (float)xs[i]));
            int y = Math.round(Mth.lerp((float)local, (float)((float)baseY - 35.0f - (float)i * 3.0f), (float)ys[i]) - Mth.sin((float)(local * (float)Math.PI)) * (11.0f + (float)i * 2.0f));
            int colorA = GameEndTransitionScreen.withAlpha(5133145, Math.round(180.0f * local));
            int colorB = GameEndTransitionScreen.withAlpha(3159098, Math.round(205.0f * local));
            int outline = GameEndTransitionScreen.withAlpha(-16251126, Math.round(220.0f * local));
            g.fill(x - 2, y - 2, x + 20, y + 14, outline);
            g.fill(x, y, x + 18, y + 12, (i & 1) == 0 ? colorA : colorB);
            g.fill(x + 4, y - 8, x + 13, y + 1, colorA);
        }
    }

    private void renderSquadStageLighting(GuiGraphics g, int count, float left, float cellWidth, float floorY, float stageT, long mvpElapsed) {
        int top = Math.max(56, Math.round((float)this.height * 0.22f));
        float pulse = 0.88f + 0.12f * Mth.sin((float)((float)mvpElapsed / 430.0f));
        for (int i = 0; i < count; ++i) {
            float x = left + cellWidth * ((float)i + 0.5f);
            float reveal = GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)((float)(mvpElapsed - (long)i * 90L) / 700.0f), (float)0.0f, (float)1.0f)) * stageT;
            int beamHalf = Math.max(18, Math.round(cellWidth * 0.46f));
            int ix = Math.round(x);
            int alpha = Math.round(34.0f * reveal * pulse);
            g.fillGradient(ix - beamHalf, top, ix + beamHalf, Math.round(floorY), GameEndTransitionScreen.withAlpha(-7776, Math.round((float)alpha * 0.18f)), GameEndTransitionScreen.withAlpha(-2511271, alpha));
            g.fill(ix - Math.round(cellWidth * 0.32f), Math.round(floorY - 2.0f), ix + Math.round(cellWidth * 0.32f), Math.round(floorY), GameEndTransitionScreen.withAlpha(-7776, Math.round(52.0f * reveal)));
        }
        int lineY = Math.max(73, Math.round((float)this.height * 0.235f));
        int lineHalf = Math.round(Math.min((float)this.width * 0.31f, 250.0f) * stageT);
        g.fill(this.width / 2 - lineHalf, lineY, this.width / 2 + lineHalf, lineY + 1, GameEndTransitionScreen.withAlpha(-2511271, Math.round(88.0f * stageT)));
        GameEndTransitionScreen.drawDiamond(g, this.width / 2, lineY, 2, GameEndTransitionScreen.withAlpha(-7776, Math.round(210.0f * stageT)));
    }

    private void renderSoloSpotlight(GuiGraphics g, float x, float floorY, float scale, float alphaT, long mvpElapsed) {
        int top = Math.max(54, Math.round((float)this.height * 0.2f));
        int half = Math.max(26, Math.round(scale * 0.8f));
        int ix = Math.round(x);
        float breathe = 0.86f + 0.14f * Mth.sin((float)((float)mvpElapsed / 360.0f));
        int alpha = Math.round(46.0f * alphaT * breathe);
        g.fillGradient(ix - half, top, ix + half, Math.round(floorY), GameEndTransitionScreen.withAlpha(-7776, Math.round((float)alpha * 0.12f)), GameEndTransitionScreen.withAlpha(12093247, alpha));
        g.fill(ix - Math.round(scale * 0.65f), Math.round(floorY - 2.0f), ix + Math.round(scale * 0.65f), Math.round(floorY), GameEndTransitionScreen.withAlpha(-7776, Math.round(68.0f * alphaT)));
    }

    private void renderStageShadow(GuiGraphics g, float centerX, float bottom, float scale, float alphaT) {
        int cx = Math.round(centerX);
        int y = Math.round(bottom - 2.0f);
        for (int row = 0; row < 5; ++row) {
            int half = Math.max(3, Math.round(scale * (0.62f - (float)row * 0.075f)));
            int alpha = Math.round((float)(34 - row * 5) * alphaT);
            g.fill(cx - half, y - row, cx + half, y - row + 1, GameEndTransitionScreen.withAlpha(0, alpha));
        }
    }

    private void renderEntranceDust(GuiGraphics g, float x, float floorY, float scale, float strength, long elapsed, int seed, boolean movingRight) {
        if (strength <= 0.01f) {
            return;
        }
        float time = (float)elapsed / 1000.0f;
        float direction = movingRight ? -1.0f : 1.0f;
        for (int i = 0; i < 7; ++i) {
            float phase = GameEndTransitionScreen.fract(time * (0.72f + (float)i * 0.035f) + (float)seed * 0.173f + (float)i * 0.211f);
            float distance = (7.0f + (float)i * 2.7f) * phase;
            int px = Math.round(x + direction * distance);
            int py = Math.round(floorY - 2.0f - Mth.sin((float)(phase * (float)Math.PI)) * (3.0f + (float)(i % 3)));
            int size = i % 3 == 0 ? 2 : 1;
            int alpha = Math.round(86.0f * strength * (1.0f - phase));
            g.fill(px, py, px + size, py + size, GameEndTransitionScreen.withAlpha(i % 2 == 0 ? 13018219 : 7823946, alpha));
        }
    }

    private void renderBloodPool(GuiGraphics g, int centerX, int baseY, float alphaT) {
        float poolT = GameEndTransitionScreen.easeOutCubic(Mth.clamp((float)((alphaT - 0.12f) / 0.78f), (float)0.0f, (float)1.0f));
        if (poolT <= 0.0f) {
            return;
        }
        int halfWidth = Math.round(18.0f + 82.0f * poolT);
        int alpha = Math.round(176.0f * poolT);
        for (int row = 0; row < 7; ++row) {
            int inset = Math.round((float)(row * row) * 0.72f);
            g.fill(centerX - halfWidth + inset, baseY - row - 1, centerX + halfWidth - inset, baseY - row, GameEndTransitionScreen.withAlpha(row < 2 ? 3998983 : 7473420, Math.max(0, alpha - row * 13)));
        }
        int lobe = Math.round(18.0f * poolT);
        g.fill(centerX - halfWidth - lobe / 2, baseY - 4, centerX - halfWidth + lobe, baseY - 2, GameEndTransitionScreen.withAlpha(5900041, Math.round(130.0f * poolT)));
        g.fill(centerX + halfWidth - lobe, baseY - 3, centerX + halfWidth + lobe / 2, baseY - 1, GameEndTransitionScreen.withAlpha(5900041, Math.round(120.0f * poolT)));
        g.fill(centerX - halfWidth / 2, baseY - 6, centerX + halfWidth / 3, baseY - 5, GameEndTransitionScreen.withAlpha(11868959, Math.round(76.0f * poolT)));
    }

    private void renderBloodParticles(GuiGraphics g, int centerX, int baseY, long mvpElapsed, float alphaT) {
        long burstElapsed = mvpElapsed - 2500L - 70L;
        if (burstElapsed < 0L) {
            return;
        }
        for (int i = 0; i < 22; ++i) {
            int size;
            long delayed = burstElapsed - (long)(i % 6) * 38L;
            if (delayed < 0L) continue;
            float flight = Mth.clamp((float)((float)delayed / (620.0f + (float)(i % 4) * 85.0f)), (float)0.0f, (float)1.0f);
            float direction = (i & 1) == 0 ? -1.0f : 1.0f;
            float reach = 13.0f + (float)(i * 19 % 58);
            float arc = 10.0f + (float)(i * 13 % 25);
            int px = Math.round((float)centerX + direction * reach * flight);
            int py = Math.round(Mth.lerp((float)flight, (float)((float)baseY - 22.0f), (float)((float)baseY - 2.0f)) - Mth.sin((float)(flight * (float)Math.PI)) * arc);
            int n = size = i % 5 == 0 ? 2 : 1;
            if (flight < 1.0f) {
                int particleAlpha = Math.round(230.0f * alphaT * (1.0f - flight * 0.38f));
                g.fill(px, py, px + size, py + size, GameEndTransitionScreen.withAlpha(i % 3 == 0 ? 13708081 : 9309716, particleAlpha));
                continue;
            }
            int splatWidth = 1 + i % 4;
            g.fill(px - splatWidth, baseY - 2 - i % 3, px + splatWidth + 1, baseY - 1 - i % 3, GameEndTransitionScreen.withAlpha(6752010, Math.round(132.0f * alphaT)));
        }
        float flash = 1.0f - Mth.clamp((float)((float)burstElapsed / 360.0f), (float)0.0f, (float)1.0f);
        if (flash > 0.0f) {
            for (int i = 0; i < 9; ++i) {
                float direction = (float)(i - 4) / 4.0f;
                int endX = centerX + Math.round(direction * (36.0f + Math.abs(direction) * 18.0f));
                int endY = baseY - 18 - i * 11 % 17;
                GameEndTransitionScreen.drawLine(g, centerX, baseY - 8, endX, endY, GameEndTransitionScreen.withAlpha(10555928, Math.round(120.0f * flash * alphaT)));
            }
        }
    }

    private static float fract(float value) {
        return value - (float)Math.floor(value);
    }

    private AbstractClientPlayer previewPlayer(UUID id, String name) {
        AbstractClientPlayer cached;
        Minecraft mc = Minecraft.getInstance();
        if (id == null || mc.level == null) {
            return null;
        }
        if (this.previewLevel != mc.level) {
            this.previewPlayers.clear();
            this.previewLevel = mc.level;
        }
        if ((cached = this.previewPlayers.get(id)) != null) {
            return cached;
        }
        String safeName = name == null || name.isBlank() ? "Player" : name;
        GameProfile profile = new GameProfile(id, safeName);
        AbstractClientPlayer source = mc.level.players().stream().filter(player -> player.getUUID().equals(id)).findFirst().orElse(null);
        if (source != null) {
            profile.getProperties().putAll((Multimap)source.getGameProfile().getProperties());
        }
        RemotePlayer created = new RemotePlayer(mc.level, profile) {

            public boolean isModelPartShown(PlayerModelPart part) {
                return true;
            }
        };
        this.previewPlayers.put(id, (AbstractClientPlayer)created);
        return created;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void renderPlayerModel(GuiGraphics g, AbstractClientPlayer player, float centerX, float bottom, int scale, float walkAmount, float walkPhase, boolean crouching, float lookOffset, ItemStack heldItem, boolean usingHeldItem) {
        if (player == null || scale <= 0) {
            return;
        }
        ItemStack oldMain = player.getMainHandItem().copy();
        Pose oldPose = player.getPose();
        boolean oldInvisible = player.isInvisible();
        boolean posePushed = false;
        try {
            player.setInvisible(false);
            player.setPose(crouching ? Pose.CROUCHING : Pose.STANDING);
            player.stopUsingItem();
            player.setItemSlot(EquipmentSlot.MAINHAND, heldItem == null ? ItemStack.EMPTY : heldItem.copy());
            if (usingHeldItem && !player.getMainHandItem().isEmpty()) {
                player.startUsingItem(InteractionHand.MAIN_HAND);
            }
            float phaseDelta = walkPhase - player.walkAnimation.position();
            player.walkAnimation.update(phaseDelta, 1.0f);
            player.walkAnimation.setSpeed(Mth.clamp((float)walkAmount, (float)0.0f, (float)1.0f));
            int halfW = Math.max(22, Math.round((float)scale * 0.86f));
            int top = Math.round(bottom - (float)scale * 2.42f);
            int bottomI = Math.round(bottom + 4.0f);
            int x = Math.round(centerX);
            g.pose().pushPose();
            posePushed = true;
            g.pose().translate(0.0f, 0.0f, 260.0f);
            InventoryScreen.renderEntityInInventoryFollowsMouse((GuiGraphics)g, (int)(x - halfW), (int)top, (int)(x + halfW), (int)bottomI, (int)scale, (float)0.0625f, (float)(centerX + Mth.clamp((float)lookOffset, (float)((float)(-scale) * 0.72f), (float)((float)scale * 0.72f))), (float)((float)(top + bottomI) * 0.5f), (LivingEntity)player);
        }
        catch (Throwable throwable) {
        }
        finally {
            if (posePushed) {
                g.pose().popPose();
            }
            player.stopUsingItem();
            player.setItemSlot(EquipmentSlot.MAINHAND, oldMain);
            player.setPose(oldPose);
            player.setInvisible(oldInvisible);
        }
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
                Item item = (Item)BuiltInRegistries.ITEM.get(id);
                if (item == null || item == Items.AIR) continue;
                ItemStack result = new ItemStack((ItemLike)item);
                this.victoryWeaponTemplates.put(roleType, result);
                return result;
            }
            catch (Throwable throwable) {
            }
        }
        this.victoryWeaponTemplates.put(roleType, ItemStack.EMPTY);
        return ItemStack.EMPTY;
    }

    private long mvpStageStartMillis() {
        long planned = this.startedAtMillis + 1150L + 650L + 1200L + 650L + 120L + 620L;
        return this.mvpAvailableAtMillis > 0L ? Math.max(planned, this.mvpAvailableAtMillis) : planned;
    }

    private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 > dx) continue;
            err += dx;
            y0 += sy;
        }
    }

    private void renderExit(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float exit = this.exitProgress();
        float local = GameEndTransitionScreen.easeInOutCubic(exit);
        float edgeX = (float)this.width * (1.0f - local);
        g.pose().pushPose();
        g.pose().translate((float)(-this.width) * local, 0.0f, 0.0f);
        this.renderComposition(g, 1.0f);
        g.pose().popPose();
        if (exit < 1.0f) {
            this.renderSlideEdge(g, edgeX, local, -1);
        }
    }

    private void renderFlowingLines(GuiGraphics g, float strength) {
        float time = (float)Util.getMillis() / 1000.0f;
        int cy = this.height / 2 - 18;
        int[] rows = new int[]{cy - 166, cy - 136, cy - 106, cy + 98, cy + 132, cy + 166};
        int[] speeds = new int[]{96, 152, 112, 176, 130, 92};
        for (int r = 0; r < rows.length; ++r) {
            int y = rows[r];
            float breathe = (0.75f + 0.25f * Mth.sin((float)(time * 0.7f + (float)r * 2.1f))) * strength;
            g.fill(0, y, this.width, y + 1, GameEndTransitionScreen.withAlpha(-7444434, Math.round(26.0f * breathe)));
            int streakW = 220 + r * 34;
            for (int s = 0; s < 3; ++s) {
                float phase = time * (float)speeds[r] + (float)(s * (streakW + 320)) + (float)r * 97.0f;
                int span = this.width + streakW;
                float center = this.width - Math.floorMod(Math.round(phase), span);
                this.renderStreak(g, center, y, streakW, breathe, (r + s) % 2 == 0);
            }
        }
        int bandHalf = 190;
        int span = this.width + bandHalf * 2 + 60;
        float bandPhase = time * (float)span / 9.0f;
        float bandCenter = this.width + bandHalf + 30 - Math.floorMod(Math.round(bandPhase), span);
        this.renderLightBand(g, bandCenter, strength);
    }

    private void renderStreak(GuiGraphics g, float center, int y, int streakW, float rowBreathe, boolean bright) {
        int x0;
        int alpha;
        float frac;
        int i;
        int half = streakW / 2;
        int steps = 12;
        int stepW = Math.max(1, half / steps);
        for (i = 1; i <= steps; ++i) {
            frac = (float)i / (float)steps;
            alpha = Math.round((4.0f + 44.0f * frac * frac) * rowBreathe);
            x0 = (int)center - half + (steps - i) * stepW;
            g.fill(x0, y, x0 + stepW, y + 1, GameEndTransitionScreen.withAlpha(bright ? -7776 : -2511271, alpha));
        }
        g.fill((int)center - 1, y, (int)center + 2, y + 1, GameEndTransitionScreen.withAlpha(-7776, Math.round(175.0f * rowBreathe)));
        for (i = 0; i < steps; ++i) {
            frac = 1.0f - (float)i / (float)steps;
            alpha = Math.round((4.0f + 30.0f * frac * frac) * rowBreathe);
            x0 = (int)center + 1 + i * stepW;
            g.fill(x0, y, x0 + stepW, y + 1, GameEndTransitionScreen.withAlpha(-2511271, alpha));
        }
    }

    private void renderLightBand(GuiGraphics g, float centerX, float strength) {
        int core = Mth.clamp((int)Math.round(centerX), (int)0, (int)Math.max(0, this.width - 1));
        g.fill(core, 0, core + 1, this.height, GameEndTransitionScreen.withAlpha(-7776, Math.round(34.0f * strength)));
        for (int i = 1; i <= 16; ++i) {
            float frac = 1.0f - (float)i / 16.0f;
            int alpha = Math.round(26.0f * frac * frac * strength);
            int xr = Math.min(this.width - 1, core + i);
            int xl = Math.max(0, core - i);
            g.fill(xl, 0, xl + 1, this.height, GameEndTransitionScreen.withAlpha(-2511271, alpha));
            g.fill(xr, 0, xr + 1, this.height, GameEndTransitionScreen.withAlpha(-2511271, alpha));
        }
    }

    private void renderParticles(GuiGraphics g, float strength) {
        float time = (float)Util.getMillis() / 1000.0f;
        int span = this.width + 60;
        for (int i = 0; i < 12; ++i) {
            float speed = 30 + i % 5 * 9;
            float x = this.width - Math.floorMod(Math.round(time * speed + (float)i * 149.0f), span);
            int y = 24 + Math.floorMod(i * 83 + 37, Math.max(1, this.height - 48));
            float pulse = 0.5f + 0.5f * Mth.sin((float)(time * 2.3f + (float)i * 1.9f));
            int size = 1 + (i % 3 == 0 ? 1 : 0);
            g.fill((int)x, y, (int)x + size, y + size, GameEndTransitionScreen.withAlpha(i % 4 == 0 ? -7776 : -2511271, Math.round(70.0f * pulse * strength)));
        }
    }

    private void renderSlideEdge(GuiGraphics g, float edgeX, float localProgress, int featherDir) {
        if (edgeX < 0.0f || edgeX > (float)this.width) {
            return;
        }
        float velocity = Mth.sin((float)(Mth.clamp((float)localProgress, (float)0.0f, (float)1.0f) * (float)Math.PI));
        for (int i = 1; i <= 26; ++i) {
            int x = Mth.clamp((int)(Math.round(edgeX) + featherDir * i), (int)0, (int)(this.width - 1));
            float strength = 1.0f - (float)i / 26.0f;
            g.fill(x, 0, x + 1, this.height, GameEndTransitionScreen.withAlpha(-15068912, Math.round(150.0f * strength * (0.25f + 0.75f * velocity))));
        }
        int coreX = Mth.clamp((int)Math.round(edgeX), (int)0, (int)Math.max(0, this.width - 1));
        g.fill(coreX - 1, 0, Math.min(this.width, coreX + 1), this.height, GameEndTransitionScreen.withAlpha(-7776, Math.round(150.0f + 100.0f * velocity)));
        if (featherDir > 0) {
            g.fill(coreX - 3, 0, coreX - 1, this.height, GameEndTransitionScreen.withAlpha(-2511271, Math.round(70.0f * velocity)));
        } else {
            g.fill(coreX + 1, 0, Math.min(this.width, coreX + 3), this.height, GameEndTransitionScreen.withAlpha(-2511271, Math.round(70.0f * velocity)));
        }
    }

    private void drawEmblem(GuiGraphics g, int cx, int cy, int alpha, float breathe) {
        if (alpha <= 0) {
            return;
        }
        int ringR = 24;
        for (int i = 0; i < 40; ++i) {
            double a = (double)i * Math.PI * 2.0 / 40.0;
            int x = cx + (int)Math.round(Math.cos(a) * (double)ringR);
            int y = cy + (int)Math.round(Math.sin(a) * (double)ringR);
            g.fill(x, y, x + 1, y + 1, GameEndTransitionScreen.withAlpha(-7444434, Math.round((float)alpha * 0.55f)));
        }
        GameEndTransitionScreen.drawDiamond(g, cx, cy, 15, GameEndTransitionScreen.withAlpha(-7444434, alpha));
        GameEndTransitionScreen.drawDiamond(g, cx, cy, 11, GameEndTransitionScreen.withAlpha(-2511271, alpha));
        GameEndTransitionScreen.drawDiamond(g, cx, cy, 6, GameEndTransitionScreen.withAlpha(-7776, alpha));
        GameEndTransitionScreen.drawDiamond(g, cx, cy, 2, GameEndTransitionScreen.withAlpha(-529457, Math.round((float)alpha * (0.6f + 0.4f * breathe))));
    }

    private void completeTransition() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == this && !this.completed) {
            this.completed = true;
            GameEndOverlayState.scheduleGrace((long)4000L);
            mc.setScreen(null);
        }
    }

    public void removed() {
        super.removed();
        if (!this.completed && GameEndOverlayState.isActive()) {
            GameEndOverlayState.scheduleGrace((long)0L);
        }
        this.previewPlayers.clear();
        this.previewLevel = null;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.exitStarted) {
                this.completeTransition();
            } else {
                this.startExit(Util.getMillis());
            }
            return true;
        }
        return true;
    }

    public boolean shouldCloseOnEsc() {
        return false;
    }

    public boolean isPauseScreen() {
        return false;
    }

    private float sweepProgress() {
        return Mth.clamp((float)((float)(Util.getMillis() - this.startedAtMillis) / 1150.0f), (float)0.0f, (float)1.0f);
    }

    private float exitProgress() {
        if (!this.exitStarted) {
            return 0.0f;
        }
        return Mth.clamp((float)((float)(Util.getMillis() - this.exitStartAtMillis) / 900.0f), (float)0.0f, (float)1.0f);
    }

    private void drawScaledCentered(GuiGraphics g, Component text, float x, float y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0f);
        g.pose().scale(scale, scale, 1.0f);
        g.drawCenteredString(this.font, text, 0, 0, color);
        g.pose().popPose();
    }

    private static void drawDiamond(GuiGraphics g, int centerX, int centerY, int radius, int color) {
        for (int dy = -radius; dy <= radius; ++dy) {
            int halfWidth = radius - Math.abs(dy);
            g.fill(centerX - halfWidth, centerY + dy, centerX + halfWidth + 1, centerY + dy + 1, color);
        }
    }

    private static float easeInOutCubic(float value) {
        float t = Mth.clamp((float)value, (float)0.0f, (float)1.0f);
        return t < 0.5f ? 4.0f * t * t * t : 1.0f - (float)Math.pow(-2.0f * t + 2.0f, 3.0) / 2.0f;
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0f - value;
        return 1.0f - inverse * inverse * inverse;
    }

    private static float easeOutBack(float value) {
        float t = Mth.clamp((float)value, (float)0.0f, (float)1.0f);
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        return 1.0f + c3 * (float)Math.pow(t - 1.0f, 3.0) + c1 * (float)Math.pow(t - 1.0f, 2.0);
    }

    private static int withAlpha(int color, int alpha) {
        return Mth.clamp((int)alpha, (int)0, (int)255) << 24 | color & 0xFFFFFF;
    }

    private static String shortOptionId(String optionId) {
        if (optionId == null) {
            return "";
        }
        int split = optionId.lastIndexOf(58);
        return split >= 0 && split + 1 < optionId.length() ? optionId.substring(split + 1) : optionId;
    }
}
