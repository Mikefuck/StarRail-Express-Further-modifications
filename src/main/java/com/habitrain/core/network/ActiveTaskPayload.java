package com.habitrain.core.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * S2C 网络包 - 服务端 → 客户端同步当前玩家的活跃自定义任务
 *
 * 作用：将服务端 com.habitrain.core.task.TaskManager 中记录的玩家活跃 DLC 任务 ID
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
    private static final int MAX_STRING_LENGTH = 65536;

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "active_task");
    public static final CustomPacketPayload.Type<ActiveTaskPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /** 活跃任务的完整 ID（如 "test_more_tasks:pet_cat"），空=无活跃任务 */
    private final String taskFullId;

    /** true=杀手假任务（来自好人任务池），false=真实任务（来自本人任务池） */
    private final boolean isFake;

    public ActiveTaskPayload(String taskFullId, boolean isFake) {
        this.taskFullId = taskFullId != null ? taskFullId : "";
        this.isFake = isFake;
    }

    /**
     * @deprecated Use {@link #ActiveTaskPayload(String, boolean)} instead.
     *             Kept for source compat; defaults isFake to false.
     */
    @Deprecated
    public ActiveTaskPayload(String taskFullId) {
        this(taskFullId, false);
    }

    public String getTaskFullId() {
        return taskFullId;
    }

    /** 是否为杀手假任务（来自好人任务池的并行任务） */
    public boolean isFake() {
        return isFake;
    }

    /** 是否为清空信号（无活跃任务） */
    public boolean isClear() {
        return taskFullId.isEmpty();
    }

    public static final StreamCodec<ByteBuf, ActiveTaskPayload> CODEC = new StreamCodec<>() {
        @Override
        public ActiveTaskPayload decode(ByteBuf buf) {
            boolean isFake = buf.readBoolean();
            int len = buf.readInt();
            if (len < 0) {
                throw new DecoderException("ActiveTaskPayload: negative length " + len);
            }
            if (len == 0) return new ActiveTaskPayload("", isFake);
            if (len > MAX_STRING_LENGTH) {
                throw new DecoderException("ActiveTaskPayload: length " + len + " exceeds max " + MAX_STRING_LENGTH);
            }
            byte[] bytes = new byte[len];
            buf.readBytes(bytes);
            return new ActiveTaskPayload(new String(bytes, StandardCharsets.UTF_8), isFake);
        }

        @Override
        public void encode(ByteBuf buf, ActiveTaskPayload payload) {
            buf.writeBoolean(payload.isFake);
            byte[] bytes = payload.taskFullId.getBytes(StandardCharsets.UTF_8);
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
     * 发送激活/假任务信息到指定客户端
     *
     * @param taskFullId 任务完整 ID，空字符串=清空
     * @param isFake     true=杀手假任务，false=真实任务
     */
    public static void sendToPlayer(ServerPlayer player, String taskFullId, boolean isFake) {
        if (player == null) return;
        ServerPlayNetworking.send(player, new ActiveTaskPayload(taskFullId, isFake));
    }

    /**
     * 发送激活任务信号到指定客户端（默认 isFake=false）
     */
    public static void sendToPlayer(ServerPlayer player, String taskFullId) {
        sendToPlayer(player, taskFullId, false);
    }

    /**
     * 清空指定任务槽位的缓存
     *
     * @param isFake true=清空假任务缓存，false=清空真实任务缓存
     */
    public static void clearForPlayer(ServerPlayer player, boolean isFake) {
        sendToPlayer(player, "", isFake);
    }

    /**
     * 清空真实任务缓存（保留假任务缓存不变）
     */
    public static void clearForPlayer(ServerPlayer player) {
        clearForPlayer(player, false);
    }
}
