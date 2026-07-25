package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 停电任务商店打开 S2C payload。
 * 服务端在玩家右键 decocraft:rotary_phone_red 时发送，客户端据此打开/刷新商店 GUI。
 */
public record BlackoutTaskShopOpenPayload(
        int balance,
        boolean generatorDestroyed,
        boolean restoreUsed,
        List<Entry> entries
) implements CustomPacketPayload {

    private static final int MAX_ENTRIES = 32;

    public record Entry(String key, String displayName, int price, boolean affordable, String lockedReason) {}

    public static final CustomPacketPayload.Type<BlackoutTaskShopOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(HabiTrainCore.id("task_shop_open"));
    public static final StreamCodec<FriendlyByteBuf, BlackoutTaskShopOpenPayload> CODEC =
            StreamCodec.ofMember(BlackoutTaskShopOpenPayload::write, BlackoutTaskShopOpenPayload::new);

    public BlackoutTaskShopOpenPayload {
        entries = List.copyOf(entries);
    }

    private BlackoutTaskShopOpenPayload(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), readEntries(buf));
    }

    private static List<Entry> readEntries(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalStateException("Invalid shop entry count: " + size);
        }
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new Entry(buf.readUtf(128), buf.readUtf(128), buf.readVarInt(),
                    buf.readBoolean(), buf.readUtf(128)));
        }
        return list;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(balance);
        buf.writeBoolean(generatorDestroyed);
        buf.writeBoolean(restoreUsed);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.key(), 128);
            buf.writeUtf(e.displayName(), 128);
            buf.writeVarInt(e.price());
            buf.writeBoolean(e.affordable());
            buf.writeUtf(e.lockedReason(), 128);
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}