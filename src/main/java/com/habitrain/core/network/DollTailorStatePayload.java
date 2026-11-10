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

/** Private observation state: never broadcasts another player's role or faction. */
public record DollTailorStatePayload(UUID selected, int sewingTicks, List<Entry> entries) implements CustomPacketPayload {
    public record Entry(UUID id, String name, int observedTicks) {}
    public DollTailorStatePayload { entries = List.copyOf(entries); }
    public static final Type<DollTailorStatePayload> TYPE = new Type<>(HabiTrainCore.id("doll_tailor_state"));
    public static final StreamCodec<FriendlyByteBuf, DollTailorStatePayload> CODEC = StreamCodec.ofMember(
            DollTailorStatePayload::write, DollTailorStatePayload::read);
    private void write(FriendlyByteBuf buf) {
        buf.writeNullable(selected, (out, id) -> out.writeUUID(id));
        buf.writeVarInt(sewingTicks);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeUUID(entry.id()); buf.writeUtf(entry.name(), 64); buf.writeVarInt(entry.observedTicks());
        }
    }
    private static DollTailorStatePayload read(FriendlyByteBuf buf) {
        UUID selected = buf.readNullable(in -> in.readUUID());
        int ticks = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > 256) throw new DecoderException("Invalid tailor roster size " + count);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entries.add(new Entry(buf.readUUID(), buf.readUtf(64), buf.readVarInt()));
        return new DollTailorStatePayload(selected, ticks, entries);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.playS2C().register(TYPE, CODEC); }
}
