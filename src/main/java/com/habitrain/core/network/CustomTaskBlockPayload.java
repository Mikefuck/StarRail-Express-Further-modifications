package com.habitrain.core.network;

import com.habitrain.core.game.sre.CustomTaskBlockCache;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CustomTaskBlockPayload implements CustomPacketPayload {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("habitrain_core", "custom_task_blocks");
    public static final CustomPacketPayload.Type<CustomTaskBlockPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    private final Map<BlockPos, Set<Integer>> blockTypeIds;

    public CustomTaskBlockPayload(Map<BlockPos, Set<Integer>> blockTypeIds) {
        this.blockTypeIds = blockTypeIds != null ? blockTypeIds : new HashMap<>();
    }

    public Map<BlockPos, Set<Integer>> getBlockTypeIds() {
        return blockTypeIds;
    }

    public static final StreamCodec<ByteBuf, CustomTaskBlockPayload> CODEC = new StreamCodec<>() {
        @Override
        public CustomTaskBlockPayload decode(ByteBuf buf) {
            int entryCount = buf.readInt();
            if (entryCount < 0) entryCount = 0;
            Map<BlockPos, Set<Integer>> data = new HashMap<>();
            for (int i = 0; i < entryCount; i++) {
                int x = buf.readInt();
                int y = buf.readInt();
                int z = buf.readInt();
                BlockPos pos = new BlockPos(x, y, z);
                int setCount = buf.readInt();
                if (setCount < 0) setCount = 0;
                Set<Integer> typeIds = new HashSet<>();
                for (int j = 0; j < setCount; j++) {
                    typeIds.add(buf.readInt());
                }
                data.put(pos, typeIds);
            }
            return new CustomTaskBlockPayload(data);
        }

        @Override
        public void encode(ByteBuf buf, CustomTaskBlockPayload payload) {
            buf.writeInt(payload.blockTypeIds.size());
            for (var entry : payload.blockTypeIds.entrySet()) {
                BlockPos pos = entry.getKey();
                buf.writeInt(pos.getX());
                buf.writeInt(pos.getY());
                buf.writeInt(pos.getZ());
                buf.writeInt(entry.getValue().size());
                for (int typeId : entry.getValue()) {
                    buf.writeInt(typeId);
                }
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }

    public static void sendToPlayer(ServerPlayer player) {
        if (player == null) return;
        ServerPlayNetworking.send(player, new CustomTaskBlockPayload(CustomTaskBlockCache.snapshot()));
    }

    public static void broadcastToAll(MinecraftServer server) {
        if (server == null) return;
        var snapshot = CustomTaskBlockCache.snapshot();
        var payload = new CustomTaskBlockPayload(snapshot);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}