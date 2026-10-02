package com.habitrain.core.client.gui;

import com.habitrain.core.HabiTrainCore;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * 结算车票用到的两个核心着色器：贴屏（纸面明暗与高光、车厢灯光、做旧 + 燃烧）与 MVP 人像（从黑暗中浮现）。
 *
 * <p>随资源重载由 Fabric 一并编译；任一不可用时返回 null，调用方回落到原版
 * {@code position_tex_color}（不做旧、不燃烧，仅淡出）。</p>
 */
public final class TicketShaders {
    private static ShaderInstance paper;
    private static ShaderInstance ghost;

    private TicketShaders() {
    }

    public static void register() {
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            // 每次重载都先丢弃旧实例（旧程序会随重载一起被关闭）；
            // 编译失败只记日志并回落，不能让整个资源重载失败。
            paper = null;
            ghost = null;
            try {
                context.register(id("ticket_paper"), DefaultVertexFormat.POSITION_TEX_COLOR, program -> paper = program);
            } catch (Exception e) {
                HabiTrainCore.LOGGER.warn("[TicketShaders] ticket_paper unavailable, end transition falls back to fade", e);
            }
            try {
                context.register(id("ticket_ghost"), DefaultVertexFormat.POSITION_TEX_COLOR, program -> ghost = program);
            } catch (Exception e) {
                HabiTrainCore.LOGGER.warn("[TicketShaders] ticket_ghost unavailable, MVP portrait falls back to plain blend", e);
            }
        });
    }

    static ShaderInstance paper() {
        return paper;
    }

    static ShaderInstance ghost() {
        return ghost;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HabiTrainCore.MOD_ID, path);
    }
}
