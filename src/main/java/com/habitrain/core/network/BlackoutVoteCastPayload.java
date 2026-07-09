package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * 通用投票 C2S 包。
 * 编码：purpose(utf) + hasTarget(bool) + [targetPlayerId(uuid)]。
 * hasTarget 由 serialization 层从 targetPlayerId != null 推导，不是独立 record 字段。
 */
public record BlackoutVoteCastPayload(String purpose, UUID targetPlayerId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutVoteCastPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("vote_cast"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutVoteCastPayload> CODEC =
            StreamCodec.ofMember(BlackoutVoteCastPayload::write, BlackoutVoteCastPayload::new);

    /** 从 buf 解码：先读 purpose，再读一次 boolean 决定是否读 UUID */
    private BlackoutVoteCastPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(32), readTarget(buf));
    }

    /** 读取一次 boolean，根据结果返回 UUID 或 null */
    private static UUID readTarget(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }

    /** 编码：先写 purpose，再写 hasTarget 标记，有目标才写 UUID */
    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(purpose, 32);
        boolean hasTarget = targetPlayerId != null;
        buf.writeBoolean(hasTarget);
        if (hasTarget) {
            buf.writeUUID(targetPlayerId);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
