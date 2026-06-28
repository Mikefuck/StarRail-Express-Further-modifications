package com.habitrain.taskapi.impl.network;

import com.habitrain.taskapi.HabiTrainTaskAPI;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S 网络包 - 客户端 → 服务端 配置更新
 *
 * 当 OP 玩家通过 ModMenu 修改任务配置时，客户端将此配置
 * 发送到服务端，由服务端（在 OP 校验通过后）应用并广播。
 *
 * 包体格式：
 * - configJson (String): HabiConfigManager 的完整 JSON 配置
 *   包含 "global" 和 "tasks" 两个部分，与配置文件格式相同。
 *
 * 安全：服务端会校验发送者是否为 OP（权限等级 ≥ 4），
 * 非 OP 玩家的更新请求会被拒绝。
 */
public class ConfigUpdateC2SPayload implements CustomPacketPayload {
    public static final ResourceLocation ID = HabiTrainTaskAPI.id("config_update_c2s");
    public static final CustomPacketPayload.Type<ConfigUpdateC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    private final String configJson;

    public ConfigUpdateC2SPayload(String configJson) {
        this.configJson = configJson != null ? configJson : "{}";
    }

    public String getConfigJson() {
        return configJson;
    }

    public static final StreamCodec<ByteBuf, ConfigUpdateC2SPayload> CODEC = new StreamCodec<>() {
        @Override
        public ConfigUpdateC2SPayload decode(ByteBuf buf) {
            int len = buf.readInt();
            if (len <= 0) return new ConfigUpdateC2SPayload("{}");
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            return new ConfigUpdateC2SPayload(new String(bytes));
        }

        @Override
        public void encode(ByteBuf buf, ConfigUpdateC2SPayload payload) {
            byte[] bytes = payload.configJson.getBytes();
            buf.writeInt(bytes.length);
            if (bytes.length > 0) {
                buf.writeBytes(bytes);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 注册 C2S 包类型
     */
    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }

    /**
     * 从客户端发送配置更新到服务端
     * @param configJson HabiConfigManager.toJsonString() 的输出
     */
    public static void sendToServer(String configJson) {
        var client = Minecraft.getInstance();
        if (client.getConnection() == null) return;

        // ★ 单机模式（集成服务器）：配置已保存在本地文件，无需网络同步
        //    client.getSingleplayerServer() != null 表示当前运行的是本地集成服务器
        if (client.getSingleplayerServer() != null) return;

        ClientPlayNetworking.send(new ConfigUpdateC2SPayload(configJson));
    }
}
