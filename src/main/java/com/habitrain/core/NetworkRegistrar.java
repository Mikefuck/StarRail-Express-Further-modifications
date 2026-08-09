package com.habitrain.core;

import com.habitrain.core.network.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers all custom C2S and S2C payload codecs. */
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
        OptionVotePayload.register();
        OptionVoteCastPayload.register();
        MapVoteLaunchTransitionPayload.register();
        MapVoteLaunchAbortPayload.register();
        MapVoteProgressPayload.register();
        GameEndTransitionPayload.register();
        GreedTradeActionPayload.register();
        GreedTradePromptPayload.register();
        GreedTradeSelectPayload.register();
        EliminatedRestTogglePayload.register();
        EliminatedRestPromptPayload.register();
        MenuGatePayload.register();
        RepairModeSyncPayload.register();
        LOGGER.info("Registered {} HabiTrain network payload types", 30);
    }
}
