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
 * C2S 网络包 - 客户端 → 服务端 报告当前使用的 Iris 光影包名称
 *
 * 当玩家加入服务器时（如果安装了 Iris 模组），客户端将此包发送
 * 到服务端。服务端会校验该光影包是否在白名单中。
 *
 * 包体格式：
 * - shaderPackName (String): 当前光影包名称（文件夹名或zip文件名）
 *   空字符串表示没有使用光影包（默认光影）
 */
public class ShaderPackInfoC2SPayload implements CustomPacketPayload {
    public static final ResourceLocation ID = HabiTrainTaskAPI.id("shaderpack_info_c2s");
    public static final CustomPacketPayload.Type<ShaderPackInfoC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    private final String shaderPackName;

    public ShaderPackInfoC2SPayload(String shaderPackName) {
        this.shaderPackName = shaderPackName != null ? shaderPackName : "";
    }

    public String getShaderPackName() {
        return shaderPackName;
    }

    /** 是否没有使用光影包（默认/内部光影） */
    public boolean isEmpty() {
        return shaderPackName.isEmpty();
    }

    public static final StreamCodec<ByteBuf, ShaderPackInfoC2SPayload> CODEC = new StreamCodec<>() {
        @Override
        public ShaderPackInfoC2SPayload decode(ByteBuf buf) {
            int len = buf.readInt();
            if (len <= 0) return new ShaderPackInfoC2SPayload("");
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            return new ShaderPackInfoC2SPayload(new String(bytes));
        }

        @Override
        public void encode(ByteBuf buf, ShaderPackInfoC2SPayload payload) {
            byte[] bytes = payload.shaderPackName.getBytes();
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
     * 从客户端发送当前光影包名称到服务端
     * @param shaderPackName 当前光影包名称，空=无光影包
     */
    public static void sendToServer(String shaderPackName) {
        if (Minecraft.getInstance().getConnection() == null) return;
        ClientPlayNetworking.send(new ShaderPackInfoC2SPayload(shaderPackName));
    }
}
