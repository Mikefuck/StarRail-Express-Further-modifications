package com.habitrain.core.game.blackout.shop;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.TaskConfigEntry;

import java.util.List;

/**
 * 停电任务商店目录。
 * <p>
 * 定义可购买的条目：专属任务 + 临时电源提灯。价格可通过 {@link TaskConfigEntry#shopPrice}
 * 在 ModMenu 中覆盖（临时电源走全局 {@code tempPowerPrice}）。
 *
 * 条目类型：
 * <ul>
 *   <li>{@code TASK} — 接取一个停电专属任务（修理线路/添煤/维持供电/炸毁发电机/破坏线路）</li>
 *   <li>{@code TEMP_POWER} — 临时电源提灯（进入停电阶段后可买，耐久 1 分钟）</li>
 * </ul>
 */
public final class BlackoutTaskShopCatalog {

    /** 商店条目阵营可见性。 */
    public enum Faction { GOOD, BAD, ANY }

    /** 条目类型。 */
    public enum Kind { TASK, TEMP_POWER }

    /** 单个商店条目定义。 */
    public record Entry(
            String key,                 // 商店条目 key（唯一），任务条目 = 任务 fullId，临时电源 = "temp_power"
            Kind kind,
            String displayName,
            int defaultPrice,
            Faction faction,
            boolean oncePerGame,        // 全局只能买一次（炸毁发电机）
            boolean hideAfterDestroy,   // 炸毁发电机后从好人商店隐藏（供电三件套）
            boolean onlyAfterDestroy    // 仅炸毁发电机后出现（历史字段；TEMP_POWER 现已不再依赖）
    ) {
        /** 解析后的实际价格：任务条目读 config.shopPrice 覆盖，否则默认价。 */
        public int resolvePrice() {
            if (kind == Kind.TEMP_POWER) {
                return ConfigManager.getInstance().getTempPowerPrice();
            }
            TaskConfigEntry cfg = ConfigManager.getInstance().getTaskConfig(key);
            if (cfg != null && cfg.hasShopPrice) {
                return cfg.shopPrice;
            }
            return defaultPrice;
        }
    }

    public static final Entry REPAIR_WIRING = new Entry(
            HabiTrainCore.TASK_REPAIR_WIRING, Kind.TASK, "修理线路", 50, Faction.GOOD, false, true, false);
    public static final Entry MAINTAIN_POWER = new Entry(
            HabiTrainCore.TASK_MAINTAIN_POWER, Kind.TASK, "维持供电", 100, Faction.GOOD, false, true, false);
    public static final Entry ADD_COAL = new Entry(
            HabiTrainCore.TASK_ADD_COAL, Kind.TASK, "添煤", 50, Faction.GOOD, false, true, false);
    public static final Entry FURNACE_EXPLOSION = new Entry(
            HabiTrainCore.MOD_ID + ":furnace_explosion", Kind.TASK, "炸毁发电机", 500, Faction.BAD, true, false, false);
    public static final Entry SABOTAGE_WIRING = new Entry(
            HabiTrainCore.MOD_ID + ":sabotage_wiring", Kind.TASK, "破坏线路", 50, Faction.BAD, false, false, false);
    /** 临时电源：默认 100 金；onlyAfterDestroy=false，改由停电阶段门控。 */
    public static final Entry TEMP_POWER = new Entry(
            "habitrain_core:temp_power", Kind.TEMP_POWER, "临时电源", 100, Faction.ANY, false, false, false);

    /** 全部条目（顺序即商店显示顺序）。 */
    public static final List<Entry> ALL = List.of(
            REPAIR_WIRING, MAINTAIN_POWER, ADD_COAL, FURNACE_EXPLOSION, SABOTAGE_WIRING, TEMP_POWER
    );

    private BlackoutTaskShopCatalog() {}

    public static Entry findByKey(String key) {
        for (Entry e : ALL) if (e.key().equals(key)) return e;
        return null;
    }
}