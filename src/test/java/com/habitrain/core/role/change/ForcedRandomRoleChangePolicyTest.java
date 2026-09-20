package com.habitrain.core.role.change;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedRandomRoleChangePolicyTest {
    @Test
    void inspectionFailureStillRejectsEvenKnownSpecialState() {
        var result = ForcedRandomRoleChangePolicy.assessSignals(
                new ForcedRandomRoleChangePolicy.RiskSignals(
                        ResourceLocation.fromNamespaceAndPath("noellesroles", "monokuma"),
                        false, false, true, false, false, true, true, List.of("panda_form")));
        assertFalse(result.allowed());
        assertEquals(ForcedRandomRoleChangePolicy.REASON_INSPECTION_FAILED, result.reasonCode());
    }

    @Test
    void coreOwnedRoleIsAllowedEvenWhenComplex() {
        var result = assess("habitrain_core", true, false, true, false, false, false);

        assertTrue(result.allowed());
    }

    @Test
    void plainComponentlessRandomizableUpstreamRoleIsAllowed() {
        var result = assess("starrailexpress", false, false, false, true, true, false);

        assertTrue(result.allowed());
    }

    @Test
    void componentBackedUpstreamRoleIsAllowed() {
        var result = assess("future_roles", false, false, true, true, true, false);

        assertTrue(result.allowed());
    }

    @Test
    void nonRandomizableUpstreamRoleIsAllowed() {
        var result = assess("future_roles", false, false, false, false, true, false);

        assertTrue(result.allowed());
    }

    @Test
    void customUpstreamRoleImplementationIsAllowed() {
        var result = assess("future_roles", false, false, false, true, false, false);

        assertTrue(result.allowed());
    }

    @Test
    void auditedRoleCanOptInAfterLifecycleReview() {
        var result = assess("reviewed_roles", false, true, true, false, false, false);

        assertTrue(result.allowed());
    }

    @Test
    void knownMonokumaLifecycleIsAllowedWithCommitCleanup() {
        var result = assess("habitrain_core", true, true, false, true, true, true);

        assertTrue(result.allowed());

    }

    private static ForcedRandomRoleChangePolicy.Assessment assess(
            String namespace,
            boolean coreOwned,
            boolean audited,
            boolean componentBacked,
            boolean randomizable,
            boolean plainNormalRole,
            boolean unsafeLifecycle) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, "test_role");
        return ForcedRandomRoleChangePolicy.assessSignals(
                new ForcedRandomRoleChangePolicy.RiskSignals(
                        id,
                        coreOwned,
                        audited,
                        componentBacked,
                        randomizable,
                        plainNormalRole,
                        unsafeLifecycle,
                        false,
                        unsafeLifecycle ? List.of("black_white_modifier") : List.of()));
    }
}
