package com.habitrain.core.client.gui;

import com.habitrain.core.HabiTrainCore;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * 结算车票用到的两个核心着色器：贴屏（纸面明暗与高光、车厢灯光、做旧 + 燃烧）与 MVP 人像（从黑暗中浮现）。
 *
 * <p>随资源重载由 Fabric 一并编译，仅在整批编译成功后替换程序；失败交由 Minecraft
 * 回滚资源包。尚无可用程序时，调用方回落到 {@code position_tex_color}。</p>
 */
public final class TicketShaders {
    private static ShaderInstance paper;
    private static ShaderInstance ghost;

    private TicketShaders() {
    }

    public static void register() {
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            // GameRenderer commits these consumers only after every shader has compiled.
            // Propagate compile failure so Minecraft rolls back the reload; swallowing it
            // would let the old shader be closed while this class still retained it.
            context.register(id("ticket_paper"), DefaultVertexFormat.POSITION_TEX_COLOR, program -> paper = program);
            context.register(id("ticket_ghost"), DefaultVertexFormat.POSITION_TEX_COLOR, program -> ghost = program);
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
