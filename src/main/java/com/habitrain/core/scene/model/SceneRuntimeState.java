package com.habitrain.core.scene.model;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * 场景运行时状态（服务端下发给客户端以确定性推进时间与位移）。
 */
public final class SceneRuntimeState {
    public static final SceneRuntimeState INACTIVE = new SceneRuntimeState(false, 0L, 0, "__default__", "", SceneProfile.createDefault());

    private final boolean active;
    private final long startGameTime;
    private final int profileRevision;
    private final String mapKey;
    private final String assetHash;
    private final SceneProfile profile;

    public SceneRuntimeState(boolean active, long startGameTime, int profileRevision,
                             String mapKey, String assetHash, SceneProfile profile) {
        this.active = active;
        this.startGameTime = startGameTime;
        this.profileRevision = profileRevision;
        this.mapKey = mapKey != null ? mapKey : "__default__";
        this.assetHash = assetHash != null ? assetHash : "";
        this.profile = profile != null ? profile : SceneProfile.createDefault();
    }

    public boolean isActive() { return active; }
    public long getStartGameTime() { return startGameTime; }
    public int getProfileRevision() { return profileRevision; }
    public String getMapKey() { return mapKey; }
    public String getAssetHash() { return assetHash; }
    public SceneProfile getProfile() { return profile; }

    /**
     * 计算自开局以来经过的真实秒数。
     */
    public double calculateElapsedSeconds(long clientGameTime, float partialTick) {
        if (!active) return 0.0;
        // Subtract the long tick counters before adding the fractional tick. If the
        // float participates in the first addition, Java promotes the large world
        // time to float and loses multiple ticks of precision in long-running worlds.
        double elapsedTicks = (double) (clientGameTime - startGameTime) + (double) partialTick;
        if (elapsedTicks < 0.0) elapsedTicks = 0.0;
        return elapsedTicks / 20.0;
    }

    /**
     * 确定性计算当前场景沿运动方向推进的相位偏移量（单位：方块）。
     * <pre>
     * phase = floorMod(speed * elapsedSeconds + phaseOffset, loopDistance)
     * </pre>
     */
    public double calculatePhase(long clientGameTime, float partialTick) {
        if (!active) return 0.0;
        double elapsedSeconds = calculateElapsedSeconds(clientGameTime, partialTick);
        double speed = profile.getSpeedBlocksPerSecond();
        SceneLoopSettings loop = profile.getLoop();
        double loopDist = SceneMotionMath.effectiveLoopDistance(
                profile.getSourceBounds(), profile.getDirection(), loop);
        return SceneMotionMath.phase(loop.isEnabled(), speed, elapsedSeconds,
                profile.getPhaseOffsetBlocks(), loopDist);
    }

    /**
     * 计算三维位移向量 [x, y, z] = direction * phase。
     */
    public double[] calculateMotionOffset(long clientGameTime, float partialTick) {
        double phase = calculatePhase(clientGameTime, partialTick);
        double[] dir = profile.getDirection();
        return new double[]{
                dir[0] * phase,
                dir[1] * phase,
                dir[2] * phase
        };
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("active", active);
        json.addProperty("startGameTime", startGameTime);
        json.addProperty("profileRevision", profileRevision);
        json.addProperty("mapKey", mapKey);
        json.addProperty("assetHash", assetHash);
        json.add("profile", profile.toJson());
        return json;
    }

    public static SceneRuntimeState fromJson(JsonObject json) {
        if (json == null) return INACTIVE;
        boolean active = json.has("active") && json.get("active").getAsBoolean();
        long start = json.has("startGameTime") ? json.get("startGameTime").getAsLong() : 0L;
        int rev = json.has("profileRevision") ? json.get("profileRevision").getAsInt() : 0;
        String map = json.has("mapKey") ? json.get("mapKey").getAsString() : "__default__";
        String hash = json.has("assetHash") ? json.get("assetHash").getAsString() : "";
        SceneProfile prof = json.has("profile") && json.get("profile").isJsonObject()
                ? SceneProfile.fromJson(json.getAsJsonObject("profile"))
                : SceneProfile.createDefault();
        return new SceneRuntimeState(active, start, rev, map, hash, prof);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneRuntimeState that)) return false;
        return active == that.active &&
                startGameTime == that.startGameTime &&
                profileRevision == that.profileRevision &&
                Objects.equals(mapKey, that.mapKey) &&
                Objects.equals(assetHash, that.assetHash) &&
                Objects.equals(profile, that.profile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(active, startGameTime, profileRevision, mapKey, assetHash, profile);
    }
}
