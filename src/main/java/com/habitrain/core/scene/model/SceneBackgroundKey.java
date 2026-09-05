package com.habitrain.core.scene.model;

/** Stable identity and asset-key rules for per-map scene backgrounds. */
public final class SceneBackgroundKey {
    public static final String DEFAULT_ID = "__default__";
    private static final String SEPARATOR = "::habiscene::";

    private SceneBackgroundKey() {}

    public static String normalizeMapKey(String mapKey) {
        return mapKey == null || mapKey.isBlank() ? DEFAULT_ID : mapKey.trim();
    }

    public static String normalizeBackgroundId(String backgroundId) {
        if (backgroundId == null || backgroundId.isBlank() || DEFAULT_ID.equals(backgroundId.trim())) {
            return DEFAULT_ID;
        }
        String normalized = backgroundId.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
        if (normalized.isBlank()) return DEFAULT_ID;
        return normalized.substring(0, Math.min(48, normalized.length()));
    }

    public static boolean isDefault(String backgroundId) {
        return backgroundId == null || backgroundId.isBlank() || DEFAULT_ID.equals(backgroundId.trim());
    }

    public static String assetKey(String mapKey, String backgroundId) {
        String map = normalizeMapKey(mapKey);
        String background = normalizeBackgroundId(backgroundId);
        return DEFAULT_ID.equals(background) ? map : map + SEPARATOR + background;
    }

    public static String mapKeyFromAssetKey(String assetKey) {
        String normalized = normalizeMapKey(assetKey);
        int split = normalized.indexOf(SEPARATOR);
        return split < 0 ? normalized : normalized.substring(0, split);
    }

    public static String backgroundIdFromAssetKey(String assetKey) {
        String normalized = normalizeMapKey(assetKey);
        int split = normalized.indexOf(SEPARATOR);
        return split < 0 ? DEFAULT_ID : normalizeBackgroundId(normalized.substring(split + SEPARATOR.length()));
    }
}
