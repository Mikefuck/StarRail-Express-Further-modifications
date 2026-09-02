package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.PillToggle;
import com.habitrain.core.client.gui.menu.ui.ScrollArea;
import com.habitrain.core.client.gui.menu.ui.SectionHeader;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.SceneMotionSettings;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.client.SceneRenderRuntime;
import com.habitrain.core.scene.model.*;
import com.habitrain.core.scene.network.SceneAssetBuildRequestC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 移动场景配置页面（位于游戏内二级导航），支持多地图场景参数、循环参数、音效、微震与实时预览/资产构建。
 */
public class SceneMotionPage implements ConfigPage {
    private static final Logger LOGGER = LoggerFactory.getLogger(SceneMotionPage.class.getSimpleName());
    private static final int FIELD_HEIGHT = 18;
    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_HEIGHT = 18;
    private static final int PAGE_PADDING = 8;
    private static final int NARROW_WIDTH = 430;

    private static final DirectionPreset[] DIRECTION_PRESETS = {
            new DirectionPreset("+X", 1.0, 0.0, 0.0),
            new DirectionPreset("-X", -1.0, 0.0, 0.0),
            new DirectionPreset("+Y", 0.0, 1.0, 0.0),
            new DirectionPreset("-Y", 0.0, -1.0, 0.0),
            new DirectionPreset("+Z", 0.0, 0.0, 1.0),
            new DirectionPreset("-Z", 0.0, 0.0, -1.0)
    };

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;

    private String selectedMapKey = "__default__";
    private final Map<String, SceneProfileDraft> drafts = new LinkedHashMap<>();

    private final ScrollArea scroll = new ScrollArea(0, 0, 100, 100);
    private boolean widgetsInitialized = false;

    // UI 输入框
    private EditBox speedField;
    private EditBox dirXField, dirYField, dirZField;
    private EditBox originXField, originYField, originZField;
    private EditBox pivotXField, pivotYField, pivotZField;
    private EditBox rotYawField, rotPitchField, rotRollField;
    private EditBox phaseOffsetField;
    private EditBox loopDistField;
    private EditBox renderMaxDistField;
    private EditBox soundIdField, soundVolField, soundPitchField;
    private EditBox shakeFreqField, shakeAmpField, shakeRotAmpField;
    private final List<EditBox> inputFields = new ArrayList<>();

    // 开关状态
    private boolean enabledState = false;
    private boolean loopState = true;
    private boolean translucentState = true;
    private boolean soundState = true;
    private boolean shakeState = false;

    private boolean previewActive = false;
    private SceneBounds currentSessionBounds = SceneBounds.EMPTY;
    private int renderedContentHeight = 650;

    private HitBox previousMapHit = HitBox.EMPTY;
    private HitBox nextMapHit = HitBox.EMPTY;
    private HitBox previewHit = HitBox.EMPTY;
    private HitBox buildAssetHit = HitBox.EMPTY;
    private HitBox enabledHit = HitBox.EMPTY;
    private HitBox applySelectionHit = HitBox.EMPTY;
    private HitBox originPlacementHit = HitBox.EMPTY;
    private HitBox loopHit = HitBox.EMPTY;
    private HitBox applyRecommendedHit = HitBox.EMPTY;
    private HitBox translucentHit = HitBox.EMPTY;
    private HitBox soundHit = HitBox.EMPTY;
    private HitBox shakeHit = HitBox.EMPTY;
    private final HitBox[] directionPresetHits = new HitBox[DIRECTION_PRESETS.length];

    private record DirectionPreset(String label, double x, double y, double z) {}

    private record HitBox(int x, int y, int width, int height) {
        private static final HitBox EMPTY = new HitBox(0, 0, 0, 0);

        boolean contains(double mx, double my) {
            return width > 0 && height > 0 && MenuTheme.inBounds(mx, my, x, y, width, height);
        }
    }

    public SceneMotionPage(ConfigMenuScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        loadDraftsFromConfig();
    }

    public void setSessionData(String mapKey, SceneProfile profile, SceneBounds selection, SceneAssetDescriptor descriptor) {
        if (mapKey != null && !mapKey.isBlank()) {
            this.selectedMapKey = mapKey;
            syncToolMapSelection(mapKey);
        }
        if (profile != null) {
            drafts.put(selectedMapKey, new SceneProfileDraft(profile));
        }
        if (selection != null) {
            this.currentSessionBounds = selection;
        }
        if (descriptor != null) {
            SceneRenderRuntime.getInstance().acceptManifest(selectedMapKey, descriptor);
        }
        SceneRenderRuntime runtime = SceneRenderRuntime.getInstance();
        if (runtime.isPreviewActive() && !runtime.isPreviewActiveFor(selectedMapKey)) {
            // The editor is opening a different map; never leave an invisible old-map preview running.
            com.habitrain.core.scene.client.SceneClientRuntime.stopPreview();
        }
        previewActive = runtime.isPreviewActiveFor(selectedMapKey);
        if (widgetsInitialized) {
            populateFieldsFromDraft();
        }
    }

    private void loadDraftsFromConfig() {
        SceneMotionSettings settings = ConfigManager.getInstance().getSceneMotionSettings();
        drafts.clear();
        for (Map.Entry<String, SceneProfile> entry : settings.profiles.entrySet()) {
            drafts.put(entry.getKey(), new SceneProfileDraft(entry.getValue()));
        }
        if (!drafts.containsKey("__default__")) {
            drafts.put("__default__", new SceneProfileDraft(settings.getProfile("__default__")));
        }
        // The vote configuration is the authoritative list of selectable SRE maps in Mod Menu.
        // Seed a draft for every voted map so admins can configure it before any scene asset exists.
        SceneProfile defaultProfile = settings.getProfile(SceneMotionSettings.DEFAULT_MAP_KEY);
        for (String mapId : ConfigManager.getInstance().getModeMapVoteSettings().maps.keySet()) {
            if (mapId != null && !mapId.isBlank()) {
                drafts.computeIfAbsent(mapId, key -> new SceneProfileDraft(defaultProfile.copy()));
            }
        }
    }

    private SceneProfileDraft getCurrentDraft() {
        return drafts.computeIfAbsent(selectedMapKey, k -> new SceneProfileDraft());
    }

    private List<String> availableMapKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(SceneMotionSettings.DEFAULT_MAP_KEY);
        keys.addAll(ConfigManager.getInstance().getModeMapVoteSettings().maps.keySet());
        keys.addAll(drafts.keySet());
        keys.removeIf(key -> key == null || key.isBlank());
        return new ArrayList<>(keys);
    }

    private void switchMap(int delta) {
        List<String> keys = availableMapKeys();
        if (keys.size() <= 1 || delta == 0) return;
        flushPending();
        if (SceneRenderRuntime.getInstance().isPreviewActive()) {
            previewActive = false;
            com.habitrain.core.scene.client.SceneClientRuntime.stopPreview();
        }
        int current = keys.indexOf(selectedMapKey);
        if (current < 0) current = 0;
        selectedMapKey = keys.get(Math.floorMod(current + delta, keys.size()));
        syncToolMapSelection(selectedMapKey);
        SceneMotionSettings settings = ConfigManager.getInstance().getSceneMotionSettings();
        drafts.computeIfAbsent(selectedMapKey,
                key -> new SceneProfileDraft(settings.getProfile(SceneMotionSettings.DEFAULT_MAP_KEY).copy()));
        populateFieldsFromDraft();
    }

    private void syncToolMapSelection(String mapKey) {
        if (com.habitrain.core.client.gui.menu.ConfigUpdateContext.currentScope()
                != com.habitrain.core.network.ConfigUpdateScope.ADMIN_SCENE_TOOL) {
            return;
        }
        com.habitrain.core.client.gui.menu.ConfigUpdateContext.setCurrentSceneMapKey(mapKey);
        com.habitrain.core.scene.client.SceneToolHud.getInstance().updateMapKey(mapKey);
        if (ClientPlayNetworking.canSend(com.habitrain.core.scene.network.SceneToolMapSelectC2S.TYPE)) {
            ClientPlayNetworking.send(new com.habitrain.core.scene.network.SceneToolMapSelectC2S(mapKey));
        }
    }

    private String selectedMapLabel() {
        if (SceneMotionSettings.DEFAULT_MAP_KEY.equals(selectedMapKey)) {
            return tr("screen.habitrain_core.scene_motion.map_default");
        }
        MapVoteEntry entry = ConfigManager.getInstance().getModeMapVoteSettings().maps.get(selectedMapKey);
        String displayName = entry != null ? entry.displayName : null;
        if (displayName == null || displayName.isBlank() || displayName.equals(selectedMapKey)) {
            return selectedMapKey;
        }
        return displayName.trim() + " (" + selectedMapKey + ")";
    }

    private void initWidgets() {
        speedField = field("speed");
        dirXField = field("direction_x");
        dirYField = field("direction_y");
        dirZField = field("direction_z");

        originXField = field("origin_x");
        originYField = field("origin_y");
        originZField = field("origin_z");

        pivotXField = field("pivot_x");
        pivotYField = field("pivot_y");
        pivotZField = field("pivot_z");

        rotYawField = field("rotation_yaw");
        rotPitchField = field("rotation_pitch");
        rotRollField = field("rotation_roll");

        phaseOffsetField = field("phase_offset");
        loopDistField = field("loop_distance");
        renderMaxDistField = field("render_distance");

        soundIdField = new EditBox(font, 0, 0, 160, FIELD_HEIGHT,
                Component.translatable("screen.habitrain_core.scene_motion.sound_id"));
        // EditBox 默认只允许 32 个字符，会把默认 ID
        // "habitrain_core:moving_scene_outside" 静默截断为不存在的音效。
        soundIdField.setMaxLength(256);
        soundVolField = field("sound_volume");
        soundPitchField = field("sound_pitch");

        shakeFreqField = field("shake_frequency");
        shakeAmpField = field("shake_translation");
        shakeRotAmpField = field("shake_rotation");

        inputFields.clear();
        Collections.addAll(inputFields,
                speedField, dirXField, dirYField, dirZField,
                originXField, originYField, originZField,
                pivotXField, pivotYField, pivotZField,
                rotYawField, rotPitchField, rotRollField,
                phaseOffsetField, loopDistField, renderMaxDistField,
                soundIdField, soundVolField, soundPitchField,
                shakeFreqField, shakeAmpField, shakeRotAmpField);
        for (EditBox field : inputFields) {
            field.setEditable(editable);
            if (field != soundIdField) {
                field.setMaxLength(32);
                field.setFilter(SceneMotionPage::isPotentialDecimal);
            }
        }

        populateFieldsFromDraft();
        widgetsInitialized = true;
    }

    private EditBox field(String suffix) {
        return new EditBox(font, 0, 0, 64, FIELD_HEIGHT,
                Component.translatable("screen.habitrain_core.scene_motion." + suffix));
    }

    private static boolean isPotentialDecimal(String value) {
        return value == null || value.isEmpty() || value.matches("[-+]?((\\d+(\\.\\d*)?)|(\\.\\d*))?");
    }

    private void populateFieldsFromDraft() {
        SceneProfileDraft d = getCurrentDraft();
        enabledState = d.isEnabled();
        speedField.setValue(String.format(Locale.ROOT, "%.2f", d.getSpeedBlocksPerSecond()));

        double[] dir = d.getDirection();
        dirXField.setValue(String.format(Locale.ROOT, "%.2f", dir[0]));
        dirYField.setValue(String.format(Locale.ROOT, "%.2f", dir[1]));
        dirZField.setValue(String.format(Locale.ROOT, "%.2f", dir[2]));

        double[] origin = d.getDisplayOrigin();
        originXField.setValue(String.format(Locale.ROOT, "%.1f", origin[0]));
        originYField.setValue(String.format(Locale.ROOT, "%.1f", origin[1]));
        originZField.setValue(String.format(Locale.ROOT, "%.1f", origin[2]));

        double[] pivot = d.getPivotLocal();
        pivotXField.setValue(String.format(Locale.ROOT, "%.1f", pivot[0]));
        pivotYField.setValue(String.format(Locale.ROOT, "%.1f", pivot[1]));
        pivotZField.setValue(String.format(Locale.ROOT, "%.1f", pivot[2]));

        SceneRotation rot = d.getRotationDegrees();
        rotYawField.setValue(String.format(Locale.ROOT, "%.1f", rot.yawDegrees()));
        rotPitchField.setValue(String.format(Locale.ROOT, "%.1f", rot.pitchDegrees()));
        rotRollField.setValue(String.format(Locale.ROOT, "%.1f", rot.rollDegrees()));

        phaseOffsetField.setValue(String.format(Locale.ROOT, "%.1f", d.getPhaseOffsetBlocks()));

        loopState = d.getLoop().isEnabled();
        loopDistField.setValue(String.format(Locale.ROOT, "%.1f", d.getLoop().getDistanceBlocks()));

        translucentState = d.getRender().isRenderTranslucent();
        renderMaxDistField.setValue(String.valueOf(d.getRender().getMaxDistanceBlocks()));

        soundIdField.setValue(d.getOutsideSound().getSoundId());
        soundState = d.getOutsideSound().isEnabled();
        soundVolField.setValue(String.format(Locale.ROOT, "%.2f", d.getOutsideSound().getVolume()));
        soundPitchField.setValue(String.format(Locale.ROOT, "%.2f", d.getOutsideSound().getPitch()));

        shakeState = d.getShake().isEnabled();
        shakeFreqField.setValue(String.format(Locale.ROOT, "%.1f", d.getShake().getFrequencyHz()));
        shakeAmpField.setValue(String.format(Locale.ROOT, "%.3f", d.getShake().getTranslationAmplitudeBlocks()));
        shakeRotAmpField.setValue(String.format(Locale.ROOT, "%.2f", d.getShake().getRotationAmplitudeDegrees()));
    }

    @Override
    public void flushPending() {
        if (!widgetsInitialized) return;
        SceneProfileDraft d = getCurrentDraft();
        d.setEnabled(enabledState);
        d.setSpeedBlocksPerSecond(parseDouble(speedField.getValue(), d.getSpeedBlocksPerSecond()));

        double[] previousDirection = d.getDirection();
        d.setDirection(
                parseDouble(dirXField.getValue(), previousDirection[0]),
                parseDouble(dirYField.getValue(), previousDirection[1]),
                parseDouble(dirZField.getValue(), previousDirection[2])
        );

        d.setDisplayOrigin(
                parseDouble(originXField.getValue(), 0.0),
                parseDouble(originYField.getValue(), 64.0),
                parseDouble(originZField.getValue(), 0.0)
        );

        d.setPivotLocal(
                parseDouble(pivotXField.getValue(), 0.0),
                parseDouble(pivotYField.getValue(), 0.0),
                parseDouble(pivotZField.getValue(), 0.0)
        );

        d.setRotationDegrees(new SceneRotation(
                (float) parseDouble(rotYawField.getValue(), 0.0),
                (float) parseDouble(rotPitchField.getValue(), 0.0),
                (float) parseDouble(rotRollField.getValue(), 0.0)
        ));

        d.setPhaseOffsetBlocks(parseDouble(phaseOffsetField.getValue(), 0.0));

        double configuredDistance = parseDouble(
                loopDistField.getValue(), d.getLoop().getDistanceBlocks());
        d.setLoop(new SceneLoopSettings(loopState, configuredDistance, 2));

        d.setRender(new SceneRenderSettings(
                parseDouble(renderMaxDistField.getValue(), 160.0),
                translucentState
        ));

        d.setOutsideSound(new SceneSoundSettings(
                soundState,
                soundIdField.getValue().trim(),
                (float) parseDouble(soundVolField.getValue(), 1.0),
                (float) parseDouble(soundPitchField.getValue(), 1.0),
                20
        ));

        d.setShake(new SceneShakeSettings(
                shakeState,
                parseDouble(shakeAmpField.getValue(), 0.03),
                parseDouble(shakeRotAmpField.getValue(), 0.15),
                parseDouble(shakeFreqField.getValue(), 4.0)
        ));
    }

    private SceneMotionMath.LoopDistances loopDistancesFromFields() {
        SceneProfileDraft draft = getCurrentDraft();
        double[] direction = directionFromFields(draft.getDirection());
        double configured = parseDouble(loopDistField.getValue(), draft.getLoop().getDistanceBlocks());
        return SceneMotionMath.resolveLoopDistances(draft.getSourceBounds(), direction, configured);
    }

    private double[] directionFromFields(double[] fallback) {
        double x = parseDouble(dirXField.getValue(), fallback[0]);
        double y = parseDouble(dirYField.getValue(), fallback[1]);
        double z = parseDouble(dirZField.getValue(), fallback[2]);
        double length = Math.sqrt(x * x + y * y + z * z);
        if (!Double.isFinite(length) || length < 1.0e-4) {
            return new double[]{-1.0, 0.0, 0.0};
        }
        return new double[]{x / length, y / length, z / length};
    }

    private void refreshFieldAvailability() {
        loopDistField.setEditable(editable && loopState);
        soundIdField.setEditable(editable && soundState);
        soundVolField.setEditable(editable && soundState);
        soundPitchField.setEditable(editable && soundState);
        shakeFreqField.setEditable(editable && shakeState);
        shakeAmpField.setEditable(editable && shakeState);
        shakeRotAmpField.setEditable(editable && shakeState);
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    @Override
    public void save() {
        flushPending();
        SceneMotionSettings settings = ConfigManager.getInstance().getSceneMotionSettings();
        for (Map.Entry<String, SceneProfileDraft> e : drafts.entrySet()) {
            settings.profiles.put(e.getKey(), e.getValue().toProfile());
        }
        SceneRenderRuntime.getInstance().updatePreviewProfile(selectedMapKey, getCurrentDraft().toProfile());
        ConfigManager.getInstance().markSceneMotionDirty();
        LOGGER.info("已保存移动场景配置: 共 {} 个地图配置", drafts.size());
    }

    @Override
    public boolean canSave() {
        return editable;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        if (!widgetsInitialized) initWidgets();
        // Screen instances are disposable; the renderer owns the actual preview-session state.
        previewActive = SceneRenderRuntime.getInstance().isPreviewActiveFor(selectedMapKey);
        refreshFieldAvailability();

        boolean narrow = w < NARROW_WIDTH;
        int topY = y + 4;
        boolean multipleMaps = availableMapKeys().size() > 1;
        previousMapHit = new HitBox(x + 6, topY, 18, BUTTON_HEIGHT);
        nextMapHit = new HitBox(x + 28, topY, 18, BUTTON_HEIGHT);
        renderMapArrow(g, mx, my, previousMapHit, "<", multipleMaps);
        renderMapArrow(g, mx, my, nextMapHit, ">", multipleMaps);

        int actionY = narrow ? topY + BUTTON_HEIGHT + 4 : topY;
        int previewW = narrow ? Math.max(30, (w - 16) / 2) : 82;
        int buildW = narrow ? previewW : 94;
        int actionX = x + w - previewW - buildW - 12;
        previewHit = new HitBox(actionX, actionY, previewW, BUTTON_HEIGHT);
        buildAssetHit = new HitBox(actionX + previewW + 4, actionY, buildW, BUTTON_HEIGHT);

        SceneAssetDescriptor currentDescriptor = currentAssetDescriptor();
        boolean hasAsset = currentDescriptor.isValid();
        boolean previewEnabled = previewActive || hasAsset;
        boolean buildEnabled = editable && ClientPlayNetworking.canSend(SceneAssetBuildRequestC2S.TYPE);
        renderButton(g, previewHit,
                tr(previewActive
                        ? "screen.habitrain_core.scene_motion.preview_on"
                        : "screen.habitrain_core.scene_motion.preview"),
                MenuTheme.ACCENT_MINT, previewEnabled, previewActive, mx, my);
        renderButton(g, buildAssetHit,
                tr(hasAsset
                        ? "screen.habitrain_core.scene_motion.asset_rebuild"
                        : "screen.habitrain_core.scene_motion.asset_missing"),
                hasAsset ? MenuTheme.ACCENT_BLUE : MenuTheme.DANGER,
                buildEnabled, false, mx, my);

        int mapTextX = x + 52;
        int mapTextRight = narrow ? x + w - 6 : actionX - 6;
        String mapText = tr("screen.habitrain_core.scene_motion.map", selectedMapLabel());
        g.drawString(font, fit(mapText, Math.max(20, mapTextRight - mapTextX)),
                mapTextX, topY + 5, MenuTheme.TEXT_PRIMARY, false);

        int contentTop = actionY + BUTTON_HEIGHT + 5;
        int contentViewportHeight = Math.max(1, y + h - contentTop);

        scroll.setBounds(x, contentTop, w, contentViewportHeight);
        scroll.setContentHeight(Math.max(renderedContentHeight, contentViewportHeight));

        g.enableScissor(x + 2, contentTop, x + w - 7, contentTop + contentViewportHeight);
        int innerWidth = Math.max(1, Math.min(620, w - PAGE_PADDING * 2 - 6));
        int innerX = x + (w - innerWidth) / 2 - 2;
        int sy = scroll.getContentY() + 4;
        int contentStartY = sy;

        sy += drawWrapped(g, Component.translatable("screen.habitrain_core.scene_motion.intro"),
                innerX + 4, sy, innerWidth - 8, MenuTheme.TEXT_SECONDARY) + 7;

        sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                tr("screen.habitrain_core.scene_motion.section_runtime"), MenuTheme.ACCENT_MINT);
        int toggleWidth = Math.min(Math.max(1, innerWidth - 8),
                Math.min(136, Math.max(64, innerWidth / 3)));
        enabledHit = new HitBox(innerX + 4, sy, toggleWidth, FIELD_HEIGHT);
        PillToggle.render(g, font, enabledHit.x(), enabledHit.y(), enabledHit.width(), enabledHit.height(),
                enabledState, tr("screen.habitrain_core.scene_motion.enabled"),
                tr("screen.habitrain_core.scene_motion.disabled"));
        int speedLabelX = enabledHit.x() + enabledHit.width() + 12;
        int speedFieldWidth = Math.min(72, Math.max(36, innerWidth / 4));
        int speedX = innerX + innerWidth - speedFieldWidth - 4;
        g.drawString(font, fit(tr("screen.habitrain_core.scene_motion.speed"),
                        Math.max(0, speedX - speedLabelX - 4)),
                speedLabelX, sy + 5, MenuTheme.TEXT_SECONDARY, false);
        placeField(speedField, speedX, sy, speedFieldWidth, mx, my, delta, g);
        sy += ROW_HEIGHT + 2;

        SceneBounds bounds = getCurrentDraft().getSourceBounds();
        String boundsText = bounds.isEmpty()
                ? tr("screen.habitrain_core.scene_motion.bounds_missing")
                : tr("screen.habitrain_core.scene_motion.bounds_value",
                bounds.sizeX(), bounds.sizeY(), bounds.sizeZ(), bounds.totalSections());
        int selectionButtonWidth = currentSessionBounds.isEmpty()
                ? 0 : Math.min(Math.max(1, innerWidth - 8), Math.min(138, Math.max(64, innerWidth / 3)));
        int boundsTextWidth = innerWidth - selectionButtonWidth - (selectionButtonWidth > 0 ? 16 : 8);
        g.drawString(font, fit(tr("screen.habitrain_core.scene_motion.bounds", boundsText), boundsTextWidth),
                innerX + 4, sy + 5, bounds.isEmpty() ? MenuTheme.DANGER : MenuTheme.TEXT_PRIMARY, false);
        if (!currentSessionBounds.isEmpty()) {
            applySelectionHit = new HitBox(innerX + innerWidth - selectionButtonWidth, sy,
                    selectionButtonWidth - 4, BUTTON_HEIGHT);
            renderButton(g, applySelectionHit,
                    tr("screen.habitrain_core.scene_motion.use_selection"),
                    MenuTheme.ACCENT_VIOLET, editable, false, mx, my);
        } else {
            applySelectionHit = HitBox.EMPTY;
        }
        sy += ROW_HEIGHT + 8;

        sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                tr("screen.habitrain_core.scene_motion.section_direction"), MenuTheme.ACCENT_BLUE);
        sy += drawWrapped(g, Component.translatable("screen.habitrain_core.scene_motion.direction_help"),
                innerX + 4, sy, innerWidth - 8, MenuTheme.TEXT_SECONDARY) + 4;
        sy = renderTripleFields(g, tr("screen.habitrain_core.scene_motion.direction_ratio"),
                new String[]{"X", "Y", "Z"}, dirXField, dirYField, dirZField,
                innerX, sy, innerWidth, narrow, mx, my, delta);

        int presetLabelWidth = narrow ? 0 : Math.min(102,
                font.width(tr("screen.habitrain_core.scene_motion.direction_quick")) + 8);
        if (narrow) {
            g.drawString(font, tr("screen.habitrain_core.scene_motion.direction_quick"),
                    innerX + 4, sy + 5, MenuTheme.TEXT_SECONDARY, false);
            sy += ROW_HEIGHT;
        } else {
            g.drawString(font, tr("screen.habitrain_core.scene_motion.direction_quick"),
                    innerX + 4, sy + 5, MenuTheme.TEXT_SECONDARY, false);
        }
        int presetStartX = innerX + 4 + presetLabelWidth;
        int presetAvailable = innerWidth - 8 - presetLabelWidth;
        int presetGap = 4;
        int presetWidth = Math.max(26,
                (presetAvailable - presetGap * (DIRECTION_PRESETS.length - 1)) / DIRECTION_PRESETS.length);
        double[] normalizedDirection = directionFromFields(getCurrentDraft().getDirection());
        for (int i = 0; i < DIRECTION_PRESETS.length; i++) {
            DirectionPreset preset = DIRECTION_PRESETS[i];
            directionPresetHits[i] = new HitBox(
                    presetStartX + i * (presetWidth + presetGap), sy, presetWidth, BUTTON_HEIGHT);
            boolean active = sameDirection(normalizedDirection, preset);
            renderButton(g, directionPresetHits[i], preset.label(), MenuTheme.ACCENT_BLUE,
                    editable, active, mx, my);
        }
        sy += ROW_HEIGHT;
        String normalizedText = tr("screen.habitrain_core.scene_motion.direction_effective",
                format(normalizedDirection[0]), format(normalizedDirection[1]), format(normalizedDirection[2]));
        g.drawString(font, fit(normalizedText, innerWidth - 8), innerX + 4, sy + 3,
                MenuTheme.ACCENT_BLUE, false);
        sy += ROW_HEIGHT + 6;

        sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                tr("screen.habitrain_core.scene_motion.section_transform"), MenuTheme.ACCENT_AMBER);
        sy = renderTripleFields(g, tr("screen.habitrain_core.scene_motion.origin"),
                new String[]{"X", "Y", "Z"}, originXField, originYField, originZField,
                innerX, sy, innerWidth, narrow, mx, my, delta);
        boolean originPickEnabled = editable && !bounds.isEmpty();
        int originButtonWidth = Math.min(Math.max(1, innerWidth - 8),
                Math.min(150, Math.max(68, innerWidth / 3)));
        originPlacementHit = new HitBox(innerX + innerWidth - originButtonWidth, sy,
                originButtonWidth - 4, BUTTON_HEIGHT);
        renderButton(g, originPlacementHit,
                tr(originPickEnabled
                        ? "screen.habitrain_core.scene_motion.origin_pick"
                        : "screen.habitrain_core.scene_motion.origin_needs_bounds"),
                MenuTheme.ACCENT_AMBER, originPickEnabled, false, mx, my);
        sy += ROW_HEIGHT + 2;
        sy = renderTripleFields(g, tr("screen.habitrain_core.scene_motion.pivot"),
                new String[]{"X", "Y", "Z"}, pivotXField, pivotYField, pivotZField,
                innerX, sy, innerWidth, narrow, mx, my, delta);
        sy = renderTripleFields(g, tr("screen.habitrain_core.scene_motion.rotation"),
                new String[]{tr("screen.habitrain_core.scene_motion.yaw"),
                        tr("screen.habitrain_core.scene_motion.pitch"),
                        tr("screen.habitrain_core.scene_motion.roll")},
                rotYawField, rotPitchField, rotRollField,
                innerX, sy, innerWidth, narrow, mx, my, delta);
        sy = renderSingleFieldRow(g, tr("screen.habitrain_core.scene_motion.phase_offset"),
                phaseOffsetField, innerX, sy, innerWidth, mx, my, delta);
        sy += 6;

        sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                tr("screen.habitrain_core.scene_motion.section_loop"), MenuTheme.ACCENT_VIOLET);
        loopHit = new HitBox(innerX + 4, sy, toggleWidth, FIELD_HEIGHT);
        PillToggle.render(g, font, loopHit.x(), loopHit.y(), loopHit.width(), loopHit.height(), loopState,
                tr("screen.habitrain_core.scene_motion.loop_on"),
                tr("screen.habitrain_core.scene_motion.loop_off"));
        int loopLabelX = loopHit.x() + loopHit.width() + 12;
        int loopFieldWidth = Math.min(72, Math.max(36, innerWidth / 4));
        int loopFieldX = innerX + innerWidth - loopFieldWidth - 4;
        g.drawString(font, fit(tr("screen.habitrain_core.scene_motion.loop_configured"),
                        Math.max(0, loopFieldX - loopLabelX - 4)),
                loopLabelX, sy + 5, MenuTheme.TEXT_SECONDARY, false);
        placeField(loopDistField, loopFieldX, sy, loopFieldWidth, mx, my, delta, g);
        sy += ROW_HEIGHT;

        SceneMotionMath.LoopDistances loopDistances = loopDistancesFromFields();
        boolean hasBounds = !bounds.isEmpty();
        String distanceSummary = hasBounds
                ? tr("screen.habitrain_core.scene_motion.loop_summary",
                format(loopDistances.configuredDistance()), format(loopDistances.recommendedDistance()),
                format(loopDistances.effectiveDistance()))
                : tr("screen.habitrain_core.scene_motion.loop_summary_no_bounds");
        int recommendedButtonWidth = Math.min(Math.max(1, innerWidth - 8),
                Math.min(132, Math.max(68, innerWidth / 3)));
        int summaryWidth = innerWidth - recommendedButtonWidth - 16;
        int summaryColor = hasBounds
                && loopDistances.effectiveDistance() + 1.0e-6 < loopDistances.configuredDistance()
                ? MenuTheme.ACCENT_AMBER : MenuTheme.TEXT_SECONDARY;
        g.drawString(font, fit(distanceSummary, summaryWidth), innerX + 4, sy + 5, summaryColor, false);
        applyRecommendedHit = new HitBox(innerX + innerWidth - recommendedButtonWidth, sy,
                recommendedButtonWidth - 4, BUTTON_HEIGHT);
        renderButton(g, applyRecommendedHit,
                tr("screen.habitrain_core.scene_motion.loop_use_recommended"),
                MenuTheme.ACCENT_VIOLET, editable && loopState && hasBounds, false, mx, my);
        sy += ROW_HEIGHT;
        sy += drawWrapped(g, Component.translatable("screen.habitrain_core.scene_motion.loop_help"),
                innerX + 4, sy, innerWidth - 8, MenuTheme.TEXT_DIM) + 4;

        translucentHit = new HitBox(innerX + 4, sy, toggleWidth, FIELD_HEIGHT);
        PillToggle.render(g, font, translucentHit.x(), translucentHit.y(), translucentHit.width(),
                translucentHit.height(), translucentState,
                tr("screen.habitrain_core.scene_motion.translucent_on"),
                tr("screen.habitrain_core.scene_motion.translucent_off"));
        int renderLabelX = translucentHit.x() + translucentHit.width() + 12;
        int renderFieldWidth = Math.min(72, Math.max(36, innerWidth / 4));
        int renderFieldX = innerX + innerWidth - renderFieldWidth - 4;
        g.drawString(font, fit(tr("screen.habitrain_core.scene_motion.render_distance"),
                        Math.max(0, renderFieldX - renderLabelX - 4)),
                renderLabelX, sy + 5, MenuTheme.TEXT_SECONDARY, false);
        placeField(renderMaxDistField, renderFieldX, sy, renderFieldWidth, mx, my, delta, g);
        sy += ROW_HEIGHT + 8;

        sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                tr("screen.habitrain_core.scene_motion.section_sound"), MenuTheme.ACCENT_BLUE);
        soundHit = new HitBox(innerX + 4, sy, toggleWidth, FIELD_HEIGHT);
        PillToggle.render(g, font, soundHit.x(), soundHit.y(), soundHit.width(), soundHit.height(), soundState,
                tr("screen.habitrain_core.scene_motion.sound_on"),
                tr("screen.habitrain_core.scene_motion.sound_off"));
        int soundLabelX = soundHit.x() + soundHit.width() + 12;
        int soundFieldWidth = Math.min(220, Math.max(48, innerWidth / 3));
        int soundFieldX = innerX + innerWidth - soundFieldWidth - 4;
        g.drawString(font, fit(tr("screen.habitrain_core.scene_motion.sound_id"),
                        Math.max(0, soundFieldX - soundLabelX - 4)),
                soundLabelX, sy + 5, MenuTheme.TEXT_SECONDARY, false);
        placeField(soundIdField, soundFieldX, sy, soundFieldWidth, mx, my, delta, g);
        sy += ROW_HEIGHT;
        sy = renderDoubleFields(g,
                tr("screen.habitrain_core.scene_motion.sound_volume"), soundVolField,
                tr("screen.habitrain_core.scene_motion.sound_pitch"), soundPitchField,
                innerX, sy, innerWidth, mx, my, delta);
        sy += 8;

        sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                tr("screen.habitrain_core.scene_motion.section_shake"), MenuTheme.ACCENT_AMBER);
        shakeHit = new HitBox(innerX + 4, sy, toggleWidth, FIELD_HEIGHT);
        PillToggle.render(g, font, shakeHit.x(), shakeHit.y(), shakeHit.width(), shakeHit.height(), shakeState,
                tr("screen.habitrain_core.scene_motion.shake_on"),
                tr("screen.habitrain_core.scene_motion.shake_off"));
        int frequencyLabelX = shakeHit.x() + shakeHit.width() + 12;
        int frequencyFieldWidth = Math.min(72, Math.max(36, innerWidth / 4));
        int frequencyFieldX = innerX + innerWidth - frequencyFieldWidth - 4;
        g.drawString(font, fit(tr("screen.habitrain_core.scene_motion.shake_frequency"),
                        Math.max(0, frequencyFieldX - frequencyLabelX - 4)),
                frequencyLabelX, sy + 5, MenuTheme.TEXT_SECONDARY, false);
        placeField(shakeFreqField, frequencyFieldX, sy, frequencyFieldWidth, mx, my, delta, g);
        sy += ROW_HEIGHT;
        sy = renderDoubleFields(g,
                tr("screen.habitrain_core.scene_motion.shake_translation"), shakeAmpField,
                tr("screen.habitrain_core.scene_motion.shake_rotation"), shakeRotAmpField,
                innerX, sy, innerWidth, mx, my, delta);
        sy += 10;

        g.disableScissor();
        renderedContentHeight = Math.max(contentViewportHeight, sy - contentStartY + 8);
        scroll.setContentHeight(renderedContentHeight);
        scroll.render(g);
    }

    private int renderTripleFields(GuiGraphics g, String label, String[] axisLabels,
                                   EditBox first, EditBox second, EditBox third,
                                   int x, int y, int width, boolean narrow,
                                   int mx, int my, float delta) {
        int labelWidth = narrow ? 0 : Math.min(128, Math.max(92, font.width(label) + 10));
        if (narrow) {
            g.drawString(font, label, x + 4, y + 5, MenuTheme.TEXT_SECONDARY, false);
            y += ROW_HEIGHT;
        } else {
            g.drawString(font, fit(label, labelWidth - 6), x + 4, y + 5,
                    MenuTheme.TEXT_SECONDARY, false);
        }
        int fieldsX = x + 4 + labelWidth;
        int available = width - 8 - labelWidth;
        int gap = 7;
        int axisLabelWidth = 10;
        int fieldWidth = Math.max(20, (available - gap * 2 - axisLabelWidth * 3) / 3);
        EditBox[] fields = {first, second, third};
        for (int i = 0; i < fields.length; i++) {
            int axisX = fieldsX + i * (axisLabelWidth + fieldWidth + gap);
            g.drawString(font, fit(axisLabels[i], axisLabelWidth), axisX, y + 5,
                    MenuTheme.TEXT_DIM, false);
            placeField(fields[i], axisX + axisLabelWidth, y, fieldWidth, mx, my, delta, g);
        }
        return y + ROW_HEIGHT;
    }

    private int renderSingleFieldRow(GuiGraphics g, String label, EditBox field,
                                     int x, int y, int width, int mx, int my, float delta) {
        int fieldWidth = Math.min(96, Math.max(58, width / 4));
        g.drawString(font, fit(label, width - fieldWidth - 16), x + 4, y + 5,
                MenuTheme.TEXT_SECONDARY, false);
        placeField(field, x + width - fieldWidth - 4, y, fieldWidth, mx, my, delta, g);
        return y + ROW_HEIGHT;
    }

    private int renderDoubleFields(GuiGraphics g,
                                   String firstLabel, EditBox first,
                                   String secondLabel, EditBox second,
                                   int x, int y, int width, int mx, int my, float delta) {
        int half = (width - 8) / 2;
        int fieldWidth = Math.min(76, Math.max(28, half / 3));
        g.drawString(font, fit(firstLabel, half - fieldWidth - 8), x + 4, y + 5,
                MenuTheme.TEXT_SECONDARY, false);
        placeField(first, x + half - fieldWidth, y, fieldWidth, mx, my, delta, g);
        int secondX = x + half + 8;
        g.drawString(font, fit(secondLabel, half - fieldWidth - 12), secondX, y + 5,
                MenuTheme.TEXT_SECONDARY, false);
        placeField(second, x + width - fieldWidth - 4, y, fieldWidth, mx, my, delta, g);
        return y + ROW_HEIGHT;
    }

    private void placeField(EditBox field, int x, int y, int width,
                            int mx, int my, float delta, GuiGraphics g) {
        field.setX(x);
        field.setY(y);
        field.setWidth(Math.max(24, width));
        field.render(g, mx, my, delta);
    }

    private int drawWrapped(GuiGraphics g, Component text, int x, int y, int width, int color) {
        List<FormattedCharSequence> lines = font.split(text, Math.max(20, width));
        if (lines.isEmpty()) return 0;
        int lineY = y;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x, lineY, color, false);
            lineY += font.lineHeight + 2;
        }
        return lineY - y;
    }

    private void renderMapArrow(GuiGraphics g, int mx, int my, HitBox hit, String text, boolean enabled) {
        renderButton(g, hit, text, MenuTheme.ACCENT_BLUE, enabled, false, mx, my);
    }

    private void renderButton(GuiGraphics g, HitBox hit, String label, int accent,
                              boolean enabled, boolean selected, int mx, int my) {
        boolean hovered = enabled && hit.contains(mx, my);
        if (selected) {
            g.fill(hit.x(), hit.y(), hit.x() + hit.width(), hit.y() + hit.height(),
                    MenuTheme.withAlpha(accent, 0x35));
        }
        MenuTheme.button(g, font, fit(label, hit.width() - 8), hit.x(), hit.y(),
                hit.width(), hit.height(), accent, enabled, hovered || selected);
    }

    private boolean sameDirection(double[] direction, DirectionPreset preset) {
        return Math.abs(direction[0] - preset.x()) < 1.0e-4
                && Math.abs(direction[1] - preset.y()) < 1.0e-4
                && Math.abs(direction[2] - preset.z()) < 1.0e-4;
    }

    private String fit(String text, int width) {
        if (text == null || width <= 0) return "";
        if (font.width(text) <= width) return text;
        String ellipsis = "…";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ellipsis))) + ellipsis;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatDirectionInput(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0e-8) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return format(value);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn, int x, int y, int w, int h) {
        if (!widgetsInitialized) return false;

        boolean multipleMaps = availableMapKeys().size() > 1;
        if (multipleMaps && previousMapHit.contains(mx, my)) {
            switchMap(-1);
            return true;
        }
        if (multipleMaps && nextMapHit.contains(mx, my)) {
            switchMap(1);
            return true;
        }
        if (previewHit.contains(mx, my)
                && (previewActive || currentAssetDescriptor().isValid())) {
            togglePreview();
            return true;
        }
        if (buildAssetHit.contains(mx, my) && editable
                && ClientPlayNetworking.canSend(SceneAssetBuildRequestC2S.TYPE)) {
            requestBuildAsset();
            return true;
        }

        // EditBox instances are rendered manually rather than registered as Screen children.
        // Let the native widget handle the click before the scroll bar and clear every other
        // field, matching the event order used by the existing configuration pages.
        if (btn == 0 && scroll.isInside(mx, my)) {
            for (EditBox field : inputFields) {
                if (!MenuTheme.inBounds(mx, my, field.getX(), field.getY(),
                        field.getWidth(), field.getHeight())) {
                    continue;
                }
                for (EditBox other : inputFields) {
                    if (other != field) other.setFocused(false);
                }
                field.mouseClicked(mx, my, btn);
                field.setFocused(true);
                return true;
            }
            clearInputFocus();
        }

        if (scroll.mouseClicked(mx, my, btn)) {
            return true;
        }

        if (enabledHit.contains(mx, my) && editable) {
            enabledState = !enabledState;
            return true;
        }

        if (applySelectionHit.contains(mx, my) && editable && !currentSessionBounds.isEmpty()) {
            getCurrentDraft().setSourceBounds(currentSessionBounds);
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(Component.translatable(
                        "screen.habitrain_core.scene_motion.message_selection_applied",
                        currentSessionBounds.sizeX(), currentSessionBounds.sizeY(), currentSessionBounds.sizeZ()), true);
            }
            return true;
        }

        if (originPlacementHit.contains(mx, my) && editable
                && !getCurrentDraft().getSourceBounds().isEmpty()) {
            beginOriginPlacement();
            return true;
        }

        for (int i = 0; i < directionPresetHits.length; i++) {
            if (directionPresetHits[i] != null && directionPresetHits[i].contains(mx, my) && editable) {
                DirectionPreset preset = DIRECTION_PRESETS[i];
                dirXField.setValue(formatDirectionInput(preset.x()));
                dirYField.setValue(formatDirectionInput(preset.y()));
                dirZField.setValue(formatDirectionInput(preset.z()));
                return true;
            }
        }

        if (loopHit.contains(mx, my) && editable) {
            loopState = !loopState;
            refreshFieldAvailability();
            return true;
        }

        if (applyRecommendedHit.contains(mx, my) && editable && loopState
                && !getCurrentDraft().getSourceBounds().isEmpty()) {
            loopDistField.setValue(format(loopDistancesFromFields().recommendedDistance()));
            return true;
        }

        if (translucentHit.contains(mx, my) && editable) {
            translucentState = !translucentState;
            return true;
        }

        if (soundHit.contains(mx, my) && editable) {
            soundState = !soundState;
            refreshFieldAvailability();
            return true;
        }

        if (shakeHit.contains(mx, my) && editable) {
            shakeState = !shakeState;
            refreshFieldAvailability();
            return true;
        }

        return false;
    }

    private void clearInputFocus() {
        for (EditBox field : inputFields) {
            field.setFocused(false);
        }
    }

    private void beginOriginPlacement() {
        if (!editable) return;
        flushPending();
        SceneBounds sourceBounds = getCurrentDraft().getSourceBounds();
        var player = Minecraft.getInstance().player;
        if (sourceBounds.isEmpty()) {
            if (player != null) player.displayClientMessage(Component.translatable(
                    "screen.habitrain_core.scene_motion.message_origin_needs_bounds"), true);
            return;
        }
        com.habitrain.core.scene.client.SceneOriginPlacementController.getInstance()
                .start(root, this, sourceBounds);
        if (player != null) {
            player.displayClientMessage(Component.translatable(
                    "screen.habitrain_core.scene_motion.message_origin_started"), true);
        }
        Minecraft.getInstance().setScreen(null);
    }

    /** 由世界内原点摆放会话回填；保留其余尚未保存的页面草稿。 */
    public void applyPlacedOrigin(BlockPos origin) {
        if (origin == null) return;
        getCurrentDraft().setDisplayOrigin(origin.getX(), origin.getY(), origin.getZ());
        if (widgetsInitialized) {
            originXField.setValue(String.format(Locale.ROOT, "%.1f", (double) origin.getX()));
            originYField.setValue(String.format(Locale.ROOT, "%.1f", (double) origin.getY()));
            originZField.setValue(String.format(Locale.ROOT, "%.1f", (double) origin.getZ()));
        }
    }

    private void togglePreview() {
        flushPending();
        SceneRenderRuntime runtime = SceneRenderRuntime.getInstance();
        previewActive = runtime.isPreviewActiveFor(selectedMapKey);
        SceneAssetDescriptor currentDescriptor = currentAssetDescriptor();
        if (!previewActive && !currentDescriptor.isValid()) {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(Component.translatable(
                        "screen.habitrain_core.scene_motion.message_preview_needs_asset"), true);
            }
            return;
        }
        previewActive = !previewActive;
        if (previewActive) {
            SceneProfile profile = getCurrentDraft().toProfile();
            runtime.startPreview(selectedMapKey, profile, currentDescriptor);
            com.habitrain.core.scene.client.SceneAmbientSoundController.getInstance()
                    .playSound(profile.getOutsideSound());
            com.habitrain.core.scene.client.SceneShakeController.getInstance()
                    .updateSettings(profile.getShake(), true);
        } else {
            com.habitrain.core.scene.client.SceneClientRuntime.stopPreview();
        }
    }

    private SceneAssetDescriptor currentAssetDescriptor() {
        return SceneRenderRuntime.getInstance().getManifest(selectedMapKey);
    }

    private void requestBuildAsset() {
        flushPending();
        save();
        // 先触发配置保存/C2S，再发送构建请求；同一连接按发送顺序处理，服务端只读权威配置。
        root.saveConfigNow();
        if (ClientPlayNetworking.canSend(SceneAssetBuildRequestC2S.TYPE)) {
            ClientPlayNetworking.send(new SceneAssetBuildRequestC2S(selectedMapKey, true));
            var player = Minecraft.getInstance().player;
            if (player != null) player.displayClientMessage(Component.translatable(
                    "screen.habitrain_core.scene_motion.message_build_requested"), true);
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        return scroll.mouseDragged(my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        return scroll.mouseReleased();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        return scroll.mouseScrolled(sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (key == 258 && editable) {
            focusAdjacentField(Screen.hasShiftDown() ? -1 : 1);
            return true;
        }
        for (EditBox field : inputFields) {
            if (field.isFocused() && field.keyPressed(key, scan, mod)) return true;
        }
        return false;
    }

    private void focusAdjacentField(int delta) {
        List<EditBox> available = inputFields.stream()
                .filter(this::isFieldAvailable)
                .toList();
        if (available.isEmpty()) return;
        int current = -1;
        for (int i = 0; i < available.size(); i++) {
            if (available.get(i).isFocused()) {
                current = i;
                break;
            }
        }
        clearInputFocus();
        int next = current < 0
                ? (delta < 0 ? available.size() - 1 : 0)
                : Math.floorMod(current + delta, available.size());
        available.get(next).setFocused(true);
    }

    private boolean isFieldAvailable(EditBox field) {
        if (!editable) return false;
        if (field == loopDistField) return loopState;
        if (field == soundIdField || field == soundVolField || field == soundPitchField) {
            return soundState;
        }
        if (field == shakeFreqField || field == shakeAmpField || field == shakeRotAmpField) {
            return shakeState;
        }
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        for (EditBox field : inputFields) {
            if (field.isFocused() && field.charTyped(codePoint, modifiers)) return true;
        }
        return false;
    }
}
