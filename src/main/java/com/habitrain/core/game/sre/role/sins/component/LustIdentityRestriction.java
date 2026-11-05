package com.habitrain.core.game.sre.role.sins.component;

/** Separate contribution to DeathPenaltyComponent; never owns its camera, position or timer. */
public interface LustIdentityRestriction {
    String SYNC_KEY = "habitrain_core:lust_identity_hidden";

    boolean habitrain$isLustIdentityHidden();

    void habitrain$setLustIdentityHidden(boolean hidden);
}
