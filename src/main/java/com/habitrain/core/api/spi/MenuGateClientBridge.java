package com.habitrain.core.api.spi;

/**
 * 客户端菜单门控桥接接口（SPI）。
 *
 * <p>对应实现 {@code client.menu.MenuAccessGuard}（静态方法，故由一个薄适配器
 * {@code client.menu.MenuGateClientBridgeImpl} 装配）。去掉
 * {@code api.client.menu → client.menu} 反向依赖（审核 A2）。
 *
 * <p><b>仅客户端</b>：实现只在客户端环境装配，专用服务端上本接口永远保持默认值
 * （{@code isScreenAllowed() == true} = 放行），不会触碰任何 {@code net.minecraft.client} 类型。
 *
 * <h2>默认值方向（审核 A-11）</h2>
 * <p>本接口两个默认值方向相反，这是<b>有意</b>的，但不是安全边界：
 * <ul>
 *   <li>{@link #isScreenAllowed()} 默认 {@code true}（fail-open）——「状态未知」时锁住页面
 *       比放行更糟：局域网客人/单人世界会看到一片锁死的配置页，而真正的门禁在服务端；</li>
 *   <li>{@link #isServerDedicated()} 默认 {@code false}（不假设专用服）——旧客户端实现用
 *       {@code Minecraft.getSingleplayerServer() == null} 自行推断，在局域网房主的整合服上
 *       把客人误判为专用服务器并锁屏（审核 B19 / M-07）。</li>
 * </ul>
 * <p><b>因此：客户端门禁只是 UX，不能当作安全边界</b>。任何受门控的写操作都必须在
 * <b>服务端</b>再用 {@code api.menu.MenuGateApi#isBlocked} 判一次，并在桥接未装配时
 * 按 {@code CoreSpi.isMenuGateInstalled()} <b>收紧</b>（审核 N-01）。
 */
public interface MenuGateClientBridge {

    /**
     * 本地玩家是否允许打开并编辑受门控的 Mod 菜单页面。
     * 未装配（专用服务端 / 尚未收到服务端状态）时返回 {@code true}，避免误锁页面。
     * <b>不是安全判定</b>，见类 javadoc。
     */
    default boolean isScreenAllowed() {
        return true;
    }

    /**
     * 服务端是否自报为专用服务器。
     *
     * <p>审核 B19/M-07：客户端旧实现用 {@code Minecraft.getSingleplayerServer() == null}
     * 自行推断「专用服务器」，在局域网房主的整合服上会把客人误判为专用服务器并锁屏，
     * 而服务端用的是 {@code ServerLevel#getServer().isDedicatedServer()}——两者不对称。
     * 现在该标志由服务端随 {@code MenuGatePayload} 下发，客户端不再自行推断。
     */
    default boolean isServerDedicated() {
        return false;
    }
}
