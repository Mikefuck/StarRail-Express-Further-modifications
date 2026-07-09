package com.habitrain.core.client.network;

import com.habitrain.core.network.BlackoutSheriffVoteCastPayload;
import com.habitrain.core.network.ConfigUpdatePayload;
import com.habitrain.core.network.ShaderInfoPayload;
import com.habitrain.core.network.VotePurpose;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * 客户端专用网络发送辅助。
 * <p>
 * 把对 {@link ClientPlayNetworking#send} 的调用（以及 {@link Minecraft} 的连接状态判断）
 * 从公共 {@code com.habitrain.core.network} 包中的 payload 类里收拢到此处。
 * 这样 payload 类只保留 {@code register()} 与 codec，不再引用客户端类，
 * 避免专用服务器在静态引用 payload 类时触发客户端类加载而崩溃。
 * <p>
 * 仅在客户端调用；dedicated server 不会加载本类。
 */
@Environment(EnvType.CLIENT)
public final class PayloadSenders {
    private PayloadSenders() {
    }

    /** 从客户端发送当前光影包名称到服务端（空字符串表示无光影包）。 */
    public static void sendShaderInfo(String shaderPackName) {
        if (Minecraft.getInstance().getConnection() == null) return;
        ClientPlayNetworking.send(new ShaderInfoPayload(shaderPackName));
    }

    /**
     * 从客户端发送配置更新到服务端。
     * 单机模式（集成服务器）下配置已保存在本地文件，无需网络同步。
     */
    public static void sendConfigUpdate(String configJson) {
        var client = Minecraft.getInstance();
        if (client.getConnection() == null) return;
        if (client.getSingleplayerServer() != null) return;
        ClientPlayNetworking.send(new ConfigUpdatePayload(configJson));
    }

    /** 从客户端发送警长投票（投给 targetPlayerId，第 slotIndex 张票，0-based）到服务端。 */
    public static void sendSheriffVoteCast(UUID targetPlayerId, int slotIndex) {
        ClientPlayNetworking.send(new BlackoutSheriffVoteCastPayload(targetPlayerId, slotIndex));
    }

    /** 从客户端发送聘请警察请求到服务端。 */
    public static void sendHirePolice() {
        if (Minecraft.getInstance().getConnection() == null) return;
        ClientPlayNetworking.send(new com.habitrain.core.network.BlackoutHirePolicePayload());
    }

    /** 从客户端发送投票。purpose 指定投票类型，targetPlayerId 为投票目标。 */
    public static void sendVoteCast(VotePurpose purpose, UUID targetPlayerId) {
        if (Minecraft.getInstance().getConnection() == null) return;
        ClientPlayNetworking.send(new com.habitrain.core.network.BlackoutVoteCastPayload(purpose, targetPlayerId));
    }

    /** 从客户端撤销当前投票（弃票）。语义等价于 {@code sendVoteCast(purpose, null)} 但意图更清晰。 */
    public static void sendVoteRevoke(VotePurpose purpose) {
        if (Minecraft.getInstance().getConnection() == null) return;
        ClientPlayNetworking.send(new com.habitrain.core.network.BlackoutVoteCastPayload(purpose, null));
    }
}