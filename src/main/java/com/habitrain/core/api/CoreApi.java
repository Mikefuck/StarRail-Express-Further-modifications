package com.habitrain.core.api;

import com.habitrain.core.api.spi.CoreSpi;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * 机器可读的 API 版本与能力查询。
 *
 * <p>审核 B15：2.0.10 及以前，全仓唯一的 API 版本入口是
 * {@code api.role.v2.RoleExtensionApi#apiVersion()}；{@code match} / {@code menu} /
 * {@code spi} / 投票组与顶层 API 都没有版本或能力查询，下游只能靠「mod 版本号」猜能力
 * （而文档里的版本号本身还在漂移）。现在统一由本类提供。</p>
 *
 * <p>约定：{@link #API_VERSION} 在同一个大版本内只增不减的<b>语义</b>变更才递增；
 * 纯修 bug 不递增。下游应当用它而不是 mod 版本做能力判断。</p>
 *
 * <p><b>注意（审核 D-01）</b>：{@link #modVersion()}（产物版本，每次修复都递增）与
 * {@link #API_VERSION}（公开 API 语义版本）是<b>两个不同概念</b>，不要混用。</p>
 *
 * <h2>下游兼容探针（审核 A-14）</h2>
 * <p>本类由 core 2.0.11 引入。老核心（≤ 2.0.10）里<b>没有</b>这个类，下游若在字段初始化、
 * 静态块或入口点里直接引用 {@code CoreApi}，会得到 {@code NoClassDefFoundError}——
 * 而且往往被 {@code catch (Throwable)} 吞掉，表现为「核心功能莫名其妙消失」。
 * 正确做法是在启动期用一次<b>可失败的类探测</b>判断核心版本，再据此分支：
 *
 * <pre>{@code
 * /** @return true 表示运行中的 core 提供 CoreApi（>= 2.0.11）。 *\/
 * static boolean hasCoreApi() {
 *     try {
 *         Class.forName("com.habitrain.core.api.CoreApi", false,
 *                 MyMod.class.getClassLoader());
 *         return true;
 *     } catch (Throwable missing) {
 *         return false; // 老核心：必须自行走保守分支（fail-closed）
 *     }
 * }
 * }</pre>
 *
 * <p>探测通过之后，仍然不要只依赖 {@link #supports(String)}：该方法是粗粒度分支依据，
 * 安全相关能力必须额外读 {@code CoreSpi} 的装配探针（{@code isMenuGateInstalled()} /
 * {@code isRestAreaInstalled()}），并且<b>每次判定</b>都读，因为装配失败会保持未装配状态。
 */
public final class CoreApi {

    /**
     * 公开 API 语义版本。
     *
     * <ul>
     *   <li>{@code 2.0} — 角色扩展 API v2（{@code api.role.v2}）</li>
     *   <li>{@code 2.1} — 2.0.11 新增：{@code api.spi} 成为唯一实现层桥接入口；
     *       {@code api.scene} 值对象迁入公开层；{@code MatchEvents} 覆盖
     *       {@link GameModeRegistry} 模式；{@code ModeMapVoteSnapshot.phase} 改为枚举</li>
     *   <li>{@code 2.2} — 2.0.12 变更：
     *       <ul>
     *         <li>新增 {@link MatchRestStateApi} + {@code api.spi.RestAreaBridge}
     *             （消除下游对 {@code game.sre} 休息区服务的越层依赖，审核 B-01）</li>
     *         <li>新增 {@link com.habitrain.core.api.role.v2.RoleVisibilityApi} +
     *             {@code api.spi.RoleVisibilityBridge}
     *             （让旋转选角等下游复用核心的隐藏/替换角色过滤，审核 M-01）</li>
     *         <li><b>破坏性</b>：{@code RoleCatalogApi} 的三个快照访问器
     *             （{@code currentSnapshot} / {@code roundSnapshot} /
     *             {@code lastEndedSnapshot}）由 {@code default} 改为<b>抽象</b>，
     *             自定义实现必须显式覆盖（审核 A-04）</li>
     *         <li><b>破坏性</b>：删除 {@code api/MenuGateApi} 废弃转发壳，
     *             改用 {@code api.menu.MenuGateApi}（审核 A-13）</li>
     *         <li>新增 {@link CoreApi#capabilityVersions()}，{@code supports(...)}
     *             开始查询装配探针（审核 A-09）</li>
     *       </ul></li>
     * </ul>
     */
    public static final String API_VERSION = "2.2";

    /** 角色扩展 API 的版本（与 {@code RoleExtensionApi#apiVersion()} 一致）。 */
    public static final String ROLE_API_VERSION = "2.0";

    private CoreApi() {}

    /** 公开 API 语义版本，如 {@code "2.1"}。 */
    public static String apiVersion() {
        return API_VERSION;
    }

    /** 角色扩展 API（{@code api.role.v2}）的版本，如 {@code "2.0"}。 */
    public static String roleApiVersion() {
        return ROLE_API_VERSION;
    }

    /**
     * 运行中的 core 是否提供某项能力。
     *
     * <p>能力键是稳定的短字符串，例如 {@code "scene.instances"}、{@code "match.settlement"}、
     * {@code "role.v2"}、{@code "vote.option"}。未知能力键返回 {@code false}
     * （fail-closed：宁可让调用方走保守分支）。</p>
     *
     * <h2>审核 A-09：能力键必须查询装配状态</h2>
     * <p>2.0.11 及以前本方法是<b>硬编码 12 键 switch</b>：只要类存在就一律返回 {@code true}，
     * 从不查询 {@link com.habitrain.core.api.spi.CoreSpi} 已公开的
     * {@code isSceneFullyInstalled()} / {@code isMenuGateInstalled()} / {@code isVoteInstalled()}
     * 等装配探针。于是核心未装配时 {@code supports("scene.instances")} 仍是 {@code true}，
     * 下游按能力分支走进去只会命中 NOOP 并静默空转——「名下无实」比不支持更糟。
     *
     * <p>现在凡是<b>有装配探针</b>的能力键都组合查询探针（探测失败或未装配 → {@code false}）。
     * 没有装配探针的键（{@code role.v2} / {@code match.*} / {@code task.registry} /
     * {@code gamemode.registry}）是「公开层自身提供的契约」，只要类在就成立。
     *
     * <p><b>下游注意</b>：本方法仍然只是粗粒度分支依据。安全相关能力
     * （{@code menu.gate} / {@code match.rest}）在<b>每次判定</b>时都应直接读
     * {@code CoreSpi.isXxxInstalled()}，因为装配状态可以在运行期变化（装配失败即保持未装配，
     * 见审核 A-01 的修正）。
     */
    public static boolean supports(String capability) {
        if (capability == null) {
            return false;
        }
        return switch (capability) {
            case "role.v2",
                 "role.v2.override",
                 "match.settlement",
                 "match.events",
                 "match.state",
                 "task.registry",
                 "gamemode.registry" -> true;
            case "menu.gate" -> isMenuGateInstalled();
            case "vote.option", "vote.mode_map" -> isVoteInstalled();
            case "scene.motion" -> isSceneMotionInstalled();
            case "scene.instances" -> isSceneInstancesInstalled();
            case "match.rest" -> isRestAreaInstalled();
            case "role.visibility" -> isRoleVisibilityInstalled();
            default -> false;
        };
    }

    /** 全部已知能力键（只读）。 */
    public static java.util.Set<String> capabilities() {
        return CAPABILITIES;
    }

    /**
     * 已知能力键到「当前是否可用」的映射（审核 A-09 新增）。
     *
     * <p>用途：下游在启动期<b>一次性</b>打印/上报真实能力集，把「文档说支持但实际未装配」
     * 这类静默错配变成可观测事实（审核 N-04 建议的可观测点）。
     */
    public static java.util.Map<String, Boolean> capabilityVersions() {
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        for (String key : CAPABILITIES) {
            out.put(key, supports(key));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * 本 core 的 mod 版本（编译期常量注入自 {@code fabric.mod.json} 的 {@code version}）。
     *
     * <p>审核 D-01：{@code mod_version} 与 {@link #API_VERSION} 是两个独立概念——
     * 前者是产物版本（每次修复都递增），后者是公开 API 的语义版本（只在契约变化时递增）。
     * 下游判断能力请用 {@link #apiVersion()} / {@link #supports(String)}，
     * 只有诊断输出才应该打印本值。
     */
    public static String modVersion() {
        return MOD_VERSION;
    }

    /** 由构建期写入的 mod 版本；未注入时为 {@code "unknown"}。 */
    private static final String MOD_VERSION = resolveModVersion();

    private static String resolveModVersion() {
        try {
            ModContainer container = FabricLoader.getInstance().getModContainer("habitrain_core").orElse(null);
            return container == null ? "unknown" : container.getMetadata().getVersion().getFriendlyString();
        } catch (Throwable t) {
            // 单元测试 / 未启动 loader 时不应让版本查询抛异常。
            return "unknown";
        }
    }

    private static final java.util.Set<String> CAPABILITIES = java.util.Set.of(
            "role.v2", "role.v2.override", "role.visibility",
            "match.settlement", "match.events", "match.state", "match.rest",
            "menu.gate",
            "vote.option", "vote.mode_map",
            "scene.motion", "scene.instances",
            "task.registry", "gamemode.registry");

    // ==================== 装配探针（全部 try/catch：见审核 A-18） ====================
    //
    // 约定：api.spi 的桥接查询在「实现缺失 / NOOP」时可能抛出 AbstractMethodError 之类的
    // Throwable，因此每个访问点都必须包 try/catch。本处的能力查询也不例外——
    // 查询失败按「该能力不可用」处理（fail-closed），这与 supports() 的文档一致。

    private static boolean isMenuGateInstalled() {
        try {
            return CoreSpi.isMenuGateInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isVoteInstalled() {
        try {
            return CoreSpi.isVoteInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isSceneInstancesInstalled() {
        try {
            return CoreSpi.isSceneFullyInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isSceneMotionInstalled() {
        try {
            return CoreSpi.isSceneFullyInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isRestAreaInstalled() {
        try {
            return CoreSpi.isRestAreaInstalled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isRoleVisibilityInstalled() {
        try {
            return CoreSpi.isRoleVisibilityInstalled();
        } catch (Throwable t) {
            return false;
        }
    }
}
