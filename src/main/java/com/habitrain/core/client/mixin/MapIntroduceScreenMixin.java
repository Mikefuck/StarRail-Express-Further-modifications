package com.habitrain.core.client.mixin;

import com.habitrain.core.client.cache.ClientMapIntroCache;
import com.habitrain.core.client.gui.MapVotePreviewCache;
import io.wifi.starrailexpress.client.gui.screen.MapIntroduceScreen;
import io.wifi.starrailexpress.network.MapIntroSyncPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.List;

@Mixin(value = MapIntroduceScreen.class)
public abstract class MapIntroduceScreenMixin extends Screen {

    @Shadow(remap = false)
    @Final
    private List<?> maps;

    @Shadow(remap = false)
    @Final
    private List<FormattedCharSequence> detailLines;

    @Shadow(remap = false)
    private int rightX, rightW, panelY, panelH, rightContentY, rightContentH;

    @Shadow(remap = false)
    private int detailScrollOffset, maxDetailScroll;

    @Shadow(remap = false)
    public abstract void updateFromPacket(MapIntroSyncPayload payload);

    @Shadow(remap = false)
    private void addWrapped(Component text, int wrapW) {
    }

    @Shadow(remap = false)
    private void addBlank() {
    }

    @Shadow(remap = false)
    private void addSection(String key, int wrapW) {
    }

    @Shadow(remap = false)
    private static int blendColors(int c1, int c2, float t) {
        return 0;
    }

    @Shadow(remap = false)
    private void renderVScrollbar(GuiGraphics g, int x, int y, int h, int scroll, int maxScroll,
                                  int totalContentH, int mouseX, int mouseY, boolean dragging) {
    }

    @Shadow(remap = false)
    private void fillGradient2D(GuiGraphics g, int x1, int y1, int x2, int y2,
                                int colorTL, int colorTR, int colorBL, int colorBR) {
    }

    private Object habitrain$getSelected() {
        try {
            Field f = MapIntroduceScreen.class.getDeclaredField("selected");
            f.setAccessible(true);
            return f.get(this);
        } catch (Exception e) {
            return null;
        }
    }

    private int habitrain$getEntryColor(Object entry) {
        if (entry == null) return 0xFF5EB7D8;
        try {
            java.lang.reflect.Method m = MapIntroduceScreen.class.getDeclaredMethod("getEntryColor", entry.getClass());
            m.setAccessible(true);
            return (int) m.invoke(this, entry);
        } catch (Exception e) {
            return 0xFF5EB7D8;
        }
    }

    protected MapIntroduceScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void habitrain$autoFillAndRequestIntro(CallbackInfo ci) {
        if (ClientMapIntroCache.hasData() && maps.isEmpty()) {
            MapIntroSyncPayload cached = ClientMapIntroCache.getLatestPayload();
            if (cached != null) {
                updateFromPacket(cached);
            }
        }
        ClientMapIntroCache.requestSyncIfNeeded();
    }

    @Inject(method = "updateFromPacket", at = @At("RETURN"), remap = false)
    private void habitrain$syncToCache(MapIntroSyncPayload payload, CallbackInfo ci) {
        if (payload != null) {
            ClientMapIntroCache.update(payload);
        }
    }

    /**
     * 优先使用在 API Mod Menu 中配置的地图中文显示名。
     */
    @Inject(method = "mapDisplayName", at = @At("HEAD"), cancellable = true, remap = false)
    private static void habitrain$overrideMapDisplayName(String id, MapIntroSyncPayload.VoteMap voteMap,
                                                         CallbackInfoReturnable<Component> cir) {
        String custom = ClientMapIntroCache.getCustomDisplayName(id);
        if (custom != null && !custom.isBlank()) {
            cir.setReturnValue(Component.literal(custom));
        }
    }

    /**
     * 在地图详情中注入 Mod Menu 中配置的中文简介与标签。
     */
    @Inject(
            method = "rebuildDetail",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/wifi/starrailexpress/client/gui/screen/MapIntroduceScreen;updateDetailScrollBounds()V",
                    ordinal = 1
            ),
            remap = false
    )
    private void habitrain$injectMapProfileDetail(CallbackInfo ci) {
        Object map = habitrain$getEntryMap(habitrain$getSelected());
        String mapId = habitrain$getMapEntryId(map);
        if (mapId.isBlank()) return;

        int wrapW = Math.max(80, rightW - 6 * 2 - 7 - 4);

        List<String> tags = ClientMapIntroCache.getTags(mapId);
        if (!tags.isEmpty()) {
            addWrapped(Component.literal("§6标签: §e" + String.join("§7, §e", tags)), wrapW);
            addBlank();
        }

        String desc = ClientMapIntroCache.getDescription(mapId);
        if (!desc.isBlank()) {
            addSection("map_intro.section.info", wrapW);
            for (String line : desc.split("\\\\n|\\n")) {
                addWrapped(Component.literal(line).withStyle(ChatFormatting.WHITE), wrapW);
            }
            addBlank();
        }
    }

    @Inject(method = "updateDetailScrollBounds", at = @At("TAIL"), remap = false)
    private void habitrain$adjustDetailScrollBounds(CallbackInfo ci) {
        Object selected = habitrain$getSelected();
        Object selectedMap = habitrain$getEntryMap(selected);
        if (selectedMap != null) {
            String mapId = habitrain$getMapEntryId(selectedMap);
            byte[] previewBytes = ClientMapIntroCache.getPreviewBytes(mapId);
            MapVotePreviewCache.Decoded decoded = MapVotePreviewCache.getOrDecode(mapId, previewBytes);
            if (decoded != null) {
                int lineH = font.lineHeight + 2;
                int contentW = rightW - 6 * 2 - 7 - 2;
                int previewW = Math.max(40, contentW - 4);
                int previewH = habitrain$calculatePreviewHeight(decoded, previewW);
                int totalH = detailLines.size() * lineH + 10 + previewH + 8;
                maxDetailScroll = Math.max(0, totalH - rightContentH);
                detailScrollOffset = net.minecraft.util.Mth.clamp(detailScrollOffset, 0, maxDetailScroll);
            }
        }
    }

    /**
     * 重绘右侧详情内容，把地图 PNG 固定放在详情正文之前。上游页面只支持文字行，若在
     * TAIL 追加图片，图片会落在很长的属性文本之后，且运行时滚动边界容易仍按纯文本计算。
     */
    @Inject(method = "renderRightPanel", at = @At("HEAD"), cancellable = true, remap = false)
    private void habitrain$renderPreviewBeforeDetail(GuiGraphics g, int mouseX, int mouseY, CallbackInfo ci) {
        int panelPad = 6;
        int scrollW = 7;
        int contentX = rightX + panelPad;
        int contentW = rightW - panelPad * 2 - scrollW - 2;
        int lineH = font.lineHeight + 2;
        Object selected = habitrain$getSelected();
        Object selectedMap = habitrain$getEntryMap(selected);
        MapVotePreviewCache.Decoded decoded = null;
        if (selectedMap != null) {
            String mapId = habitrain$getMapEntryId(selectedMap);
            decoded = MapVotePreviewCache.getOrDecode(mapId, ClientMapIntroCache.getPreviewBytes(mapId));
        }

        // 横幅保持上游的视觉结构与坐标，避免覆盖分类栏或详情正文。
        if (selected != null) {
            int rawColor = habitrain$getEntryColor(selected);
            g.fillGradient(rightX + 1, panelY + 1, rightX + rightW / 2, panelY + 26,
                    (rawColor & 0x00FFFFFF) | 0xCC000000,
                    (rawColor & 0x00FFFFFF) | 0x44000000);
            fillGradient2D(g, rightX + rightW / 2, panelY + 1, rightX + rightW - 1, panelY + 26,
                    (rawColor & 0x00FFFFFF) | 0xCC000000, 0x00000000,
                    (rawColor & 0x00FFFFFF) | 0x44000000, 0x00000000);

            int iconSize = 20;
            int iconX = rightX + panelPad;
            int iconY = panelY + 3;
            g.fill(iconX, iconY, iconX + iconSize, iconY + iconSize,
                    blendColors(0xFF120A04, rawColor | 0xFF000000, 0.3f));
            Object selectedItem = habitrain$getEntryItem(selected);
            Item iconItem = selectedItem instanceof Item item ? item
                    : (selectedMap != null ? Items.FILLED_MAP : Items.BOOK);
            g.renderItem(new ItemStack(iconItem), iconX + 2, iconY + 2);
            g.renderOutline(iconX, iconY, iconSize, iconSize,
                    (rawColor & 0x00FFFFFF) | 0xAA000000);
            g.drawString(font, habitrain$getEntryName(selected), iconX + iconSize + 5,
                    panelY + (26 - font.lineHeight) / 2, 0xFFFFF4DC, true);
        } else {
            g.drawCenteredString(font,
                    Component.translatable("map_intro.loading").withStyle(ChatFormatting.GRAY),
                    rightX + rightW / 2, panelY + panelH / 2, 0xFF9E8B6E);
        }

        int previewGap = decoded == null ? 0 : 8;
        int previewW = decoded == null ? 0 : Math.max(40, contentW - 4);
        int previewH = habitrain$calculatePreviewHeight(decoded, previewW);
        int imageBlockH = previewH + previewGap;
        int totalContentH = imageBlockH + detailLines.size() * lineH;

        // 档案包可能在页面打开后才到达，因此每帧根据图片重新计算滚动边界。
        maxDetailScroll = Math.max(0, totalContentH - rightContentH);
        detailScrollOffset = net.minecraft.util.Mth.clamp(detailScrollOffset, 0, maxDetailScroll);

        g.enableScissor(contentX, rightContentY, contentX + contentW, rightContentY + rightContentH);
        if (decoded != null) {
            int previewX = contentX + 2;
            int previewY = rightContentY - detailScrollOffset;
            g.fill(previewX, previewY, previewX + previewW, previewY + previewH, 0xD81A1008);
            habitrain$drawTextureCover(g, decoded.texture(), previewX + 1, previewY + 1,
                    previewW - 2, previewH - 2, decoded.width(), decoded.height(), 255);
            g.renderOutline(previewX, previewY, previewW, previewH, 0xFF8B6914);
            g.fill(previewX + 1, previewY + 1, previewX + previewW - 1, previewY + 2, 0x33FFE8C0);
        }

        int textY = rightContentY + imageBlockH - detailScrollOffset;
        for (int i = 0; i < detailLines.size(); i++) {
            int lineY = textY + i * lineH;
            if (lineY + lineH > rightContentY && lineY < rightContentY + rightContentH) {
                g.drawString(font, detailLines.get(i), contentX, lineY, 0xFFFFF4DC, false);
            }
        }
        g.disableScissor();

        renderVScrollbar(g, rightX + rightW - panelPad - scrollW, rightContentY, rightContentH,
                detailScrollOffset, maxDetailScroll, totalContentH, mouseX, mouseY, false);
        ci.cancel();
    }

    private static int habitrain$calculatePreviewHeight(MapVotePreviewCache.Decoded decoded, int previewW) {
        if (decoded == null || previewW <= 0) return 0;
        int decW = Math.max(1, decoded.width());
        int decH = Math.max(1, decoded.height());
        int computed = Math.round(previewW * ((float) decH / decW));
        return Math.min(180, Math.max(60, computed));
    }

    private static void habitrain$drawTextureCover(GuiGraphics g, ResourceLocation tex, int x, int y,
                                                  int w, int h, int texW, int texH, int alpha) {
        if (w <= 0 || h <= 0 || texW <= 0 || texH <= 0) return;
        float scale = Math.max(w / (float) texW, h / (float) texH);
        int srcW = Math.max(1, Math.round(w / scale));
        int srcH = Math.max(1, Math.round(h / scale));
        int srcX = Math.max(0, (texW - srcW) / 2);
        int srcY = Math.max(0, (texH - srcH) / 2);
        g.setColor(1.0f, 1.0f, 1.0f, (alpha & 0xFF) / 255.0f);
        g.blit(tex, x, y, w, h, srcX, srcY, srcW, srcH, texW, texH);
        g.setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static String habitrain$getMapEntryId(Object mapEntry) {
        if (mapEntry == null) return "";
        try {
            Field f = mapEntry.getClass().getDeclaredField("id");
            f.setAccessible(true);
            return (String) f.get(mapEntry);
        } catch (Exception e) {
            return "";
        }
    }

    private static Object habitrain$getEntryMap(Object entry) {
        if (entry == null) return null;
        try {
            Field f = entry.getClass().getDeclaredField("map");
            f.setAccessible(true);
            return f.get(entry);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object habitrain$getEntryItem(Object entry) {
        if (entry == null) return null;
        try {
            Field f = entry.getClass().getDeclaredField("item");
            f.setAccessible(true);
            return f.get(entry);
        } catch (Exception e) {
            return null;
        }
    }

    private static Component habitrain$getEntryName(Object entry) {
        if (entry == null) return Component.empty();
        try {
            Field f = entry.getClass().getDeclaredField("name");
            f.setAccessible(true);
            return (Component) f.get(entry);
        } catch (Exception e) {
            return Component.empty();
        }
    }
}
