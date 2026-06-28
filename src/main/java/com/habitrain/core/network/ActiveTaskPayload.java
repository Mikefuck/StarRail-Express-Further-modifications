package com.habitrain.core.network;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * S2C 网络包 - 服务端 → 客户端同步当前玩家的活跃自定义任务
 *
 * 作用：将服务端 HabiTaskManager 中记录的玩家活跃 DLC 任务 ID
 * 同步到客户端，以便 CustomTaskBlockRendererMixin 在多人模式下
 * 也能正确渲染自定义任务方块的高亮边框。
 *
 * 发送时机：
 * 1. 玩家加入服务器时（在 JOIN 事件中）
 * 2. DLC 任务生成时（在 createAndTrackDlcTask 中）
 * 3. DLC 任务完成/清理时（清空活跃任务）
 *
 * 包体格式：
 * - taskFullId (String): 活跃任务的完整 ID，空字符串=无活跃任务
 */
public class ActiveTaskPayload implements CustomPacketPayload {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "active_task");
    public static final CustomPacketPayload.Type<ActiveTaskPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /** 活跃任务的完整 ID（如 "test_more_tasks:pet_cat"），空=无活跃任务 */
    private final String taskFullId;

    public ActiveTaskPayload(String taskFullId) {
        this.taskFullId = taskFullId != null ? taskFullId : "";
    }

    public String getTaskFullId() {
        return taskFullId;
    }

    /** 是否为清空信号（无活跃任务） */
    public boolean isClear() {
        return taskFullId.isEmpty();
    }

    public static final StreamCodec<ByteBuf, ActiveTaskPayload> CODEC = new StreamCodec<>() {
        @Override
        public ActiveTaskPayload decode(ByteBuf buf) {
            int len = buf.readInt();
            if (len <= 0) return new ActiveTaskPayload("");
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            return new ActiveTaskPayload(new String(bytes));
        }

        @Override
        public void encode(ByteBuf buf, ActiveTaskPayload payload) {
            byte[] bytes = payload.taskFullId.getBytes();
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
     * 注册 S2C 包类型
     */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    /**
     * 发送激活任务信息到指定客户端
     */
    public static void sendToPlayer(ServerPlayer player, String taskFullId) {
        if (player == null) return;
        ServerPlayNetworking.send(player, new ActiveTaskPayload(taskFullId));
    }

    /**
     * 发送清空活跃任务信号到指定客户端
     */
    public static void clearForPlayer(ServerPlayer player) {
        sendToPlayer(player, "");
    }
}
