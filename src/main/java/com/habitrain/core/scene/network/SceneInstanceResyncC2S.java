package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S：请求服务端重新下发当前维度的全部 API 场景实例。
 *
 * <p>客户端只在"自己刚刚清空了本地场景状态、但并没有换会话"的场合需要它——典型例子是对局结束
 * 时 {@code SceneClientRuntime.onMatchFinished()} 会清掉运行时网格与实例表，而服务端并不知道
 * 客户端做过这件事。服务端对同一玩家的重同步请求有冷却（默认 1 秒），因此这里没有额外字段。</p>
 */
public record SceneInstanceResyncC2S() implements CustomPacketPayload {
    public static final Type<SceneInstanceResyncC2S> TYPE =
            new Type<>(HabiTrainCore.id("scene_instance_resync"));

    public static final StreamCodec<ByteBuf, SceneInstanceResyncC2S> CODEC = new StreamCodec<>() {
        @Override
        public SceneInstanceResyncC2S decode(ByteBuf buf) {
            return new SceneInstanceResyncC2S();
        }

        @Override
        public void encode(ByteBuf buf, SceneInstanceResyncC2S payload) {
            // 无负载
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
