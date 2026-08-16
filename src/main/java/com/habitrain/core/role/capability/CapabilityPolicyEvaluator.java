package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.capability.ChatDecision;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityContext;
import com.habitrain.core.api.role.v2.capability.RoleChatPolicy;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.capability.VoiceDecision;

import java.util.Collection;

/**
 * Pure fold of registered policies onto a speaker/listener pair.
 *
 * <p>No Fabric / voicechat types — unit-testable without a launched game.
 * First matching mute wins; later policies cannot reopen a blocked channel.
 */
public final class CapabilityPolicyEvaluator {

    private CapabilityPolicyEvaluator() {}

    public static VoiceDecision voice(Collection<RoleVoicePolicy> policies, RoleCapabilityContext ctx) {
        if (policies == null || policies.isEmpty() || ctx == null) {
            return VoiceDecision.PASS;
        }
        for (RoleVoicePolicy policy : policies) {
            if (policy == null) {
                continue;
            }
            if (applyVoice(policy, ctx) == VoiceDecision.BLOCK) {
                return VoiceDecision.BLOCK;
            }
        }
        return VoiceDecision.PASS;
    }

    public static ChatDecision chat(Collection<RoleChatPolicy> policies, RoleCapabilityContext ctx) {
        if (policies == null || policies.isEmpty() || ctx == null) {
            return ChatDecision.PASS;
        }
        for (RoleChatPolicy policy : policies) {
            if (policy == null) {
                continue;
            }
            if (applyChat(policy, ctx) == ChatDecision.BLOCK) {
                return ChatDecision.BLOCK;
            }
        }
        return ChatDecision.PASS;
    }

    private static VoiceDecision applyVoice(RoleVoicePolicy policy, RoleCapabilityContext ctx) {
        RoleKey role = policy.role();
        boolean speakerMatch = ctx.speakerIs(role);
        boolean listenerMatch = ctx.listenerIs(role);
        if (!speakerMatch && !listenerMatch) {
            return VoiceDecision.PASS;
        }
        if (speakerMatch && policy.muteSend()) {
            return VoiceDecision.BLOCK;
        }
        if (listenerMatch && policy.muteReceive()) {
            return VoiceDecision.BLOCK;
        }
        // Audit P1-3: maxDistance is enforced when the adapter supplies the
        // speaker→listener distance (unknown distance passes).
        if ((speakerMatch || listenerMatch) && policy.maxDistance() > 0
                && !ctx.withinDistance(policy.maxDistance())) {
            return VoiceDecision.BLOCK;
        }
        if (listenerMatch && policy.isolateGroup() && !policy.hearWorld()) {
            if (ctx.sameGroup() || ctx.samePlayer()) {
                return VoiceDecision.PASS;
            }
            return VoiceDecision.BLOCK;
        }
        return VoiceDecision.PASS;
    }

    private static ChatDecision applyChat(RoleChatPolicy policy, RoleCapabilityContext ctx) {
        RoleKey role = policy.role();
        boolean speakerMatch = ctx.speakerIs(role);
        boolean listenerMatch = ctx.listenerIs(role);
        if (!speakerMatch && !listenerMatch) {
            return ChatDecision.PASS;
        }
        if (speakerMatch && policy.muteSend()) {
            return ChatDecision.BLOCK;
        }
        if (listenerMatch && policy.muteReceive()) {
            return ChatDecision.BLOCK;
        }
        return ChatDecision.PASS;
    }
}