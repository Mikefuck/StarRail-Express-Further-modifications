package com.habitrain.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.habitrain.core.persist.AtomicJsonFiles;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Mod 菜单访问门控（服务端权威）。
 *
 * <p>在专用服务器上默认启用：未授权玩家打开受门控的 Mod 菜单页面时，客户端会用
 * 「当前为未授权的访问」覆盖层锁住整屏且无法修改数据，服务端 C2S 校验也会拒绝其
 * 配置保存请求。仅 OP 4 且非玩家（服务器后台控制台/命令方块等）可通过
 * {@code /habi_api menugate} 开关门控并维护允许访问的玩家列表。</p>
 *
 * <p>单机 / 局域网（非专用服务器）不生效：客户端直接放行，服务端 C2S 校验也跳过。</p>
 *
 * <p>状态持久化到独立文件 {@code config/habitrain_menu_gate.json}，不随主配置 JSON
 * 下发，避免被 OP 客户端经 C2S 覆盖，也不污染客户端本地配置盘。</p>
 */
public final class MenuGateService {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MenuGateService");
    private static final String FILE_NAME = "habitrain_menu_gate.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<AllowedPlayer> ALLOWED = new ArrayList<>();
    private static boolean enabled = true;
    /** 已成功从盘加载或成功写入过；坏档时据此保留内存中的上一份好配置。 */
    private static boolean appliedFromDisk = false;
    private static volatile @Nullable Path fileOverride;

    private MenuGateService() {}

    /** 允许访问的玩家条目：双方都有 UUID 时只比 UUID，避免同名/改名绕过。 */
    public static final class AllowedPlayer {
        private String name;
        private String uuid;

        public AllowedPlayer(String name, String uuid) {
            this.name = name == null ? "" : name;
            this.uuid = uuid == null ? "" : uuid;
        }

        public String getName() { return name; }

        public String getUuid() { return uuid; }
    }

    /** 测试注入落盘路径，并复位内存状态。生产路径仍读 Fabric config 目录。 */
    public static void setFileForTests(@Nullable Path path) {
        fileOverride = path;
        ALLOWED.clear();
        enabled = true;
        appliedFromDisk = false;
    }

    static Path file() {
        Path override = fileOverride;
        if (override != null) {
            return override;
        }
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        Path path = file();
        AtomicJsonFiles.JsonLoad<JsonObject> loaded = AtomicJsonFiles.readJson(path, JsonObject.class, GSON);
        if (loaded.ok()) {
            applyJson(loaded.value());
            appliedFromDisk = true;
            if (loaded.usedBackup()) {
                save();
            }
            LOGGER.info("已加载 Mod 菜单门控: enabled={}, 允许 {} 人", enabled, ALLOWED.size());
            return;
        }
        if (loaded.isMissing()) {
            if (!appliedFromDisk) {
                enabled = true;
                ALLOWED.clear();
            }
            if (save()) {
                LOGGER.info("已写入默认 Mod 菜单门控: enabled={}, 允许 {} 人", enabled, ALLOWED.size());
            }
            return;
        }
        LOGGER.error("加载 Mod 菜单门控失败（配置已隔离），不覆盖允许列表");
        if (!appliedFromDisk) {
            enabled = true;
            ALLOWED.clear();
        }
    }

    public static boolean save() {
        JsonObject root = toJson();
        boolean ok = AtomicJsonFiles.writeJson(file(), root, GSON, true);
        if (!ok) {
            LOGGER.error("保存 Mod 菜单门控失败");
            return false;
        }
        appliedFromDisk = true;
        return true;
    }

    public static boolean isEnabled() { return enabled; }

    /**
     * Dedicated + gate enabled + player not allowed → blocked.
     * Null player, non-dedicated, unresolved server, or gate disabled → not blocked
     * (lottery {@code MenuGateServerBridge} semantics).
     */
    public static boolean isBlocked(@Nullable ServerPlayer player) {
        if (player == null) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null && player.level() != null) {
            server = player.level().getServer();
        }
        return isBlockedOnDedicated(server != null && server.isDedicatedServer(), isAllowed(player));
    }

    /** Same blocking predicate without Minecraft dedicated-server objects. */
    static boolean isBlockedOnDedicated(boolean dedicatedServer, boolean allowed) {
        if (!dedicatedServer || !enabled) {
            return false;
        }
        return !allowed;
    }

    public static boolean setEnabled(boolean value) {
        if (enabled == value) {
            return true;
        }
        boolean previous = enabled;
        enabled = value;
        if (save()) {
            return true;
        }
        enabled = previous;
        return false;
    }

    public static boolean isAllowed(ServerPlayer player) {
        return player != null && isAllowed(player.getUUID(), player.getGameProfile().getName());
    }

    public static boolean isAllowed(UUID uuid, String name) {
        String us = uuid == null ? "" : uuid.toString();
        for (AllowedPlayer ap : ALLOWED) {
            boolean apHasUuid = ap.uuid != null && !ap.uuid.isEmpty();
            boolean usHasUuid = !us.isEmpty();
            if (apHasUuid && usHasUuid) {
                // 双方都有 UUID 时只认 UUID，避免同名/改名绕过。
                if (ap.uuid.equalsIgnoreCase(us)) return true;
                continue;
            }
            // 只有一方缺少 UUID 时，才允许按名字回退（旧档兼容）。
            if (ap.name != null && !ap.name.isEmpty() && name != null && ap.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public static List<AllowedPlayer> getAllowed() {
        return Collections.unmodifiableList(ALLOWED);
    }

    /**
     * 添加允许访问的玩家。必须带可解析 UUID；空 UUID 一律拒绝。
     * 已存在（同 UUID 或同名字忽略大小写）时仅补齐信息并保存，返回 false；
     * 新增且落盘成功返回 true。保存失败会回滚内存变更。
     */
    public static boolean add(String name, String uuid) {
        if (name == null || name.trim().isEmpty()) return false;
        name = name.trim();
        UUID parsed = parseUuid(uuid);
        if (parsed == null) return false;
        String us = parsed.toString();
        for (AllowedPlayer ap : ALLOWED) {
            if (!ap.uuid.isEmpty() && ap.uuid.equalsIgnoreCase(us)) {
                String oldName = ap.name;
                if (!ap.name.equals(name)) {
                    ap.name = name;
                    if (!save()) {
                        ap.name = oldName;
                    }
                }
                return false;
            }
            if (ap.name.equalsIgnoreCase(name)) {
                String oldUuid = ap.uuid;
                String oldName = ap.name;
                ap.uuid = us;
                ap.name = name;
                if (!save()) {
                    ap.uuid = oldUuid;
                    ap.name = oldName;
                }
                return false;
            }
        }
        AllowedPlayer created = new AllowedPlayer(name, us);
        ALLOWED.add(created);
        if (!save()) {
            ALLOWED.remove(created);
            return false;
        }
        return true;
    }

    /** 按名字（忽略大小写）移除，返回是否移除成功。 */
    public static boolean removeByName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        return removeMatching(ap -> ap.name.equalsIgnoreCase(trimmed));
    }

    /** 按 UUID 移除，返回是否移除成功。 */
    public static boolean removeByUuid(UUID uuid) {
        if (uuid == null) return false;
        String us = uuid.toString();
        return removeMatching(ap -> ap.uuid.equalsIgnoreCase(us));
    }

    /** 命令入口：名字或 UUID 均可；返回是否移除成功。 */
    public static boolean remove(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.trim().isEmpty()) return false;
        String s = nameOrUuid.trim();
        UUID parsed = parseUuid(s);
        if (parsed != null && removeByUuid(parsed)) {
            return true;
        }
        return removeByName(s);
    }

    public static @Nullable UUID parseUuid(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean removeMatching(java.util.function.Predicate<AllowedPlayer> match) {
        List<AllowedPlayer> snapshot = copyAllowed();
        boolean removed = ALLOWED.removeIf(match);
        if (!removed) {
            return false;
        }
        if (save()) {
            return true;
        }
        ALLOWED.clear();
        ALLOWED.addAll(snapshot);
        return false;
    }

    private static List<AllowedPlayer> copyAllowed() {
        List<AllowedPlayer> copy = new ArrayList<>(ALLOWED.size());
        for (AllowedPlayer ap : ALLOWED) {
            copy.add(new AllowedPlayer(ap.name, ap.uuid));
        }
        return copy;
    }

    private static JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        JsonArray arr = new JsonArray();
        for (AllowedPlayer ap : ALLOWED) {
            JsonObject o = new JsonObject();
            o.addProperty("name", ap.name);
            o.addProperty("uuid", ap.uuid);
            arr.add(o);
        }
        root.add("allowed", arr);
        return root;
    }

    private static void applyJson(JsonObject root) {
        ALLOWED.clear();
        enabled = true;
        if (root == null) {
            return;
        }
        if (root.has("enabled") && root.get("enabled").isJsonPrimitive()) {
            enabled = root.get("enabled").getAsBoolean();
        }
        if (root.has("allowed") && root.get("allowed").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("allowed");
            for (var el : arr) {
                if (el == null || !el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String name = o.has("name") && o.get("name").isJsonPrimitive()
                        ? o.get("name").getAsString() : "";
                String uuid = o.has("uuid") && o.get("uuid").isJsonPrimitive()
                        ? o.get("uuid").getAsString() : "";
                UUID parsed = parseUuid(uuid);
                if (parsed == null) {
                    LOGGER.warn("忽略无有效 UUID 的门控条目 name={}", name);
                    continue;
                }
                if (name == null || name.isBlank()) {
                    name = parsed.toString();
                }
                ALLOWED.add(new AllowedPlayer(name, parsed.toString()));
            }
        }
    }
}
