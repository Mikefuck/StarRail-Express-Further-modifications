package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityContext;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.capability.VoiceDecision;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Safely published voice view. Contains no player, world, CCA or config references. */
public record VoicePermissionSnapshot(Map<UUID, Participant> players, List<RoleVoicePolicy> policies) {
    public static final VoicePermissionSnapshot EMPTY = new VoicePermissionSnapshot(Map.of(), List.of());
    public VoicePermissionSnapshot {
        players = Map.copyOf(players);
        policies = List.copyOf(policies);
    }
    public record Participant(RoleKey role, UUID group, String dimension, double x, double y, double z) {}

    public VoiceDecision evaluate(UUID speaker, UUID listener, UUID nativeSpeakerGroup, UUID nativeListenerGroup) {
        Participant from = players.get(speaker);
        Participant to = listener == null ? null : players.get(listener);
        // An absent view means JOIN/STOPPING or a failed server-side refresh. Retry next packet.
        if (from == null || (listener != null && to == null)) return VoiceDecision.BLOCK;
        UUID fromGroup = from.group() != null ? from.group() : nativeSpeakerGroup;
        UUID toGroup = to != null && to.group() != null ? to.group() : nativeListenerGroup;
        Double distance = null;
        if (to != null) {
            double dx = from.x() - to.x(), dy = from.y() - to.y(), dz = from.z() - to.z();
            distance = from.dimension().equals(to.dimension()) ? Math.sqrt(dx * dx + dy * dy + dz * dz)
                    : Double.POSITIVE_INFINITY;
        }
        RoleCapabilityContext context = RoleCapabilityContext.of(speaker, from.role(), listener,
                to == null ? null : to.role(), fromGroup, toGroup, distance);
        // A microphone event has no listener; receiver/isolation rules apply at delivery.
        List<RoleVoicePolicy> applicable = listener == null
                ? policies.stream().filter(p -> p.muteSend()).toList() : policies;
        return CapabilityPolicyEvaluator.voice(applicable, context);
    }
}
