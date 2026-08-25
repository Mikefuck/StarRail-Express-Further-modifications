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
 * Pure merge policy for G10 P1.4: v1 / RolePatch overlay never races v2,
 * never applies on DENY, and never overwrites a v2 winner declaration.
 */
class WinFoldOwnershipTest {

    private static final UUID V2 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID V1 = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    void denySkipsV1OverlayEvenWhenV2IsSilent() {
        WinPatch v1 = WinPatch.declareCustom("habitrain_core:v1_role", List.of(V1), "v1 hijack");
        WinPatch folded = WinFoldResult.overlayV1(Decision.DENY, WinPatch.noChange(), v1);
        assertEquals(WinPatchOp.NO_CHANGE, folded.op());
        assertTrue(folded.winners().isEmpty());
    }

    @Test
    void denyKeepsV2PatchAndStillIgnoresV1() {
        WinPatch v2 = WinPatch.declareCustom("habitrain_core:v2_role", List.of(V2), "v2 win");
        WinPatch v1 = WinPatch.declareCustom("habitrain_core:v1_role", List.of(V1), "v1 hijack");
        WinPatch folded = WinFoldResult.overlayV1(Decision.DENY, v2, v1);
        assertSame(v2, folded);
        assertEquals(List.of(V2), folded.winners());
    }

    @Test
    void v1AppliesWhenGatePassesAndV2IsSilent() {
        WinPatch v1 = WinPatch.declareCustom("habitrain_core:v1_role", List.of(V1), "v1 hijack");
        WinPatch folded = WinFoldResult.overlayV1(Decision.PASS, WinPatch.noChange(), v1);
        assertEquals(WinPatchOp.DECLARE_CUSTOM, folded.op());
        assertEquals("habitrain_core:v1_role", folded.customId());
        assertEquals(List.of(V1), folded.winners());
        assertEquals("v1 hijack", folded.reason());
    }

    @Test
    void v2DeclareCustomBeatsV1() {
        WinPatch v2 = WinPatch.declareCustom("habitrain_core:v2_role", List.of(V2), "v2 win");
        WinPatch v1 = WinPatch.declareCustom("habitrain_core:v1_role", List.of(V1), "v1 hijack");
        WinPatch folded = WinFoldResult.overlayV1(Decision.PASS, v2, v1);
        assertSame(v2, folded);
        assertEquals(List.of(V2), folded.winners());
    }

    @Test
    void v2AddWinnersBeatsV1Declare() {
        WinPatch v2 = WinPatch.addWinners(V2);
        WinPatch v1 = WinPatch.declareCustom("habitrain_core:v1_role", List.of(V1), "v1 hijack");
        WinPatch folded = WinFoldResult.overlayV1(Decision.ALLOW, v2, v1);
        assertSame(v2, folded);
        assertEquals(WinPatchOp.ADD_WINNER, folded.op());
        assertEquals(List.of(V2), folded.winners());
    }

    @Test
    void silentV1LeavesV2NoChange() {
        WinPatch folded = WinFoldResult.overlayV1(
                Decision.PASS, WinPatch.noChange(), WinPatch.noChange());
        assertEquals(WinPatchOp.NO_CHANGE, folded.op());
        WinFoldResult result = new WinFoldResult(Decision.PASS, folded);
        assertFalse(result.denied());
        assertFalse(result.hasPatch());
    }

    @Test
    void nullV2TreatedAsNoChange() {
        WinPatch v1 = WinPatch.declareCustom("habitrain_core:v1_role", List.of(V1), "v1 hijack");
        WinPatch folded = WinFoldResult.overlayV1(Decision.PASS, null, v1);
        assertEquals(WinPatchOp.DECLARE_CUSTOM, folded.op());
        assertEquals(List.of(V1), folded.winners());
    }
}
