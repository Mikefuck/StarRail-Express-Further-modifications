package com.habitrain.core.client.gui.menu;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.network.PayloadSenders;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MapVoteEntry;
import com.habitrain.core.config.MapVoteProfileSettings;
import com.habitrain.core.config.ModeMapVoteSettings;
import com.habitrain.core.vote.MapVoteProfileStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/** Edits every configurable value displayed by the map-vote information sheet. */
public final class MapVoteProfileScreen extends Screen {
    private static final int HEADER_H = 44;
    private static final int FOOTER_H = 42;
    private static final int FIELD_H = 20;
    private static final int ACCENT = MenuTheme.ACCENT_BLUE;

    private final ConfigMenuScreen parent;
    private final String mapId;
    private final boolean editable;
    private final String originalPreviewPath;

    private String draftDisplayName;
    private String draftDescription;
    private String draftTags;
    private String draftMinPlayers;
    private String draftMaxPlayers;
    private boolean profileOverride;
    private MapVotePreviewFilePicker.PreparedPreview pendingPreview;
    private Component uploadStatus = Component.empty();

    private EditBox displayNameField;
    private EditBox descriptionField;
    private EditBox tagsField;
    private EditBox minPlayersField;
    private EditBox maxPlayersField;
    private Button uploadButton;
    private Button backButton;
    private Button sourceButton;
    private Button saveButton;

    private double scrollOffset;
    private int scrollMaximum;
    private int editorX;
    private int editorWidth;
    private int contentTop;
    private int contentBottom;
    private Component validationError = Component.empty();
    private long previewGeneration;
    private long screenGeneration;
    private boolean saving;

    public MapVoteProfileScreen(ConfigMenuScreen parent, String mapId) {
        super(Component.translatable("config.habitrain_core.map_profile.title"));
        this.parent = parent;
        this.mapId = mapId == null ? "" : mapId;
        this.editable = MenuPermissions.canEditRemoteConfigs();

        MapVoteEntry entry = settings().maps.get(this.mapId);
        this.draftDisplayName = entry != null && entry.displayName != null
                ? entry.displayName : "";
        this.draftMinPlayers = String.valueOf(entry != null ? Math.max(0, entry.minPlayers) : 0);
        this.draftMaxPlayers = String.valueOf(entry != null ? Math.max(0, entry.maxPlayers) : 0);
        this.profileOverride = entry != null && entry.profile != null;
        MapVoteProfileSettings profile = entry != null && entry.profile != null
                ? entry.profile : MapVoteProfileSettings.createDefault();
        this.originalPreviewPath = profile.previewPath == null || profile.previewPath.isBlank()
                ? defaultPreviewPath(this.mapId) : profile.previewPath;
        String inheritedDesc = com.habitrain.core.client.cache.ClientMapIntroCache.getDescription(this.mapId);
        List<String> inheritedTags = com.habitrain.core.client.cache.ClientMapIntroCache.getTags(this.mapId);
        this.draftDescription = this.profileOverride
                ? (profile.description == null ? "" : profile.description)
                : (inheritedDesc == null ? "" : inheritedDesc);
        this.draftTags = String.join(", ", MapVoteProfileSettings.normalizedTags(
                this.profileOverride ? profile.tags : inheritedTags));
    }

    private ModeMapVoteSettings settings() {
        return ConfigManager.getInstance().getModeMapVoteSettings();
    }

    @Override
    protected void init() {
        ConfigManager.getInstance().beginClientEditing();
        snapshotDraft();
        super.init();

        backButton = addRenderableWidget(Button.builder(
                Component.translatable("config.habitrain_core.common.cancel"), button -> onClose())
                .bounds(10, 8, 72, 20).build());
        sourceButton = addRenderableWidget(Button.builder(sourceLabel(), button -> {
            if (!editable) {
                MenuPermissions.showDeniedMessage();
                return;
            }
            profileOverride = !profileOverride;
            previewGeneration++;
            if (!profileOverride) {
                pendingPreview = null;
                uploadStatus = Component.translatable(
                        "config.habitrain_core.map_profile.preview_inherited");
            }
            refreshState();
            MenuSounds.playClick();
        }).bounds(-10000, -10000, 150, 20).build());
        saveButton = addRenderableWidget(Button.builder(
                Component.translatable("config.habitrain_core.common.save"), button -> saveAndClose())
                .bounds(-10000, -10000, 84, 20).build());

        displayNameField = editBox("config.habitrain_core.map_profile.display_name", 64, draftDisplayName);
        displayNameField.setHint(Component.literal(mapId));
        descriptionField = editBox("config.habitrain_core.map_profile.description",
                MapVoteProfileSettings.MAX_DESCRIPTION_LENGTH, draftDescription);
        tagsField = editBox("config.habitrain_core.map_profile.tags", 320, draftTags);
        minPlayersField = numberBox("config.habitrain_core.map_profile.min_players", draftMinPlayers);
        maxPlayersField = numberBox("config.habitrain_core.map_profile.max_players", draftMaxPlayers);
        uploadButton = addRenderableWidget(Button.builder(
                Component.translatable("config.habitrain_core.map_profile.choose_preview"),
                button -> choosePreview())
                .bounds(-10000, -10000, 150, FIELD_H).build());

        setInitialFocus(displayNameField);
        refreshState();
    }

    @Override
    public void removed() {
        previewGeneration++;
        screenGeneration++;
        ConfigManager.getInstance().endClientEditing();
        super.removed();
    }

    private EditBox editBox(String key, int maxLength, String value) {
        EditBox box = new EditBox(font, -10000, -10000, 120, FIELD_H, Component.translatable(key));
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        box.setEditable(editable);
        box.setResponder(ignored -> refreshState());
        return addRenderableWidget(box);
    }

    private EditBox numberBox(String key, String value) {
        EditBox box = editBox(key, 2, value);
        box.setFilter(text -> text.isEmpty() || text.matches("\\d*"));
        return box;
    }

    private void snapshotDraft() {
        if (displayNameField == null) return;
        draftDisplayName = displayNameField.getValue();
        draftDescription = descriptionField.getValue();
        draftTags = tagsField.getValue();
        draftMinPlayers = minPlayersField.getValue();
        draftMaxPlayers = maxPlayersField.getValue();
    }

    private Component sourceLabel() {
        return Component.translatable(profileOverride
                ? "config.habitrain_core.map_profile.source_modmenu"
                : "config.habitrain_core.map_profile.source_file");
    }

    private void refreshState() {
        if (displayNameField == null) return;
        boolean profileEditable = editable && profileOverride && !saving;
        displayNameField.setEditable(editable && !saving);
        minPlayersField.setEditable(editable && !saving);
        maxPlayersField.setEditable(editable && !saving);
        descriptionField.setEditable(profileEditable);
        tagsField.setEditable(profileEditable);
        uploadButton.active = profileEditable && !preparingPreview && !saving && Minecraft.getInstance().getConnection() != null;
        sourceButton.setMessage(sourceLabel());
        sourceButton.active = editable && !saving;
        backButton.active = !saving;

        validationError = validateDraft();
        saveButton.active = editable && !preparingPreview && !saving && validationError.getString().isEmpty();
    }

    private Component validateDraft() {
        int min = parsePlayers(minPlayersField.getValue());
        int max = parsePlayers(maxPlayersField.getValue());
        if (min < 0 || max < 0) {
            return Component.translatable("config.habitrain_core.map_profile.error_players");
        }
        if (min > 0 && max > 0 && min > max) {
            return Component.translatable("config.habitrain_core.map_profile.error_range");
        }
        if (profileOverride) {
            List<String> parsedTags = parseTags(tagsField.getValue());
            if (parsedTags.size() > MapVoteProfileSettings.MAX_TAGS) {
                return Component.translatable("config.habitrain_core.map_profile.error_tags",
                        MapVoteProfileSettings.MAX_TAGS);
            }
            if (parsedTags.stream().anyMatch(tag -> tag.length() > MapVoteProfileSettings.MAX_TAG_LENGTH)) {
                return Component.translatable("config.habitrain_core.map_profile.error_tag_length",
                        MapVoteProfileSettings.MAX_TAG_LENGTH);
            }
        }
        return Component.empty();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        MenuTheme.drawBackdrop(graphics, width, height, ACCENT);

        String subtitle = Component.translatable("config.habitrain_core.map_profile.subtitle", mapId).getString();
        int subtitleWidth = Math.max(32, width - 112);
        subtitle = font.plainSubstrByWidth(subtitle, subtitleWidth);
        MenuTheme.editorHeader(graphics, font, width,
                title.getString(), subtitle, ACCENT);
        MenuTheme.editorFooter(graphics, width, height, FOOTER_H);

        editorWidth = Math.max(180, Math.min(620, width - 24));
        editorX = (width - editorWidth) / 2;
        contentTop = HEADER_H + 4;
        contentBottom = Math.max(contentTop + 1, height - FOOTER_H - 4);
        int viewportHeight = contentBottom - contentTop;
        int bodyHeight = 252;
        scrollMaximum = Math.max(0, bodyHeight - viewportHeight);
        scrollOffset = Mth.clamp(scrollOffset, 0, scrollMaximum);
        int bodyY = contentTop - (int) scrollOffset;

        graphics.enableScissor(editorX, contentTop, editorX + editorWidth, contentBottom);
        try {
            MenuTheme.panel(graphics, editorX, bodyY, editorWidth, bodyHeight);
            renderForm(graphics, bodyY);
            renderVisibleFields(graphics, mouseX, mouseY, delta);
            if (uploadButton.visible) uploadButton.render(graphics, mouseX, mouseY, delta);
        } finally {
            graphics.disableScissor();
        }
        MenuTheme.drawScrollbar(graphics, editorX + editorWidth - 4, contentTop,
                viewportHeight, scrollOffset, scrollMaximum, 3);

        backButton.render(graphics, mouseX, mouseY, delta);
        int footerY = height - 31;
        int sourceW = Math.min(180, Math.max(120, width / 3));
        sourceButton.setX(width / 2 - sourceW - 5);
        sourceButton.setY(footerY);
        sourceButton.setWidth(sourceW);
        saveButton.setX(width / 2 + 5);
        saveButton.setY(footerY);
        saveButton.setWidth(Math.min(100, Math.max(74, width / 5)));
        sourceButton.render(graphics, mouseX, mouseY, delta);
        saveButton.render(graphics, mouseX, mouseY, delta);

        if (!validationError.getString().isEmpty()) {
            graphics.drawString(font, validationError, 10, height - 11, MenuTheme.DANGER, false);
        } else if (!editable) {
            graphics.drawString(font,
                    Component.translatable("config.habitrain_core.map_profile.read_only"),
                    10, height - 11, MenuTheme.DANGER, false);
        }
    }

    private void renderForm(GuiGraphics graphics, int y) {
        int left = editorX + 14;
        int right = editorX + editorWidth - 14;
        int fieldWidth = Math.max(80, right - left);

        graphics.drawString(font, Component.translatable("config.habitrain_core.map_profile.section_identity"),
                left, y + 10, MenuTheme.TEXT_PRIMARY, false);
        layoutField(displayNameField, left, y + 32, fieldWidth);
        drawLabel(graphics, "config.habitrain_core.map_profile.display_name", left, y + 21, fieldWidth);

        int half = Math.max(44, (fieldWidth - 10) / 2);
        layoutField(minPlayersField, left, y + 68, half);
        layoutField(maxPlayersField, left + half + 10, y + 68, fieldWidth - half - 10);
        drawLabel(graphics, "config.habitrain_core.map_profile.min_players", left, y + 57, half);
        drawLabel(graphics, "config.habitrain_core.map_profile.max_players",
                left + half + 10, y + 57, fieldWidth - half - 10);

        graphics.hLine(left, right, y + 98, MenuTheme.SEPARATOR);
        graphics.drawString(font, Component.translatable("config.habitrain_core.map_profile.section_content"),
                left, y + 108, profileOverride ? ACCENT : MenuTheme.TEXT_DIM, false);
        drawFitted(graphics, Component.translatable(profileOverride
                        ? "config.habitrain_core.map_profile.override_hint"
                        : "config.habitrain_core.map_profile.file_hint"),
                left, y + 121, fieldWidth, MenuTheme.TEXT_SECONDARY);

        layoutField(descriptionField, left, y + 148, fieldWidth);
        drawLabel(graphics, "config.habitrain_core.map_profile.description", left, y + 137, fieldWidth);
        layoutField(tagsField, left, y + 184, fieldWidth);
        drawLabel(graphics, "config.habitrain_core.map_profile.tags", left, y + 173, fieldWidth);
        drawLabel(graphics, "config.habitrain_core.map_profile.preview", left, y + 209, fieldWidth);
        uploadButton.setX(left);
        uploadButton.setY(y + 220);
        uploadButton.setWidth(Math.min(160, fieldWidth));
        uploadButton.visible = y + 220 + FIELD_H > contentTop && y + 220 < contentBottom;
        Component shownStatus = !uploadStatus.getString().isEmpty()
                ? uploadStatus
                : Component.translatable(Minecraft.getInstance().getConnection() == null
                        ? "config.habitrain_core.map_profile.preview_offline"
                        : "config.habitrain_core.map_profile.preview_hint");
        int statusX = left + Math.min(170, fieldWidth);
        drawFitted(graphics, shownStatus, statusX, y + 225,
                Math.max(20, right - statusX),
                pendingPreview != null ? MenuTheme.ACCENT_MINT : MenuTheme.TEXT_DIM);
        drawFitted(graphics, Component.translatable("config.habitrain_core.map_profile.preview_save_hint"),
                left, y + 243, fieldWidth, MenuTheme.TEXT_DIM);
    }

    private void drawLabel(GuiGraphics graphics, String key, int x, int y, int maxWidth) {
        drawFitted(graphics, Component.translatable(key), x, y, maxWidth, MenuTheme.TEXT_SECONDARY);
    }

    private void drawFitted(GuiGraphics graphics, Component text, int x, int y,
                            int maxWidth, int color) {
        String value = text.getString();
        if (font.width(value) > maxWidth) {
            String ellipsis = "…";
            value = font.plainSubstrByWidth(value,
                    Math.max(0, maxWidth - font.width(ellipsis))) + ellipsis;
        }
        graphics.drawString(font, value, x, y, color, false);
    }

    private void layoutField(EditBox field, int x, int y, int width) {
        field.setX(x);
        field.setY(y);
        field.setWidth(width);
        field.visible = y >= contentTop && y + FIELD_H <= contentBottom;
        if (!field.visible) {
            field.setFocused(false);
            if (getFocused() == field) setFocused(null);
        }
    }

    private void renderVisibleFields(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        for (EditBox field : fields()) {
            if (field.visible) field.render(graphics, mouseX, mouseY, delta);
        }
    }

    private List<EditBox> fields() {
        return List.of(displayNameField, minPlayersField, maxPlayersField,
                descriptionField, tagsField);
    }

    private void saveAndClose() {
        if (preparingPreview || saving) return;
        if (com.habitrain.core.client.config.ClientConfigSubmission.isPending()) {
            com.habitrain.core.client.config.ClientConfigSubmission.show(
                    new com.habitrain.core.config.ConfigSaveResult(com.habitrain.core.config.ConfigSaveResult.Status.SUBMITTING,
                            "正在等待上一次配置提交，请稍后再次保存"));
            return;
        }
        if (!editable || !MenuPermissions.canEditRemoteConfigs()) {
            MenuPermissions.showDeniedMessage();
            return;
        }
        refreshState();
        if (!validationError.getString().isEmpty()) return;
        if (pendingPreview != null && Minecraft.getInstance().getConnection() == null) {
            validationError = Component.translatable(
                    "config.habitrain_core.map_profile.preview_offline");
            return;
        }

        ModeMapVoteSettings voteSettings = settings();
        MapVoteEntry entry = voteSettings.maps.get(mapId);
        if (entry == null && (profileOverride || !displayNameField.getValue().isBlank()
                || parsePlayers(minPlayersField.getValue()) != 0 || parsePlayers(maxPlayersField.getValue()) != 0)) {
            entry = MapVoteEntry.createDefault();
            voteSettings.maps.put(mapId, entry);
        }
        String resolvedPreviewPath = pendingPreview != null ? defaultPreviewPath(mapId)
                : (originalPreviewPath != null && !originalPreviewPath.isBlank())
                ? originalPreviewPath : defaultPreviewPath(mapId);
        if (entry != null) {
            entry.displayName = displayNameField.getValue().trim();
            entry.minPlayers = parsePlayers(minPlayersField.getValue());
            entry.maxPlayers = parsePlayers(maxPlayersField.getValue());
            if (profileOverride) {
                MapVoteProfileSettings profile = MapVoteProfileSettings.createDefault();
                profile.description = descriptionField.getValue().trim();
                profile.tags = MapVoteProfileSettings.normalizedTags(parseTags(tagsField.getValue()));
                profile.previewPath = resolvedPreviewPath;
                entry.profile = profile;
            } else {
                entry.profile = null;
            }
        }

        ConfigManager.getInstance().setModeMapVoteSettings(voteSettings);
        var result = ConfigManager.getInstance().save();
        com.habitrain.core.client.config.ClientConfigSubmission.show(result);
        if (result.status() == com.habitrain.core.config.ConfigSaveResult.Status.SUBMITTING) {
            saving = true;
            long generation = screenGeneration;
            var connection = Minecraft.getInstance().getConnection();
            refreshState();
            com.habitrain.core.client.config.ClientConfigSubmission.onCompletion(confirmed -> {
                saving = false;
                if (generation != screenGeneration || Minecraft.getInstance().screen != this
                        || Minecraft.getInstance().getConnection() != connection) return;
                if (confirmed.successful()) finishSave(resolvedPreviewPath);
                else {
                    refreshState();
                    validationError = Component.literal(confirmed.message());
                }
            });
        } else if (result.successful()) {
            finishSave(resolvedPreviewPath);
        } else {
            validationError = Component.literal(result.message());
        }
    }

    private void finishSave(String resolvedPreviewPath) {
        byte[] existingBytes = com.habitrain.core.client.cache.ClientMapIntroCache.getPreviewBytes(mapId);
        byte[] previewBytes = pendingPreview != null ? pendingPreview.pngBytes()
                : (existingBytes != null ? existingBytes : new byte[0]);
        String cachedDesc = profileOverride
                ? descriptionField.getValue().trim()
                : com.habitrain.core.client.cache.ClientMapIntroCache.getDescription(mapId);
        List<String> cachedTags = profileOverride
                ? MapVoteProfileSettings.normalizedTags(parseTags(tagsField.getValue()))
                : com.habitrain.core.client.cache.ClientMapIntroCache.getTags(mapId);
        com.habitrain.core.network.MapVoteProfilePayload.MapProfile updatedProfile =
                new com.habitrain.core.network.MapVoteProfilePayload.MapProfile(
                        cachedDesc,
                        cachedTags,
                        parsePlayers(minPlayersField.getValue()),
                        parsePlayers(maxPlayersField.getValue()),
                        previewBytes
                );
        try {
            if (pendingPreview != null && !PayloadSenders.sendMapVotePreviewUpload(
                    mapId, resolvedPreviewPath, pendingPreview.pngBytes())) {
                validationError = Component.translatable(
                        "config.habitrain_core.map_profile.preview_offline");
                return;
            }
        } catch (RuntimeException error) {
            HabiTrainCore.LOGGER.warn("Failed to send map vote preview for '{}'", mapId, error);
            validationError = Component.translatable(
                    "config.habitrain_core.map_profile.preview_pick_failed", error.getMessage());
            return;
        }
        com.habitrain.core.client.cache.ClientMapIntroCache.putProfile(mapId, updatedProfile);
        parent.refreshVoteMapEntry(mapId);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseY < contentTop || mouseY >= contentBottom) {
            if (backButton != null && backButton.mouseClicked(mouseX, mouseY, button)) return true;
            if (sourceButton != null && sourceButton.mouseClicked(mouseX, mouseY, button)) return true;
            if (saveButton != null && saveButton.mouseClicked(mouseX, mouseY, button)) return true;
            setFocused(null);
            for (EditBox field : fields()) {
                if (field != null) field.setFocused(false);
            }
            return false;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        setFocused(null);
        for (EditBox field : fields()) {
            if (field != null) field.setFocused(false);
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (MenuTheme.inBounds(mouseX, mouseY, editorX, contentTop,
                editorWidth, contentBottom - contentTop)) {
            scrollOffset = Mth.clamp(scrollOffset - scrollY * 18, 0, scrollMaximum);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (saving) return;
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int parsePlayers(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 && parsed <= 99 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static List<String> parseTags(String value) {
        List<String> tags = new ArrayList<>();
        if (value == null || value.isBlank()) return tags;
        for (String part : value.split("[,，;；]")) {
            String tag = part.trim();
            if (!tag.isEmpty() && !tags.contains(tag)) tags.add(tag);
        }
        return tags;
    }

    private static String defaultPreviewPath(String mapId) {
        return MapVoteProfileStore.PREVIEW_DIR + "/" + MapVoteProfileStore.escapeId(mapId) + ".png";
    }

    private volatile boolean preparingPreview;

    private void finishPreview(long generation, net.minecraft.client.multiplayer.ClientPacketListener connection,
                               MapVotePreviewFilePicker.PreparedPreview prepared, Component status) {
        preparingPreview = false;
        Minecraft client = Minecraft.getInstance();
        if (generation == previewGeneration && profileOverride && client.screen == this
                && client.getConnection() == connection) {
            if (prepared != null) pendingPreview = prepared;
            uploadStatus = status;
        }
        if (client.screen == this) refreshState();
    }

    private void choosePreview() {
        if (preparingPreview) return;
        if (!editable || !profileOverride) {
            MenuPermissions.showDeniedMessage();
            return;
        }
        Minecraft client = Minecraft.getInstance();
        var connection = client.getConnection();
        if (connection == null) {
            uploadStatus = Component.translatable(
                    "config.habitrain_core.map_profile.preview_offline");
            return;
        }
        String dialogTitle = Component.translatable(
                "config.habitrain_core.map_profile.file_dialog_title").getString();
        long generation = ++previewGeneration;
        preparingPreview = true;
        uploadStatus = Component.translatable("config.habitrain_core.map_profile.preview_preparing");
        refreshState();
        boolean queued = MapVotePreviewFilePicker.submit(() -> {
            try {
                java.nio.file.Path selected = MapVotePreviewFilePicker.choose(dialogTitle);
                if (selected == null) {
                    client.execute(() -> finishPreview(generation, connection, null, Component.empty()));
                    return;
                }
                MapVotePreviewFilePicker.PreparedPreview prepared =
                        MapVotePreviewFilePicker.readAndPrepare(selected);
                client.execute(() -> finishPreview(generation, connection, prepared, Component.translatable(
                            "config.habitrain_core.map_profile.preview_selected", prepared.fileName(),
                            prepared.width(), prepared.height(), prepared.kibibytes())));
            } catch (Exception | LinkageError failure) {
                HabiTrainCore.LOGGER.warn("Failed to prepare map vote preview for '{}'", mapId, failure);
                client.execute(() -> finishPreview(generation, connection, null, Component.translatable(
                            "config.habitrain_core.map_profile.preview_pick_failed", failure.getMessage())));
            }
        });
        if (!queued) {
            preparingPreview = false;
            uploadStatus = Component.translatable("config.habitrain_core.map_profile.preview_busy");
            refreshState();
        }
    }
}
