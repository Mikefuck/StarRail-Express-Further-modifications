package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** OP-only C2S upload of one normalized PNG for a map-vote introduction page. */
public record MapVotePreviewUploadPayload(String mapId, String previousPreviewPath, byte[] pngBytes)
        implements CustomPacketPayload {

    public static final int MAX_MAP_ID_LENGTH = 128;
    public static final int MAX_PREVIEW_PATH_LENGTH = 256;

    public static final Type<MapVotePreviewUploadPayload> TYPE =
            new Type<>(HabiTrainCore.id("map_vote_preview_upload"));
    public static final StreamCodec<FriendlyByteBuf, MapVotePreviewUploadPayload> CODEC =
            StreamCodec.ofMember(MapVotePreviewUploadPayload::write, MapVotePreviewUploadPayload::new);

    public MapVotePreviewUploadPayload {
        mapId = mapId == null ? "" : mapId;
        previousPreviewPath = previousPreviewPath == null ? "" : previousPreviewPath;
        pngBytes = pngBytes == null ? new byte[0] : pngBytes.clone();
    }

    private MapVotePreviewUploadPayload(FriendlyByteBuf buffer) {
        this(readMapId(buffer), buffer.readUtf(MAX_PREVIEW_PATH_LENGTH), readPng(buffer));
    }

    private static String readMapId(FriendlyByteBuf buffer) {
        return buffer.readUtf(MAX_MAP_ID_LENGTH);
    }

    private static byte[] readPng(FriendlyByteBuf buffer) {
        int length = buffer.readVarInt();
        if (length <= 0 || length > MapVoteProfilePayload.MAX_PREVIEW_BYTES) {
            throw new DecoderException("Invalid map preview upload length: " + length);
        }
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return bytes;
    }

    private void write(FriendlyByteBuf buffer) {
        if (mapId.length() > MAX_MAP_ID_LENGTH) {
            throw new EncoderException("Map id too long: " + mapId.length());
        }
        if (pngBytes.length <= 0 || pngBytes.length > MapVoteProfilePayload.MAX_PREVIEW_BYTES) {
            throw new EncoderException("Invalid map preview upload length: " + pngBytes.length);
        }
        buffer.writeUtf(mapId, MAX_MAP_ID_LENGTH);
        buffer.writeUtf(previousPreviewPath, MAX_PREVIEW_PATH_LENGTH);
        buffer.writeVarInt(pngBytes.length);
        buffer.writeBytes(pngBytes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
