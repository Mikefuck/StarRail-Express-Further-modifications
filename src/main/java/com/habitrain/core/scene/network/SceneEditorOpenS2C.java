package com.habitrain.core.scene.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneBounds;
import com.habitrain.core.scene.model.SceneProfile;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;

/**
 * S2C 网络包 - 道具右键时向管理员下发当前地图配置、临时选区与资产状态以打开设置页。
 */
public final class SceneEditorOpenS2C implements CustomPacketPayload {
    public static final Type<SceneEditorOpenS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_editor_open"));

    private static final int MAX_STRING_LENGTH = 1048576;

    private final String mapKey;
    private final int profileRevision;
    private final String profileJson;
    private final SceneBounds sessionSelection;
    private final SceneAssetDescriptor assetDescriptor;

    public SceneEditorOpenS2C(String mapKey, int profileRevision, String profileJson,
                              SceneBounds sessionSelection, SceneAssetDescriptor assetDescriptor) {
        this.mapKey = mapKey != null ? mapKey : "__default__";
        this.profileRevision = profileRevision;
        this.profileJson = profileJson != null ? profileJson : "{}";
        this.sessionSelection = sessionSelection != null ? sessionSelection : SceneBounds.EMPTY;
        this.assetDescriptor = assetDescriptor != null ? assetDescriptor : SceneAssetDescriptor.EMPTY;
    }

    public String mapKey() { return mapKey; }
    public int profileRevision() { return profileRevision; }
    public String profileJson() { return profileJson; }
    public SceneBounds sessionSelection() { return sessionSelection; }
    public SceneAssetDescriptor assetDescriptor() { return assetDescriptor; }

    public SceneProfile parseProfile() {
        try {
            JsonObject obj = JsonParser.parseString(profileJson).getAsJsonObject();
            return SceneProfile.fromJson(obj);
        } catch (Exception e) {
            return SceneProfile.createDefault();
        }
    }

    public static final StreamCodec<ByteBuf, SceneEditorOpenS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneEditorOpenS2C decode(ByteBuf buf) {
            String mapKey = readString(buf);
            int revision = buf.readInt();
            String json = readString(buf);
            int minX = buf.readInt();
            int minY = buf.readInt();
            int minZ = buf.readInt();
            int maxX = buf.readInt();
            int maxY = buf.readInt();
            int maxZ = buf.readInt();
            SceneBounds bounds = new SceneBounds(minX, minY, minZ, maxX, maxY, maxZ);

            String hash = readString(buf);
            long uncomp = buf.readLong();
            long comp = buf.readLong();
            int sections = buf.readInt();
            int version = buf.readInt();
            String fp = readString(buf);
            long created = buf.readLong();
            SceneAssetDescriptor descriptor = new SceneAssetDescriptor(hash, uncomp, comp, sections, version, fp, created);

            return new SceneEditorOpenS2C(mapKey, revision, json, bounds, descriptor);
        }

        @Override
        public void encode(ByteBuf buf, SceneEditorOpenS2C payload) {
            writeString(buf, payload.mapKey);
            buf.writeInt(payload.profileRevision);
            writeString(buf, payload.profileJson);
            buf.writeInt(payload.sessionSelection.minX());
            buf.writeInt(payload.sessionSelection.minY());
            buf.writeInt(payload.sessionSelection.minZ());
            buf.writeInt(payload.sessionSelection.maxX());
            buf.writeInt(payload.sessionSelection.maxY());
            buf.writeInt(payload.sessionSelection.maxZ());

            writeString(buf, payload.assetDescriptor.sha256());
            buf.writeLong(payload.assetDescriptor.uncompressedSize());
            buf.writeLong(payload.assetDescriptor.compressedSize());
            buf.writeInt(payload.assetDescriptor.sectionCount());
            buf.writeInt(payload.assetDescriptor.dataVersion());
            writeString(buf, payload.assetDescriptor.fingerprint());
            buf.writeLong(payload.assetDescriptor.createdAt());
        }
    };

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        if (len <= 0) return "";
        if (len > MAX_STRING_LENGTH) throw new DecoderException("String too long: " + len);
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = (s != null ? s : "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_LENGTH) throw new EncoderException("String too long: " + bytes.length);
        buf.writeInt(bytes.length);
        if (bytes.length > 0) buf.writeBytes(bytes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
