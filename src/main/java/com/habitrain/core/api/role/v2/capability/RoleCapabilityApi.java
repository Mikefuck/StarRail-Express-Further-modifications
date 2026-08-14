package com.habitrain.core.api.role.v2.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Public capability service (design §16.4 / §18.4).
 *
 * <p>Providers register declarative voice / chat policies. Core
 * never loads a third-party class from this API: adapters bind themselves
 * via {@link #bindAdapter} from an optional entrypoint (e.g. voicechat).
 * Missing adapters stay {@link RoleCapabilityStatus#UNAVAILABLE}; policies
 * remain evaluable so tests and dedicated servers do not need the mod.
 */
public interface RoleCapabilityApi {

    static RoleCapabilityApi instance() {
        return DefaultHolder.INSTANCE;
    }

    final class DefaultHolder {
        private DefaultHolder() {}

        static final RoleCapabilityApi INSTANCE =
                new com.habitrain.core.role.capability.RoleCapabilityServiceImpl();
    }

    RoleVoicePolicy voice(RoleVoicePolicy policy);

    RoleChatPolicy chat(RoleChatPolicy policy);

    Collection<RoleVoicePolicy> voices();

    List<RoleVoicePolicy> voicesFor(RoleKey role);

    Collection<RoleChatPolicy> chats();

    List<RoleChatPolicy> chatsFor(RoleKey role);

    /**
     * Marks a built-in or provider capability as available this process.
     * Adapters call this from their own entrypoint so the common class
     * loader never sees the optional dependency.
     */
    void bindAdapter(RoleCapabilityKey key, RoleCapabilityStatus status);

    RoleCapabilityStatus status(RoleCapabilityKey key);

    default boolean supports(RoleCapabilityKey key) {
        return status(key) == RoleCapabilityStatus.AVAILABLE
                || status(key) == RoleCapabilityStatus.DEGRADED;
    }

    VoiceDecision evaluateVoice(RoleCapabilityContext ctx);

    ChatDecision evaluateChat(RoleCapabilityContext ctx);

    /**
     * Records the isolation group a player currently belongs to (e.g. a
     * swallowed-by owner). {@code null} group clears membership.
     */
    void setGroup(@Nullable UUID playerId, @Nullable UUID groupId);

    @Nullable UUID groupOf(@Nullable UUID playerId);

    void freeze();

    boolean isFrozen();
}
