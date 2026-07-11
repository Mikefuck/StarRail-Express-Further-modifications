package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.Nullable;

/**
 * 通用选项投票 C2S 投票/弃票包。
 * optionId == null 表示弃票；voteId 必须与服务端当前投票匹配，否则服务端忽略。
 */
public record OptionVoteCastPayload(String voteId, @Nullable String optionId)
        implements CustomPacketPayload {

    public static final Type<OptionVoteCastPayload> TYPE =
            new Type<>(HabiTrainCore.id("option_vote_cast"));
    public static final StreamCodec<FriendlyByteBuf, OptionVoteCastPayload> CODEC =
            StreamCodec.ofMember(OptionVoteCastPayload::write, OptionVoteCastPayload::new);

    private OptionVoteCastPayload(FriendlyByteBuf buf) {
        this(buf.readUtf(64), readOptionId(buf));
    }

    private static @Nullable String readOptionId(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf(64) : null;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(voteId == null ? "" : voteId, 64);
        boolean hasOption = optionId != null;
        buf.writeBoolean(hasOption);
        if (hasOption) {
            buf.writeUtf(optionId, 64);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
