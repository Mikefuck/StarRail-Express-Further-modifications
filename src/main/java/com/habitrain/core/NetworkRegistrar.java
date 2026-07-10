package com.habitrain.core;

import com.habitrain.core.network.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网络包注册器 — 负责注册所有 C2S 与 S2C 的自定义数据包类型。
 * <p>在 {@link HabiTrainCore#onInitialize()} 中调用 {@link #init()}。</p>
 */
public final class NetworkRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|NetworkRegistrar");

    private NetworkRegistrar() {}

    public static void init() {
        TaskConfigPayload.register();
        ActiveTaskPayload.register();
        ConfigUpdatePayload.register();
        ShaderConfigPayload.register();
        ShaderInfoPayload.register();
        BlackoutTimerPayload.register();
        BlackoutAnnouncePayload.register();
        BlackoutSheriffVotePayload.register();       // S2C: 投票状态同步
        BlackoutSheriffVoteCastPayload.register();   // C2S: 玩家投票
        BlackoutPhoneOpenPayload.register();
        BlackoutHirePolicePayload.register();
        BlackoutHireResultPayload.register();
        BlackoutVotePayload.register();
        BlackoutVoteCastPayload.register();
        CustomTaskBlockPayload.register();
        FullConfigSyncPayload.register();
        BlackoutTaskShopOpenPayload.register();
        BlackoutTaskShopBuyPayload.register();
        BlackoutTaskShopResultPayload.register();
        // 注：字幕报幕包 starrailexpress:subtitle 由 SRE 4.3.0 原生注册（SREPayloadRegister），
        //     本模组不再重复注册；客户端接收、HUD tick/render 也由 SRE 接管。
        LOGGER.info("已注册 19 个网络数据包类型");
    }
}
