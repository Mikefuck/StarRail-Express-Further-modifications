package com.habitrain.core.role.behavior;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.v2.behavior.Decision;
import com.habitrain.core.api.role.v2.behavior.WinPatch;
import com.habitrain.core.api.role.v2.behavior.WinPatchOp;
import org.jetbrains.annotations.Nullable;

/**
 * Result of the single victory fold used by both the SRE murder
 * {@code AllowGameEnd} listener and the blackout checker.
 *
 * <p>Fold order (G10 P1.4):
 * <ol>
 *   <li>v2 {@code RoleWinHooks.allowGameEnd} — {@link Decision#DENY} blocks the proposed end</li>
 *   <li>v2 {@code RoleWinHooks.evaluateWin}</li>
 *   <li>v1 {@code ModifyRoleDefinition.winConditionHook} / RolePatch win hook,
 *       as overlay input only — never a second Fabric listener. Skipped on
 *       DENY, and never overwrites a v2 patch that already declares winners.</li>
 * </ol>
 *
 * <p>Both chains call {@link RoleEventDispatcher#foldWin}; the v1 overlay is
 * folded here rather than racing {@code first non-NOT_MODIFY wins}.
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

    /**
     * Merges a v1 / RolePatch overlay onto the v2 {@code evaluateWin} accumulator.
     * DENY skips the overlay. A v2 patch that already declares winners is kept.
     */
    public static WinPatch overlayV1(Decision gate, @Nullable WinPatch v2, @Nullable WinPatch v1) {
        WinPatch acc = v2 == null ? WinPatch.noChange() : v2;
        if (gate == Decision.DENY || acc.op() != WinPatchOp.NO_CHANGE) {
            return acc;
        }
        return WinPatch.merge(acc, v1);
    }
}
