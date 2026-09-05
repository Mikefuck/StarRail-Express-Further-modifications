package com.habitrain.core.scene.server;

import com.habitrain.core.scene.model.SceneBounds;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员临时选区会话管理器（按玩家 UUID 隔离，防止多 OP 互相覆盖选区）。
 */
public final class SceneSelectionSessionManager {
    public static final long DEFAULT_EXPIRY_MS = 30 * 60 * 1000L; // 30 分钟无操作自动清理

    private static final SceneSelectionSessionManager INSTANCE = new SceneSelectionSessionManager();

    public static SceneSelectionSessionManager getInstance() {
        return INSTANCE;
    }

    public static final class SelectionSession {
        private final UUID playerId;
        private String dimension;
        private String mapKey;
        private BlockPos pointA;
        private BlockPos pointB;
        private long updatedAt;

        public SelectionSession(UUID playerId, String dimension, String mapKey) {
            this.playerId = Objects.requireNonNull(playerId);
            this.dimension = dimension != null ? dimension : "";
            this.mapKey = mapKey != null ? mapKey : "";
            this.updatedAt = System.currentTimeMillis();
        }

        public UUID getPlayerId() { return playerId; }
        public String getDimension() { return dimension; }
        public String getMapKey() { return mapKey; }
        public BlockPos getPointA() { return pointA; }
        public BlockPos getPointB() { return pointB; }
        public long getUpdatedAt() { return updatedAt; }

        public boolean isComplete() {
            return pointA != null && pointB != null;
        }

        public SceneBounds toBounds() {
            if (pointA == null || pointB == null) return SceneBounds.EMPTY;
            return SceneBounds.fromPoints(pointA, pointB);
        }

        public void touch() {
            this.updatedAt = System.currentTimeMillis();
        }
    }

    public enum SelectionStep {
        SET_POINT_A,
        SET_POINT_B_COMPLETED,
        RESET_AND_SET_POINT_A,
        CLEARED
    }

    public record SelectionResult(SelectionStep step, SelectionSession session, SceneBounds bounds) {}

    private final Map<UUID, SelectionSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> editorMapKeys = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, String>> editorBackgroundIds = new ConcurrentHashMap<>();
    private final Map<UUID, String> editorSessionIds = new ConcurrentHashMap<>();

    private SceneSelectionSessionManager() {}

    /**
     * 处理 Shift + 右键方块逻辑：
     * 1. 尚无 A：设置点 A。
     * 2. 已有 A 无 B：设置点 B，形成完整选区。
     * 3. A/B 均已完成：重新开始新选区，并将该点击位置设为新 A。
     */
    public SelectionResult handleShiftRightClickBlock(UUID playerId, String dimension, String mapKey, BlockPos pos) {
        if (playerId == null || pos == null) {
            return new SelectionResult(SelectionStep.CLEARED, null, SceneBounds.EMPTY);
        }
        SelectionSession session = sessions.computeIfAbsent(playerId, id -> new SelectionSession(id, dimension, mapKey));
        rotateEditorSession(playerId);
        // 若维度或地图切换，则重置选区
        if (!Objects.equals(session.dimension, dimension) || !Objects.equals(session.mapKey, mapKey)) {
            session.dimension = dimension;
            session.mapKey = mapKey;
            session.pointA = null;
            session.pointB = null;
        }
        session.touch();

        SelectionStep step;
        if (session.pointA == null) {
            session.pointA = pos.immutable();
            session.pointB = null;
            step = SelectionStep.SET_POINT_A;
        } else if (session.pointB == null) {
            session.pointB = pos.immutable();
            step = SelectionStep.SET_POINT_B_COMPLETED;
        } else {
            session.pointA = pos.immutable();
            session.pointB = null;
            step = SelectionStep.RESET_AND_SET_POINT_A;
        }

        return new SelectionResult(step, session, session.toBounds());
    }

    /**
     * 清除指定玩家的临时选区。
     */
    public SelectionResult clear(UUID playerId) {
        if (playerId == null) return new SelectionResult(SelectionStep.CLEARED, null, SceneBounds.EMPTY);
        rotateEditorSession(playerId);
        SelectionSession removed = sessions.remove(playerId);
        return new SelectionResult(SelectionStep.CLEARED, removed, SceneBounds.EMPTY);
    }

    public SelectionSession getSession(UUID playerId) {
        if (playerId == null) return null;
        SelectionSession session = sessions.get(playerId);
        if (session != null && isExpired(session, DEFAULT_EXPIRY_MS)) {
            sessions.remove(playerId);
            return null;
        }
        return session;
    }

    public SceneBounds getBounds(UUID playerId) {
        SelectionSession session = getSession(playerId);
        return session != null ? session.toBounds() : SceneBounds.EMPTY;
    }

    /** Remembers the map being edited without changing the independently owned temporary selection. */
    public void selectEditorMap(UUID playerId, String mapKey) {
        if (playerId == null) return;
        String normalized = mapKey == null ? "" : mapKey.trim();
        if (normalized.isEmpty()) {
            editorMapKeys.remove(playerId);
            editorSessionIds.remove(playerId);
        } else {
            String previous = editorMapKeys.put(playerId, normalized);
            if (!Objects.equals(previous, normalized) || !editorSessionIds.containsKey(playerId)) {
                rotateEditorSession(playerId);
            }
        }
    }

    public String getEditorMapKey(UUID playerId) {
        if (playerId == null) return "";
        return editorMapKeys.getOrDefault(playerId, "");
    }

    public void selectEditorBackground(UUID playerId, String mapKey, String backgroundId) {
        if (playerId == null) return;
        String normalizedMap = mapKey == null ? "" : mapKey.trim();
        if (normalizedMap.isEmpty()) return;
        String normalizedBg = backgroundId != null && !backgroundId.isBlank()
                ? com.habitrain.core.scene.model.SceneBackgroundKey.normalizeBackgroundId(backgroundId)
                : com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID;
        editorBackgroundIds.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>())
                .put(normalizedMap, normalizedBg);
    }

    public String getEditorBackgroundId(UUID playerId, String mapKey) {
        if (playerId == null) return com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID;
        String normalizedMap = mapKey == null ? "" : mapKey.trim();
        Map<String, String> mapBgs = editorBackgroundIds.get(playerId);
        if (mapBgs == null) return com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID;
        return mapBgs.getOrDefault(normalizedMap, com.habitrain.core.scene.model.SceneBackgroundKey.DEFAULT_ID);
    }

    /** Opaque connection-local token used to invalidate pending staging confirmations. */
    public String getEditorSessionId(UUID playerId) {
        if (playerId == null) return "";
        return editorSessionIds.getOrDefault(playerId, "");
    }

    private void rotateEditorSession(UUID playerId) {
        if (playerId != null) editorSessionIds.put(playerId, UUID.randomUUID().toString());
    }

    public void onPlayerDisconnect(UUID playerId) {
        if (playerId != null) {
            sessions.remove(playerId);
            editorMapKeys.remove(playerId);
            editorBackgroundIds.remove(playerId);
            editorSessionIds.remove(playerId);
        }
    }

    public void onPlayerChangeDimension(UUID playerId, String newDimension) {
        if (playerId == null) return;
        SelectionSession session = sessions.get(playerId);
        if (session != null && !Objects.equals(session.dimension, newDimension)) {
            sessions.remove(playerId);
            rotateEditorSession(playerId);
        }
    }

    public void cleanupExpired(long timeoutMs) {
        sessions.entrySet().removeIf(e -> isExpired(e.getValue(), timeoutMs));
    }

    public void clearAll() {
        sessions.clear();
        editorMapKeys.clear();
        editorBackgroundIds.clear();
        editorSessionIds.clear();
    }

    private static boolean isExpired(SelectionSession s, long timeoutMs) {
        return (System.currentTimeMillis() - s.updatedAt) > timeoutMs;
    }
}
