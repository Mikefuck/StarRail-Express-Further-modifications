package com.habitrain.core.game.sre;

import com.habitrain.core.api.TaskCategory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 原版 SRE 任务镜像的<b>单一真相表</b>（audit P1-5 的根治项）。
 *
 * <p>此前「哪些任务是原版任务」有三套各自维护的清单，内容还互不相同：
 * <ol>
 *   <li>{@code GenerateTaskMixin.BUILTIN_SRE_TASK_IDS} —— 排除出派发池（20 项，含 6 个从未登记的死条目）；</li>
 *   <li>{@code ModeTasksPage.ORIGINAL_GROUP_TASK_IDS} —— 配置 UI 归组（16 项，含当时自建的 eat/drink）；</li>
 *   <li>{@code ConfigStore.countOriginalTasks} —— 比例展示（曾按 modId 判定，与实际派发口径不符）。</li>
 * </ol>
 * 现已收敛为一张表：<b>镜像表就是「原版任务」的定义</b>，三个使用点全部从
 * {@link #all()} / {@link #ids()} 派生，不再各自维护。
 *
 * <p>镜像只承载<b>配置</b>（启用开关、地图过滤、权重、透视颜色、描边）；
 * 原版任务的派发、加权、完成判定与透视渲染都由上游 SRE 负责，Core 不重做。
 *
 * @param id         任务 ID（同时也是 {@code habitrain_core:<id>} 配置键的后半段）
 * @param displayName 配置页显示名
 * @param category   配置页分类
 * @param weight     兜底权重（配置页可覆盖）
 * @param blockTypeId 上游 {@code GameUtils.taskBlocks} 的类型号，用于颜色/描边覆盖；
 *                    {@code -1} 表示上游无对应任务点方块（无透视）
 */
public record SreTaskMirrors(String id, String displayName, TaskCategory category,
                             float weight, int blockTypeId) {

    /** 上游 {@code GameUtils.taskBlocks} 使用的类型号上限（1–24，见上游 MapScanner）。 */
    public static final int MAX_UPSTREAM_TYPE_ID = 24;

    /**
     * 全部原版任务镜像。<b>新增内置任务时只改这里</b>：
     * 注册、派发池排除、配置页归组与计数会同时跟上。
     */
    private static final List<SreTaskMirrors> MIRRORS = List.of(
            // ── 谋杀模式 / 通用非场景任务 ──
            new SreTaskMirrors("sleep", "睡觉", TaskCategory.MURDER, 1.0f, 4),
            new SreTaskMirrors("exercise", "锻炼", TaskCategory.MURDER, 1.0f, 5),
            new SreTaskMirrors("raed_book", "阅读", TaskCategory.MURDER, 1.0f, 6),
            new SreTaskMirrors("bathe", "洗澡", TaskCategory.MURDER, 1.0f, 3),
            new SreTaskMirrors("toilet", "上厕所", TaskCategory.MURDER, 1.0f, 8),
            new SreTaskMirrors("chair", "坐椅子", TaskCategory.MURDER, 1.0f, 9),
            new SreTaskMirrors("note_block", "音符盒", TaskCategory.MURDER, 1.0f, 10),
            new SreTaskMirrors("meditate", "冥想", TaskCategory.MURDER, 1.0f, -1),
            new SreTaskMirrors("outside", "外出", TaskCategory.MURDER, 1.0f, -1),
            new SreTaskMirrors("breathe", "呼吸新鲜空气", TaskCategory.MURDER, 1.0f, -1),
            new SreTaskMirrors("be_alone", "一个人静静", TaskCategory.MURDER, 1.0f, -1),
            new SreTaskMirrors("vending_machine", "售货机", TaskCategory.ALL, 0.5f, 11),

            // ── 饮食（上游 EAT / DRINK，type 1 = 食物台，type 2 = 饮品台）──
            new SreTaskMirrors("eat", "进食", TaskCategory.ALL, 1.0f, 1),
            new SreTaskMirrors("drink", "喝水", TaskCategory.ALL, 1.0f, 2),

            // ── 修理模式任务（上游按模式派发，Core 无对应方块类型）──
            new SreTaskMirrors("repair_wire", "修复线路", TaskCategory.REPAIR, 1.0f, -1),
            new SreTaskMirrors("repair_panel", "修复面板", TaskCategory.REPAIR, 1.0f, -1),

            // ── 场景任务（是否入池由地图 enabledSceneTasks 决定）──
            new SreTaskMirrors("light_stove", "取暖", TaskCategory.ALL, 1.0f, 16),
            new SreTaskMirrors("clean_dust", "清扫灰尘", TaskCategory.ALL, 1.0f, 17),
            new SreTaskMirrors("transport", "运输", TaskCategory.ALL, 1.0f, 18),
            new SreTaskMirrors("pray", "祷告", TaskCategory.ALL, 1.0f, 20),
            new SreTaskMirrors("prune_bush", "修剪灌木", TaskCategory.ALL, 1.0f, 21),
            new SreTaskMirrors("harvest_crop", "收获作物", TaskCategory.ALL, 1.0f, 22)
    );

    private static final Set<String> IDS;

    static {
        Set<String> ids = new LinkedHashSet<>();
        for (SreTaskMirrors mirror : MIRRORS) {
            ids.add(mirror.id());
        }
        IDS = Set.copyOf(ids);
    }

    /** 镜像表（顺序即注册顺序，也是配置页显示顺序）。 */
    public static List<SreTaskMirrors> all() {
        return MIRRORS;
    }

    /** 镜像任务 ID 集合 —— 派发池排除清单与配置页「原版哈比任务」分组的唯一来源。 */
    public static Set<String> ids() {
        return IDS;
    }
}
