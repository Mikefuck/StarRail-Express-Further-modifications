package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Dimension-scoped links survive the doll leaving the viewer's entity tracking range. */
public record DollProtectionPayload(List<Link> links, boolean snapped) implements CustomPacketPayload {
    public record Link(UUID owner, UUID target, UUID doll, Vec3 position) {}
    public DollProtectionPayload { links = List.copyOf(links); }
    public static final Type<DollProtectionPayload> TYPE = new Type<>(HabiTrainCore.id("doll_protection"));
    public static final StreamCodec<FriendlyByteBuf, DollProtectionPayload> CODEC = StreamCodec.ofMember(
            DollProtectionPayload::write, DollProtectionPayload::read);
    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(snapped); buf.writeVarInt(links.size());
        for (Link link : links) {
            buf.writeUUID(link.owner()); buf.writeUUID(link.target()); buf.writeUUID(link.doll());
            buf.writeDouble(link.position().x); buf.writeDouble(link.position().y); buf.writeDouble(link.position().z);
        }
    }
    private static DollProtectionPayload read(FriendlyByteBuf buf) {
        boolean snapped = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > 256) throw new DecoderException("Invalid doll link count " + count);
        List<Link> links = new ArrayList<>(count);
        for (int i = 0; i < count; i++) links.add(new Link(buf.readUUID(), buf.readUUID(), buf.readUUID(),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())));
        return new DollProtectionPayload(links, snapped);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void register() { PayloadTypeRegistry.playS2C().register(TYPE, CODEC); }
}
