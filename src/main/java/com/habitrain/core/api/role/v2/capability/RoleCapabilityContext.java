package com.habitrain.core.api.role.v2.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Common-side evaluation context for voice / chat policies.
 *
 * <p>No live player objects — tests and dedicated servers can evaluate
 * without a launched game. {@code speakerGroup}/{@code listenerGroup} are
 * opaque isolation keys (e.g. a voicechat group id); {@code null} means the
 * player is in the world channel. {@code distance} is the speaker→listener
 * distance in blocks, or {@code null} when unknown (then distance caps are
 * not applied — audit P1-3: {@code maxDistance} is enforced by the voice
 * adapter, which fills this field).
 */
public record RoleCapabilityContext(
        @Nullable UUID speakerId,
        @Nullable RoleKey speakerRole,
        @Nullable UUID listenerId,
        @Nullable RoleKey listenerRole,
        @Nullable UUID speakerGroup,
        @Nullable UUID listenerGroup,
        @Nullable Double distance) {

    public static RoleCapabilityContext of(
            @Nullable UUID speakerId, @Nullable RoleKey speakerRole,
            @Nullable UUID listenerId, @Nullable RoleKey listenerRole) {
        return new RoleCapabilityContext(speakerId, speakerRole, listenerId, listenerRole, null, null, null);
    }

    public static RoleCapabilityContext of(
            @Nullable UUID speakerId, @Nullable RoleKey speakerRole,
            @Nullable UUID listenerId, @Nullable RoleKey listenerRole,
            @Nullable UUID speakerGroup, @Nullable UUID listenerGroup,
            @Nullable Double distance) {
        return new RoleCapabilityContext(
                speakerId, speakerRole, listenerId, listenerRole, speakerGroup, listenerGroup, distance);
    }

    public RoleCapabilityContext withGroups(@Nullable UUID speakerGroup, @Nullable UUID listenerGroup) {
        return new RoleCapabilityContext(
                speakerId, speakerRole, listenerId, listenerRole, speakerGroup, listenerGroup, distance);
    }

    public RoleCapabilityContext withDistance(@Nullable Double distance) {
        return new RoleCapabilityContext(
                speakerId, speakerRole, listenerId, listenerRole, speakerGroup, listenerGroup, distance);
    }

    /** Whether the pair is within {@code max} blocks; unknown distance always passes. */
    public boolean withinDistance(double max) {
        return distance == null || distance <= max;
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
