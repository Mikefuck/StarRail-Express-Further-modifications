package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** S2C authoritative list of awake players eligible for Sloth's endless sleep. */
public record SlothSleepRosterPayload(List<Entry> entries) implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 256;

    public record Entry(UUID playerId, String playerName) {}

    public static final Type<SlothSleepRosterPayload> TYPE =
            new Type<>(HabiTrainCore.id("sloth_sleep_roster"));
    public static final StreamCodec<FriendlyByteBuf, SlothSleepRosterPayload> CODEC =
            StreamCodec.ofMember(SlothSleepRosterPayload::write, SlothSleepRosterPayload::new);

    public SlothSleepRosterPayload {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    private SlothSleepRosterPayload(FriendlyByteBuf buf) {
        this(readEntries(buf));
    }

    private static List<Entry> readEntries(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("Invalid Sloth roster size: " + size);
        }
        List<Entry> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(new Entry(buf.readUUID(), buf.readUtf(64)));
        }
        return List.copyOf(result);
    }

    private void write(FriendlyByteBuf buf) {
        int size = Math.min(entries.size(), MAX_ENTRIES);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            Entry entry = entries.get(i);
            buf.writeUUID(entry.playerId());
            buf.writeUtf(entry.playerName() == null ? "" : entry.playerName(), 64);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
