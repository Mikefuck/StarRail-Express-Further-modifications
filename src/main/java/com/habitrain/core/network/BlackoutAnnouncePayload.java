package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record BlackoutAnnouncePayload(
    String roleName,        // 角色显示名, e.g. "杀手" / "平民" / "警长"（见 BlackoutRoles.announcementName）
    String subtitle,        // 副标题, e.g. "坏人阵营。利用黑暗清除所有好人。"
    String goal,            // 目标描述
    int killerCount,        // 坏人（杀手）阵营剩余人数
    int targetCount         // 好人阵营剩余人数（对杀手而言是目标数）
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlackoutAnnouncePayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("blackout_announce"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutAnnouncePayload> CODEC =
            StreamCodec.ofMember(BlackoutAnnouncePayload::write, BlackoutAnnouncePayload::new);

    private BlackoutAnnouncePayload(FriendlyByteBuf buf) {
        this(buf.readUtf(256), buf.readUtf(256), buf.readUtf(256), buf.readVarInt(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(roleName, 256);
        buf.writeUtf(subtitle, 256);
        buf.writeUtf(goal, 256);
        buf.writeVarInt(killerCount);
        buf.writeVarInt(targetCount);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
