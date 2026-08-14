package com.habitrain.core.api.role.v2.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Common-side evaluation context for voice / chat policies.
 *
 * <p>No live player objects — tests and dedicated servers can evaluate
 * without a launched game. {@code groupId} is an opaque isolation key
 * (e.g. a swallowed-by owner); {@code null} means the player is in the
 * world channel.
 */
public record RoleCapabilityContext(
        @Nullable UUID speakerId,
        @Nullable RoleKey speakerRole,
        @Nullable UUID listenerId,
        @Nullable RoleKey listenerRole,
        @Nullable UUID speakerGroup,
        @Nullable UUID listenerGroup) {

    public static RoleCapabilityContext of(
            @Nullable UUID speakerId, @Nullable RoleKey speakerRole,
            @Nullable UUID listenerId, @Nullable RoleKey listenerRole) {
        return new RoleCapabilityContext(speakerId, speakerRole, listenerId, listenerRole, null, null);
    }

    public RoleCapabilityContext withGroups(@Nullable UUID speakerGroup, @Nullable UUID listenerGroup) {
        return new RoleCapabilityContext(
                speakerId, speakerRole, listenerId, listenerRole, speakerGroup, listenerGroup);
    }

    public boolean sameGroup() {
        return speakerGroup != null && speakerGroup.equals(listenerGroup);
    }

    public boolean speakerIs(RoleKey role) {
        return role != null && role.equals(speakerRole);
    }

    public boolean listenerIs(RoleKey role) {
        return role != null && role.equals(listenerRole);
    }

    public boolean samePlayer() {
        return speakerId != null && speakerId.equals(listenerId);
    }
}
