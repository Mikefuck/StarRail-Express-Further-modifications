package com.habitrain.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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
    private static final File FILE = new File(
            FabricLoader.getInstance().getConfigDir().toFile(),
            "habitrain_menu_gate.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<AllowedPlayer> ALLOWED = new ArrayList<>();
    private static boolean enabled = true;

    private MenuGateService() {}

    /** 允许访问的玩家条目：优先按 UUID 匹配，未解析到 UUID（离线添加）时回退到名字匹配。 */
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

    public static void load() {
        ALLOWED.clear();
        enabled = true;
        if (!FILE.exists()) {
            save();
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("enabled")) {
                enabled = root.get("enabled").getAsBoolean();
            }
            if (root.has("allowed") && root.get("allowed").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("allowed");
                for (var el : arr) {
                    if (el == null || !el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String name = o.has("name") ? o.get("name").getAsString() : "";
                    String uuid = o.has("uuid") ? o.get("uuid").getAsString() : "";
                    ALLOWED.add(new AllowedPlayer(name, uuid));
                }
            }
            LOGGER.info("已加载 Mod 菜单门控: enabled={}, 允许 {} 人", enabled, ALLOWED.size());
        } catch (Exception e) {
            LOGGER.error("加载 Mod 菜单门控失败，使用默认值", e);
            enabled = true;
            ALLOWED.clear();
        }
    }

    public static void save() {
        try {
            if (!FILE.getParentFile().exists()) {
                FILE.getParentFile().mkdirs();
            }
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
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception e) {
            LOGGER.error("保存 Mod 菜单门控失败", e);
        }
    }

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    public static boolean isAllowed(ServerPlayer player) {
        return player != null && isAllowed(player.getUUID(), player.getGameProfile().getName());
    }

    public static boolean isAllowed(UUID uuid, String name) {
        String us = uuid == null ? "" : uuid.toString();
        for (AllowedPlayer ap : ALLOWED) {
            boolean apHasUuid = !ap.uuid.isEmpty();
            boolean usHasUuid = !us.isEmpty();
            if (apHasUuid && usHasUuid) {
                // 双方都有 UUID 时只认 UUID，避免同名/改名绕过。
                if (ap.uuid.equalsIgnoreCase(us)) return true;
                continue;
            }
            // 只有一方缺少 UUID 时，才允许按名字回退（离线添加场景）。
            if (!ap.name.isEmpty() && name != null && ap.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static List<AllowedPlayer> getAllowed() {
        return Collections.unmodifiableList(ALLOWED);
    }

    /**
     * 添加允许访问的玩家。已存在（同 UUID 或同名字忽略大小写）时仅补齐信息并保存，返回 false；
     * 新增返回 true。uuid 可为空字符串（离线添加，按名字匹配）。
     */
    public static boolean add(String name, String uuid) {
        if (name == null || name.trim().isEmpty()) return false;
        name = name.trim();
        String us = uuid == null ? "" : uuid.trim();
        for (AllowedPlayer ap : ALLOWED) {
            if (!us.isEmpty() && !ap.uuid.isEmpty() && ap.uuid.equalsIgnoreCase(us)) {
                if (!ap.name.equals(name)) ap.name = name;
                save();
                return false;
            }
            if (ap.name.equalsIgnoreCase(name)) {
                if (!us.isEmpty() && ap.uuid.isEmpty()) ap.uuid = us;
                save();
                return false;
            }
        }
        ALLOWED.add(new AllowedPlayer(name, us));
        save();
        return true;
    }

    /** 按名字（忽略大小写）移除，返回是否移除成功。 */
    public static boolean removeByName(String name) {
        if (name == null) return false;
        boolean removed = ALLOWED.removeIf(ap -> ap.name.equalsIgnoreCase(name.trim()));
        if (removed) save();
        return removed;
    }

    /** 按 UUID 移除，返回是否移除成功。 */
    public static boolean removeByUuid(UUID uuid) {
        if (uuid == null) return false;
        String us = uuid.toString();
        boolean removed = ALLOWED.removeIf(ap -> ap.uuid.equalsIgnoreCase(us));
        if (removed) save();
        return removed;
    }

    /** 命令入口：名字或 UUID 均可；返回是否移除成功。 */
    public static boolean remove(String nameOrUuid) {
        if (nameOrUuid == null || nameOrUuid.trim().isEmpty()) return false;
        String s = nameOrUuid.trim();
        try {
            UUID u = UUID.fromString(s);
            if (removeByUuid(u)) return true;
            // UUID 精确匹配失败后回退到名字（例如输入了含短横线 UUID 但条目只有名字）
        } catch (IllegalArgumentException ignored) {
        }
        return removeByName(s);
    }
}
