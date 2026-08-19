package com.habitrain.core.role.change;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForcedRandomRoleChangePolicyTest {

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
    void componentBackedUpstreamRoleIsDenied() {
        var result = assess("future_roles", false, false, true, true, true, false);

        assertFalse(result.allowed());
        assertEquals(ForcedRandomRoleChangePolicy.REASON_UNAUDITED_UPSTREAM_STATE,
                result.reasonCode());
        assertTrue(result.riskSignals().contains("component_backed"));
    }

    @Test
    void nonRandomizableUpstreamRoleIsDenied() {
        var result = assess("future_roles", false, false, false, false, true, false);

        assertFalse(result.allowed());
        assertTrue(result.riskSignals().contains("not_randomizable_by_other_roles"));
    }

    @Test
    void customUpstreamRoleImplementationIsDenied() {
        var result = assess("future_roles", false, false, false, true, false, false);

        assertFalse(result.allowed());
        assertTrue(result.riskSignals().contains("custom_role_implementation"));
    }

    @Test
    void auditedRoleCanOptInAfterLifecycleReview() {
        var result = assess("reviewed_roles", false, true, true, false, false, false);

        assertTrue(result.allowed());
    }

    @Test
    void knownMonokumaLifecycleAlwaysWinsOverAllowRules() {
        var result = assess("habitrain_core", true, true, false, true, true, true);

        assertFalse(result.allowed());
        assertEquals(ForcedRandomRoleChangePolicy.REASON_MONOKUMA_LIFECYCLE,
                result.reasonCode());
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
