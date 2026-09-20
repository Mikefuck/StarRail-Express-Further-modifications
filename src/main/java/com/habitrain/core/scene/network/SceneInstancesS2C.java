package com.habitrain.core.scene.network;

import com.google.gson.JsonParser;
import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.scene.SceneInstanceAnchor;
import com.habitrain.core.api.scene.SceneInstanceSpec;
import com.habitrain.core.api.scene.model.SceneInstance;
import com.habitrain.core.api.scene.model.SceneProfile;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * S2C：API 移动场景实例的增量同步（新增/更新 + 删除 + 全清）。
 *
 * <p><b>没有实例数量上限。</b>服务端按"一次变更一个条目"发包（删除按批），因此同步规模只取决于
 * 实际变更数量，而不是某个预定义容量。协议里唯一的上限是<b>单个包内条目数</b>
 * （{@value #MAX_ENTRIES_PER_PACKET}）与单个条目里 profile JSON 的字节数
 * （{@value #MAX_PROFILE_JSON_BYTES}），它们约束的是"包"，不是"同时存在的场景数"。</p>
 *
 * <p>客户端语义：先处理 {@code clear}（丢弃本地全部 API 实例），再处理 {@code removals}，
 * 最后按 ID 覆盖插入 {@code upserts}。因此一个包可以同时表达"某个实例被替换成另一个"。</p>
 */
public record SceneInstancesS2C(List<SceneInstance> upserts, List<String> removals, boolean clear)
        implements CustomPacketPayload {
    public static final Type<SceneInstancesS2C> TYPE =
            new Type<>(HabiTrainCore.id("scene_instances"));

    /** 单包条目上限（仅约束包大小；同时存在的实例总数不受此限制）。 */
    public static final int MAX_ENTRIES_PER_PACKET = 256;
    /** 单个实例的 profile JSON 上限。 */
    public static final int MAX_PROFILE_JSON_BYTES = 128 * 1024;
    /** 单个实例的标签数量上限。 */
    /** 审核 S-07：与服务端 {@code SceneInstanceSpec} 的「标签数量上限 32」保持一致。 */
    public static final int MAX_TAGS = 32;
    /** 单个实例的可见性白名单条目上限。 */
    public static final int MAX_VISIBILITY_ENTRIES = 4096;

    public SceneInstancesS2C {
        upserts = upserts != null ? List.copyOf(upserts) : List.of();
        removals = removals != null ? List.copyOf(removals) : List.of();
        if (upserts.size() > MAX_ENTRIES_PER_PACKET || removals.size() > MAX_ENTRIES_PER_PACKET) {
            throw new IllegalArgumentException("Too many scene instance entries in one packet");
        }
    }

    /** 便捷工厂：单个 upsert。 */
    public static SceneInstancesS2C upsert(SceneInstance instance) {
        return new SceneInstancesS2C(List.of(instance), List.of(), false);
    }

    /** 便捷工厂：单个删除。 */
    public static SceneInstancesS2C removal(String instanceId) {
        return new SceneInstancesS2C(List.of(), List.of(instanceId), false);
    }

    /** 便捷工厂：清空客户端全部 API 实例。 */
    public static SceneInstancesS2C clearAll() {
        return new SceneInstancesS2C(List.of(), List.of(), true);
    }

    public boolean isEmpty() {
        return !clear && upserts.isEmpty() && removals.isEmpty();
    }

    public static final StreamCodec<ByteBuf, SceneInstancesS2C> CODEC = new StreamCodec<>() {
        @Override
        public SceneInstancesS2C decode(ByteBuf buf) {
            boolean clear = buf.readBoolean();

            int removalCount = readCount(buf, MAX_ENTRIES_PER_PACKET, "removals");
            List<String> removals = new ArrayList<>(removalCount);
            for (int i = 0; i < removalCount; i++) {
                removals.add(ScenePacketIo.readId(buf));
            }

            int upsertCount = readCount(buf, MAX_ENTRIES_PER_PACKET, "upserts");
            List<SceneInstance> upserts = new ArrayList<>(upsertCount);
            for (int i = 0; i < upsertCount; i++) {
                upserts.add(readInstance(buf));
            }
            return new SceneInstancesS2C(upserts, removals, clear);
        }

        @Override
        public void encode(ByteBuf buf, SceneInstancesS2C payload) {
            buf.writeBoolean(payload.clear);

            buf.writeInt(payload.removals.size());
            for (String id : payload.removals) {
                ScenePacketIo.writeId(buf, id);
            }

            buf.writeInt(payload.upserts.size());
            for (SceneInstance instance : payload.upserts) {
                writeInstance(buf, instance);
            }
        }
    };

    // ------------------------------------------------------------------
    // 条目编解码
    // ------------------------------------------------------------------

    private static void writeInstance(ByteBuf buf, SceneInstance instance) {
        if (instance == null) throw new EncoderException("Scene instance cannot be null");
        SceneInstanceSpec spec = instance.spec();
        ScenePacketIo.writeId(buf, instance.id());
        ScenePacketIo.writeId(buf, instance.ownerId());
        ScenePacketIo.writeId(buf, instance.dimensionKey());
        ScenePacketIo.writeId(buf, instance.assetKey());
        ScenePacketIo.writeHash(buf, instance.assetHash());
        buf.writeLong(instance.startGameTime());
        buf.writeLong(instance.expireAtGameTime());
        buf.writeLong(instance.pausedAtGameTime());
        buf.writeDouble(instance.timeScale());
        buf.writeBoolean(instance.paused());
        buf.writeInt(instance.priority());
        buf.writeInt(instance.revision());
        buf.writeLong(spec.durationTicks());

        Set<String> tags = instance.tags();
        buf.writeInt(tags.size());
        for (String tag : tags) {
            ScenePacketIo.writeId(buf, tag);
        }

        writeAnchor(buf, instance.anchor());

        Set<UUID> visibleTo = instance.visibleTo();
        if (instance.visibleToAll() || visibleTo.isEmpty()) {
            buf.writeInt(0);
        } else {
            buf.writeInt(visibleTo.size());
            for (UUID uuid : visibleTo) {
                buf.writeLong(uuid.getMostSignificantBits());
                buf.writeLong(uuid.getLeastSignificantBits());
            }
        }

        writeProfileJson(buf, instance.profile());
    }

    private static SceneInstance readInstance(ByteBuf buf) {
        String id = ScenePacketIo.readId(buf);
        String ownerId = ScenePacketIo.readId(buf);
        String dimensionKey = ScenePacketIo.readId(buf);
        String assetKey = ScenePacketIo.readId(buf);
        String assetHash = ScenePacketIo.readHash(buf);
        long startGameTime = buf.readLong();
        long expireAtGameTime = buf.readLong();
        long pausedAtGameTime = buf.readLong();
        double timeScale = buf.readDouble();
        boolean paused = buf.readBoolean();
        int priority = buf.readInt();
        int revision = buf.readInt();
        long durationTicks = buf.readLong();

        int tagCount = readCount(buf, MAX_TAGS, "tags");
        Set<String> tags = new LinkedHashSet<>();
        for (int i = 0; i < tagCount; i++) {
            tags.add(ScenePacketIo.readId(buf));
        }

        SceneInstanceAnchor anchor = readAnchor(buf);

        int visibilityCount = readCount(buf, MAX_VISIBILITY_ENTRIES, "visibility");
        Set<UUID> visibleTo = new LinkedHashSet<>();
        for (int i = 0; i < visibilityCount; i++) {
            visibleTo.add(new UUID(buf.readLong(), buf.readLong()));
        }

        SceneProfile profile = readProfile(buf);

        SceneInstanceSpec.Builder builder = SceneInstanceSpec.builder(id)
                .owner(ownerId)
                .dimension(dimensionKey)
                .assetKey(assetKey)
                .profile(profile)
                .startGameTime(startGameTime)
                .timeScale(timeScale)
                .paused(paused)
                .priority(priority)
                .tags(tags)
                .anchor(anchor)
                .durationTicks(durationTicks);
        if (visibilityCount > 0) {
            builder.visibleTo(visibleTo);
        } else {
            builder.visibleToAll();
        }
        return new SceneInstance(builder.build(), assetHash, revision,
                expireAtGameTime, pausedAtGameTime, System.currentTimeMillis());
    }

    private static void writeAnchor(ByteBuf buf, SceneInstanceAnchor anchor) {
        SceneInstanceAnchor safe = anchor != null ? anchor : SceneInstanceAnchor.WORLD;
        buf.writeByte(safe.mode().ordinal());
        switch (safe.mode()) {
            case PLAYER -> {
                UUID playerId = safe.playerId();
                buf.writeLong(playerId != null ? playerId.getMostSignificantBits() : 0L);
                buf.writeLong(playerId != null ? playerId.getLeastSignificantBits() : 0L);
            }
            case ENTITY -> buf.writeInt(safe.entityId());
            default -> { }
        }
        buf.writeDouble(safe.offsetX());
        buf.writeDouble(safe.offsetY());
        buf.writeDouble(safe.offsetZ());
    }

    private static SceneInstanceAnchor readAnchor(ByteBuf buf) {
        int ordinal = buf.readUnsignedByte();
        SceneInstanceAnchor.Mode[] modes = SceneInstanceAnchor.Mode.values();
        SceneInstanceAnchor.Mode mode = ordinal >= 0 && ordinal < modes.length
                ? modes[ordinal] : SceneInstanceAnchor.Mode.WORLD;
        UUID playerId = null;
        int entityId = -1;
        if (mode == SceneInstanceAnchor.Mode.PLAYER) {
            long most = buf.readLong();
            long least = buf.readLong();
            playerId = new UUID(most, least);
        } else if (mode == SceneInstanceAnchor.Mode.ENTITY) {
            entityId = buf.readInt();
        }
        double ox = buf.readDouble();
        double oy = buf.readDouble();
        double oz = buf.readDouble();
        return new SceneInstanceAnchor(mode, playerId, entityId, ox, oy, oz);
    }

    private static void writeProfileJson(ByteBuf buf, SceneProfile profile) {
        SceneProfile safe = profile != null ? profile : SceneProfile.createDefault();
        byte[] bytes = safe.toJson().toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PROFILE_JSON_BYTES) {
            throw new EncoderException("Scene profile JSON too large: " + bytes.length);
        }
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static SceneProfile readProfile(ByteBuf buf) {
        int length = buf.readInt();
        if (length <= 0) return SceneProfile.createDefault();
        if (length > MAX_PROFILE_JSON_BYTES) {
            throw new DecoderException("Scene profile JSON too large: " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        try {
            return SceneProfile.fromJson(JsonParser.parseString(
                    new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (RuntimeException ignored) {
            return SceneProfile.createDefault();
        }
    }

    private static int readCount(ByteBuf buf, int max, String label) {
        int count = buf.readInt();
        if (count < 0 || count > max) {
            throw new DecoderException("Invalid scene instance " + label + " count: " + count);
        }
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(TYPE, CODEC);
    }
}
