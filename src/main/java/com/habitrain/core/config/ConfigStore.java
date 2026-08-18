package com.habitrain.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.habitrain.core.api.TaskDefinition;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.task.TaskBalancer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class ConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigStore.class.getSimpleName());
    private final File configFile;
    private final Gson gson;
    private boolean dirty = false;

    public ConfigStore() {
        this.configFile = new File(
                FabricLoader.getInstance().getConfigDir().toFile(),
                "habitrain_core.json"
        );
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public File getConfigFile() { return configFile; }

    public void markDirty() {
        this.dirty = true;
    }

    /**
     * Save to file only if dirty. Resets the dirty flag on success.
     * @return true if the file was actually written, false if no-op
     */
    public boolean commit(ConfigRepository repo) {
        if (!dirty) return false;
        boolean saved = save(repo);
        if (saved) {
            dirty = false;
            return true;
        }
        // save 失败：保留 dirty，下次 commit 重试，避免改动永久丢失
        return false;
    }

    public void load(ConfigRepository repo) {
        this.dirty = false;
        applyDefaults(repo);

        if (!configFile.exists()) {
            createDefaultConfig(repo);
            save(repo);
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            if (root.has("global")) {
                JsonObject global = root.getAsJsonObject("global");

                if (global.has("dlcProbabilityTarget")) {
                    repo.setDlcProbabilityTarget((float) Math.max(0.1, Math.min(0.8,
                            global.get("dlcProbabilityTarget").getAsDouble())));
                } else if (global.has("dlcWeightBoost")) {
                    float oldBoost = Math.max(0f, global.get("dlcWeightBoost").getAsFloat());
                    long dlcCount = countDlcTasks();
                    long origCount = countOriginalTasks();
                    if (dlcCount > 0 && origCount > 0) {
                        float total = oldBoost * dlcCount + origCount;
                        repo.setDlcProbabilityTarget((float) Math.max(0.1, Math.min(0.8, oldBoost * dlcCount / total)));
                    }
                }

                if (global.has("shaderWhitelistEnabled")) {
                    repo.setShaderWhitelistEnabled(global.get("shaderWhitelistEnabled").getAsBoolean());
                }
                if (global.has("shaderWhitelist")) {
                    var arr = global.getAsJsonArray("shaderWhitelist");
                    List<String> list = new ArrayList<>();
                    for (var el : arr) {
                        String name = el.getAsString();
                        if (!name.isEmpty()) list.add(name);
                    }
                    repo.setShaderWhitelist(list);
                }

                if (global.has("sheriffCountDivisor")) {
                    int div = global.get("sheriffCountDivisor").getAsInt();
                    if (div > 0) repo.setSheriffCountDivisor(div);
                }

                if (global.has("tempPowerPrice")) {
                    int price = global.get("tempPowerPrice").getAsInt();
                    if (price >= 0) repo.setTempPowerPrice(price);
                }

                if (global.has("knifeDurabilityEnabled")) {
                    repo.setKnifeDurabilityEnabled(global.get("knifeDurabilityEnabled").getAsBoolean());
                }

                if (global.has("lobbyVoiceGroupEnabled")) {
                    repo.setLobbyVoiceGroupEnabled(global.get("lobbyVoiceGroupEnabled").getAsBoolean());
                }

                if (global.has("blackoutEffectEnhancementEnabled")) {
                    repo.setBlackoutEffectEnhancementEnabled(
                            global.get("blackoutEffectEnhancementEnabled").getAsBoolean());
                }

                LOGGER.info("全局设置: DLC目标占比={}%, 光影白名单={}, 允许{}个光影, 警长除数={}, 大厅语音群组={}, 停电黑暗增强={}",
                        Math.round(repo.getDlcProbabilityTarget() * 100),
                        repo.isShaderWhitelistEnabled() ? "启用" : "禁用",
                        repo.getShaderWhitelist().size(),
                        repo.getSheriffCountDivisor(),
                        repo.isLobbyVoiceGroupEnabled() ? "启用" : "禁用",
                        repo.isBlackoutEffectEnhancementEnabled() ? "启用" : "禁用");
            }

            if (root.has("tasks")) {
                JsonObject tasks = root.getAsJsonObject("tasks");
                Map<String, TaskConfigEntry> taskMap = repo.getMutableTaskConfigs();
                for (var entry : tasks.entrySet()) {
                    taskMap.put(entry.getKey(),
                            TaskConfigEntry.fromJson(entry.getValue().getAsJsonObject()));
                }
            }

            if (root.has("gameModes")) {
                JsonObject modes = root.getAsJsonObject("gameModes");
                Map<String, GameModeConfigScope> modeMap = repo.getMutableGameModeConfigs();
                modeMap.clear();
                for (var entry : modes.entrySet()) {
                    modeMap.put(entry.getKey(),
                            GameModeConfigScope.fromJson(entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            }

            if (root.has("minigames")) {
                JsonObject mg = root.getAsJsonObject("minigames");
                Map<String, MinigameConfigEntry> mgMap = repo.getMutableMinigameConfigs();
                mgMap.clear();
                if (mg.has("globalEnabled")) {
                    repo.setMinigameGlobalEnabled(mg.get("globalEnabled").getAsBoolean());
                }
                if (mg.has("entries")) {
                    JsonObject entries = mg.getAsJsonObject("entries");
                    for (var e : entries.entrySet()) {
                        mgMap.put(e.getKey(),
                                MinigameConfigEntry.fromJson(e.getValue().getAsJsonObject()));
                    }
                }
            }

            if (root.has("modeMapVote") && root.get("modeMapVote").isJsonObject()) {
                repo.setModeMapVote(ModeMapVoteSettings.fromJson(root.getAsJsonObject("modeMapVote")));
            } else {
                repo.setModeMapVote(ModeMapVoteSettings.createDefault());
            }

            if (root.has("environment") && root.get("environment").isJsonObject()) {
                repo.setEnvironment(EnvironmentSettings.fromJson(root.getAsJsonObject("environment")));
            } else {
                repo.setEnvironment(EnvironmentSettings.createDefault());
            }

            if (root.has("roleOverrides") && root.get("roleOverrides").isJsonObject()) {
                repo.setRoleOverrides(RoleOverrideConfigSection.fromJson(root.getAsJsonObject("roleOverrides")));
            } else {
                repo.setRoleOverrides(RoleOverrideConfigSection.createDefault());
            }

            LOGGER.info("任务配置已加载: {} 个任务, {} 个GameMode, {} 个小游戏",
                    repo.getMutableTaskConfigs().size(),
                    repo.getMutableGameModeConfigs().size(),
                    repo.getMutableMinigameConfigs().size());
        } catch (Exception e) {
            LOGGER.error("加载任务配置失败，备份损坏文件后重建默认配置", e);
            quarantineCorruptConfig();
            // 恢复流程必须重置与 load() 开头相同的完整默认值序列：JSON 在 global
            // 段中途抛错时，此前已 set 的项（如 knifeDurabilityEnabled /
            // sheriffCountDivisor / tempPowerPrice）会残留半解析值并被写入
            // "默认"配置（review M19）。
            applyDefaults(repo);
            createDefaultConfig(repo);
            save(repo);
        }
    }

    /** 与 load() 开头一致的默认值重置序列（review M19 提取共用）。 */
    private void applyDefaults(ConfigRepository repo) {
        repo.getMutableTaskConfigs().clear();
        repo.getMutableGameModeConfigs().clear();
        repo.getMutableMinigameConfigs().clear();
        repo.setDlcProbabilityTarget(0.5f);
        repo.setShaderWhitelistEnabled(false);
        repo.setShaderWhitelist(List.of());
        repo.setSheriffCountDivisor(6);
        repo.setTempPowerPrice(100);
        repo.setMinigameGlobalEnabled(true);
        repo.setKnifeDurabilityEnabled(false);
        repo.setLobbyVoiceGroupEnabled(true);
        repo.setBlackoutEffectEnhancementEnabled(false);
        repo.setModeMapVote(ModeMapVoteSettings.createDefault());
        repo.setEnvironment(EnvironmentSettings.createDefault());
        repo.setRoleOverrides(RoleOverrideConfigSection.createDefault());
    }

    /**
     * Move a corrupt config aside before overwriting with defaults so operators can recover.
     */
    private void quarantineCorruptConfig() {
        try {
            if (!configFile.exists()) return;
            Path src = configFile.toPath();
            Path bak = src.resolveSibling(
                    configFile.getName() + ".corrupt-" + System.currentTimeMillis() + ".bak");
            Files.move(src, bak, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("已将损坏配置备份为 {}", bak.getFileName());
        } catch (Exception e) {
            LOGGER.warn("备份损坏配置失败，将直接覆盖", e);
        }
    }

    /**
     * @return true 写盘成功；false 失败（含 IO 与 buildJsonRoot 抛出的 RuntimeException），
     *         调用方据此决定是否保留 dirty flag（见 commit）。
     */
    public boolean save(ConfigRepository repo) {
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            JsonObject root = buildJsonRoot(repo, true);
            Path target = configFile.toPath();
            Path temp = target.resolveSibling(configFile.getName() + ".tmp");

            // Write to temp, then atomic replace — avoids half-written JSON on crash.
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(temp.toFile()), StandardCharsets.UTF_8)) {
                gson.toJson(root, writer);
                writer.flush();
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            // 同时捕获 IOException 与 buildJsonRoot 抛出的 RuntimeException，
            // 避免非 IO 异常传出后 commit 已清 dirty 导致改动永久丢失。
            LOGGER.error("保存配置失败", e);
            try {
                Path temp = configFile.toPath().resolveSibling(configFile.getName() + ".tmp");
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    public JsonObject buildJsonRoot(ConfigRepository repo, boolean includeWeightBoost) {
        JsonObject root = new JsonObject();

        JsonObject global = new JsonObject();
        global.addProperty("dlcProbabilityTarget", repo.getDlcProbabilityTarget());
        if (includeWeightBoost) {
            global.addProperty("dlcWeightBoost", getDlcWeightBoost(repo));
        }
        global.addProperty("shaderWhitelistEnabled", repo.isShaderWhitelistEnabled());
        var whitelistArray = new com.google.gson.JsonArray();
        for (String name : repo.getShaderWhitelist()) whitelistArray.add(name);
        global.add("shaderWhitelist", whitelistArray);
        global.addProperty("sheriffCountDivisor", repo.getSheriffCountDivisor());
        global.addProperty("tempPowerPrice", repo.getTempPowerPrice());
        global.addProperty("knifeDurabilityEnabled", repo.isKnifeDurabilityEnabled());
        global.addProperty("lobbyVoiceGroupEnabled", repo.isLobbyVoiceGroupEnabled());
        global.addProperty("blackoutEffectEnhancementEnabled", repo.isBlackoutEffectEnhancementEnabled());
        root.add("global", global);

        JsonObject tasks = new JsonObject();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            String fullId = def.getFullId();
            TaskConfigEntry entry = repo.getTaskConfig(fullId);
            if (entry == null) entry = new TaskConfigEntry(true);
            tasks.add(fullId, entry.toJson());
        }
        root.add("tasks", tasks);

        JsonObject gameModes = new JsonObject();
        for (Map.Entry<String, GameModeConfigScope> e : repo.getAllGameModeConfigs().entrySet()) {
            gameModes.add(e.getKey(), e.getValue().toJson());
        }
        root.add("gameModes", gameModes);

        JsonObject minigames = new JsonObject();
        minigames.addProperty("globalEnabled", repo.isMinigameGlobalEnabled());
        JsonObject mgEntries = new JsonObject();
        for (String id : safeGetAllMinigameIds()) {
            MinigameConfigEntry entry = repo.getMinigameConfig(id);
            if (entry == null) entry = MinigameConfigEntry.createDefault();
            mgEntries.add(id, entry.toJson());
        }
        minigames.add("entries", mgEntries);
        root.add("minigames", minigames);

        root.add("modeMapVote", repo.getModeMapVote().toJson());

        root.add("environment", repo.getEnvironment().toJson());

        root.add("roleOverrides", repo.getRoleOverrides().toJson());

        return root;
    }

    public void createDefaultConfig(ConfigRepository repo) {
        Map<String, TaskConfigEntry> taskMap = repo.getMutableTaskConfigs();
        taskMap.clear();
        for (TaskDefinition def : TaskRegistry.getAll()) {
            taskMap.put(def.getFullId(), new TaskConfigEntry(true));
        }
        repo.setDlcProbabilityTarget(0.5f);
        Map<String, MinigameConfigEntry> mgMap = repo.getMutableMinigameConfigs();
        mgMap.clear();
        for (String id : safeGetAllMinigameIds()) {
            mgMap.put(id, MinigameConfigEntry.createDefault());
        }
        repo.setMinigameGlobalEnabled(true);
        repo.setLobbyVoiceGroupEnabled(true);
        repo.setBlackoutEffectEnhancementEnabled(false);
        repo.setModeMapVote(ModeMapVoteSettings.createDefault());
        repo.setEnvironment(EnvironmentSettings.createDefault());
    }

    public String toJsonString(ConfigRepository repo) {
        return gson.toJson(buildJsonRoot(repo, false));
    }

    public List<String> safeGetAllMinigameIds() {
        return SREIntegration.getAllMinigameIds();
    }

    public long countDlcTasks() {
        return TaskRegistry.getAll().stream()
                .filter(t -> !"habitrain_core".equals(t.getModId()))
                .count();
    }

    public long countOriginalTasks() {
        return TaskRegistry.getAll().stream()
                .filter(t -> "habitrain_core".equals(t.getModId()))
                .count();
    }

    public float getDlcWeightBoost(ConfigRepository repo) {
        long dlcCount = countDlcTasks();
        long origCount = countOriginalTasks();
        float boost = TaskBalancer.calcBoost(repo.getDlcProbabilityTarget(), dlcCount, origCount);

        if (dlcCount > 0) {
            float dlcTotal = boost * dlcCount;
            float pct = (dlcTotal + origCount) > 0 ? dlcTotal / (dlcTotal + origCount) : 0;
            LOGGER.info("[DLC概率] 目标={}%, {}个DLC vs {}个原版 → autoBoost={} (实际概率≈{}%)",
                    Math.round(repo.getDlcProbabilityTarget() * 100), dlcCount, origCount,
                    String.format("%.2f", boost), Math.round(pct * 100));
        }
        return boost;
    }

}
