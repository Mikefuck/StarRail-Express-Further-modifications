package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S request sent when Sloth opens the backpack selector. */
public record SlothSleepRosterRequestPayload() implements CustomPacketPayload {
    public static final Type<SlothSleepRosterRequestPayload> TYPE =
            new Type<>(HabiTrainCore.id("sloth_sleep_roster_request"));
    public static final StreamCodec<FriendlyByteBuf, SlothSleepRosterRequestPayload> CODEC =
            StreamCodec.ofMember(SlothSleepRosterRequestPayload::write, SlothSleepRosterRequestPayload::new);

    private SlothSleepRosterRequestPayload(FriendlyByteBuf buf) {
        this();
    }

    private void write(FriendlyByteBuf buf) {
        // no fields
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
