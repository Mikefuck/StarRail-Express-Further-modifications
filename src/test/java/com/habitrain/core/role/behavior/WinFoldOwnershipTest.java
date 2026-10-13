package com.habitrain.core.role.behavior;

import com.habitrain.core.api.role.v2.behavior.Decision;
import com.habitrain.core.api.role.v2.behavior.WinPatch;
import com.habitrain.core.api.role.v2.behavior.WinPatchOp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure merge policy for G10 P1.4: declarative / RolePatch overlay never races hooks,
 * never applies on DENY, and never overwrites a hooks winner declaration.
 */
class WinFoldOwnershipTest {

    private static final UUID HOOKS = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID DECLARATIVE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    void denySkipsDECLARATIVEOverlayEvenWhenHOOKSIsSilent() {
        WinPatch declarative = WinPatch.declareCustom("habitrain_core:declarative_role", List.of(DECLARATIVE), "declarative hijack");
        WinPatch folded = WinFoldResult.overlayPatch(Decision.DENY, WinPatch.noChange(), declarative);
        assertEquals(WinPatchOp.NO_CHANGE, folded.op());
        assertTrue(folded.winners().isEmpty());
    }

    @Test
    void denyKeepsHOOKSPatchAndStillIgnoresDECLARATIVE() {
        WinPatch hooks = WinPatch.declareCustom("habitrain_core:hooks_role", List.of(HOOKS), "hooks win");
        WinPatch declarative = WinPatch.declareCustom("habitrain_core:declarative_role", List.of(DECLARATIVE), "declarative hijack");
        WinPatch folded = WinFoldResult.overlayPatch(Decision.DENY, hooks, declarative);
        assertSame(hooks, folded);
        assertEquals(List.of(HOOKS), folded.winners());
    }

    @Test
    void declarativeAppliesWhenGatePassesAndHOOKSIsSilent() {
        WinPatch declarative = WinPatch.declareCustom("habitrain_core:declarative_role", List.of(DECLARATIVE), "declarative hijack");
        WinPatch folded = WinFoldResult.overlayPatch(Decision.PASS, WinPatch.noChange(), declarative);
        assertEquals(WinPatchOp.DECLARE_CUSTOM, folded.op());
        assertEquals("habitrain_core:declarative_role", folded.customId());
        assertEquals(List.of(DECLARATIVE), folded.winners());
        assertEquals("declarative hijack", folded.reason());
    }

    @Test
    void hooksDeclareCustomBeatsDECLARATIVE() {
        WinPatch hooks = WinPatch.declareCustom("habitrain_core:hooks_role", List.of(HOOKS), "hooks win");
        WinPatch declarative = WinPatch.declareCustom("habitrain_core:declarative_role", List.of(DECLARATIVE), "declarative hijack");
        WinPatch folded = WinFoldResult.overlayPatch(Decision.PASS, hooks, declarative);
        assertSame(hooks, folded);
        assertEquals(List.of(HOOKS), folded.winners());
    }

    @Test
    void hooksAddWinnersBeatsDECLARATIVEDeclare() {
        WinPatch hooks = WinPatch.addWinners(HOOKS);
        WinPatch declarative = WinPatch.declareCustom("habitrain_core:declarative_role", List.of(DECLARATIVE), "declarative hijack");
        WinPatch folded = WinFoldResult.overlayPatch(Decision.ALLOW, hooks, declarative);
        assertSame(hooks, folded);
        assertEquals(WinPatchOp.ADD_WINNER, folded.op());
        assertEquals(List.of(HOOKS), folded.winners());
    }

    @Test
    void silentDECLARATIVELeavesHOOKSNoChange() {
        WinPatch folded = WinFoldResult.overlayPatch(
                Decision.PASS, WinPatch.noChange(), WinPatch.noChange());
        assertEquals(WinPatchOp.NO_CHANGE, folded.op());
        WinFoldResult result = new WinFoldResult(Decision.PASS, folded);
        assertFalse(result.denied());
        assertFalse(result.hasPatch());
    }

    @Test
    void nullHOOKSTreatedAsNoChange() {
        WinPatch declarative = WinPatch.declareCustom("habitrain_core:declarative_role", List.of(DECLARATIVE), "declarative hijack");
        WinPatch folded = WinFoldResult.overlayPatch(Decision.PASS, null, declarative);
        assertEquals(WinPatchOp.DECLARE_CUSTOM, folded.op());
        assertEquals(List.of(DECLARATIVE), folded.winners());
    }
}
