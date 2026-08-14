package com.habitrain.core.role.client;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.InstinctDecision;
import com.habitrain.core.api.role.v2.client.InstinctPhase;
import com.habitrain.core.api.role.v2.client.RoleInstinctRule;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Pure instinct-rule fold. First matching non-pass rule wins.
 */
public final class InstinctRuleResolver {

    private InstinctRuleResolver() {}

    public static InstinctDecision resolve(List<RoleInstinctRule> rules,
                                          InstinctPhase phase,
                                          @Nullable RoleKey viewerRole,
                                          @Nullable RoleKey targetRole) {
        if (rules == null || rules.isEmpty() || viewerRole == null) {
            return InstinctDecision.pass();
        }
        for (RoleInstinctRule rule : rules) {
            if (rule.phase() != phase) {
                continue;
            }
            if (!viewerRole.equals(rule.viewerRole())) {
                continue;
            }
            if (rule.targetRole() != null && !rule.targetRole().equals(targetRole)) {
                continue;
            }
            if (rule.hide()) {
                return InstinctDecision.hide();
            }
            if (rule.color() != null) {
                return InstinctDecision.custom(rule.color());
            }
        }
        return InstinctDecision.pass();
    }
}
