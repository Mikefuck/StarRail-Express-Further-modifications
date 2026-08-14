package com.habitrain.core.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图投票档案 S2C 一次性同步。
 *
 * <p>地图阶段开始时由服务端推送一次（不随 1Hz 票数广播重复推），玩家中途加入时经
 * {@code OptionVoteManager.syncTo} 补发。档案数据（介绍/标签/推荐人数/预览图字节）来自
 * 服务端世界目录 {@code <world>/train_maps/map_vote/}，dedicated server 上客户端读不到
 * 该目录，因此预览图以字节随包下发，客户端懒解码为 GPU 纹理。</p>
 *
 * <p>包体积硬上限：单图 {@value #MAX_PREVIEW_BYTES}、整包 {@value #MAX_TOTAL_PREVIEW_BYTES}，
 * 超限的预览图在写入端直接省略并记日志（客户端回退占位图）。</p>
 */
public record MapVoteProfilePayload(Map<String, MapProfile> profiles)
        implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MapVoteProfile");

    public static final int MAX_PROFILES = 64;
    public static final int MAX_TAGS = 8;
    public static final int MAX_TAG_LEN = 32;
    public static final int MAX_DESC_LEN = 256;
    public static final int MAX_PREVIEW_BYTES = 128 * 1024;
    public static final int MAX_TOTAL_PREVIEW_BYTES = 192 * 1024;

    /** 单张地图的档案数据。 */
    public record MapProfile(
            String description,
            List<String> tags,
            int minPlayers,
            int maxPlayers,
            byte[] previewBytes) {

        public MapProfile {
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            minPlayers = Math.max(0, minPlayers);
            maxPlayers = Math.max(0, maxPlayers);
            previewBytes = previewBytes == null ? new byte[0] : previewBytes;
        }
    }

    public static final Type<MapVoteProfilePayload> TYPE =
            new Type<>(HabiTrainCore.id("map_vote_profile"));
    public static final StreamCodec<FriendlyByteBuf, MapVoteProfilePayload> CODEC =
            StreamCodec.ofMember(MapVoteProfilePayload::write, MapVoteProfilePayload::new);

    public MapVoteProfilePayload {
        profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
    }

    private MapVoteProfilePayload(FriendlyByteBuf buf) {
        this(readProfiles(buf));
    }

    private static Map<String, MapProfile> readProfiles(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_PROFILES) {
            throw new DecoderException("Invalid map vote profile count: " + size);
        }
        Map<String, MapProfile> map = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            String id = buf.readUtf(128);
            String description = buf.readUtf(MAX_DESC_LEN);
            int tagCount = buf.readVarInt();
            if (tagCount < 0 || tagCount > MAX_TAGS) {
                throw new DecoderException("Invalid map vote tag count: " + tagCount);
            }
            List<String> tags = new ArrayList<>(tagCount);
            for (int t = 0; t < tagCount; t++) {
                tags.add(buf.readUtf(MAX_TAG_LEN));
            }
            int minPlayers = buf.readVarInt();
            int maxPlayers = buf.readVarInt();
            int previewLen = buf.readVarInt();
            if (previewLen < 0 || previewLen > MAX_PREVIEW_BYTES) {
                throw new DecoderException("Invalid map vote preview length: " + previewLen);
            }
            byte[] preview = new byte[previewLen];
            buf.readBytes(preview);
            map.put(id, new MapProfile(description, tags, minPlayers, maxPlayers, preview));
        }
        return map;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(profiles.size());
        int totalPreview = 0;
        for (Map.Entry<String, MapProfile> e : profiles.entrySet()) {
            MapProfile p = e.getValue();
            byte[] preview = p.previewBytes();
            if (preview.length > MAX_PREVIEW_BYTES) {
                LOGGER.warn("[MapVoteProfile] preview for '{}' exceeds {} bytes, dropping image",
                        e.getKey(), MAX_PREVIEW_BYTES);
                preview = new byte[0];
            }
            if (totalPreview + preview.length > MAX_TOTAL_PREVIEW_BYTES) {
                LOGGER.warn("[MapVoteProfile] total preview bytes would exceed {}, dropping image for '{}'",
                        MAX_TOTAL_PREVIEW_BYTES, e.getKey());
                preview = new byte[0];
            }
            totalPreview += preview.length;

            buf.writeUtf(e.getKey(), 128);
            buf.writeUtf(p.description(), MAX_DESC_LEN);
            List<String> tags = p.tags();
            buf.writeVarInt(tags.size());
            for (String tag : tags) {
                buf.writeUtf(tag, MAX_TAG_LEN);
            }
            buf.writeVarInt(p.minPlayers());
            buf.writeVarInt(p.maxPlayers());
            buf.writeVarInt(preview.length);
            buf.writeBytes(preview);
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
