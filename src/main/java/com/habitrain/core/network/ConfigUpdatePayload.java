package com.habitrain.core.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
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
 * - configJson (String): protocol=1、requestId、baseRevision、patch(set/remove)
 * - scope: FULL_MOD_MENU / BACKPACK_TASKS / BACKPACK_MAP_VOTE / ADMIN_SCENE_TOOL
 * UTF-8 字节预算为 30000，含 JSON 元数据，给底层 C2S 包头留余量。
 *
 * 背包入口范围作为 JSON 元数据携带；旧版完整配置提交会被明确拒绝。服务端仍会
 * 独立校验 OP2 和 Mod Menu 授权名单，客户端声明不能扩大权限。
 */
public class ConfigUpdatePayload implements CustomPacketPayload {
    public static final int MAX_JSON_LENGTH = com.habitrain.core.config.ConfigPatch.MAX_BYTES;

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "config_update");
    public static final CustomPacketPayload.Type<ConfigUpdatePayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    private final String configJson;

    public ConfigUpdatePayload(String configJson) {
        this.configJson = configJson != null ? configJson : "{}";
    }

    public String getConfigJson() {
        return configJson;
    }

    public static final StreamCodec<ByteBuf, ConfigUpdatePayload> CODEC = new StreamCodec<>() {
        @Override
        public ConfigUpdatePayload decode(ByteBuf buf) {
            int len = buf.readInt();
            if (len <= 0) return new ConfigUpdatePayload("{}");
            if (len > MAX_JSON_LENGTH) {
                throw new DecoderException("ConfigUpdate payload 过大: " + len + " > " + MAX_JSON_LENGTH);
            }
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            return new ConfigUpdatePayload(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void encode(ByteBuf buf, ConfigUpdatePayload payload) {
            byte[] bytes = payload.configJson.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_JSON_LENGTH) {
                throw new EncoderException("ConfigUpdate payload 过大: " + bytes.length + " > " + MAX_JSON_LENGTH);
            }
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

    // 客户端发送逻辑见 com.habitrain.core.client.network.PayloadSenders#sendConfigUpdate
    // （移出公共 payload 类以避免引用客户端类，防止专用服务器加载风险）
}
