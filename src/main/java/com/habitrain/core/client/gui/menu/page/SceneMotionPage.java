package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.ConfigPage;
import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.client.gui.menu.ui.PillToggle;
import com.habitrain.core.client.gui.menu.ui.SceneAngleDial;
import com.habitrain.core.client.gui.menu.ui.SceneMapDropdown;
import com.habitrain.core.client.gui.menu.ui.SceneRangePreview;
import com.habitrain.core.client.gui.menu.ui.ScrollArea;
import com.habitrain.core.client.gui.menu.ui.SectionHeader;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.SceneMotionSettings;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.client.SceneProjectionDiagnostics;
import com.habitrain.core.scene.client.SceneRenderRuntime;
import com.habitrain.core.scene.client.SceneStagingClientController;
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

    private record OrbitDirPreset(String labelKey, double angle) {}
    private static final OrbitDirPreset[] ORBIT_DIR_PRESETS = {
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_n", 0.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_ne", 45.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_e", 90.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_se", 135.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_s", 180.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_sw", 225.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_w", 270.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_nw", 315.0),
    };

    private static final double[] ORBIT_SWEEP_PRESETS = {0.0, 90.0, 180.0, 270.0, 360.0};

    private static final Map<String, String> LAST_SELECTED_BACKGROUND_PER_MAP = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String lastSelectedMapKey = null;

    private final ConfigMenuScreen root;
    private final Font font;
    private final boolean editable;
    private final SceneMapDropdown mapDropdown;
    private final SceneMapDropdown backgroundDropdown;

    private String selectedMapKey = "__default__";
    private String runtimeMapKey = "";
    private String selectionMapKey = "";
    private final Map<String, SceneProfileDraft> drafts = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, SceneProfileDraft>> backgroundDrafts = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, String>> backgroundNames = new LinkedHashMap<>();
    private final Set<String> pendingDeletedAssetKeys = new LinkedHashSet<>();
    private String selectedBackgroundId = SceneBackgroundKey.DEFAULT_ID;

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
    private EditBox backgroundNameField;

    // 环绕旋转 UI 输入框
    private EditBox orbitStartAngleField;
    private EditBox orbitSweepField;
    private EditBox orbitSpeedField;
    private EditBox orbitVerticalBobField;
    private EditBox orbitRadialBobField;
    private EditBox orbitBobCyclesField;
    private EditBox orbitInstanceCountField;
    private EditBox orbitInstanceSpreadField;

    private final List<EditBox> inputFields = new ArrayList<>();

    // 开关与模式状态
    private boolean enabledState = false;
    private SceneMotionMode motionMode = SceneMotionMode.LINEAR;
    private boolean loopState = true;
    private SceneLoopDistanceMode loopDistanceMode = SceneLoopDistanceMode.AUTO;
    private SceneOrbitCenterMode orbitCenterMode = SceneOrbitCenterMode.WORLD_BLOCK;
    private SceneOrbitAxis orbitAxis = SceneOrbitAxis.Y;
    private boolean orbitClockwise = true;
    private boolean orbitRotateModel = true;

    private boolean translucentState = true;
    private boolean soundState = true;
    private boolean shakeState = false;

    private boolean previewActive = false;
    private SceneBounds currentSessionBounds = SceneBounds.EMPTY;
    private int renderedContentHeight = 780;
    private final List<TooltipTarget> tooltipTargets = new ArrayList<>();

    private HitBox jumpRuntimeMapHit = HitBox.EMPTY;
    private HitBox addBackgroundHit = HitBox.EMPTY;
    private HitBox deleteBackgroundHit = HitBox.EMPTY;
    private HitBox previewHit = HitBox.EMPTY;
    private HitBox buildAssetHit = HitBox.EMPTY;
    private HitBox discardStagingHit = HitBox.EMPTY;
    private HitBox enabledHit = HitBox.EMPTY;
    private HitBox applySelectionHit = HitBox.EMPTY;
    private HitBox originPlacementHit = HitBox.EMPTY;
    private HitBox compatScanHit = HitBox.EMPTY;
    private HitBox compatPolicyStrictHit = HitBox.EMPTY;
    private HitBox compatPolicySkipWarnHit = HitBox.EMPTY;
    private HitBox compatCopyBlockIdHit = HitBox.EMPTY;

    // 运动模式切换与环绕按钮
    private HitBox motionLinearHit = HitBox.EMPTY;
    private HitBox motionOrbitHit = HitBox.EMPTY;
    private HitBox addOrbitHit = HitBox.EMPTY;
    private HitBox orbitCenterWorldHit = HitBox.EMPTY;
    private HitBox orbitCenterModelHit = HitBox.EMPTY;
    private HitBox orbitCenterPickHit = HitBox.EMPTY;
    private HitBox orbitAxisYHit = HitBox.EMPTY;
    private HitBox orbitAxisXHit = HitBox.EMPTY;
    private HitBox orbitAxisZHit = HitBox.EMPTY;
    private HitBox orbitClockwiseHit = HitBox.EMPTY;
    private HitBox orbitCounterClockwiseHit = HitBox.EMPTY;
    private HitBox orbitRotateModelWithHit = HitBox.EMPTY;
    private HitBox orbitRotateModelFixedHit = HitBox.EMPTY;
    private HitBox orbitDialHit = HitBox.EMPTY;
    private final HitBox[] orbitQuickDirHits = new HitBox[ORBIT_DIR_PRESETS.length];
    private final HitBox[] orbitQuickAngleHits = new HitBox[ORBIT_SWEEP_PRESETS.length];
    private boolean orbitDialDragging = false;
    private boolean orbitDialFocused = false;

    private HitBox loopHit = HitBox.EMPTY;
    private HitBox loopAutoHit = HitBox.EMPTY;
    private HitBox loopCustomHit = HitBox.EMPTY;
    private HitBox rangePreviewHit = HitBox.EMPTY;
    private HitBox rangeSliderHit = HitBox.EMPTY;
    private HitBox applyRecommendedHit = HitBox.EMPTY;
    private HitBox translucentHit = HitBox.EMPTY;
    private HitBox soundHit = HitBox.EMPTY;
    private HitBox shakeHit = HitBox.EMPTY;
    private final HitBox[] directionPresetHits = new HitBox[DIRECTION_PRESETS.length];
    private boolean rangeSliderDragging;
    /** -1 none, 0 interval source, 1 slider, 2 copy-recommendation action. */
    private int loopKeyboardFocus = -1;

    private record DirectionPreset(String label, double x, double y, double z) {}

    private record TooltipTarget(HitBox bounds, Component text) {}

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
        this.mapDropdown = new SceneMapDropdown(font, this::selectMap);
        this.backgroundDropdown = new SceneMapDropdown(font, this::selectBackground, "background");
        loadDraftsFromConfig();
        if (lastSelectedMapKey != null && drafts.containsKey(lastSelectedMapKey)) {
            this.selectedMapKey = lastSelectedMapKey;
        }
        String rememberedBg = LAST_SELECTED_BACKGROUND_PER_MAP.get(this.selectedMapKey);
        if (rememberedBg != null && availableBackgroundIds().contains(rememberedBg)) {
            this.selectedBackgroundId = rememberedBg;
        }
    }

    public void setSessionData(String editorMapKey, String runtimeMapKey, String selectionMapKey,
                               SceneProfile profile, SceneBounds selection, SceneAssetDescriptor descriptor) {
        setSessionData(editorMapKey, SceneBackgroundKey.DEFAULT_ID, runtimeMapKey, selectionMapKey, profile, selection, descriptor);
    }

    public void setSessionData(String editorMapKey, String editorBackgroundId, String runtimeMapKey, String selectionMapKey,
                               SceneProfile profile, SceneBounds selection, SceneAssetDescriptor descriptor) {
        if (editorMapKey != null && !editorMapKey.isBlank()) {
            this.selectedMapKey = editorMapKey;
            lastSelectedMapKey = editorMapKey;
        }
        String sessionBackgroundId = SceneBackgroundKey.normalizeBackgroundId(editorBackgroundId);
        this.runtimeMapKey = runtimeMapKey == null ? "" : runtimeMapKey.trim();
        this.selectionMapKey = selectionMapKey == null ? "" : selectionMapKey.trim();
        if (profile != null) {
            if (SceneBackgroundKey.isDefault(sessionBackgroundId)) {
                drafts.put(selectedMapKey, new SceneProfileDraft(profile));
            } else {
                backgroundDrafts.computeIfAbsent(selectedMapKey, k -> new LinkedHashMap<>())
                        .put(sessionBackgroundId, new SceneProfileDraft(profile));
            }
        }
        String rememberedBackgroundId = LAST_SELECTED_BACKGROUND_PER_MAP.get(selectedMapKey);
        this.selectedBackgroundId = SceneEditorBackgroundPolicy.resolve(
                sessionBackgroundId, rememberedBackgroundId, availableBackgroundIds());
        LAST_SELECTED_BACKGROUND_PER_MAP.put(selectedMapKey, selectedBackgroundId);
        if (selection != null) {
            this.currentSessionBounds = selection;
        }
        if (descriptor != null) {
            SceneRenderRuntime.getInstance().acceptManifest(
                    SceneBackgroundKey.assetKey(selectedMapKey, sessionBackgroundId), descriptor);
        }
        SceneRenderRuntime runtime = SceneRenderRuntime.getInstance();
        if (runtime.isPreviewActive() && !runtime.isPreviewActiveFor(currentAssetKey())) {
            // The editor is opening a different map/background; never leave an invisible old preview running.
            com.habitrain.core.scene.client.SceneClientRuntime.stopPreview();
        }
        previewActive = runtime.isPreviewActiveFor(currentAssetKey());
        if (widgetsInitialized) {
            populateFieldsFromDraft();
        }
        syncToolSelection(selectedMapKey, selectedBackgroundId);
    }

    /** An old play connection must not influence the editor target after reconnecting. */
    public static void clearRememberedEditorTarget() {
        LAST_SELECTED_BACKGROUND_PER_MAP.clear();
        lastSelectedMapKey = null;
    }

    /** Records the server-confirmed tool target even while no editor screen exists. */
    public static void rememberAuthoritativeEditorTarget(String mapKey, String backgroundId) {
        if (mapKey == null || mapKey.isBlank()) return;
        String normalizedMap = mapKey.trim();
        LAST_SELECTED_BACKGROUND_PER_MAP.put(
                normalizedMap, SceneBackgroundKey.normalizeBackgroundId(backgroundId));
        lastSelectedMapKey = normalizedMap;
    }

    private void loadDraftsFromConfig() {
        SceneMotionSettings settings = ConfigManager.getInstance().getSceneMotionSettings();
        drafts.clear();
        backgroundDrafts.clear();
        backgroundNames.clear();
        for (Map.Entry<String, SceneProfile> entry : settings.profiles.entrySet()) {
            drafts.put(entry.getKey(), new SceneProfileDraft(entry.getValue()));
        }
        for (Map.Entry<String, LinkedHashMap<String, SceneBackgroundConfig>> mapEntry
                : settings.backgrounds.entrySet()) {
            LinkedHashMap<String, SceneProfileDraft> mapDrafts = new LinkedHashMap<>();
            LinkedHashMap<String, String> mapNames = new LinkedHashMap<>();
            mapEntry.getValue().forEach((id, config) -> {
                mapDrafts.put(id, new SceneProfileDraft(config.getProfile()));
                mapNames.put(id, config.getName());
            });
            backgroundDrafts.put(mapEntry.getKey(), mapDrafts);
            backgroundNames.put(mapEntry.getKey(), mapNames);
        }
        if (!drafts.containsKey("__default__")) {
            drafts.put("__default__", new SceneProfileDraft(settings.getProfile("__default__")));
        }
        // The vote configuration is the authoritative list of selectable SRE maps in Mod Menu.
        // Seed a draft for every voted map so admins can configure it before any scene asset exists.
        SceneProfile defaultProfile = settings.getProfile(SceneMotionSettings.DEFAULT_MAP_KEY);
        for (String mapId : ConfigManager.getInstance().getModeMapVoteSettings().maps.keySet()) {
            if (mapId != null && !mapId.isBlank()) {
                drafts.computeIfAbsent(mapId,
                        key -> new SceneProfileDraft(defaultProfile.copyForNewProfile()));
            }
        }
    }

    private SceneProfileDraft getCurrentDraft() {
        if (SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId)) {
            return drafts.computeIfAbsent(selectedMapKey, k -> new SceneProfileDraft());
        }
        return backgroundDrafts.computeIfAbsent(selectedMapKey, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(selectedBackgroundId, ignored -> new SceneProfileDraft());
    }

    private SceneProfileDraft getSharedDraft() {
        return drafts.computeIfAbsent(selectedMapKey, ignored -> new SceneProfileDraft());
    }

    private String currentAssetKey() {
        return SceneBackgroundKey.assetKey(selectedMapKey, selectedBackgroundId);
    }

    private List<String> availableMapKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(SceneMotionSettings.DEFAULT_MAP_KEY);
        keys.addAll(ConfigManager.getInstance().getModeMapVoteSettings().maps.keySet());
        keys.addAll(drafts.keySet());
        keys.removeIf(key -> key == null || key.isBlank());
        return new ArrayList<>(keys);
    }

    private void selectMap(String mapKey) {
        if (mapKey == null || mapKey.isBlank() || mapKey.equals(selectedMapKey)
                || !availableMapKeys().contains(mapKey)) return;
        if (widgetsInitialized && !loopDistanceValidation().isValid()) {
            showClientMessage("screen.habitrain_core.scene_motion.message_fix_loop_distance");
            return;
        }
        flushPending();
        if (SceneRenderRuntime.getInstance().isPreviewActive()) {
            previewActive = false;
            com.habitrain.core.scene.client.SceneClientRuntime.stopPreview();
        }
        selectedMapKey = mapKey;
        lastSelectedMapKey = mapKey;
        String rememberedBg = LAST_SELECTED_BACKGROUND_PER_MAP.get(mapKey);
        if (rememberedBg != null && availableBackgroundIds().contains(rememberedBg)) {
            selectedBackgroundId = rememberedBg;
        } else {
            selectedBackgroundId = SceneBackgroundKey.DEFAULT_ID;
        }
        LAST_SELECTED_BACKGROUND_PER_MAP.put(selectedMapKey, selectedBackgroundId);
        syncToolSelection(selectedMapKey, selectedBackgroundId);
        SceneMotionSettings settings = ConfigManager.getInstance().getSceneMotionSettings();
        drafts.computeIfAbsent(selectedMapKey,
                key -> new SceneProfileDraft(settings.getProfile(
                        SceneMotionSettings.DEFAULT_MAP_KEY).copyForNewProfile()));
        populateFieldsFromDraft();
    }

    private void selectBackground(String backgroundId) {
        String normalized = SceneBackgroundKey.normalizeBackgroundId(backgroundId);
        if (normalized.equals(selectedBackgroundId) || !availableBackgroundIds().contains(normalized)) return;
        if (widgetsInitialized && !isSaveAllowed()) {
            showClientMessage(motionMode == SceneMotionMode.ORBIT
                    ? "screen.habitrain_core.scene_motion.message_fix_orbit_fields"
                    : "screen.habitrain_core.scene_motion.message_fix_loop_distance");
            return;
        }
        flushPending();
        if (SceneRenderRuntime.getInstance().isPreviewActive()) {
            com.habitrain.core.scene.client.SceneClientRuntime.stopPreview();
        }
        selectedBackgroundId = normalized;
        LAST_SELECTED_BACKGROUND_PER_MAP.put(selectedMapKey, selectedBackgroundId);
        syncToolSelection(selectedMapKey, selectedBackgroundId);
        populateFieldsFromDraft();
    }

    private List<String> availableBackgroundIds() {
        List<String> ids = new ArrayList<>();
        ids.add(SceneBackgroundKey.DEFAULT_ID);
        ids.addAll(backgroundDrafts.getOrDefault(selectedMapKey, new LinkedHashMap<>()).keySet());
        return ids;
    }

    private List<SceneMapDropdownModel.Entry> backgroundDropdownEntries() {
        List<SceneMapDropdownModel.Entry> entries = new ArrayList<>();
        for (String id : availableBackgroundIds()) {
            SceneProfileDraft draft = SceneBackgroundKey.DEFAULT_ID.equals(id)
                    ? drafts.get(selectedMapKey)
                    : backgroundDrafts.getOrDefault(selectedMapKey, new LinkedHashMap<>()).get(id);
            String name = SceneBackgroundKey.DEFAULT_ID.equals(id)
                    ? tr("screen.habitrain_core.scene_motion.background_default")
                    : backgroundNames.getOrDefault(selectedMapKey, new LinkedHashMap<>()).getOrDefault(id, id);
            entries.add(new SceneMapDropdownModel.Entry(id, name,
                    draft != null && draft.isEnabled(), false, id.equals(selectedBackgroundId)));
        }
        return entries;
    }

    private void addBackground() {
        if (!editable || availableBackgroundIds().size() >= SceneMotionSettings.MAX_BACKGROUNDS_PER_MAP) return;
        flushPending();
        LinkedHashMap<String, SceneProfileDraft> mapDrafts =
                backgroundDrafts.computeIfAbsent(selectedMapKey, ignored -> new LinkedHashMap<>());
        int suffix = 1;
        String id;
        do id = "background_" + suffix++; while (mapDrafts.containsKey(id));
        SceneProfile template = getSharedDraft().toProfile().copyForNewProfile();
        template.setEnabled(false);
        template.setSourceBounds(SceneBounds.EMPTY);
        mapDrafts.put(id, new SceneProfileDraft(template));
        backgroundNames.computeIfAbsent(selectedMapKey, ignored -> new LinkedHashMap<>())
                .put(id, tr("screen.habitrain_core.scene_motion.background_new", availableBackgroundIds().size() + 1));
        selectedBackgroundId = id;
        LAST_SELECTED_BACKGROUND_PER_MAP.put(selectedMapKey, selectedBackgroundId);
        syncToolSelection(selectedMapKey, selectedBackgroundId);
        populateFieldsFromDraft();
    }

    private void deleteBackground() {
        if (!editable || SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId)) return;
        String deletedAssetKey = currentAssetKey();
        if (SceneRenderRuntime.getInstance().isPreviewActiveFor(currentAssetKey())) {
            com.habitrain.core.scene.client.SceneClientRuntime.stopPreview();
        }
        LinkedHashMap<String, SceneProfileDraft> mapDrafts = backgroundDrafts.get(selectedMapKey);
        LinkedHashMap<String, String> mapNames = backgroundNames.get(selectedMapKey);
        if (mapDrafts != null) mapDrafts.remove(selectedBackgroundId);
        if (mapNames != null) mapNames.remove(selectedBackgroundId);
        pendingDeletedAssetKeys.add(deletedAssetKey);
        selectedBackgroundId = SceneBackgroundKey.DEFAULT_ID;
        LAST_SELECTED_BACKGROUND_PER_MAP.put(selectedMapKey, selectedBackgroundId);
        syncToolSelection(selectedMapKey, selectedBackgroundId);
        populateFieldsFromDraft();
    }

    private void syncToolSelection(String mapKey, String backgroundId) {
        com.habitrain.core.client.gui.menu.ConfigUpdateContext.setCurrentSceneMapKey(mapKey);
        String bgName = SceneBackgroundKey.DEFAULT_ID.equals(backgroundId)
                ? tr("screen.habitrain_core.scene_motion.background_default")
                : backgroundNames.getOrDefault(mapKey, new LinkedHashMap<>()).getOrDefault(backgroundId, backgroundId);
        com.habitrain.core.scene.client.SceneToolHud.getInstance().updateSelectionTarget(mapKey, backgroundId, bgName);
        if (ClientPlayNetworking.canSend(com.habitrain.core.scene.network.SceneToolMapSelectC2S.TYPE)) {
            ClientPlayNetworking.send(new com.habitrain.core.scene.network.SceneToolMapSelectC2S(mapKey, backgroundId));
        }
    }

    private void syncToolMapSelection(String mapKey) {
        syncToolSelection(mapKey, selectedBackgroundId);
    }

    private String mapLabel(String mapKey) {
        if (SceneMotionSettings.DEFAULT_MAP_KEY.equals(mapKey)) {
            return tr("screen.habitrain_core.scene_motion.map_default");
        }
        MapVoteEntry entry = ConfigManager.getInstance().getModeMapVoteSettings().maps.get(mapKey);
        String displayName = entry != null ? entry.displayName : null;
        if (displayName == null || displayName.isBlank() || displayName.equals(mapKey)) {
            return mapKey;
        }
        return displayName.trim();
    }

    private List<SceneMapDropdownModel.Entry> mapDropdownEntries() {
        List<SceneMapDropdownModel.Entry> entries = new ArrayList<>();
        for (String key : availableMapKeys()) {
            SceneProfileDraft draft = drafts.get(key);
            entries.add(new SceneMapDropdownModel.Entry(
                    key,
                    mapLabel(key),
                    key.equals(selectedMapKey) && widgetsInitialized
                            ? enabledState : draft != null && draft.isEnabled(),
                    key.equals(runtimeMapKey),
                    key.equals(selectedMapKey)
            ));
        }
        return entries;
    }

    private void initWidgets() {
        backgroundNameField = new EditBox(font, 0, 0, 160, FIELD_HEIGHT,
                Component.translatable("screen.habitrain_core.scene_motion.background_name"));
        backgroundNameField.setMaxLength(32);
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

        orbitStartAngleField = field("orbit_start_angle");
        orbitSweepField = field("orbit_sweep");
        orbitSpeedField = field("orbit_speed");
        orbitVerticalBobField = field("orbit_vertical_bob");
        orbitRadialBobField = field("orbit_radial_bob");
        orbitBobCyclesField = field("orbit_bob_cycles");
        orbitInstanceCountField = field("orbit_instance_count");
        orbitInstanceSpreadField = field("orbit_instance_spread");

        inputFields.clear();
        Collections.addAll(inputFields,
                speedField, dirXField, dirYField, dirZField,
                originXField, originYField, originZField,
                pivotXField, pivotYField, pivotZField,
                rotYawField, rotPitchField, rotRollField,
                phaseOffsetField, loopDistField, renderMaxDistField,
                soundIdField, soundVolField, soundPitchField,
                shakeFreqField, shakeAmpField, shakeRotAmpField,
                orbitStartAngleField, orbitSweepField, orbitSpeedField,
                orbitVerticalBobField, orbitRadialBobField, orbitBobCyclesField,
                orbitInstanceCountField, orbitInstanceSpreadField);
        for (EditBox field : inputFields) {
            field.setEditable(editable);
            if (field != soundIdField) {
                field.setMaxLength(32);
                field.setFilter(SceneMotionPage::isPotentialDecimal);
            }
        }
        orbitInstanceCountField.setFilter(SceneMotionPage::isPotentialInteger);

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

    private static boolean isPotentialInteger(String value) {
        return value == null || value.isEmpty() || value.matches("[+]?\\d*");
    }

    private void populateFieldsFromDraft() {
        SceneProfileDraft d = getCurrentDraft();
        if (backgroundNameField != null) {
            String name = SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId)
                    ? tr("screen.habitrain_core.scene_motion.background_default")
                    : backgroundNames.getOrDefault(selectedMapKey, new LinkedHashMap<>())
                    .getOrDefault(selectedBackgroundId, selectedBackgroundId);
            backgroundNameField.setValue(name);
            backgroundNameField.setEditable(editable && !SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId));
        }
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
        loopDistanceMode = d.getLoop().getDistanceMode();
        loopDistField.setValue(String.format(Locale.ROOT, "%.1f", d.getLoop().getDistanceBlocks()));

        motionMode = d.getMotionMode();
        SceneOrbitSettings orbit = d.getOrbit();
        orbitCenterMode = orbit.getCenterMode();
        orbitAxis = orbit.getAxis();
        orbitClockwise = orbit.isClockwise();
        orbitRotateModel = orbit.isRotateModelWithOrbit();

        if (orbitCenterMode == SceneOrbitCenterMode.MODEL_CENTER) {
            double derivedAngle = SceneOrbitMath.resolveStartAngle(d.toProfile());
            orbitStartAngleField.setValue(String.format(Locale.ROOT, "%.1f", derivedAngle));
        } else {
            orbitStartAngleField.setValue(String.format(Locale.ROOT, "%.1f", orbit.getStartAngleDegrees()));
        }
        orbitSweepField.setValue(String.format(Locale.ROOT, "%.1f", orbit.getSweepDegrees()));
        orbitSpeedField.setValue(String.format(Locale.ROOT, "%.1f", orbit.getAngularSpeedDegreesPerSecond()));
        orbitVerticalBobField.setValue(String.format(Locale.ROOT, "%.2f", orbit.getVerticalBobAmplitudeBlocks()));
        orbitRadialBobField.setValue(String.format(Locale.ROOT, "%.2f", orbit.getRadialBobAmplitudeBlocks()));
        orbitBobCyclesField.setValue(String.format(Locale.ROOT, "%.2f", orbit.getBobCyclesPerSecond()));
        orbitInstanceCountField.setValue(Integer.toString(orbit.getInstanceCount()));
        orbitInstanceSpreadField.setValue(String.format(Locale.ROOT, "%.1f", orbit.getInstanceSpreadDegrees()));

        translucentState = d.getRender().isRenderTranslucent();
        renderMaxDistField.setValue(String.valueOf(d.getRender().getMaxDistanceBlocks()));

        SceneProfileDraft shared = getSharedDraft();
        soundIdField.setValue(shared.getOutsideSound().getSoundId());
        soundState = shared.getOutsideSound().isEnabled();
        soundVolField.setValue(String.format(Locale.ROOT, "%.2f", shared.getOutsideSound().getVolume()));
        soundPitchField.setValue(String.format(Locale.ROOT, "%.2f", shared.getOutsideSound().getPitch()));

        shakeState = shared.getShake().isEnabled();
        shakeFreqField.setValue(String.format(Locale.ROOT, "%.1f", shared.getShake().getFrequencyHz()));
        shakeAmpField.setValue(String.format(Locale.ROOT, "%.3f", shared.getShake().getTranslationAmplitudeBlocks()));
        shakeRotAmpField.setValue(String.format(Locale.ROOT, "%.2f", shared.getShake().getRotationAmplitudeDegrees()));
    }

    @Override
    public void flushPending() {
        if (!widgetsInitialized) return;
        SceneProfileDraft d = getCurrentDraft();
        if (!SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId)) {
            backgroundNames.computeIfAbsent(selectedMapKey, ignored -> new LinkedHashMap<>())
                    .put(selectedBackgroundId, backgroundNameField.getValue());
        }
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

        SceneProfileValidator.LoopDistanceValidation loopValidation = loopDistanceValidation();
        if (loopValidation.isValid()) {
            d.setLoop(new SceneLoopSettings(loopState, loopDistanceMode,
                    loopValidation.value(), SceneLoopSettings.DEFAULT_COPIES));
        }

        d.setMotionMode(motionMode);
        SceneOrbitSettings orbit = d.getOrbit();
        orbit.setCenterMode(orbitCenterMode);
        orbit.setAxis(orbitAxis);
        orbit.setClockwise(orbitClockwise);
        if (orbitCenterMode == SceneOrbitCenterMode.WORLD_BLOCK) {
            if (SceneProfileValidator.validateStartAngle(orbitStartAngleField.getValue()).isValid()) {
                orbit.setStartAngleDegrees(parseDouble(orbitStartAngleField.getValue(), orbit.getStartAngleDegrees()));
            }
        }
        if (SceneProfileValidator.validateSweep(orbitSweepField.getValue()).isValid()) {
            orbit.setSweepDegrees(parseDouble(orbitSweepField.getValue(), orbit.getSweepDegrees()));
        }
        if (SceneProfileValidator.validateAngularSpeed(orbitSpeedField.getValue()).isValid()) {
            orbit.setAngularSpeedDegreesPerSecond(parseDouble(orbitSpeedField.getValue(), orbit.getAngularSpeedDegreesPerSecond()));
        }
        if (SceneProfileValidator.validateVerticalBob(orbitVerticalBobField.getValue()).isValid()) {
            orbit.setVerticalBobAmplitudeBlocks(parseDouble(
                    orbitVerticalBobField.getValue(), orbit.getVerticalBobAmplitudeBlocks()));
        }
        if (SceneProfileValidator.validateRadialBob(orbitRadialBobField.getValue()).isValid()) {
            orbit.setRadialBobAmplitudeBlocks(parseDouble(
                    orbitRadialBobField.getValue(), orbit.getRadialBobAmplitudeBlocks()));
        }
        if (SceneProfileValidator.validateBobCycles(orbitBobCyclesField.getValue()).isValid()) {
            orbit.setBobCyclesPerSecond(parseDouble(
                    orbitBobCyclesField.getValue(), orbit.getBobCyclesPerSecond()));
        }
        SceneProfileValidator.IntValidation instanceCountValidation = orbitInstanceCountValidation();
        if (instanceCountValidation.isValid()) {
            orbit.setInstanceCount(instanceCountValidation.value());
        }
        if (SceneProfileValidator.validateInstanceSpread(orbitInstanceSpreadField.getValue()).isValid()) {
            orbit.setInstanceSpreadDegrees(parseDouble(
                    orbitInstanceSpreadField.getValue(), orbit.getInstanceSpreadDegrees()));
        }
        orbit.setRotateModelWithOrbit(orbitCenterMode == SceneOrbitCenterMode.MODEL_CENTER || orbitRotateModel);

        d.setRender(new SceneRenderSettings(
                parseDouble(renderMaxDistField.getValue(), 160.0),
                translucentState
        ));

        SceneProfileDraft shared = getSharedDraft();
        shared.setOutsideSound(new SceneSoundSettings(
                soundState,
                soundIdField.getValue().trim(),
                (float) parseDouble(soundVolField.getValue(), 1.0),
                (float) parseDouble(soundPitchField.getValue(), 1.0),
                20
        ));

        shared.setShake(new SceneShakeSettings(
                shakeState,
                parseDouble(shakeAmpField.getValue(), 0.03),
                parseDouble(shakeRotAmpField.getValue(), 0.15),
                parseDouble(shakeFreqField.getValue(), 4.0)
        ));
    }

    public void applyPlacedOrbitCenter(double x, double y, double z) {
        SceneProfileDraft draft = getCurrentDraft();
        SceneOrbitSettings orbit = draft.getOrbit();
        orbit.setCenterMode(SceneOrbitCenterMode.WORLD_BLOCK);
        orbit.setCenterWorld(x, y, z);
        double derived = SceneOrbitMath.deriveStartAngle(new double[]{x, y, z}, draft.getDisplayOrigin(), orbit.getAxis());
        orbit.setStartAngleDegrees(derived);
        this.orbitCenterMode = SceneOrbitCenterMode.WORLD_BLOCK;
        if (widgetsInitialized) {
            populateFieldsFromDraft();
        }
    }

    private SceneMotionMath.LoopDistances loopDistancesFromFields() {
        SceneProfileDraft draft = getCurrentDraft();
        double[] direction = directionFromFields(draft.getDirection());
        SceneProfileValidator.LoopDistanceValidation validation = loopDistanceValidation();
        double configured = validation.isValid()
                ? validation.value() : draft.getLoop().getDistanceBlocks();
        SceneLoopSettings loop = new SceneLoopSettings(loopState, loopDistanceMode,
                configured, SceneLoopSettings.DEFAULT_COPIES);
        return SceneMotionMath.resolveLoopDistances(draft.getSourceBounds(), direction, loop);
    }

    private SceneProfileValidator.LoopDistanceValidation loopDistanceValidation() {
        if (!widgetsInitialized || loopDistField == null) {
            return new SceneProfileValidator.LoopDistanceValidation(
                    SceneProfileValidator.LoopDistanceError.NONE,
                    getCurrentDraft().getLoop().getDistanceBlocks());
        }
        return SceneProfileValidator.validateLoopDistance(loopDistField.getValue());
    }

    public SceneProfileValidator.NumericValidation orbitStartAngleValidation() {
        if (!widgetsInitialized || orbitStartAngleField == null) {
            return new SceneProfileValidator.NumericValidation(SceneProfileValidator.NumericError.NONE, 0.0);
        }
        if (orbitCenterMode == SceneOrbitCenterMode.MODEL_CENTER) {
            return new SceneProfileValidator.NumericValidation(SceneProfileValidator.NumericError.NONE, 0.0);
        }
        return SceneProfileValidator.validateStartAngle(orbitStartAngleField.getValue());
    }

    public SceneProfileValidator.NumericValidation orbitSweepValidation() {
        if (!widgetsInitialized || orbitSweepField == null) {
            return new SceneProfileValidator.NumericValidation(SceneProfileValidator.NumericError.NONE, SceneOrbitSettings.DEFAULT_SWEEP);
        }
        return SceneProfileValidator.validateSweep(orbitSweepField.getValue());
    }

    public SceneProfileValidator.NumericValidation orbitSpeedValidation() {
        if (!widgetsInitialized || orbitSpeedField == null) {
            return new SceneProfileValidator.NumericValidation(SceneProfileValidator.NumericError.NONE, SceneOrbitSettings.DEFAULT_SPEED);
        }
        return SceneProfileValidator.validateAngularSpeed(orbitSpeedField.getValue());
    }

    public SceneProfileValidator.NumericValidation orbitVerticalBobValidation() {
        if (!widgetsInitialized || orbitVerticalBobField == null) {
            return new SceneProfileValidator.NumericValidation(SceneProfileValidator.NumericError.NONE, 0.0);
        }
        return SceneProfileValidator.validateVerticalBob(orbitVerticalBobField.getValue());
    }

    public SceneProfileValidator.NumericValidation orbitRadialBobValidation() {
        if (!widgetsInitialized || orbitRadialBobField == null) {
            return new SceneProfileValidator.NumericValidation(SceneProfileValidator.NumericError.NONE, 0.0);
        }
        return SceneProfileValidator.validateRadialBob(orbitRadialBobField.getValue());
    }

    public SceneProfileValidator.NumericValidation orbitBobCyclesValidation() {
        if (!widgetsInitialized || orbitBobCyclesField == null) {
            return new SceneProfileValidator.NumericValidation(SceneProfileValidator.NumericError.NONE, 0.0);
        }
        return SceneProfileValidator.validateBobCycles(orbitBobCyclesField.getValue());
    }

    public SceneProfileValidator.IntValidation orbitInstanceCountValidation() {
        if (!widgetsInitialized || orbitInstanceCountField == null) {
            return new SceneProfileValidator.IntValidation(
                    SceneProfileValidator.NumericError.NONE, SceneOrbitSettings.MIN_INSTANCES);
        }
        return SceneProfileValidator.validateInstanceCount(orbitInstanceCountField.getValue());
    }

    public SceneProfileValidator.NumericValidation orbitInstanceSpreadValidation() {
        if (!widgetsInitialized || orbitInstanceSpreadField == null) {
            return new SceneProfileValidator.NumericValidation(
                    SceneProfileValidator.NumericError.NONE, SceneOrbitSettings.DEFAULT_SPREAD);
        }
        return SceneProfileValidator.validateInstanceSpread(orbitInstanceSpreadField.getValue());
    }

    public boolean isOrbitValid() {
        return orbitStartAngleValidation().isValid()
                && orbitSweepValidation().isValid()
                && orbitSpeedValidation().isValid()
                && orbitVerticalBobValidation().isValid()
                && orbitRadialBobValidation().isValid()
                && orbitBobCyclesValidation().isValid()
                && orbitInstanceCountValidation().isValid()
                && orbitInstanceSpreadValidation().isValid();
    }

    private SceneLoopSettings loopSettingsFromFields() {
        SceneProfileDraft draft = getCurrentDraft();
        SceneProfileValidator.LoopDistanceValidation validation = loopDistanceValidation();
        double custom = validation.isValid() ? validation.value() : draft.getLoop().getDistanceBlocks();
        return new SceneLoopSettings(loopState, loopDistanceMode, custom,
                SceneLoopSettings.DEFAULT_COPIES);
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
        boolean isLinear = motionMode == SceneMotionMode.LINEAR;
        dirXField.setEditable(editable && isLinear);
        dirYField.setEditable(editable && isLinear);
        dirZField.setEditable(editable && isLinear);
        loopDistField.setEditable(editable && isLinear && loopState
                && loopDistanceMode == SceneLoopDistanceMode.CUSTOM);

        boolean isOrbit = motionMode == SceneMotionMode.ORBIT;
        orbitStartAngleField.setEditable(editable && isOrbit && orbitCenterMode == SceneOrbitCenterMode.WORLD_BLOCK);
        orbitSweepField.setEditable(editable && isOrbit);
        orbitSpeedField.setEditable(editable && isOrbit);
        orbitVerticalBobField.setEditable(editable && isOrbit);
        orbitRadialBobField.setEditable(editable && isOrbit);
        orbitBobCyclesField.setEditable(editable && isOrbit);
        orbitInstanceCountField.setEditable(editable && isOrbit);
        orbitInstanceSpreadField.setEditable(editable && isOrbit);

        boolean sharedEditable = editable && SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId);
        soundIdField.setEditable(sharedEditable && soundState);
        soundVolField.setEditable(sharedEditable && soundState);
        soundPitchField.setEditable(sharedEditable && soundState);
        shakeFreqField.setEditable(sharedEditable && shakeState);
        shakeAmpField.setEditable(sharedEditable && shakeState);
        shakeRotAmpField.setEditable(sharedEditable && shakeState);
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    @Override
    public void save() {
        if (!isSaveAllowed()) {
            if (motionMode == SceneMotionMode.ORBIT) {
                showClientMessage("screen.habitrain_core.scene_motion.message_fix_orbit_fields");
            } else {
                showClientMessage("screen.habitrain_core.scene_motion.message_fix_loop_distance");
            }
            return;
        }
        flushPending();
        SceneMotionSettings settings = ConfigManager.getInstance().getSceneMotionSettings();
        for (Map.Entry<String, SceneProfileDraft> e : drafts.entrySet()) {
            settings.profiles.put(e.getKey(), e.getValue().toProfile());
        }
        settings.backgrounds.clear();
        for (Map.Entry<String, LinkedHashMap<String, SceneProfileDraft>> mapEntry : backgroundDrafts.entrySet()) {
            for (Map.Entry<String, SceneProfileDraft> entry : mapEntry.getValue().entrySet()) {
                String name = backgroundNames.getOrDefault(mapEntry.getKey(), new LinkedHashMap<>())
                        .getOrDefault(entry.getKey(), entry.getKey());
                settings.putBackground(mapEntry.getKey(), entry.getKey(),
                        new SceneBackgroundConfig(name, entry.getValue().toProfile()));
            }
        }
        SceneRenderRuntime.getInstance().updatePreviewProfile(currentAssetKey(), getCurrentDraft().toProfile());
        ConfigManager.getInstance().markSceneMotionDirty();
        var integratedServer = Minecraft.getInstance().getSingleplayerServer();
        if (integratedServer != null && !pendingDeletedAssetKeys.isEmpty()) {
            Set<String> deleted = Set.copyOf(pendingDeletedAssetKeys);
            integratedServer.execute(() -> deleted.forEach(assetKey ->
                    com.habitrain.core.scene.server.SceneAssetStore.getInstance().deleteAsset(assetKey)));
        }
        pendingDeletedAssetKeys.clear();
        int backgroundCount = backgroundDrafts.values().stream().mapToInt(Map::size).sum();
        LOGGER.info("已保存移动场景配置: {} 个地图，{} 个附加动态背景", drafts.size(), backgroundCount);
    }

    @Override
    public boolean canSave() {
        return editable;
    }

    @Override
    public boolean isSaveAllowed() {
        if (!editable) return false;
        if (motionMode == SceneMotionMode.ORBIT) {
            return isOrbitValid();
        }
        return loopDistanceValidation().isValid();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta, int x, int y, int w, int h) {
        if (!widgetsInitialized) initWidgets();
        tooltipTargets.clear();
        // Screen instances are disposable; the renderer owns the actual preview-session state.
        previewActive = SceneRenderRuntime.getInstance().isPreviewActiveFor(currentAssetKey());
        if (previewActive) {
            SceneRenderRuntime.getInstance().updatePreviewProfile(
                    currentAssetKey(), visibilityProfileFromFields());
        }
        refreshFieldAvailability();

        boolean narrow = w < NARROW_WIDTH;
        int topY = y + 4;
        int actionY = narrow ? topY + BUTTON_HEIGHT + 4 : topY;
        int previewW;
        int buildW;
        int actionX;
        int dropdownX = x + 6;
        int dropdownWidth;
        int jumpWidth;
        if (narrow) {
            int actionAvailable = Math.max(1, w - 12);
            jumpWidth = Math.max(76, actionAvailable * 36 / 100);
            int remaining = Math.max(2, actionAvailable - jumpWidth - 8);
            previewW = Math.max(1, remaining / 2);
            buildW = Math.max(1, remaining - previewW);
            actionX = dropdownX + jumpWidth + 4;
            dropdownWidth = actionAvailable;
            jumpRuntimeMapHit = new HitBox(dropdownX, actionY, jumpWidth, BUTTON_HEIGHT);
        } else {
            previewW = 82;
            buildW = 94;
            actionX = x + w - previewW - buildW - 12;
            jumpWidth = Math.min(126, Math.max(92, w / 5));
            dropdownWidth = Math.max(96, Math.min(360,
                    actionX - dropdownX - jumpWidth - 8));
            jumpRuntimeMapHit = new HitBox(dropdownX + dropdownWidth + 4, topY,
                    jumpWidth, BUTTON_HEIGHT);
        }
        mapDropdown.setEntries(mapDropdownEntries(), selectedMapKey);
        mapDropdown.renderButton(g, mx, my, dropdownX, topY, dropdownWidth, BUTTON_HEIGHT);

        previewHit = new HitBox(actionX, actionY, previewW, BUTTON_HEIGHT);
        int buildX = actionX + previewW + 4;
        SceneStagingClientController.Action stagingAction =
                SceneStagingClientController.getInstance().getAction(currentAssetKey());
        if (stagingAction == SceneStagingClientController.Action.BUILD) {
            buildAssetHit = new HitBox(buildX, actionY, buildW, BUTTON_HEIGHT);
            discardStagingHit = HitBox.EMPTY;
        } else {
            int discardW = Math.min(42, Math.max(30, buildW / 3));
            buildAssetHit = new HitBox(buildX, actionY,
                    Math.max(1, buildW - discardW - 2), BUTTON_HEIGHT);
            discardStagingHit = new HitBox(buildAssetHit.x() + buildAssetHit.width() + 2,
                    actionY, discardW, BUTTON_HEIGHT);
        }

        boolean runtimeMapAvailable = !runtimeMapKey.isBlank()
                && availableMapKeys().contains(runtimeMapKey);
        renderButton(g, jumpRuntimeMapHit,
                tr("screen.habitrain_core.scene_motion.map_jump_runtime"),
                MenuTheme.ACCENT_BLUE, runtimeMapAvailable && !runtimeMapKey.equals(selectedMapKey),
                runtimeMapKey.equals(selectedMapKey), mx, my);

        SceneAssetDescriptor currentDescriptor = currentAssetDescriptor();
        boolean hasAsset = currentDescriptor.isValid();
        boolean previewEnabled = previewActive || hasAsset;
        boolean buildEnabled = editable && (stagingAction == SceneStagingClientController.Action.AWAITING_CONFIRMATION
                || stagingAction == SceneStagingClientController.Action.POLICY_BLOCKED
                || (stagingAction == SceneStagingClientController.Action.BUILD
                && ClientPlayNetworking.canSend(SceneAssetBuildRequestC2S.TYPE)));
        renderButton(g, previewHit,
                tr(previewActive
                        ? "screen.habitrain_core.scene_motion.preview_on"
                        : "screen.habitrain_core.scene_motion.preview"),
                MenuTheme.ACCENT_MINT, previewEnabled, previewActive, mx, my);
        renderButton(g, buildAssetHit,
                tr(stagingAction == SceneStagingClientController.Action.DIAGNOSING
                        ? "screen.habitrain_core.scene_motion.asset_diagnosing"
                        : stagingAction == SceneStagingClientController.Action.POLICY_BLOCKED
                        ? "screen.habitrain_core.scene_motion.asset_retry_policy"
                        : stagingAction == SceneStagingClientController.Action.AWAITING_CONFIRMATION
                        ? "screen.habitrain_core.scene_motion.asset_publish"
                        : hasAsset
                        ? "screen.habitrain_core.scene_motion.asset_rebuild"
                        : "screen.habitrain_core.scene_motion.asset_missing"),
                hasAsset ? MenuTheme.ACCENT_BLUE : MenuTheme.DANGER,
                buildEnabled, false, mx, my);
        if (stagingAction != SceneStagingClientController.Action.BUILD) {
            renderButton(g, discardStagingHit,
                    tr("screen.habitrain_core.scene_motion.asset_discard"),
                    MenuTheme.DANGER, editable, false, mx, my);
        }

        int backgroundY = actionY + BUTTON_HEIGHT + 4;
        int rowWidth = Math.max(1, w - 12);
        int addWidth = 22;
        int deleteWidth = Math.min(54, Math.max(42, rowWidth / 7));
        int nameWidth = narrow ? rowWidth : Math.min(150, Math.max(72, rowWidth / 4));
        int backgroundDropdownWidth = narrow
                ? Math.max(48, rowWidth - addWidth - deleteWidth - 8)
                : Math.max(72, rowWidth - addWidth - deleteWidth - nameWidth - 12);
        backgroundDropdown.setEntries(backgroundDropdownEntries(), selectedBackgroundId);
        backgroundDropdown.renderButton(g, mx, my, dropdownX, backgroundY,
                backgroundDropdownWidth, BUTTON_HEIGHT);
        int nameX = narrow ? dropdownX : dropdownX + backgroundDropdownWidth + 4;
        int nameY = narrow ? backgroundY + BUTTON_HEIGHT + 4 : backgroundY;
        backgroundNameField.setX(nameX);
        backgroundNameField.setY(nameY);
        backgroundNameField.setWidth(nameWidth);
        backgroundNameField.setEditable(editable && !SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId));
        backgroundNameField.render(g, mx, my, delta);
        int controlsX = narrow ? dropdownX + backgroundDropdownWidth + 4 : nameX + nameWidth + 4;
        addBackgroundHit = new HitBox(controlsX, backgroundY, addWidth, BUTTON_HEIGHT);
        deleteBackgroundHit = new HitBox(addBackgroundHit.x() + addWidth + 4,
                backgroundY, deleteWidth, BUTTON_HEIGHT);
        renderButton(g, addBackgroundHit, "+", MenuTheme.ACCENT_MINT,
                editable && availableBackgroundIds().size() < SceneMotionSettings.MAX_BACKGROUNDS_PER_MAP,
                false, mx, my);
        renderButton(g, deleteBackgroundHit,
                tr("screen.habitrain_core.scene_motion.background_delete"), MenuTheme.DANGER,
                editable && !SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId), false, mx, my);
        addTooltip(addBackgroundHit, Component.translatable(
                "screen.habitrain_core.scene_motion.background_add_tooltip",
                availableBackgroundIds().size(), SceneMotionSettings.MAX_BACKGROUNDS_PER_MAP));
        addTooltip(deleteBackgroundHit, Component.translatable(
                "screen.habitrain_core.scene_motion.background_delete_tooltip"));

        int contentTop = nameY + BUTTON_HEIGHT + 5;
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
        addTooltip(speedLabelX, sy, Math.max(0, speedX - speedLabelX), FIELD_HEIGHT,
                "screen.habitrain_core.scene_motion.speed_help");
        placeField(speedField, speedX, sy, speedFieldWidth, mx, my, delta, g);
        sy += ROW_HEIGHT + 2;

        SceneBounds bounds = getCurrentDraft().getSourceBounds();
        boolean hasBounds = !bounds.isEmpty();
        String boundsText = bounds.isEmpty()
                ? tr("screen.habitrain_core.scene_motion.bounds_missing")
                : tr("screen.habitrain_core.scene_motion.bounds_value",
                bounds.sizeX(), bounds.sizeY(), bounds.sizeZ(), bounds.totalSections());
        boolean selectionMatchesEditor = SceneEditorMapPolicy.selectionBelongsToEditor(
                selectedMapKey, selectionMapKey);
        int selectionButtonWidth = currentSessionBounds.isEmpty() || !selectionMatchesEditor
                ? 0 : Math.min(Math.max(1, innerWidth - 8), Math.min(138, Math.max(64, innerWidth / 3)));
        int boundsTextWidth = innerWidth - selectionButtonWidth - (selectionButtonWidth > 0 ? 16 : 8);
        g.drawString(font, fit(tr("screen.habitrain_core.scene_motion.bounds", boundsText), boundsTextWidth),
                innerX + 4, sy + 5, bounds.isEmpty() ? MenuTheme.DANGER : MenuTheme.TEXT_PRIMARY, false);
        if (!currentSessionBounds.isEmpty() && selectionMatchesEditor) {
            applySelectionHit = new HitBox(innerX + innerWidth - selectionButtonWidth, sy,
                    selectionButtonWidth - 4, BUTTON_HEIGHT);
            renderButton(g, applySelectionHit,
                    tr("screen.habitrain_core.scene_motion.use_selection"),
                    MenuTheme.ACCENT_VIOLET, editable, false, mx, my);
        } else {
            applySelectionHit = HitBox.EMPTY;
        }
        sy += ROW_HEIGHT + 8;

        // ===== 方块与材质兼容性诊断卡片 =====
        sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                tr("screen.habitrain_core.scene_motion.section_compatibility"), MenuTheme.ACCENT_MINT);

        com.habitrain.core.scene.client.SceneCompatibilityReport compatReport =
                com.habitrain.core.scene.client.SceneRenderRuntime.getInstance().getLastCompatibilityReport();
        com.habitrain.core.scene.model.ScenePublishPolicy currentPolicy =
                com.habitrain.core.scene.client.SceneRenderRuntime.getInstance().getPublishPolicy();

        // 扫描按钮与策略切换行
        int scanBtnWidth = Math.min(100, Math.max(72, innerWidth / 4));
        compatScanHit = new HitBox(innerX + innerWidth - scanBtnWidth - 4, sy, scanBtnWidth, BUTTON_HEIGHT);
        renderButton(g, compatScanHit,
                tr("screen.habitrain_core.scene_motion.compat_scan"),
                MenuTheme.ACCENT_MINT, hasAsset, false, mx, my);

        int policyLabelWidth = Math.min(68, font.width(tr("screen.habitrain_core.scene_motion.compat_policy_label")) + 4);
        g.drawString(font, tr("screen.habitrain_core.scene_motion.compat_policy_label"),
                innerX + 4, sy + 5, MenuTheme.TEXT_SECONDARY, false);
        int policyX = innerX + 4 + policyLabelWidth;
        int policyStrictW = Math.max(48, Math.min(84, (innerWidth - scanBtnWidth - policyLabelWidth - 20) / 2));
        int policySkipW = policyStrictW;
        compatPolicyStrictHit = new HitBox(policyX, sy, policyStrictW, BUTTON_HEIGHT);
        compatPolicySkipWarnHit = new HitBox(policyX + policyStrictW + 4, sy, policySkipW, BUTTON_HEIGHT);
        renderButton(g, compatPolicyStrictHit,
                tr("screen.habitrain_core.scene_motion.compat_policy_strict"),
                MenuTheme.ACCENT_BLUE, editable, currentPolicy == com.habitrain.core.scene.model.ScenePublishPolicy.STRICT, mx, my);
        renderButton(g, compatPolicySkipWarnHit,
                tr("screen.habitrain_core.scene_motion.compat_policy_skip_warn"),
                MenuTheme.ACCENT_AMBER, editable, currentPolicy == com.habitrain.core.scene.model.ScenePublishPolicy.SKIP_AND_WARN, mx, my);
        addTooltip(innerX + 4, sy, policyLabelWidth + policyStrictW * 2 + 8, BUTTON_HEIGHT,
                "screen.habitrain_core.scene_motion.compat_policy_help");
        sy += ROW_HEIGHT + 2;

        if (compatReport != null && compatReport.totalBlocks() > 0) {
            double rate = compatReport.compatibilityPercentage(currentPolicy);
            String rateStr = String.format(Locale.ROOT, "%.1f", rate);

            int barHeight = 8;
            int barWidth = innerWidth - 8;
            int barX = innerX + 4;
            g.fill(barX, sy, barX + barWidth, sy + barHeight, MenuTheme.BG_ELEVATED);
            MenuTheme.outline(g, barX, sy, barWidth, barHeight, MenuTheme.BORDER_SOFT);
            int fillW = (int) Math.round((barWidth - 2) * (rate / 100.0));
            int barColor = rate >= 99.9 ? MenuTheme.ACCENT_MINT : rate >= 90.0 ? MenuTheme.ACCENT_AMBER : MenuTheme.DANGER;
            if (fillW > 0) {
                g.fill(barX + 1, sy + 1, barX + 1 + fillW, sy + barHeight - 1, barColor);
            }
            g.drawString(font, tr("screen.habitrain_core.scene_motion.compat_rate", rateStr),
                    barX + barWidth - font.width(tr("screen.habitrain_core.scene_motion.compat_rate", rateStr)) - 2,
                    sy - 10, barColor, false);
            sy += barHeight + 4;

            int colW = (innerWidth - 8) / 2;
            g.drawString(font, tr("screen.habitrain_core.scene_motion.compat_stat_normal", compatReport.normalBlocks()),
                    innerX + 4, sy, MenuTheme.ACCENT_MINT, false);
            g.drawString(font, tr("screen.habitrain_core.scene_motion.compat_stat_adapted", compatReport.adaptedBlocks()),
                    innerX + 4 + colW, sy, MenuTheme.ACCENT_BLUE, false);
            sy += 11;
            g.drawString(font, tr("screen.habitrain_core.scene_motion.compat_stat_warning", compatReport.warningBlocks()),
                    innerX + 4, sy, MenuTheme.ACCENT_AMBER, false);
            g.drawString(font, tr("screen.habitrain_core.scene_motion.compat_stat_severe", compatReport.severeBlocks()),
                    innerX + 4 + colW, sy, compatReport.severeBlocks() > 0 ? MenuTheme.DANGER : MenuTheme.TEXT_SECONDARY, false);
            sy += 13;

            List<com.habitrain.core.scene.client.SceneCompatibilityReport.Entry> issues = compatReport.getIssues();
            if (!issues.isEmpty()) {
                com.habitrain.core.scene.client.SceneCompatibilityReport.Entry first = issues.get(0);
                String issueStr = tr("screen.habitrain_core.scene_motion.compat_issue_detail",
                        first.blockId().toString(), compatibilityIssueLabel(first.issueType()));
                int copyBtnW = Math.min(84, font.width(tr("screen.habitrain_core.scene_motion.compat_copy_block_id")) + 12);
                int issueTextW = Math.max(1, innerWidth - copyBtnW - 16);
                g.drawString(font, fit(issueStr, issueTextW), innerX + 4, sy + 4,
                        first.isBlocking(currentPolicy) ? MenuTheme.DANGER : MenuTheme.ACCENT_AMBER, false);
                compatCopyBlockIdHit = new HitBox(innerX + innerWidth - copyBtnW - 4, sy, copyBtnW, BUTTON_HEIGHT);
                renderButton(g, compatCopyBlockIdHit,
                        tr("screen.habitrain_core.scene_motion.compat_copy_block_id"),
                        MenuTheme.ACCENT_BLUE, true, false, mx, my);
                sy += ROW_HEIGHT;
            } else {
                compatCopyBlockIdHit = HitBox.EMPTY;
            }
        } else {
            g.drawString(font, fit(tr("screen.habitrain_core.scene_motion.compat_no_report"), innerWidth - 8),
                    innerX + 4, sy + 3, MenuTheme.TEXT_DIM, false);
            compatCopyBlockIdHit = HitBox.EMPTY;
            sy += ROW_HEIGHT;
        }
        sy += 6;

        // ===== 运动方式单选 =====
        sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                tr("screen.habitrain_core.scene_motion.section_motion_mode"), MenuTheme.ACCENT_BLUE);
        int modeBtnWidth = Math.max(70, (innerWidth - 12) / 2);
        motionLinearHit = new HitBox(innerX + 4, sy, modeBtnWidth, BUTTON_HEIGHT);
        motionOrbitHit = new HitBox(innerX + 8 + modeBtnWidth, sy, innerWidth - 12 - modeBtnWidth, BUTTON_HEIGHT);
        renderButton(g, motionLinearHit,
                tr("screen.habitrain_core.scene_motion.motion_mode_linear"), MenuTheme.ACCENT_BLUE,
                editable, motionMode == SceneMotionMode.LINEAR, mx, my);
        renderButton(g, motionOrbitHit,
                tr("screen.habitrain_core.scene_motion.motion_mode_orbit"), MenuTheme.ACCENT_VIOLET,
                editable, motionMode == SceneMotionMode.ORBIT, mx, my);
        sy += ROW_HEIGHT;
        sy += drawWrapped(g, Component.translatable("screen.habitrain_core.scene_motion.motion_mode_help"),
                innerX + 4, sy, innerWidth - 8, MenuTheme.TEXT_SECONDARY) + 4;

        if (motionMode == SceneMotionMode.LINEAR && hasBounds) {
            int addOrbitWidth = Math.min(160, innerWidth - 8);
            addOrbitHit = new HitBox(innerX + 4, sy, addOrbitWidth, BUTTON_HEIGHT);
            renderButton(g, addOrbitHit,
                    tr("screen.habitrain_core.scene_motion.add_orbit"),
                    MenuTheme.ACCENT_VIOLET, editable, false, mx, my);
            sy += ROW_HEIGHT + 2;
        } else {
            addOrbitHit = HitBox.EMPTY;
        }

        // ===== 场景摆放与初始朝向（模型摆放点与固定方向，两种模式共用） =====
        int transformHeaderY = sy;
        sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                tr("screen.habitrain_core.scene_motion.section_transform"), MenuTheme.ACCENT_AMBER);
        addTooltip(innerX, transformHeaderY, innerWidth, 17,
                "screen.habitrain_core.scene_motion.section_transform_help");
        int originRowY = sy;
        sy = renderTripleFields(g, tr("screen.habitrain_core.scene_motion.origin"),
                new String[]{"X", "Y", "Z"}, originXField, originYField, originZField,
                innerX, sy, innerWidth, narrow, mx, my, delta);
        addTooltip(innerX, originRowY, innerWidth, narrow ? ROW_HEIGHT * 2 : ROW_HEIGHT,
                "screen.habitrain_core.scene_motion.origin_help");
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
        int pivotRowY = sy;
        sy = renderTripleFields(g, tr("screen.habitrain_core.scene_motion.pivot"),
                new String[]{"X", "Y", "Z"}, pivotXField, pivotYField, pivotZField,
                innerX, sy, innerWidth, narrow, mx, my, delta);
        addTooltip(innerX, pivotRowY, innerWidth, narrow ? ROW_HEIGHT * 2 : ROW_HEIGHT,
                "screen.habitrain_core.scene_motion.pivot_help");
        int rotationRowY = sy;
        sy = renderTripleFields(g, tr("screen.habitrain_core.scene_motion.rotation"),
                new String[]{tr("screen.habitrain_core.scene_motion.yaw"),
                        tr("screen.habitrain_core.scene_motion.pitch"),
                        tr("screen.habitrain_core.scene_motion.roll")},
                rotYawField, rotPitchField, rotRollField,
                innerX, sy, innerWidth, narrow, mx, my, delta);
        addTooltip(innerX, rotationRowY, innerWidth, narrow ? ROW_HEIGHT * 2 : ROW_HEIGHT,
                "screen.habitrain_core.scene_motion.rotation_help");
        int phaseRowY = sy;
        sy = renderSingleFieldRow(g, tr("screen.habitrain_core.scene_motion.phase_offset"),
                phaseOffsetField, innerX, sy, innerWidth, mx, my, delta);
        addTooltip(innerX, phaseRowY, innerWidth, ROW_HEIGHT,
                "screen.habitrain_core.scene_motion.phase_offset_help");
        sy += 6;

        if (motionMode == SceneMotionMode.LINEAR) {
            // ===== 直线移动模式：方向与区间 =====
            sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                    tr("screen.habitrain_core.scene_motion.section_direction"), MenuTheme.ACCENT_BLUE);
            sy += drawWrapped(g, Component.translatable("screen.habitrain_core.scene_motion.direction_help"),
                    innerX + 4, sy, innerWidth - 8, MenuTheme.TEXT_SECONDARY) + 4;
            int directionRowY = sy;
            sy = renderTripleFields(g, tr("screen.habitrain_core.scene_motion.direction_ratio"),
                    new String[]{"X", "Y", "Z"}, dirXField, dirYField, dirZField,
                    innerX, sy, innerWidth, narrow, mx, my, delta);
            addTooltip(innerX, directionRowY, innerWidth, narrow ? ROW_HEIGHT * 2 : ROW_HEIGHT,
                    "screen.habitrain_core.scene_motion.direction_ratio_help");

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
                    tr("screen.habitrain_core.scene_motion.section_loop"), MenuTheme.ACCENT_VIOLET);
            loopHit = new HitBox(innerX + 4, sy, toggleWidth, FIELD_HEIGHT);
            PillToggle.render(g, font, loopHit.x(), loopHit.y(), loopHit.width(), loopHit.height(), loopState,
                    tr("screen.habitrain_core.scene_motion.loop_on"),
                    tr("screen.habitrain_core.scene_motion.loop_off"));
            int loopHelpX = loopHit.x() + loopHit.width() + 12;
            g.drawString(font, fit(tr("screen.habitrain_core.scene_motion.loop_distance_help"),
                            Math.max(0, innerX + innerWidth - loopHelpX - 4)),
                    loopHelpX, sy + 5, MenuTheme.TEXT_SECONDARY, false);
            sy += ROW_HEIGHT;

            int modeLabelWidth = narrow ? 0 : Math.min(116,
                    font.width(tr("screen.habitrain_core.scene_motion.loop_source")) + 12);
            if (narrow) {
                g.drawString(font, tr("screen.habitrain_core.scene_motion.loop_source"),
                        innerX + 4, sy + 5, MenuTheme.TEXT_SECONDARY, false);
                sy += ROW_HEIGHT;
            } else {
                g.drawString(font, tr("screen.habitrain_core.scene_motion.loop_source"),
                        innerX + 4, sy + 5, MenuTheme.TEXT_SECONDARY, false);
            }
            int modeStartX = innerX + 4 + modeLabelWidth;
            int modeAvailable = innerWidth - 8 - modeLabelWidth;
            int modeButtonWidth = Math.max(68, (modeAvailable - 4) / 2);
            loopAutoHit = new HitBox(modeStartX, sy, modeButtonWidth, BUTTON_HEIGHT);
            loopCustomHit = new HitBox(modeStartX + modeButtonWidth + 4, sy, modeButtonWidth, BUTTON_HEIGHT);
            renderButton(g, loopAutoHit,
                    tr("screen.habitrain_core.scene_motion.loop_source_auto"), MenuTheme.ACCENT_BLUE,
                    editable && loopState, loopDistanceMode == SceneLoopDistanceMode.AUTO, mx, my);
            renderButton(g, loopCustomHit,
                    tr("screen.habitrain_core.scene_motion.loop_source_custom"), MenuTheme.ACCENT_MINT,
                    editable && loopState, loopDistanceMode == SceneLoopDistanceMode.CUSTOM, mx, my);
            if (loopKeyboardFocus == 0) {
                HitBox focusedHit = loopDistanceMode == SceneLoopDistanceMode.AUTO ? loopAutoHit : loopCustomHit;
                MenuTheme.outline(g, focusedHit.x() - 1, focusedHit.y() - 1,
                        focusedHit.width() + 2, focusedHit.height() + 2, MenuTheme.ACCENT_MINT);
            }
            addTooltip(innerX, sy, innerWidth, BUTTON_HEIGHT,
                    "screen.habitrain_core.scene_motion.loop_source_help");
            sy += ROW_HEIGHT + 2;

            int distRowY = sy;
            sy = renderSingleFieldRow(g, tr("screen.habitrain_core.scene_motion.loop_distance"),
                    loopDistField, innerX, sy, innerWidth, mx, my, delta);
            addTooltip(innerX, distRowY, innerWidth, ROW_HEIGHT,
                    "screen.habitrain_core.scene_motion.loop_distance_field_help");

            SceneMotionMath.LoopDistances loopDistances = loopDistancesFromFields();
            rangePreviewHit = new HitBox(innerX + 4, sy, innerWidth - 8, SceneRangePreview.HEIGHT);
            SceneMotionMath.SeamRelation seam = SceneMotionMath.seamRelation(bounds, normalizedDirection, loopSettingsFromFields());
            double phase = parseDouble(phaseOffsetField.getValue(), 0.0);
            SceneRangePreview.render(g, font, rangePreviewHit.x(), rangePreviewHit.y(), rangePreviewHit.width(),
                    loopDistanceMode, loopDistances, seam, phase, loopState);
            double speedForPreview = Math.abs(getCurrentDraft().getSpeedBlocksPerSecond());
            double cycleSeconds = speedForPreview > 1.0e-4 ? loopDistances.effectiveDistance() / speedForPreview : Double.NaN;
            addTooltip(rangePreviewHit, Component.translatable(
                    "screen.habitrain_core.scene_motion.range_tooltip",
                    format(speedForPreview), Double.isFinite(cycleSeconds)
                            ? format(cycleSeconds) : tr("screen.habitrain_core.scene_motion.range_stopped"),
                    format(loopDistances.effectiveDistance())));
            sy += SceneRangePreview.HEIGHT + 4;

            rangeSliderHit = new HitBox(innerX + 4, sy, innerWidth - 8, 12);
            renderRangeSlider(g, rangeSliderHit, loopDistanceValidation(),
                    editable && loopState && loopDistanceMode == SceneLoopDistanceMode.CUSTOM);
            if (loopKeyboardFocus == 1) {
                MenuTheme.outline(g, rangeSliderHit.x() - 1, rangeSliderHit.y(),
                        rangeSliderHit.width() + 2, rangeSliderHit.height(), MenuTheme.ACCENT_MINT);
            }
            sy += 16;

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
                    MenuTheme.ACCENT_VIOLET, editable && loopState && hasBounds,
                    loopKeyboardFocus == 2, mx, my);
            sy += ROW_HEIGHT;
            SceneProfileValidator.LoopDistanceValidation loopValidation = loopDistanceValidation();
            if (!loopValidation.isValid()) {
                sy += drawWrapped(g, Component.translatable(loopDistanceErrorKey(loopValidation.error()),
                                format(SceneLoopSettings.MIN_DISTANCE), format(SceneLoopSettings.MAX_DISTANCE)),
                        innerX + 4, sy, innerWidth - 8, MenuTheme.DANGER) + 2;
            } else if (!loopState) {
                sy += drawWrapped(g, Component.translatable(
                                "screen.habitrain_core.scene_motion.loop_inactive_help"),
                        innerX + 4, sy, innerWidth - 8, MenuTheme.TEXT_DIM) + 2;
            }
            sy += drawWrapped(g, Component.translatable("screen.habitrain_core.scene_motion.loop_help"),
                    innerX + 4, sy, innerWidth - 8, MenuTheme.TEXT_DIM) + 4;
        } else {
            // ===== 围绕中心旋转模式配置 =====
            sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                    tr("screen.habitrain_core.scene_motion.section_orbit"), MenuTheme.ACCENT_VIOLET);

            // 1. 绕转平面选择
            g.drawString(font, tr("screen.habitrain_core.scene_motion.orbit_axis"), innerX + 4, sy + 5, MenuTheme.TEXT_SECONDARY, false);
            int axisLabelW = font.width(tr("screen.habitrain_core.scene_motion.orbit_axis")) + 8;
            int axisBtnW = Math.max(50, (innerWidth - 8 - axisLabelW - 8) / 3);
            orbitAxisYHit = new HitBox(innerX + 4 + axisLabelW, sy, axisBtnW, BUTTON_HEIGHT);
            orbitAxisXHit = new HitBox(innerX + 4 + axisLabelW + axisBtnW + 4, sy, axisBtnW, BUTTON_HEIGHT);
            orbitAxisZHit = new HitBox(innerX + 4 + axisLabelW + (axisBtnW + 4) * 2, sy, axisBtnW, BUTTON_HEIGHT);
            renderButton(g, orbitAxisYHit, tr("screen.habitrain_core.scene_motion.orbit_axis_y"),
                    MenuTheme.ACCENT_BLUE, editable, orbitAxis == SceneOrbitAxis.Y, mx, my);
            renderButton(g, orbitAxisXHit, tr("screen.habitrain_core.scene_motion.orbit_axis_x"),
                    MenuTheme.ACCENT_BLUE, editable, orbitAxis == SceneOrbitAxis.X, mx, my);
            renderButton(g, orbitAxisZHit, tr("screen.habitrain_core.scene_motion.orbit_axis_z"),
                    MenuTheme.ACCENT_BLUE, editable, orbitAxis == SceneOrbitAxis.Z, mx, my);
            sy += ROW_HEIGHT + 2;

            // 2. 绕转中心来源
            g.drawString(font, tr("screen.habitrain_core.scene_motion.orbit_center_source"), innerX + 4, sy + 5, MenuTheme.TEXT_SECONDARY, false);
            int centerSourceLabelW = font.width(tr("screen.habitrain_core.scene_motion.orbit_center_source")) + 8;
            int centerSourceBtnW = Math.max(68, (innerWidth - 8 - centerSourceLabelW - 4) / 2);
            orbitCenterWorldHit = new HitBox(innerX + 4 + centerSourceLabelW, sy, centerSourceBtnW, BUTTON_HEIGHT);
            orbitCenterModelHit = new HitBox(innerX + 4 + centerSourceLabelW + centerSourceBtnW + 4, sy, centerSourceBtnW, BUTTON_HEIGHT);
            renderButton(g, orbitCenterWorldHit, tr("screen.habitrain_core.scene_motion.orbit_center_world_block"),
                    MenuTheme.ACCENT_MINT, editable, orbitCenterMode == SceneOrbitCenterMode.WORLD_BLOCK, mx, my);
            renderButton(g, orbitCenterModelHit, tr("screen.habitrain_core.scene_motion.orbit_center_model_center"),
                    MenuTheme.ACCENT_AMBER, editable, orbitCenterMode == SceneOrbitCenterMode.MODEL_CENTER, mx, my);
            sy += ROW_HEIGHT + 2;

            // 中心坐标与半径摘要 + 手选世界中心按钮
            SceneOrbitSettings orbitSettings = getCurrentDraft().getOrbit();
            double[] resolvedCenter = SceneOrbitMath.resolveOrbitCenter(getCurrentDraft().toProfile());
            double radius = SceneOrbitMath.resolveRadius(getCurrentDraft().toProfile());
            if (orbitCenterMode == SceneOrbitCenterMode.WORLD_BLOCK) {
                int pickBtnW = Math.min(130, Math.max(70, innerWidth / 3));
                orbitCenterPickHit = new HitBox(innerX + innerWidth - pickBtnW - 4, sy, pickBtnW, BUTTON_HEIGHT);
                renderButton(g, orbitCenterPickHit, tr("screen.habitrain_core.scene_motion.orbit_center_pick"),
                        MenuTheme.ACCENT_MINT, editable, false, mx, my);
                String centerText = tr("screen.habitrain_core.scene_motion.orbit_center_summary",
                        resolvedCenter[0], resolvedCenter[1], resolvedCenter[2]);
                g.drawString(font, fit(centerText, innerWidth - pickBtnW - 12), innerX + 4, sy + 5, MenuTheme.TEXT_PRIMARY, false);
                sy += ROW_HEIGHT;
                String radiusText = tr("screen.habitrain_core.scene_motion.orbit_radius_summary", radius);
                g.drawString(font, radiusText, innerX + 4, sy + 3, MenuTheme.ACCENT_MINT, false);
                sy += ROW_HEIGHT;
            } else {
                orbitCenterPickHit = HitBox.EMPTY;
                String centerText = tr("screen.habitrain_core.scene_motion.orbit_center_summary_model",
                        resolvedCenter[0], resolvedCenter[1], resolvedCenter[2]);
                g.drawString(font, fit(centerText, innerWidth - 8), innerX + 4, sy + 5, MenuTheme.ACCENT_AMBER, false);
                sy += ROW_HEIGHT;
                String radiusText = tr("screen.habitrain_core.scene_motion.orbit_radius_summary", radius);
                g.drawString(font, radiusText, innerX + 4, sy + 3, MenuTheme.ACCENT_MINT, false);
                sy += ROW_HEIGHT;
            }

            // 3. 方向盘 UI
            orbitDialHit = new HitBox(innerX + 4, sy, innerWidth - 8, SceneAngleDial.HEIGHT);
            double startAngleVal = parseDouble(orbitStartAngleField.getValue(), orbitSettings.getStartAngleDegrees());
            double sweepVal = parseDouble(orbitSweepField.getValue(), orbitSettings.getSweepDegrees());
            int instanceCountVal = orbitInstanceCountValidation().isValid()
                    ? orbitInstanceCountValidation().value() : orbitSettings.getInstanceCount();
            double instanceSpreadVal = orbitInstanceSpreadValidation().isValid()
                    ? orbitInstanceSpreadValidation().value() : orbitSettings.getInstanceSpreadDegrees();
            SceneAngleDial.render(g, font, orbitDialHit.x(), orbitDialHit.y(), orbitDialHit.width(),
                    orbitAxis, startAngleVal, sweepVal, orbitClockwise,
                    instanceCountVal, instanceSpreadVal, editable,
                    orbitCenterMode == SceneOrbitCenterMode.MODEL_CENTER, orbitDialFocused);
            sy += SceneAngleDial.HEIGHT + 6;

            // 4. 起始方向输入框与 8 个快捷方向按钮
            int startAngleRowY = sy;
            sy = renderSingleFieldRow(g, tr("screen.habitrain_core.scene_motion.orbit_start_angle"),
                    orbitStartAngleField, innerX, sy, innerWidth, mx, my, delta);
            addTooltip(innerX, startAngleRowY, innerWidth, ROW_HEIGHT,
                    "screen.habitrain_core.scene_motion.orbit_start_angle_help");

            int dirQuickGap = 3;
            int dirQuickW = Math.max(22, (innerWidth - 8 - dirQuickGap * 7) / 8);
            for (int i = 0; i < ORBIT_DIR_PRESETS.length; i++) {
                orbitQuickDirHits[i] = new HitBox(innerX + 4 + i * (dirQuickW + dirQuickGap), sy, dirQuickW, BUTTON_HEIGHT);
                boolean active = Math.abs(SceneOrbitMath.normalizeAngle(startAngleVal) - ORBIT_DIR_PRESETS[i].angle()) < 1.0;
                renderButton(g, orbitQuickDirHits[i], tr(ORBIT_DIR_PRESETS[i].labelKey()), MenuTheme.ACCENT_BLUE,
                        editable && orbitCenterMode == SceneOrbitCenterMode.WORLD_BLOCK, active, mx, my);
            }
            sy += ROW_HEIGHT + 2;

            // 5. 旋转范围输入框与 5 个快捷角度按钮
            int sweepRowY = sy;
            sy = renderSingleFieldRow(g, tr("screen.habitrain_core.scene_motion.orbit_sweep"),
                    orbitSweepField, innerX, sy, innerWidth, mx, my, delta);
            addTooltip(innerX, sweepRowY, innerWidth, ROW_HEIGHT,
                    "screen.habitrain_core.scene_motion.orbit_sweep_help");

            int angleQuickGap = 4;
            int angleQuickW = Math.max(30, (innerWidth - 8 - angleQuickGap * 4) / 5);
            for (int i = 0; i < ORBIT_SWEEP_PRESETS.length; i++) {
                orbitQuickAngleHits[i] = new HitBox(innerX + 4 + i * (angleQuickW + angleQuickGap), sy, angleQuickW, BUTTON_HEIGHT);
                boolean active = Math.abs(sweepVal - ORBIT_SWEEP_PRESETS[i]) < 1.0;
                renderButton(g, orbitQuickAngleHits[i], String.format(Locale.ROOT, "%.0f°", ORBIT_SWEEP_PRESETS[i]),
                        MenuTheme.ACCENT_MINT, editable, active, mx, my);
            }
            sy += ROW_HEIGHT + 2;

            // 6. 旋转方向切换按钮
            g.drawString(font, tr("screen.habitrain_core.scene_motion.orbit_direction"), innerX + 4, sy + 5, MenuTheme.TEXT_SECONDARY, false);
            int dirLabelW = font.width(tr("screen.habitrain_core.scene_motion.orbit_direction")) + 8;
            int dirBtnW = Math.max(54, (innerWidth - 8 - dirLabelW - 4) / 2);
            orbitClockwiseHit = new HitBox(innerX + 4 + dirLabelW, sy, dirBtnW, BUTTON_HEIGHT);
            orbitCounterClockwiseHit = new HitBox(innerX + 4 + dirLabelW + dirBtnW + 4, sy, dirBtnW, BUTTON_HEIGHT);
            renderButton(g, orbitClockwiseHit, tr("screen.habitrain_core.scene_motion.orbit_clockwise"),
                    MenuTheme.ACCENT_BLUE, editable, orbitClockwise, mx, my);
            renderButton(g, orbitCounterClockwiseHit, tr("screen.habitrain_core.scene_motion.orbit_counter_clockwise"),
                    MenuTheme.ACCENT_BLUE, editable, !orbitClockwise, mx, my);
            sy += ROW_HEIGHT + 2;

            // 7. 旋转速度输入框
            int speedRowY = sy;
            sy = renderSingleFieldRow(g, tr("screen.habitrain_core.scene_motion.orbit_speed"),
                    orbitSpeedField, innerX, sy, innerWidth, mx, my, delta);
            addTooltip(innerX, speedRowY, innerWidth, ROW_HEIGHT,
                    "screen.habitrain_core.scene_motion.orbit_speed_help");

            // 8. 上下/径向浮动与浮动频率
            int bobRowY = sy;
            sy = renderDoubleFields(g,
                    tr("screen.habitrain_core.scene_motion.orbit_vertical_bob"), orbitVerticalBobField,
                    tr("screen.habitrain_core.scene_motion.orbit_radial_bob"), orbitRadialBobField,
                    innerX, sy, innerWidth, mx, my, delta);
            addTooltip(innerX, bobRowY, innerWidth, ROW_HEIGHT,
                    "screen.habitrain_core.scene_motion.orbit_bob_help");
            int bobCyclesRowY = sy;
            sy = renderSingleFieldRow(g, tr("screen.habitrain_core.scene_motion.orbit_bob_cycles"),
                    orbitBobCyclesField, innerX, sy, innerWidth, mx, my, delta);
            addTooltip(innerX, bobCyclesRowY, innerWidth, ROW_HEIGHT,
                    "screen.habitrain_core.scene_motion.orbit_bob_cycles_help");
            if (!orbitVerticalBobValidation().isValid()
                    || !orbitRadialBobValidation().isValid()
                    || !orbitBobCyclesValidation().isValid()) {
                sy += drawWrapped(g, Component.translatable(
                                "screen.habitrain_core.scene_motion.orbit_bob_invalid",
                                format(SceneOrbitSettings.MAX_BOB_AMPLITUDE),
                                format(SceneOrbitSettings.MAX_BOB_CYCLES)),
                        innerX + 4, sy, innerWidth - 8, MenuTheme.DANGER) + 2;
            }

            // 9. 多副本数量与分布角度
            int instanceRowY = sy;
            sy = renderDoubleFields(g,
                    tr("screen.habitrain_core.scene_motion.orbit_instance_count"), orbitInstanceCountField,
                    tr("screen.habitrain_core.scene_motion.orbit_instance_spread"), orbitInstanceSpreadField,
                    innerX, sy, innerWidth, mx, my, delta);
            addTooltip(innerX, instanceRowY, innerWidth, ROW_HEIGHT,
                    "screen.habitrain_core.scene_motion.orbit_instances_help");
            if (!orbitInstanceCountValidation().isValid() || !orbitInstanceSpreadValidation().isValid()) {
                sy += drawWrapped(g, Component.translatable(
                                "screen.habitrain_core.scene_motion.orbit_instances_invalid",
                                SceneOrbitSettings.MIN_INSTANCES, SceneOrbitSettings.MAX_INSTANCES,
                                format(SceneOrbitSettings.MIN_SPREAD), format(SceneOrbitSettings.MAX_SPREAD)),
                        innerX + 4, sy, innerWidth - 8, MenuTheme.DANGER) + 2;
            } else {
                if (instanceCountVal > 1 && (radius < 1.0e-4
                        || orbitCenterMode == SceneOrbitCenterMode.MODEL_CENTER)) {
                    sy += drawWrapped(g, Component.translatable(
                                    "screen.habitrain_core.scene_motion.orbit_instances_overlap_warning"),
                            innerX + 4, sy, innerWidth - 8, MenuTheme.ACCENT_AMBER) + 2;
                }
                if (instanceCountVal >= 8) {
                    sy += drawWrapped(g, Component.translatable(
                                    "screen.habitrain_core.scene_motion.orbit_instances_performance_warning"),
                            innerX + 4, sy, innerWidth - 8, MenuTheme.ACCENT_AMBER) + 2;
                }
                SceneRenderRuntime runtime = SceneRenderRuntime.getInstance();
                Component workload;
                if (runtime.isPreviewActiveFor(currentAssetKey())
                        && runtime.getLastConfiguredOrbitInstances() == instanceCountVal) {
                    workload = Component.translatable(
                            "screen.habitrain_core.scene_motion.orbit_workload_live",
                            runtime.getLastVisibleOrbitInstances(), instanceCountVal);
                } else {
                    workload = Component.translatable(
                            "screen.habitrain_core.scene_motion.orbit_workload_estimate",
                            instanceCountVal);
                }
                sy += drawWrapped(g, workload, innerX + 4, sy, innerWidth - 8, MenuTheme.TEXT_DIM) + 3;
            }

            // 10. 模型朝向切换按钮
            g.drawString(font, tr("screen.habitrain_core.scene_motion.orbit_rotate_model"), innerX + 4, sy + 5, MenuTheme.TEXT_SECONDARY, false);
            int rotModelLabelW = font.width(tr("screen.habitrain_core.scene_motion.orbit_rotate_model")) + 8;
            int rotModelBtnW = Math.max(68, (innerWidth - 8 - rotModelLabelW - 4) / 2);
            orbitRotateModelWithHit = new HitBox(innerX + 4 + rotModelLabelW, sy, rotModelBtnW, BUTTON_HEIGHT);
            orbitRotateModelFixedHit = new HitBox(innerX + 4 + rotModelLabelW + rotModelBtnW + 4, sy, rotModelBtnW, BUTTON_HEIGHT);
            boolean isModelCenter = orbitCenterMode == SceneOrbitCenterMode.MODEL_CENTER;
            renderButton(g, orbitRotateModelWithHit, tr("screen.habitrain_core.scene_motion.orbit_rotate_model_with"),
                    MenuTheme.ACCENT_AMBER, editable, orbitRotateModel || isModelCenter, mx, my);
            renderButton(g, orbitRotateModelFixedHit, tr("screen.habitrain_core.scene_motion.orbit_rotate_model_fixed"),
                    MenuTheme.ACCENT_AMBER, editable && !isModelCenter, !orbitRotateModel && !isModelCenter, mx, my);
            if (isModelCenter) {
                addTooltip(innerX + 4 + rotModelLabelW, sy, rotModelBtnW * 2 + 4, BUTTON_HEIGHT,
                        "screen.habitrain_core.scene_motion.orbit_rotate_model_locked_help");
            }
            sy += ROW_HEIGHT + 4;

            // 11. 动态路径摘要文字
            String pathSummary = SceneAngleDial.getPathSummary(orbitAxis, startAngleVal, sweepVal, orbitClockwise);
            sy += drawWrapped(g, Component.literal(pathSummary), innerX + 4, sy, innerWidth - 8, MenuTheme.ACCENT_BLUE) + 6;
        }

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
        addTooltip(renderLabelX, sy, Math.max(0, renderFieldX - renderLabelX), FIELD_HEIGHT,
                "screen.habitrain_core.scene_motion.render_distance_help");
        placeField(renderMaxDistField, renderFieldX, sy, renderFieldWidth, mx, my, delta, g);
        sy += ROW_HEIGHT + 8;

        sy = renderVisibilityDiagnostics(g, innerX, sy, innerWidth);
        sy += 8;

        if (!SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId)) {
            soundHit = HitBox.EMPTY;
            shakeHit = HitBox.EMPTY;
            sy = SectionHeader.render(g, font, innerX, sy, innerWidth,
                    tr("screen.habitrain_core.scene_motion.section_shared"), MenuTheme.ACCENT_BLUE);
            sy += drawWrapped(g, Component.translatable(
                            "screen.habitrain_core.scene_motion.shared_managed_by_default"),
                    innerX + 4, sy, innerWidth - 8, MenuTheme.TEXT_SECONDARY) + 10;
        } else {
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
        }

        g.disableScissor();
        renderedContentHeight = Math.max(contentViewportHeight, sy - contentStartY + 8);
        scroll.setContentHeight(renderedContentHeight);
        scroll.render(g);
        mapDropdown.renderOverlay(g, mx, my, delta, y + h);
        backgroundDropdown.renderOverlay(g, mx, my, delta, y + h);
        if (!mapDropdown.isOpen() && !backgroundDropdown.isOpen()) renderPageTooltip(g, mx, my);
        mapDropdown.renderTooltip(g, mx, my);
        backgroundDropdown.renderTooltip(g, mx, my);
    }

    private void addTooltip(int x, int y, int width, int height, String translationKey) {
        if (width <= 0 || height <= 0) return;
        tooltipTargets.add(new TooltipTarget(new HitBox(x, y, width, height),
                Component.translatable(translationKey)));
    }

    private void addTooltip(HitBox bounds, Component text) {
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0 || text == null) return;
        tooltipTargets.add(new TooltipTarget(bounds, text));
    }

    private void renderPageTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (TooltipTarget target : tooltipTargets) {
            if (target.bounds().contains(mouseX, mouseY)) {
                graphics.renderTooltip(font, target.text(), mouseX, mouseY);
                return;
            }
        }
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

    private int renderVisibilityDiagnostics(GuiGraphics graphics, int x, int y, int width) {
        y = SectionHeader.render(graphics, font, x, y, width,
                tr("screen.habitrain_core.scene_motion.section_visibility"), MenuTheme.ACCENT_MINT);
        SceneProfile profile = visibilityProfileFromFields();
        SceneProjectionDiagnostics.Snapshot projection = SceneProjectionDiagnostics.snapshot();
        Minecraft minecraft = Minecraft.getInstance();
        var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        SceneVisibilityAdvisor.Advice advice = SceneVisibilityAdvisor.advise(profile,
                camera.x, camera.y, camera.z,
                projection.clientMaximumChunks(), projection.clientConfiguredChunks(),
                projection.effectiveChunks());

        int textX = x + 4;
        int textWidth = width - 8;
        if (!advice.animationBounds().valid()) {
            return y + drawWrapped(graphics, Component.translatable(
                            "screen.habitrain_core.scene_motion.visibility_no_bounds"),
                    textX, y, textWidth, MenuTheme.TEXT_DIM);
        }

        SceneVisibilityAdvisor.AnimationBounds bounds = advice.animationBounds();
        y += drawWrapped(graphics, Component.translatable(
                        "screen.habitrain_core.scene_motion.visibility_bounds",
                        format(bounds.sizeX()), format(bounds.sizeY()), format(bounds.sizeZ()),
                        bounds.finiteEnvelope()
                                ? Component.translatable("screen.habitrain_core.scene_motion.visibility_finite")
                                : Component.translatable("screen.habitrain_core.scene_motion.visibility_single_pass")),
                textX, y, textWidth, MenuTheme.TEXT_SECONDARY) + 2;
        y += drawWrapped(graphics, Component.translatable(
                        "screen.habitrain_core.scene_motion.visibility_values",
                        advice.clientConfiguredChunks(), advice.effectiveChunks(),
                        advice.rawRequiredChunks(), advice.clientMaximumChunks()),
                textX, y, textWidth, MenuTheme.TEXT_SECONDARY) + 2;

        String statusKey = switch (advice.viewStatus()) {
            case ENOUGH -> "screen.habitrain_core.scene_motion.visibility_enough";
            case CLIENT_SETTING_LOW -> "screen.habitrain_core.scene_motion.visibility_client_low";
            case SERVER_LIMIT_LOW -> "screen.habitrain_core.scene_motion.visibility_server_low";
            case UNATTAINABLE -> "screen.habitrain_core.scene_motion.visibility_unattainable";
        };
        int statusColor = advice.viewStatus() == SceneVisibilityAdvisor.ViewStatus.ENOUGH
                ? MenuTheme.ACCENT_MINT : MenuTheme.ACCENT_AMBER;
        y += drawWrapped(graphics, Component.translatable(statusKey, advice.rawRequiredChunks()),
                textX, y, textWidth, statusColor) + 2;

        if (advice.sceneDistanceSufficient()) {
            y += drawWrapped(graphics, Component.translatable(
                            "screen.habitrain_core.scene_motion.visibility_scene_limit_ok",
                            format(advice.configuredSceneDistanceBlocks())),
                    textX, y, textWidth, MenuTheme.TEXT_DIM) + 2;
        } else {
            y += drawWrapped(graphics, Component.translatable(
                            "screen.habitrain_core.scene_motion.visibility_scene_limit_low",
                            format(advice.configuredSceneDistanceBlocks()),
                            format(advice.requiredSceneDistanceBlocks())),
                    textX, y, textWidth, MenuTheme.DANGER) + 2;
        }

        Component renderer = Component.translatable(projection.irisLoaded()
                ? "screen.habitrain_core.scene_motion.visibility_renderer_iris"
                : "screen.habitrain_core.scene_motion.visibility_renderer_vanilla");
        String projectionKey = projection.measured()
                ? "screen.habitrain_core.scene_motion.visibility_projection_measured"
                : "screen.habitrain_core.scene_motion.visibility_projection_approximate";
        y += drawWrapped(graphics, Component.translatable(projectionKey,
                        renderer, format(projection.farPlaneBlocks())),
                textX, y, textWidth, MenuTheme.TEXT_DIM) + 2;
        return y + drawWrapped(graphics, Component.translatable(
                        "screen.habitrain_core.scene_motion.visibility_advisory_only"),
                textX, y, textWidth, MenuTheme.TEXT_DIM);
    }

    /** Builds a disposable profile so live diagnostics never mutate the page draft. */
    private SceneProfile visibilityProfileFromFields() {
        SceneProfileDraft draft = getCurrentDraft();
        SceneProfile profile = draft.toProfile();
        profile.setOrbit(profile.getOrbit().copy());
        profile.setMotionMode(motionMode);
        profile.setSpeedBlocksPerSecond(parseDouble(speedField.getValue(), profile.getSpeedBlocksPerSecond()));
        double[] direction = directionFromFields(profile.getDirection());
        profile.setDirection(direction[0], direction[1], direction[2]);
        profile.setDisplayOrigin(
                parseDouble(originXField.getValue(), profile.getDisplayOrigin()[0]),
                parseDouble(originYField.getValue(), profile.getDisplayOrigin()[1]),
                parseDouble(originZField.getValue(), profile.getDisplayOrigin()[2]));
        profile.setPivotLocal(
                parseDouble(pivotXField.getValue(), profile.getPivotLocal()[0]),
                parseDouble(pivotYField.getValue(), profile.getPivotLocal()[1]),
                parseDouble(pivotZField.getValue(), profile.getPivotLocal()[2]));
        profile.setRotationDegrees(new SceneRotation(
                (float) parseDouble(rotYawField.getValue(), profile.getRotationDegrees().yawDegrees()),
                (float) parseDouble(rotPitchField.getValue(), profile.getRotationDegrees().pitchDegrees()),
                (float) parseDouble(rotRollField.getValue(), profile.getRotationDegrees().rollDegrees())));
        profile.setPhaseOffsetBlocks(parseDouble(phaseOffsetField.getValue(), profile.getPhaseOffsetBlocks()));
        profile.setLoop(loopSettingsFromFields());
        profile.setRender(new SceneRenderSettings(
                parseDouble(renderMaxDistField.getValue(), profile.getRender().getMaxDistanceBlocks()),
                translucentState));

        SceneOrbitSettings orbit = profile.getOrbit();
        orbit.setCenterMode(orbitCenterMode);
        orbit.setAxis(orbitAxis);
        orbit.setClockwise(orbitClockwise);
        if (orbitCenterMode == SceneOrbitCenterMode.WORLD_BLOCK && orbitStartAngleValidation().isValid()) {
            orbit.setStartAngleDegrees(orbitStartAngleValidation().value());
        }
        if (orbitSweepValidation().isValid()) orbit.setSweepDegrees(orbitSweepValidation().value());
        if (orbitSpeedValidation().isValid()) orbit.setAngularSpeedDegreesPerSecond(orbitSpeedValidation().value());
        if (orbitVerticalBobValidation().isValid()) orbit.setVerticalBobAmplitudeBlocks(orbitVerticalBobValidation().value());
        if (orbitRadialBobValidation().isValid()) orbit.setRadialBobAmplitudeBlocks(orbitRadialBobValidation().value());
        if (orbitBobCyclesValidation().isValid()) orbit.setBobCyclesPerSecond(orbitBobCyclesValidation().value());
        if (orbitInstanceCountValidation().isValid()) orbit.setInstanceCount(orbitInstanceCountValidation().value());
        if (orbitInstanceSpreadValidation().isValid()) orbit.setInstanceSpreadDegrees(orbitInstanceSpreadValidation().value());
        orbit.setRotateModelWithOrbit(orbitCenterMode == SceneOrbitCenterMode.MODEL_CENTER || orbitRotateModel);
        return profile;
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

    private void renderRangeSlider(GuiGraphics graphics, HitBox hit,
                                   SceneProfileValidator.LoopDistanceValidation validation,
                                   boolean enabled) {
        int trackY = hit.y() + hit.height() / 2 - 1;
        graphics.fill(hit.x(), trackY, hit.x() + hit.width(), trackY + 2,
                enabled ? MenuTheme.BORDER : MenuTheme.BORDER_SOFT);
        double value = validation.isValid()
                ? validation.value() : getCurrentDraft().getLoop().getDistanceBlocks();
        double ratio = (value - SceneLoopSettings.MIN_DISTANCE)
                / (SceneLoopSettings.MAX_DISTANCE - SceneLoopSettings.MIN_DISTANCE);
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        int markerX = hit.x() + (int) Math.round(hit.width() * ratio);
        graphics.fill(hit.x(), trackY, markerX, trackY + 2,
                enabled ? MenuTheme.ACCENT_VIOLET : MenuTheme.TEXT_DIM);
        graphics.fill(markerX - 2, hit.y() + 1, markerX + 3, hit.y() + hit.height() - 1,
                enabled ? MenuTheme.ACCENT_VIOLET : MenuTheme.TEXT_DIM);
    }

    private void updateLoopDistanceFromSlider(double mouseX) {
        if (rangeSliderHit.width() <= 0) return;
        double ratio = (mouseX - rangeSliderHit.x()) / rangeSliderHit.width();
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        double value = SceneLoopSettings.MIN_DISTANCE
                + ratio * (SceneLoopSettings.MAX_DISTANCE - SceneLoopSettings.MIN_DISTANCE);
        loopDistField.setValue(String.format(Locale.ROOT, "%.0f", value));
    }

    private static String loopDistanceErrorKey(SceneProfileValidator.LoopDistanceError error) {
        return switch (error) {
            case REQUIRED -> "screen.habitrain_core.scene_motion.loop_error_required";
            case NOT_A_NUMBER -> "screen.habitrain_core.scene_motion.loop_error_number";
            case NOT_FINITE -> "screen.habitrain_core.scene_motion.loop_error_finite";
            case OUT_OF_RANGE -> "screen.habitrain_core.scene_motion.loop_error_range";
            case NONE -> "screen.habitrain_core.scene_motion.loop_error_number";
        };
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

        if (mapDropdown.mouseClicked(mx, my, btn)) {
            if (mapDropdown.isOpen()) backgroundDropdown.dismiss();
            loopKeyboardFocus = -1;
            return true;
        }
        mapDropdown.blur();
        if (backgroundDropdown.mouseClicked(mx, my, btn)) {
            if (backgroundDropdown.isOpen()) mapDropdown.dismiss();
            loopKeyboardFocus = -1;
            return true;
        }
        backgroundDropdown.blur();
        if (addBackgroundHit.contains(mx, my)) {
            addBackground();
            return true;
        }
        if (deleteBackgroundHit.contains(mx, my)) {
            deleteBackground();
            return true;
        }
        if (btn == 0 && MenuTheme.inBounds(mx, my, backgroundNameField.getX(), backgroundNameField.getY(),
                backgroundNameField.getWidth(), backgroundNameField.getHeight())
                && !SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId)) {
            clearInputFocus();
            backgroundNameField.mouseClicked(mx, my, btn);
            backgroundNameField.setFocused(true);
            return true;
        }
        if (jumpRuntimeMapHit.contains(mx, my) && !runtimeMapKey.isBlank()
                && availableMapKeys().contains(runtimeMapKey)
                && !runtimeMapKey.equals(selectedMapKey)) {
            selectMap(runtimeMapKey);
            return true;
        }
        if (previewHit.contains(mx, my)
                && (previewActive || currentAssetDescriptor().isValid())) {
            togglePreview();
            return true;
        }
        SceneStagingClientController staging = SceneStagingClientController.getInstance();
        SceneStagingClientController.Action stagingAction = staging.getAction(currentAssetKey());
        if (discardStagingHit.contains(mx, my) && editable
                && stagingAction != SceneStagingClientController.Action.BUILD) {
            staging.sendDecision(currentAssetKey(), false);
            return true;
        }
        if (buildAssetHit.contains(mx, my) && editable) {
            if (stagingAction == SceneStagingClientController.Action.AWAITING_CONFIRMATION) {
                staging.sendDecision(currentAssetKey(), true);
            } else if (stagingAction == SceneStagingClientController.Action.POLICY_BLOCKED) {
                staging.retryInspection(currentAssetKey());
            } else if (stagingAction == SceneStagingClientController.Action.BUILD
                    && ClientPlayNetworking.canSend(SceneAssetBuildRequestC2S.TYPE)) {
                requestBuildAsset();
            }
            return true;
        }

        // EditBox instances are rendered manually rather than registered as Screen children.
        // Let the native widget handle the click before the scroll bar and clear every other
        // field, matching the event order used by the existing configuration pages.
        if (btn == 0 && scroll.isInside(mx, my)) {
            for (EditBox field : inputFields) {
                if (!isFieldAvailable(field)) continue;
                if (!MenuTheme.inBounds(mx, my, field.getX(), field.getY(),
                        field.getWidth(), field.getHeight())) {
                    continue;
                }
                for (EditBox other : inputFields) {
                    if (other != field) other.setFocused(false);
                }
                loopKeyboardFocus = -1;
                mapDropdown.blur();
                field.mouseClicked(mx, my, btn);
                field.setFocused(true);
                return true;
            }
            clearInputFocus();
        }

        if (btn == 0 && scroll.isInside(mx, my) && rangeSliderHit.contains(mx, my)
                && editable && loopState
                && loopDistanceMode == SceneLoopDistanceMode.CUSTOM) {
            rangeSliderDragging = true;
            loopKeyboardFocus = 1;
            updateLoopDistanceFromSlider(mx);
            return true;
        }

        if (scroll.mouseClicked(mx, my, btn)) {
            return true;
        }

        if (enabledHit.contains(mx, my) && editable) {
            enabledState = !enabledState;
            return true;
        }

        if (applySelectionHit.contains(mx, my) && editable && !currentSessionBounds.isEmpty()
                && SceneEditorMapPolicy.selectionBelongsToEditor(selectedMapKey, selectionMapKey)) {
            getCurrentDraft().setSourceBounds(currentSessionBounds);
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(Component.translatable(
                        "screen.habitrain_core.scene_motion.message_selection_applied",
                        currentSessionBounds.sizeX(), currentSessionBounds.sizeY(), currentSessionBounds.sizeZ()), true);
            }
            return true;
        }

        if (compatScanHit.contains(mx, my)) {
            triggerCompatibilityScan();
            return true;
        }

        if (compatPolicyStrictHit.contains(mx, my) && editable) {
            com.habitrain.core.scene.client.SceneRenderRuntime.getInstance()
                    .setPublishPolicy(com.habitrain.core.scene.model.ScenePublishPolicy.STRICT);
            return true;
        }

        if (compatPolicySkipWarnHit.contains(mx, my) && editable) {
            com.habitrain.core.scene.client.SceneRenderRuntime.getInstance()
                    .setPublishPolicy(com.habitrain.core.scene.model.ScenePublishPolicy.SKIP_AND_WARN);
            SceneStagingClientController.getInstance().retryInspection(currentAssetKey());
            return true;
        }

        if (compatCopyBlockIdHit.contains(mx, my)) {
            copyFirstIssueBlockId();
            return true;
        }

        if (originPlacementHit.contains(mx, my) && editable
                && !getCurrentDraft().getSourceBounds().isEmpty()) {
            beginOriginPlacement();
            return true;
        }

        if (motionLinearHit.contains(mx, my) && editable) {
            motionMode = SceneMotionMode.LINEAR;
            refreshFieldAvailability();
            return true;
        }
        if ((motionOrbitHit.contains(mx, my) || addOrbitHit.contains(mx, my)) && editable) {
            motionMode = SceneMotionMode.ORBIT;
            refreshFieldAvailability();
            return true;
        }

        if (motionMode == SceneMotionMode.ORBIT) {
            if (orbitAxisYHit.contains(mx, my) && editable) {
                orbitAxis = SceneOrbitAxis.Y;
                flushPending();
                populateFieldsFromDraft();
                return true;
            }
            if (orbitAxisXHit.contains(mx, my) && editable) {
                orbitAxis = SceneOrbitAxis.X;
                flushPending();
                populateFieldsFromDraft();
                return true;
            }
            if (orbitAxisZHit.contains(mx, my) && editable) {
                orbitAxis = SceneOrbitAxis.Z;
                flushPending();
                populateFieldsFromDraft();
                return true;
            }
            if (orbitCenterWorldHit.contains(mx, my) && editable) {
                orbitCenterMode = SceneOrbitCenterMode.WORLD_BLOCK;
                flushPending();
                populateFieldsFromDraft();
                refreshFieldAvailability();
                return true;
            }
            if (orbitCenterModelHit.contains(mx, my) && editable) {
                orbitCenterMode = SceneOrbitCenterMode.MODEL_CENTER;
                orbitRotateModel = true;
                flushPending();
                populateFieldsFromDraft();
                refreshFieldAvailability();
                return true;
            }
            if (orbitCenterPickHit.contains(mx, my) && editable) {
                flushPending();
                com.habitrain.core.scene.client.SceneOriginPlacementController.getInstance()
                        .startOrbitCenter(root, this, getCurrentDraft().getDisplayOrigin(), orbitAxis);
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(Component.translatable(
                            "screen.habitrain_core.scene_motion.message_orbit_pick_started"), true);
                }
                Minecraft.getInstance().setScreen(null);
                return true;
            }
            if (orbitClockwiseHit.contains(mx, my) && editable) {
                orbitClockwise = true;
                return true;
            }
            if (orbitCounterClockwiseHit.contains(mx, my) && editable) {
                orbitClockwise = false;
                return true;
            }
            if (orbitRotateModelWithHit.contains(mx, my) && editable) {
                orbitRotateModel = true;
                return true;
            }
            if (orbitRotateModelFixedHit.contains(mx, my) && editable && orbitCenterMode != SceneOrbitCenterMode.MODEL_CENTER) {
                orbitRotateModel = false;
                return true;
            }
            for (int i = 0; i < orbitQuickDirHits.length; i++) {
                if (orbitQuickDirHits[i] != null && orbitQuickDirHits[i].contains(mx, my)
                        && editable && orbitCenterMode == SceneOrbitCenterMode.WORLD_BLOCK) {
                    orbitStartAngleField.setValue(String.format(Locale.ROOT, "%.1f", ORBIT_DIR_PRESETS[i].angle()));
                    return true;
                }
            }
            for (int i = 0; i < orbitQuickAngleHits.length; i++) {
                if (orbitQuickAngleHits[i] != null && orbitQuickAngleHits[i].contains(mx, my) && editable) {
                    orbitSweepField.setValue(String.format(Locale.ROOT, "%.1f", ORBIT_SWEEP_PRESETS[i]));
                    return true;
                }
            }
            if (orbitDialHit.contains(mx, my) && editable) {
                if (orbitCenterMode == SceneOrbitCenterMode.WORLD_BLOCK) {
                    orbitDialFocused = true;
                    orbitDialDragging = true;
                    clearInputFocus();
                    SceneAngleDial.handleClick(mx, my, orbitDialHit.x(), orbitDialHit.y(), orbitDialHit.width(),
                            true, false, angle -> orbitStartAngleField.setValue(String.format(Locale.ROOT, "%.1f", angle)));
                    return true;
                }
            }
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

        if (loopAutoHit.contains(mx, my) && editable && loopState) {
            loopDistanceMode = SceneLoopDistanceMode.AUTO;
            loopKeyboardFocus = 0;
            loopDistField.setFocused(false);
            refreshFieldAvailability();
            return true;
        }

        if (loopCustomHit.contains(mx, my) && editable && loopState) {
            loopDistanceMode = SceneLoopDistanceMode.CUSTOM;
            loopKeyboardFocus = 0;
            refreshFieldAvailability();
            return true;
        }

        if (applyRecommendedHit.contains(mx, my) && editable && loopState
                && !getCurrentDraft().getSourceBounds().isEmpty()) {
            loopDistField.setValue(format(loopDistancesFromFields().recommendedDistance()));
            loopDistanceMode = SceneLoopDistanceMode.CUSTOM;
            loopKeyboardFocus = 2;
            refreshFieldAvailability();
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
        orbitDialFocused = false;
        if (backgroundNameField != null) backgroundNameField.setFocused(false);
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
        if (!isSaveAllowed()) {
            showClientMessage("screen.habitrain_core.scene_motion.message_fix_loop_distance");
            return;
        }
        flushPending();
        SceneRenderRuntime runtime = SceneRenderRuntime.getInstance();
        previewActive = runtime.isPreviewActiveFor(currentAssetKey());
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
            SceneProfile shared = getSharedDraft().toProfile();
            profile.setOutsideSound(shared.getOutsideSound());
            profile.setShake(shared.getShake());
            runtime.startPreview(currentAssetKey(), profile, currentDescriptor);
            com.habitrain.core.scene.client.SceneAmbientSoundController.getInstance()
                    .playSound(profile.getOutsideSound());
            com.habitrain.core.scene.client.SceneShakeController.getInstance()
                    .updateSettings(profile.getShake(), true);
        } else {
            com.habitrain.core.scene.client.SceneClientRuntime.stopPreview();
        }
    }

    private SceneAssetDescriptor currentAssetDescriptor() {
        return SceneRenderRuntime.getInstance().getManifest(currentAssetKey());
    }

    private void triggerCompatibilityScan() {
        SceneAssetDescriptor currentDescriptor = currentAssetDescriptor();
        if (!currentDescriptor.isValid()) {
            showClientMessage("screen.habitrain_core.scene_motion.message_preview_needs_asset");
            return;
        }
        showClientMessage("screen.habitrain_core.scene_motion.compat_scanning");
        SceneRenderRuntime.getInstance().inspectStagingAsset(currentDescriptor);
    }

    private String compatibilityIssueLabel(
            com.habitrain.core.scene.client.SceneCompatibilityReport.IssueType issueType) {
        String suffix = switch (issueType) {
            case MISSING_TEXTURE -> "missing_texture";
            case INVALID_ATLAS -> "invalid_atlas";
            case ABNORMAL_PACKED_LIGHT -> "abnormal_packed_light";
            case MISSING_ADAPTER -> "missing_adapter";
            case UNSUPPORTED_RENDER_PATH -> "unsupported_render_path";
            case SKIPPED -> "skipped";
            case NONE -> "none";
        };
        return tr("screen.habitrain_core.scene_motion.compat_issue_" + suffix);
    }

    private void copyFirstIssueBlockId() {
        com.habitrain.core.scene.client.SceneCompatibilityReport report =
                SceneRenderRuntime.getInstance().getLastCompatibilityReport();
        if (report == null || report.getIssues().isEmpty()) return;
        com.habitrain.core.scene.client.SceneCompatibilityReport.Entry first = report.getIssues().get(0);
        String idStr = first.blockId().toString();
        Minecraft.getInstance().keyboardHandler.setClipboard(idStr);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(
                    "screen.habitrain_core.scene_motion.compat_copied", idStr), true);
        }
    }

    private void requestBuildAsset() {
        if (!isSaveAllowed()) {
            showClientMessage("screen.habitrain_core.scene_motion.message_fix_loop_distance");
            return;
        }
        flushPending();
        save();
        // 先触发配置保存/C2S，再发送构建请求；同一连接按发送顺序处理，服务端只读权威配置。
        root.saveConfigNow();
        if (ClientPlayNetworking.canSend(SceneAssetBuildRequestC2S.TYPE)) {
            ClientPlayNetworking.send(new SceneAssetBuildRequestC2S(currentAssetKey(), true));
            var player = Minecraft.getInstance().player;
            if (player != null) player.displayClientMessage(Component.translatable(
                    "screen.habitrain_core.scene_motion.message_build_requested"), true);
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy, int x, int y, int w, int h) {
        if (mapDropdown.isOpen() || backgroundDropdown.isOpen()) return true;
        if (rangeSliderDragging && btn == 0) {
            updateLoopDistanceFromSlider(mx);
            return true;
        }
        if (orbitDialDragging && btn == 0 && orbitDialHit.width() > 0) {
            SceneAngleDial.applyMouseAngle(mx, my, orbitDialHit.x(), orbitDialHit.y(), orbitDialHit.width(),
                    angle -> orbitStartAngleField.setValue(String.format(Locale.ROOT, "%.1f", angle)));
            return true;
        }
        return scroll.mouseDragged(my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (mapDropdown.isOpen() || backgroundDropdown.isOpen()) return true;
        if (rangeSliderDragging) {
            rangeSliderDragging = false;
            return true;
        }
        if (orbitDialDragging) {
            orbitDialDragging = false;
            return true;
        }
        return scroll.mouseReleased();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy, int x, int y, int w, int h) {
        if (mapDropdown.mouseScrolled(sy)) return true;
        if (backgroundDropdown.mouseScrolled(sy)) return true;
        return scroll.mouseScrolled(sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mod) {
        if (mapDropdown.keyPressed(key, scan, mod)) return true;
        if (backgroundDropdown.keyPressed(key, scan, mod)) return true;
        if (backgroundDropdown.isFocused() && key == 260) {
            addBackground();
            return true;
        }
        if (backgroundDropdown.isFocused() && key == 261) {
            deleteBackground();
            return true;
        }
        if (backgroundNameField.isFocused() && backgroundNameField.keyPressed(key, scan, mod)) return true;
        if (key == 258) {
            focusAdjacentControl(Screen.hasShiftDown() ? -1 : 1);
            return true;
        }
        if (handleLoopKeyboardInput(key)) return true;
        if (orbitDialFocused && motionMode == SceneMotionMode.ORBIT
                && orbitCenterMode == SceneOrbitCenterMode.WORLD_BLOCK) {
            if (key == 262 || key == 263 || key == 264 || key == 265) {
                double cur = parseDouble(orbitStartAngleField.getValue(), 0.0);
                double step = Screen.hasShiftDown() ? 15.0 : 1.0;
                double adjusted = (key == 262 || key == 265) ? cur + step : cur - step;
                adjusted = SceneOrbitMath.normalizeAngle(adjusted);
                orbitStartAngleField.setValue(String.format(Locale.ROOT, "%.1f", adjusted));
                double sweep = parseDouble(orbitSweepField.getValue(), SceneOrbitSettings.DEFAULT_SWEEP);
                double end = SceneOrbitMath.normalizeAngle(adjusted + (orbitClockwise ? sweep : -sweep));
                Minecraft.getInstance().getNarrator().sayNow(Component.translatable(
                        "screen.habitrain_core.scene_motion.dial_narrate",
                        format(adjusted), format(sweep),
                        Component.translatable(orbitClockwise
                                ? "screen.habitrain_core.scene_motion.orbit_clockwise"
                                : "screen.habitrain_core.scene_motion.orbit_counter_clockwise"),
                        format(end)));
                return true;
            }
            if (key == 32) {
                orbitClockwise = !orbitClockwise;
                return true;
            }
        }
        for (EditBox field : inputFields) {
            if (field == loopDistField && field.isFocused()
                    && loopDistanceMode == SceneLoopDistanceMode.CUSTOM
                    && (key == 262 || key == 263)) {
                SceneProfileValidator.LoopDistanceValidation validation = loopDistanceValidation();
                double current = validation.isValid()
                        ? validation.value() : getCurrentDraft().getLoop().getDistanceBlocks();
                double step = Screen.hasShiftDown() ? 10.0 : 1.0;
                double adjusted = current + (key == 262 ? step : -step);
                adjusted = Math.max(SceneLoopSettings.MIN_DISTANCE,
                        Math.min(SceneLoopSettings.MAX_DISTANCE, adjusted));
                field.setValue(format(adjusted));
                return true;
            }
            if (field.isFocused() && field.keyPressed(key, scan, mod)) return true;
        }
        return false;
    }

    private void focusAdjacentControl(int delta) {
        if (mapDropdown.isFocused()) {
            mapDropdown.blur();
            clearInputFocus();
            backgroundDropdown.focus();
            return;
        }
        if (backgroundDropdown.isFocused()) {
            backgroundDropdown.blur();
            clearInputFocus();
            if (delta < 0) mapDropdown.focus();
            else if (!SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId)) backgroundNameField.setFocused(true);
            else focusField(speedField);
            return;
        }
        if (backgroundNameField.isFocused()) {
            backgroundNameField.setFocused(false);
            if (delta < 0) backgroundDropdown.focus();
            else focusField(speedField);
            return;
        }
        EditBox focusedField = inputFields.stream().filter(EditBox::isFocused)
                .findFirst().orElse(null);
        if (loopKeyboardFocus >= 0) {
            if (loopKeyboardFocus == 0) {
                if (delta < 0) focusField(phaseOffsetField);
                else if (loopDistanceMode == SceneLoopDistanceMode.CUSTOM) focusField(loopDistField);
                else focusLoopControl(1);
            } else if (loopKeyboardFocus == 1) {
                if (delta < 0 && loopDistanceMode == SceneLoopDistanceMode.CUSTOM) focusField(loopDistField);
                else if (delta < 0) focusLoopControl(0);
                else focusLoopControl(2);
            } else {
                if (delta < 0) focusLoopControl(1);
                else focusField(renderMaxDistField);
            }
            return;
        }
        if (focusedField == phaseOffsetField && delta > 0) {
            focusLoopControl(0);
            return;
        }
        if (focusedField == loopDistField) {
            if (delta < 0) focusLoopControl(0);
            else focusLoopControl(1);
            return;
        }
        if (focusedField == renderMaxDistField && delta < 0) {
            focusLoopControl(2);
            return;
        }
        List<EditBox> available = inputFields.stream()
                .filter(this::isFieldAvailable)
                .toList();
        if (available.isEmpty()) {
            clearInputFocus();
            mapDropdown.focus();
            return;
        }
        int current = -1;
        for (int i = 0; i < available.size(); i++) {
            if (available.get(i).isFocused()) {
                current = i;
                break;
            }
        }
        boolean dropdownFocused = mapDropdown.isFocused();
        clearInputFocus();
        mapDropdown.blur();
        if (dropdownFocused) {
            available.get(delta < 0 ? available.size() - 1 : 0).setFocused(true);
            return;
        }
        if (current < 0) {
            if (delta < 0) available.get(available.size() - 1).setFocused(true);
            else mapDropdown.focus();
            return;
        }
        int next = current + delta;
        if (next < 0 || next >= available.size()) {
            mapDropdown.focus();
        } else {
            available.get(next).setFocused(true);
        }
    }

    private void focusLoopControl(int target) {
        clearInputFocus();
        mapDropdown.blur();
        loopKeyboardFocus = Math.max(0, Math.min(2, target));
        Component narration = switch (loopKeyboardFocus) {
            case 0 -> Component.translatable("screen.habitrain_core.scene_motion.loop_source_narration",
                    Component.translatable(loopDistanceMode == SceneLoopDistanceMode.AUTO
                            ? "screen.habitrain_core.scene_motion.loop_source_auto"
                            : "screen.habitrain_core.scene_motion.loop_source_custom"));
            case 1 -> Component.translatable("screen.habitrain_core.scene_motion.loop_slider_narration",
                    loopDistField.getValue(), loopDistanceMode == SceneLoopDistanceMode.CUSTOM && loopState);
            default -> Component.translatable(
                    "screen.habitrain_core.scene_motion.loop_recommend_narration");
        };
        Minecraft.getInstance().getNarrator().sayNow(narration);
    }

    private void focusField(EditBox field) {
        clearInputFocus();
        mapDropdown.blur();
        loopKeyboardFocus = -1;
        if (field != null && isFieldAvailable(field)) field.setFocused(true);
    }

    private boolean handleLoopKeyboardInput(int key) {
        if (loopKeyboardFocus < 0) return false;
        if (loopKeyboardFocus == 0 && (key == 262 || key == 263 || key == 257 || key == 32)) {
            if (editable && loopState) {
                if (key == 263) loopDistanceMode = SceneLoopDistanceMode.AUTO;
                else if (key == 262) loopDistanceMode = SceneLoopDistanceMode.CUSTOM;
                else loopDistanceMode = loopDistanceMode == SceneLoopDistanceMode.AUTO
                            ? SceneLoopDistanceMode.CUSTOM : SceneLoopDistanceMode.AUTO;
                refreshFieldAvailability();
            }
            focusLoopControl(0);
            return true;
        }
        if (loopKeyboardFocus == 1 && (key == 262 || key == 263)) {
            if (editable && loopState && loopDistanceMode == SceneLoopDistanceMode.CUSTOM) {
                SceneProfileValidator.LoopDistanceValidation validation = loopDistanceValidation();
                double value = validation.isValid()
                        ? validation.value() : getCurrentDraft().getLoop().getDistanceBlocks();
                double step = Screen.hasShiftDown() ? 10.0 : 1.0;
                value += key == 262 ? step : -step;
                value = Math.max(SceneLoopSettings.MIN_DISTANCE,
                        Math.min(SceneLoopSettings.MAX_DISTANCE, value));
                loopDistField.setValue(format(value));
            }
            focusLoopControl(1);
            return true;
        }
        if (loopKeyboardFocus == 2 && (key == 257 || key == 32)) {
            if (editable && loopState && !getCurrentDraft().getSourceBounds().isEmpty()) {
                loopDistField.setValue(format(loopDistancesFromFields().recommendedDistance()));
                loopDistanceMode = SceneLoopDistanceMode.CUSTOM;
                refreshFieldAvailability();
            }
            focusLoopControl(2);
            return true;
        }
        return false;
    }

    private boolean isFieldAvailable(EditBox field) {
        if (!editable) return false;
        if (field == loopDistField) {
            return motionMode == SceneMotionMode.LINEAR && loopState && loopDistanceMode == SceneLoopDistanceMode.CUSTOM;
        }
        if (field == dirXField || field == dirYField || field == dirZField) {
            return motionMode == SceneMotionMode.LINEAR;
        }
        if (field == orbitStartAngleField) {
            return motionMode == SceneMotionMode.ORBIT && orbitCenterMode == SceneOrbitCenterMode.WORLD_BLOCK;
        }
        if (field == orbitSweepField || field == orbitSpeedField) {
            return motionMode == SceneMotionMode.ORBIT;
        }
        if (field == soundIdField || field == soundVolField || field == soundPitchField) {
            return SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId) && soundState;
        }
        if (field == shakeFreqField || field == shakeAmpField || field == shakeRotAmpField) {
            return SceneBackgroundKey.DEFAULT_ID.equals(selectedBackgroundId) && shakeState;
        }
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (mapDropdown.charTyped(codePoint, modifiers)) return true;
        if (backgroundDropdown.charTyped(codePoint, modifiers)) return true;
        if (backgroundNameField.isFocused() && backgroundNameField.charTyped(codePoint, modifiers)) return true;
        for (EditBox field : inputFields) {
            if (field.isFocused() && field.charTyped(codePoint, modifiers)) return true;
        }
        return false;
    }

    private void showClientMessage(String translationKey) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(translationKey), true);
        }
    }
}
