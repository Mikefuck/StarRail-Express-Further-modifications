package com.habitrain.core.api.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.UUID;

/**
 * 移动场景实例的空间锚点：决定实例每帧的「显示原点」从哪里取得。
 *
 * <p>场景几何的显示原点来自 {@link com.habitrain.core.api.scene.model.SceneProfile#getDisplayOrigin()}。
 * 锚点把这份原点变成"动态解析"：</p>
 * <ul>
 *   <li>{@link Mode#WORLD}：直接使用 profile 里写死的显示原点（不产生任何额外分配）。</li>
 *   <li>{@link Mode#PLAYER}：显示原点 = 目标玩家当前位置 + {@link #offset()}。目标玩家离线时
 *       自动退回 profile 的静态原点。</li>
 *   <li>{@link Mode#ENTITY}：显示原点 = 目标实体当前位置 + {@link #offset()}。实体不存在或
 *       不在客户端当前维度时自动退回 profile 的静态原点。</li>
 * </ul>
 *
 * <p>锚点只在客户端渲染时解析（服务端 tick 不参与），因此外部 Mod 不需要每 tick 重发位置。
 * 偏移量是纯世界轴偏移，不会随实体朝向旋转。</p>
 */
public final class SceneInstanceAnchor {
    /** 锚点模式。 */
    public enum Mode {
        /** 使用 profile 中静态的显示原点。 */
        WORLD,
        /** 跟随一名玩家（按 UUID 解析）。 */
        PLAYER,
        /** 跟随一个实体（按客户端网络实体 ID 解析）。 */
        ENTITY
    }

    /** 全局共享的静态世界锚点（不可变，可安全复用）。 */
    public static final SceneInstanceAnchor WORLD = new SceneInstanceAnchor(Mode.WORLD, null, -1, 0.0, 0.0, 0.0);

    private final Mode mode;
    private final UUID playerId;
    private final int entityId;
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;

    public SceneInstanceAnchor(Mode mode, UUID playerId, int entityId,
                               double offsetX, double offsetY, double offsetZ) {
        this.mode = mode != null ? mode : Mode.WORLD;
        this.playerId = playerId;
        this.entityId = entityId;
        this.offsetX = finiteOrZero(offsetX);
        this.offsetY = finiteOrZero(offsetY);
        this.offsetZ = finiteOrZero(offsetZ);
    }

    /** 静态世界锚点（等价于 {@link #WORLD}）。 */
    public static SceneInstanceAnchor world() {
        return WORLD;
    }

    /**
     * 跟随玩家。
     *
     * @param playerId 目标玩家 UUID；为 null 时退化为世界锚点
     * @param offsetX  偏移 X（格）
     * @param offsetY  偏移 Y（格）
     * @param offsetZ  偏移 Z（格）
     */
    public static SceneInstanceAnchor player(UUID playerId, double offsetX, double offsetY, double offsetZ) {
        if (playerId == null) return WORLD;
        return new SceneInstanceAnchor(Mode.PLAYER, playerId, -1, offsetX, offsetY, offsetZ);
    }

    /**
     * 跟随实体。
     *
     * @param entityId 客户端网络实体 ID（{@code Entity#getId()}）；负数视为无效
     * @param offsetX  偏移 X（格）
     * @param offsetY  偏移 Y（格）
     * @param offsetZ  偏移 Z（格）
     */
    public static SceneInstanceAnchor entity(int entityId, double offsetX, double offsetY, double offsetZ) {
        if (entityId < 0) return WORLD;
        return new SceneInstanceAnchor(Mode.ENTITY, null, entityId, offsetX, offsetY, offsetZ);
    }

    public Mode mode() { return mode; }
    public UUID playerId() { return playerId; }
    public int entityId() { return entityId; }

    public double offsetX() { return offsetX; }
    public double offsetY() { return offsetY; }
    public double offsetZ() { return offsetZ; }

    /** 返回偏移量副本 {@code [x, y, z]}。 */
    public double[] offset() { return new double[]{offsetX, offsetY, offsetZ}; }

    public boolean isWorld() { return mode == Mode.WORLD; }
    public boolean isPlayer() { return mode == Mode.PLAYER && playerId != null; }
    public boolean isEntity() { return mode == Mode.ENTITY && entityId >= 0; }

    /** 换一个偏移量，模式与目标保持不变。 */
    public SceneInstanceAnchor withOffset(double offsetX, double offsetY, double offsetZ) {
        return new SceneInstanceAnchor(mode, playerId, entityId, offsetX, offsetY, offsetZ);
    }

    /** 是否需要在客户端每帧解析锚点（{@code false} 表示可以零分配地直接用 profile 原点）。 */
    public boolean requiresRuntimeResolution() {
        return isPlayer() || isEntity();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("mode", mode.name());
        if (playerId != null) json.addProperty("playerId", playerId.toString());
        if (entityId >= 0) json.addProperty("entityId", entityId);
        JsonArray offset = new JsonArray();
        offset.add(offsetX);
        offset.add(offsetY);
        offset.add(offsetZ);
        json.add("offset", offset);
        return json;
    }

    public static SceneInstanceAnchor fromJson(JsonObject json) {
        if (json == null) return WORLD;
        Mode parsed;
        try {
            parsed = json.has("mode") ? Mode.valueOf(json.get("mode").getAsString().trim().toUpperCase()) : Mode.WORLD;
        } catch (IllegalArgumentException ignored) {
            parsed = Mode.WORLD;
        }
        double ox = 0.0;
        double oy = 0.0;
        double oz = 0.0;
        if (json.has("offset") && json.get("offset").isJsonArray()) {
            JsonArray offset = json.getAsJsonArray("offset");
            if (offset.size() >= 3) {
                ox = offset.get(0).getAsDouble();
                oy = offset.get(1).getAsDouble();
                oz = offset.get(2).getAsDouble();
            }
        }
        UUID player = null;
        if (json.has("playerId")) {
            try {
                player = UUID.fromString(json.get("playerId").getAsString());
            } catch (IllegalArgumentException ignored) {
                player = null;
            }
        }
        int entity = json.has("entityId") ? json.get("entityId").getAsInt() : -1;
        return new SceneInstanceAnchor(parsed, player, entity, ox, oy, oz);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneInstanceAnchor that)) return false;
        return entityId == that.entityId
                && mode == that.mode
                && Double.compare(offsetX, that.offsetX) == 0
                && Double.compare(offsetY, that.offsetY) == 0
                && Double.compare(offsetZ, that.offsetZ) == 0
                && Objects.equals(playerId, that.playerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, playerId, entityId, offsetX, offsetY, offsetZ);
    }

    @Override
    public String toString() {
        return "SceneInstanceAnchor[" + mode + (playerId != null ? " player=" + playerId : "")
                + (entityId >= 0 ? " entity=" + entityId : "")
                + " offset=(" + offsetX + "," + offsetY + "," + offsetZ + ")]";
    }
}
