package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 {@link RoleCapabilityKey#VOICE} 绑定为 AVAILABLE 的两方握手。
 *
 * <p>Fabric 不保证 {@code main} 与 {@code voicechat} 入口的先后：若 voicechat 先初始化，
 * {@link RoleVoiceCapabilityPlugin#initialize} 执行时 core 尚未装配 {@code RoleSpi.capability}，
 * 直接访问 {@link RoleCapabilityApi#instance()} 会让其静态持有类初始化失败并永久损坏，
 * 进而拖垮 core 的 main 入口。因此改为：插件只登记"需要绑定"，core 装配完 SPI 后标记就绪，
 * 两者中后到的一方执行绑定，且只绑定一次。</p>
 *
 * <p>本类不引用任何 voicechat 类，core 主入口在未安装 voicechat 时也可安全加载它。</p>
 */
public final class RoleVoiceAdapterBinding {
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleVoiceCapability");
    private static final Runnable DEFAULT_BINDER = () -> RoleCapabilityApi.instance().bindAdapter(
            RoleCapabilityKey.VOICE, RoleCapabilityStatus.AVAILABLE);

    private static boolean coreReady;
    private static boolean requested;
    private static boolean bound;
    private static Runnable binder = DEFAULT_BINDER;

    private RoleVoiceAdapterBinding() {
    }

    /** core 装配完角色 SPI 后调用。 */
    public static synchronized void markCoreReady() {
        coreReady = true;
        bindIfPossible();
    }

    /** Simple Voice Chat 插件初始化时调用（可能早于 core）。 */
    public static synchronized void requestFromVoicechat() {
        requested = true;
        if (!coreReady) {
            LOGGER.info("Simple Voice Chat initialised before habitrain_core; deferring voice adapter binding");
        }
        bindIfPossible();
    }

    private static void bindIfPossible() {
        if (!coreReady || !requested || bound) {
            return;
        }
        bound = true;
        binder.run();
        LOGGER.info("Simple Voice Chat adapter bound");
    }

    /** 仅测试用：替换绑定动作并复位状态。 */
    static synchronized void resetForTest(Runnable testBinder) {
        coreReady = false;
        requested = false;
        bound = false;
        binder = testBinder == null ? DEFAULT_BINDER : testBinder;
    }
}
