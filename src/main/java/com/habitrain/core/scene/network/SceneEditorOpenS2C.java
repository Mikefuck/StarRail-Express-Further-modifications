package com.habitrain.core.scene.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.api.scene.model.SceneProfile;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * S2C 网络包 - 道具右键时向管理员下发当前地图配置、临时选区与资产状态以打开设置页。
 */
public final class SceneEditorOpenS2C implements CustomPacketPayload {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|SceneEditorOpenS2C");

    public static final Type<SceneEditorOpenS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_editor_open"));

    private static final int MAX_STRING_LENGTH = 1048576;
    private static final int PROTOCOL_MAGIC = 0x48534345; // HSCE
    public static final int PROTOCOL_VERSION = 3;

    private final String editorMapKey;
    private final String editorBackgroundId;
    private final String runtimeMapKey;
    private final String selectionMapKey;
    private final int profileRevision;
    private final String profileJson;
    private final SceneBounds sessionSelection;
    private final SceneAssetDescriptor assetDescriptor;

    public SceneEditorOpenS2C(String editorMapKey, String runtimeMapKey, String selectionMapKey,
                              int profileRevision, String profileJson,
                              SceneBounds sessionSelection, SceneAssetDescriptor assetDescriptor) {
        this(editorMapKey, com.habitrain.core.api.scene.model.SceneBackgroundKey.DEFAULT_ID,
                runtimeMapKey, selectionMapKey, profileRevision, profileJson, sessionSelection, assetDescriptor);
    }

    public SceneEditorOpenS2C(String editorMapKey, String editorBackgroundId,
                              String runtimeMapKey, String selectionMapKey,
                              int profileRevision, String profileJson,
                              SceneBounds sessionSelection, SceneAssetDescriptor assetDescriptor) {
        this.editorMapKey = normalizeMapKey(editorMapKey, com.habitrain.core.api.scene.SceneMotionSettings.DEFAULT_MAP_KEY);
        this.editorBackgroundId = editorBackgroundId != null && !editorBackgroundId.isBlank()
                ? com.habitrain.core.api.scene.model.SceneBackgroundKey.normalizeBackgroundId(editorBackgroundId)
                : com.habitrain.core.api.scene.model.SceneBackgroundKey.DEFAULT_ID;
        this.runtimeMapKey = normalizeMapKey(runtimeMapKey, "");
        this.selectionMapKey = normalizeMapKey(selectionMapKey, "");
        this.profileRevision = profileRevision;
        this.profileJson = profileJson != null && !profileJson.isBlank() ? profileJson : "{}";
        this.sessionSelection = sessionSelection != null ? sessionSelection : SceneBounds.EMPTY;
        this.assetDescriptor = assetDescriptor != null ? assetDescriptor : SceneAssetDescriptor.EMPTY;
    }

    public String editorMapKey() { return editorMapKey; }
    public String editorBackgroundId() { return editorBackgroundId; }
    public String runtimeMapKey() { return runtimeMapKey; }
    public String selectionMapKey() { return selectionMapKey; }
    public int profileRevision() { return profileRevision; }
    public String profileJson() { return profileJson; }
    public SceneBounds sessionSelection() { return sessionSelection; }
    public SceneAssetDescriptor assetDescriptor() { return assetDescriptor; }

    /**
     * X-CUT-11：解析场景配置 JSON；若 JSON 损坏则记录错误并抛出异常（保留 cause），
     * 绝不静默回退到空默认配置，防止用户随后点击“保存”将服务端原有场景配置抹掉。
     */
    public SceneProfile parseProfile() {
        try {
            JsonObject obj = JsonParser.parseString(com.habitrain.core.config.JsonInputLimits.check(profileJson)).getAsJsonObject();
            return SceneProfile.fromJson(obj);
        } catch (Exception e) {
            LOGGER.error("Failed to parse scene editor profile JSON for map '{}' (bg='{}', rev={})",
                    editorMapKey, editorBackgroundId, profileRevision, e);
            throw new IllegalStateException("Invalid scene editor profile JSON for map '" + editorMapKey + "'", e);
        }
    }

    public static final StreamCodec<ByteBuf, SceneEditorOpenS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneEditorOpenS2C decode(ByteBuf buf) {
            int magic = buf.readInt();
            if (magic != PROTOCOL_MAGIC) {
                throw new DecoderException("Unsupported legacy scene editor open payload");
            }
            int protocolVersion = buf.readInt();
            if (protocolVersion != 2 && protocolVersion != PROTOCOL_VERSION) {
                throw new DecoderException("Unsupported scene editor protocol version: " + protocolVersion);
            }
            String editorMapKey = readString(buf);
            String editorBackgroundId = protocolVersion >= 3
                    ? readString(buf)
                    : com.habitrain.core.api.scene.model.SceneBackgroundKey.DEFAULT_ID;
            String runtimeMapKey = readString(buf);
            String selectionMapKey = readString(buf);
            int revision = buf.readInt();
            String json = readString(buf);
            if (!json.isBlank()) {
                try {
                    if (!JsonParser.parseString(com.habitrain.core.config.JsonInputLimits.check(json)).isJsonObject()) {
                        throw new DecoderException("Scene editor profile JSON is not a JSON object");
                    }
                } catch (DecoderException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new DecoderException("Invalid scene editor profile JSON", e);
                }
            }
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

            return new SceneEditorOpenS2C(editorMapKey, editorBackgroundId, runtimeMapKey, selectionMapKey,
                    revision, json, bounds, descriptor);
        }

        @Override
        public void encode(ByteBuf buf, SceneEditorOpenS2C payload) {
            buf.writeInt(PROTOCOL_MAGIC);
            buf.writeInt(PROTOCOL_VERSION);
            writeString(buf, payload.editorMapKey);
            writeString(buf, payload.editorBackgroundId);
            writeString(buf, payload.runtimeMapKey);
            writeString(buf, payload.selectionMapKey);
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

    private static String normalizeMapKey(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim();
    }

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        if (len < 0) throw new DecoderException("Negative string length: " + len);
        if (len == 0) return "";
        if (len > MAX_STRING_LENGTH || len > buf.readableBytes()) {
            throw new DecoderException("String too long: " + len);
        }
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
