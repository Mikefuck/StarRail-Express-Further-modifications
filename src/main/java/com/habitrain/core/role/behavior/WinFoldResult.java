package com.habitrain.core.role.behavior;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.v2.behavior.Decision;
import com.habitrain.core.api.role.v2.behavior.WinPatch;
import com.habitrain.core.api.role.v2.behavior.WinPatchOp;
import org.jetbrains.annotations.Nullable;

/**
 * The two-stage result of the unified victory fold: the {@code allowGameEnd} gate
 * decision plus the folded {@code evaluateWin} patch. Both the standard SRE murder
 * chain and the blackout chain call {@link RoleEventDispatcher#foldWin} and read
 * this — the fold no longer lives in two places.
 */
public record WinFoldResult(Decision gate, WinPatch patch) {

    /** Whether the gate denies the proposed end (pride-style "not yet"). */
    public boolean denied() {
        return gate == Decision.DENY;
    }

    /** Whether the folded patch actually declares/rewrites a winner set. */
    public boolean hasPatch() {
        return patch != null && patch.op() != WinPatchOp.NO_CHANGE;
    }

    /** The folded patch as a {@link WinResult}, or {@code null} when there is no patch. */
    public @Nullable WinResult toWinResult() {
        if (!hasPatch()) {
            return null;
        }
        String reason = patch.reason() != null ? patch.reason()
                : (patch.customId() != null ? patch.customId()
                : (patch.faction() != null ? patch.faction() : "v2 win"));
        return new WinResult(patch.winners(), reason);
    }
}
