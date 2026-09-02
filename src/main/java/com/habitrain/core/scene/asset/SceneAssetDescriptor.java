package com.habitrain.core.scene.asset;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * 场景只读资产描述符。
 */
public final class SceneAssetDescriptor {
    public static final SceneAssetDescriptor EMPTY = new SceneAssetDescriptor("", 0L, 0L, 0, 0, "", 0L);

    private final String sha256;
    private final long uncompressedSize;
    private final long compressedSize;
    private final int sectionCount;
    private final int dataVersion;
    private final String fingerprint;
    private final long createdAt;

    public SceneAssetDescriptor(String sha256, long uncompressedSize, long compressedSize,
                                int sectionCount, int dataVersion, String fingerprint, long createdAt) {
        this.sha256 = sha256 != null ? sha256.trim().toLowerCase() : "";
        this.uncompressedSize = Math.max(0L, uncompressedSize);
        this.compressedSize = Math.max(0L, compressedSize);
        this.sectionCount = Math.max(0, sectionCount);
        this.dataVersion = dataVersion;
        this.fingerprint = fingerprint != null ? fingerprint : "";
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
    }

    public String sha256() { return sha256; }
    public long uncompressedSize() { return uncompressedSize; }
    public long compressedSize() { return compressedSize; }
    public int sectionCount() { return sectionCount; }
    public int dataVersion() { return dataVersion; }
    public String fingerprint() { return fingerprint; }
    public long createdAt() { return createdAt; }

    public boolean isValid() {
        return sha256.length() == 64 && compressedSize > 0 && sectionCount > 0;
    }

    public String shortHash() {
        return sha256.length() >= 12 ? sha256.substring(0, 12) : sha256;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("sha256", sha256);
        json.addProperty("uncompressedSize", uncompressedSize);
        json.addProperty("compressedSize", compressedSize);
        json.addProperty("sectionCount", sectionCount);
        json.addProperty("dataVersion", dataVersion);
        json.addProperty("fingerprint", fingerprint);
        json.addProperty("createdAt", createdAt);
        return json;
    }

    public static SceneAssetDescriptor fromJson(JsonObject json) {
        if (json == null) return EMPTY;
        String hash = json.has("sha256") ? json.get("sha256").getAsString() : "";
        long uncompressed = json.has("uncompressedSize") ? json.get("uncompressedSize").getAsLong() : 0L;
        long compressed = json.has("compressedSize") ? json.get("compressedSize").getAsLong() : 0L;
        int sections = json.has("sectionCount") ? json.get("sectionCount").getAsInt() : 0;
        int version = json.has("dataVersion") ? json.get("dataVersion").getAsInt() : 0;
        String fp = json.has("fingerprint") ? json.get("fingerprint").getAsString() : "";
        long time = json.has("createdAt") ? json.get("createdAt").getAsLong() : 0L;
        return new SceneAssetDescriptor(hash, uncompressed, compressed, sections, version, fp, time);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneAssetDescriptor that)) return false;
        return uncompressedSize == that.uncompressedSize &&
                compressedSize == that.compressedSize &&
                sectionCount == that.sectionCount &&
                dataVersion == that.dataVersion &&
                createdAt == that.createdAt &&
                Objects.equals(sha256, that.sha256) &&
                Objects.equals(fingerprint, that.fingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sha256, uncompressedSize, compressedSize, sectionCount, dataVersion, fingerprint, createdAt);
    }

    @Override
    public String toString() {
        return "SceneAssetDescriptor[hash=" + shortHash() + ",comp=" + compressedSize + ",sections=" + sectionCount + "]";
    }
}
