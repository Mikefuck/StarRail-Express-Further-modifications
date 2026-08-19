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

    public MapVoteProfileScreen(ConfigMenuScreen parent, String mapId) {
        super(Component.translatable("config.habitrain_core.map_profile.title"));
        this.parent = parent;
        this.mapId = mapId == null ? "" : mapId;
        this.editable = MenuPermissions.canEditRemoteConfigs();

        MapVoteEntry entry = settings().maps.get(this.mapId);
        this.draftDisplayName = entry != null && entry.displayName != null
                ? entry.displayName : this.mapId;
        this.draftMinPlayers = String.valueOf(entry != null ? Math.max(0, entry.minPlayers) : 0);
        this.draftMaxPlayers = String.valueOf(entry != null ? Math.max(0, entry.maxPlayers) : 0);
        // Opening this editor means the user wants to edit the introduction directly.
        // The explicit source button remains available to restore maps.json inheritance.
        this.profileOverride = true;
        MapVoteProfileSettings profile = entry != null && entry.profile != null
                ? entry.profile : MapVoteProfileSettings.createDefault();
        this.originalPreviewPath = profile.previewPath == null || profile.previewPath.isBlank()
                ? defaultPreviewPath(this.mapId) : profile.previewPath;
        this.draftDescription = profile.description == null ? "" : profile.description;
        this.draftTags = String.join(", ", MapVoteProfileSettings.normalizedTags(profile.tags));
    }

    private ModeMapVoteSettings settings() {
        return ConfigManager.getInstance().getModeMapVoteSettings();
    }

    @Override
    protected void init() {
        snapshotDraft();
        super.init();

        displayNameField = editBox("config.habitrain_core.map_profile.display_name", 64, draftDisplayName);
        descriptionField = editBox("config.habitrain_core.map_profile.description",
                MapVoteProfileSettings.MAX_DESCRIPTION_LENGTH, draftDescription);
        tagsField = editBox("config.habitrain_core.map_profile.tags", 320, draftTags);
        minPlayersField = numberBox("config.habitrain_core.map_profile.min_players", draftMinPlayers);
        maxPlayersField = numberBox("config.habitrain_core.map_profile.max_players", draftMaxPlayers);
        uploadButton = addRenderableWidget(Button.builder(
                Component.translatable("config.habitrain_core.map_profile.choose_preview"),
                button -> choosePreview())
                .bounds(-10000, -10000, 150, FIELD_H).build());

        backButton = addRenderableWidget(Button.builder(
                Component.translatable("config.habitrain_core.common.cancel"), button -> onClose())
                .bounds(10, 8, 72, 20).build());
        sourceButton = addRenderableWidget(Button.builder(sourceLabel(), button -> {
            if (!editable) {
                MenuPermissions.showDeniedMessage();
                return;
            }
            profileOverride = !profileOverride;
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

        setInitialFocus(displayNameField);
        refreshState();
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
        boolean profileEditable = editable && profileOverride;
        descriptionField.setEditable(profileEditable);
        tagsField.setEditable(profileEditable);
        uploadButton.active = profileEditable && Minecraft.getInstance().getConnection() != null;
        sourceButton.setMessage(sourceLabel());
        sourceButton.active = editable;

        validationError = validateDraft();
        saveButton.active = editable && validationError.getString().isEmpty();
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
        int bodyHeight = 238;
        scrollMaximum = Math.max(0, bodyHeight - viewportHeight);
        scrollOffset = Mth.clamp(scrollOffset, 0, scrollMaximum);
        int bodyY = contentTop - (int) scrollOffset;

        graphics.enableScissor(editorX, contentTop, editorX + editorWidth, contentBottom);
        MenuTheme.panel(graphics, editorX, bodyY, editorWidth, bodyHeight);
        renderForm(graphics, bodyY);
        renderVisibleFields(graphics, mouseX, mouseY, delta);
        if (uploadButton.visible) uploadButton.render(graphics, mouseX, mouseY, delta);
        graphics.disableScissor();
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
        int sourceHintX = left + Math.min(86, fieldWidth / 3);
        drawFitted(graphics, Component.translatable(profileOverride
                        ? "config.habitrain_core.map_profile.override_hint"
                        : "config.habitrain_core.map_profile.file_hint"),
                sourceHintX, y + 108, Math.max(20, right - sourceHintX), MenuTheme.TEXT_SECONDARY);

        layoutField(descriptionField, left, y + 134, fieldWidth);
        drawLabel(graphics, "config.habitrain_core.map_profile.description", left, y + 123, fieldWidth);
        layoutField(tagsField, left, y + 170, fieldWidth);
        drawLabel(graphics, "config.habitrain_core.map_profile.tags", left, y + 159, fieldWidth);
        drawLabel(graphics, "config.habitrain_core.map_profile.preview", left, y + 195, fieldWidth);
        uploadButton.setX(left);
        uploadButton.setY(y + 206);
        uploadButton.setWidth(Math.min(160, fieldWidth));
        uploadButton.visible = y + 206 + FIELD_H > contentTop && y + 206 < contentBottom;
        Component shownStatus = !uploadStatus.getString().isEmpty()
                ? uploadStatus
                : Component.translatable(Minecraft.getInstance().getConnection() == null
                        ? "config.habitrain_core.map_profile.preview_offline"
                        : "config.habitrain_core.map_profile.preview_hint");
        int statusX = left + Math.min(170, fieldWidth);
        drawFitted(graphics, shownStatus, statusX, y + 211,
                Math.max(20, right - statusX),
                pendingPreview != null ? MenuTheme.ACCENT_MINT : MenuTheme.TEXT_DIM);
        drawFitted(graphics, Component.translatable("config.habitrain_core.map_profile.preview_save_hint"),
                left, y + 229, fieldWidth, MenuTheme.TEXT_DIM);
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
        field.visible = y + FIELD_H > contentTop && y < contentBottom;
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
        MapVoteEntry entry = voteSettings.maps.computeIfAbsent(mapId, ignored -> MapVoteEntry.createDefault());
        entry.displayName = displayNameField.getValue().trim();
        entry.minPlayers = parsePlayers(minPlayersField.getValue());
        entry.maxPlayers = parsePlayers(maxPlayersField.getValue());
        if (profileOverride) {
            MapVoteProfileSettings profile = MapVoteProfileSettings.createDefault();
            profile.description = descriptionField.getValue().trim();
            profile.tags = MapVoteProfileSettings.normalizedTags(parseTags(tagsField.getValue()));
            profile.previewPath = defaultPreviewPath(mapId);
            entry.profile = profile;
        } else {
            entry.profile = null;
        }

        ConfigManager.getInstance().setModeMapVoteSettings(voteSettings);
        ConfigManager.getInstance().save();
        if (pendingPreview != null && !PayloadSenders.sendMapVotePreviewUpload(
                mapId, originalPreviewPath, pendingPreview.pngBytes())) {
            validationError = Component.translatable(
                    "config.habitrain_core.map_profile.preview_offline");
            return;
        }
        parent.refreshVoteMapEntry(mapId);
        Minecraft.getInstance().setScreen(parent);
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

    private void choosePreview() {
        if (!editable || !profileOverride) {
            MenuPermissions.showDeniedMessage();
            return;
        }
        if (Minecraft.getInstance().getConnection() == null) {
            uploadStatus = Component.translatable(
                    "config.habitrain_core.map_profile.preview_offline");
            return;
        }
        try {
            MapVotePreviewFilePicker.PreparedPreview selected =
                    MapVotePreviewFilePicker.chooseAndPrepare(
                            Component.translatable(
                                    "config.habitrain_core.map_profile.file_dialog_title").getString());
            if (selected == null) return;
            pendingPreview = selected;
            uploadStatus = Component.translatable(
                    "config.habitrain_core.map_profile.preview_selected",
                    selected.fileName(), selected.width(), selected.height(), selected.kibibytes());
            refreshState();
        } catch (Exception | LinkageError e) {
            pendingPreview = null;
            uploadStatus = Component.translatable(
                    "config.habitrain_core.map_profile.preview_pick_failed", e.getMessage());
            HabiTrainCore.LOGGER.warn("Failed to prepare map vote preview for '{}'", mapId, e);
        }
    }
}
