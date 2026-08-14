package com.habitrain.core;

import com.habitrain.core.network.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Registers all custom C2S and S2C payload codecs. */
public final class NetworkRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|NetworkRegistrar");

    private NetworkRegistrar() {}

    public static void init() {
        int count = 0;
        TaskConfigPayload.register(); count++;
        ActiveTaskPayload.register(); count++;
        ConfigUpdatePayload.register(); count++;
        ShaderConfigPayload.register(); count++;
        ShaderInfoPayload.register(); count++;
        BlackoutTimerPayload.register(); count++;
        BlackoutAnnouncePayload.register(); count++;
        BlackoutPhoneOpenPayload.register(); count++;
        BlackoutHirePolicePayload.register(); count++;
        BlackoutHireResultPayload.register(); count++;
        BlackoutVotePayload.register(); count++;
        BlackoutVoteCastPayload.register(); count++;
        CustomTaskBlockPayload.register(); count++;
        FullConfigSyncPayload.register(); count++;
        BlackoutTaskShopOpenPayload.register(); count++;
        BlackoutTaskShopBuyPayload.register(); count++;
        BlackoutTaskShopResultPayload.register(); count++;
        OptionVotePayload.register(); count++;
        OptionVoteCastPayload.register(); count++;
        MapVoteProfilePayload.register(); count++;
        MapVoteLaunchTransitionPayload.register(); count++;
        MapVoteLaunchAbortPayload.register(); count++;
        MapVoteProgressPayload.register(); count++;
        MapVoteStartConfirmedPayload.register(); count++;
        GameEndTransitionPayload.register(); count++;
        GreedTradeActionPayload.register(); count++;
        GreedTradePromptPayload.register(); count++;
        GreedTradeSelectPayload.register(); count++;
        EliminatedRestTogglePayload.register(); count++;
        EliminatedRestPromptPayload.register(); count++;
        MenuGatePayload.register(); count++;
        RepairModeSyncPayload.register(); count++;
        RoleActionC2SPayload.register(); count++;
        RoleActionS2CPayload.register(); count++;
        RoleStateSyncPayload.register(); count++;
        RoleManifestPayload.register(); count++;
        RoleConfigUpdatePayload.register(); count++;
        RoleSnapshotPayload.register(); count++;
        LOGGER.info("Registered {} HabiTrain network payload types", count);
    }
}
