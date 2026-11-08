package com.habitrain.core.api.scene.asset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
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
        this.sha256 = sha256 != null ? sha256.trim().toLowerCase(Locale.ROOT) : "";
        this.uncompressedSize = Math.max(0L, uncompressedSize);
        this.compressedSize = Math.max(0L, compressedSize);
        this.sectionCount = Math.max(0, sectionCount);
        this.dataVersion = dataVersion;
        this.fingerprint = fingerprint != null ? fingerprint : "";
        this.createdAt = createdAt > 0 ? createdAt : (this.sha256.isEmpty() ? 0L : System.currentTimeMillis());
    }

    /**
     * 严格校验 SHA-256 字符串是否为 64 位十六进制字符（拒绝任何路径分隔符、冒号或 {@code ..}）。
     */
    public static boolean isValidSha256(String hash) {
        if (hash == null || hash.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = hash.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    public String sha256() { return sha256; }
    public long uncompressedSize() { return uncompressedSize; }
    public long compressedSize() { return compressedSize; }
    public int sectionCount() { return sectionCount; }
    public int dataVersion() { return dataVersion; }
    public String fingerprint() { return fingerprint; }
    public long createdAt() { return createdAt; }

    public boolean isValid() {
        return isValidSha256(sha256) && compressedSize > 0 && sectionCount > 0;
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
        String hash = getString(json, "sha256", "");
        long uncompressed = getLong(json, "uncompressedSize", 0L);
        long compressed = getLong(json, "compressedSize", 0L);
        int sections = getInt(json, "sectionCount", 0);
        int version = getInt(json, "dataVersion", 0);
        String fp = getString(json, "fingerprint", "");
        long time = getLong(json, "createdAt", 0L);
        return new SceneAssetDescriptor(hash, uncompressed, compressed, sections, version, fp, time);
    }

    private static String getString(JsonObject json, String key, String fallback) {
        JsonElement el = json.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : fallback;
    }

    private static long getLong(JsonObject json, String key, long fallback) {
        JsonElement el = json.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int getInt(JsonObject json, String key, int fallback) {
        JsonElement el = json.get(key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsInt();
        } catch (NumberFormatException e) {
            return fallback;
        }
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
