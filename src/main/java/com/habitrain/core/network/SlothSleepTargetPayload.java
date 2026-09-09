package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** C2S target selected from Sloth's backpack roster. */
public record SlothSleepTargetPayload(UUID targetId) implements CustomPacketPayload {
    public static final Type<SlothSleepTargetPayload> TYPE =
            new Type<>(HabiTrainCore.id("sloth_sleep_target"));
    public static final StreamCodec<FriendlyByteBuf, SlothSleepTargetPayload> CODEC =
            StreamCodec.ofMember(SlothSleepTargetPayload::write, SlothSleepTargetPayload::new);

    private SlothSleepTargetPayload(FriendlyByteBuf buf) {
        this(buf.readUUID());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(targetId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
