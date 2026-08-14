package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.capability.ChatDecision;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityContext;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityStatus;
import com.habitrain.core.api.role.v2.capability.RoleChatPolicy;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.capability.VoiceDecision;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link RoleCapabilityApi}. Policies are common and freeze-gated;
 * adapters only flip a status flag so voicechat types never enter this class.
 */
public final class RoleCapabilityServiceImpl implements RoleCapabilityApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleCapabilityApi");

    private final Map<ResourceLocation, RoleVoicePolicy> voices = new LinkedHashMap<>();
    private final Map<ResourceLocation, RoleChatPolicy> chats = new LinkedHashMap<>();
    private final Map<RoleCapabilityKey, RoleCapabilityStatus> adapters = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> groups = new ConcurrentHashMap<>();
    private volatile boolean frozen;

    public RoleCapabilityServiceImpl() {}

    @Override
    public synchronized RoleVoicePolicy voice(RoleVoicePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        rejectIfFrozen();
        if (voices.putIfAbsent(policy.id(), policy) != null) {
            throw new IllegalArgumentException("Duplicate voice policy: " + policy.id());
        }
        LOGGER.info("Registered voice policy {} for {}", policy.id(), policy.role());
        return policy;
    }

    @Override
    public synchronized RoleChatPolicy chat(RoleChatPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        rejectIfFrozen();
        if (chats.putIfAbsent(policy.id(), policy) != null) {
            throw new IllegalArgumentException("Duplicate chat policy: " + policy.id());
        }
        LOGGER.info("Registered chat policy {} for {}", policy.id(), policy.role());
        return policy;
    }

    @Override
    public Collection<RoleVoicePolicy> voices() {
        return Collections.unmodifiableCollection(voices.values());
    }

    @Override
    public List<RoleVoicePolicy> voicesFor(RoleKey role) {
        return filterByRole(voices.values(), role, RoleVoicePolicy::role);
    }

    @Override
    public Collection<RoleChatPolicy> chats() {
        return Collections.unmodifiableCollection(chats.values());
    }

    @Override
    public List<RoleChatPolicy> chatsFor(RoleKey role) {
        return filterByRole(chats.values(), role, RoleChatPolicy::role);
    }

    @Override
    public void bindAdapter(RoleCapabilityKey key, RoleCapabilityStatus status) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        adapters.put(key, status);
        LOGGER.info("Capability {} -> {}", key, status);
    }

    @Override
    public RoleCapabilityStatus status(RoleCapabilityKey key) {
        if (key == null) {
            return RoleCapabilityStatus.UNAVAILABLE;
        }
        return adapters.getOrDefault(key, RoleCapabilityStatus.UNAVAILABLE);
    }

    @Override
    public VoiceDecision evaluateVoice(RoleCapabilityContext ctx) {
        return CapabilityPolicyEvaluator.voice(voices.values(), withStoredGroups(ctx));
    }

    @Override
    public ChatDecision evaluateChat(RoleCapabilityContext ctx) {
        return CapabilityPolicyEvaluator.chat(chats.values(), withStoredGroups(ctx));
    }

    @Override
    public void setGroup(@Nullable UUID playerId, @Nullable UUID groupId) {
        if (playerId == null) {
            return;
        }
        if (groupId == null) {
            groups.remove(playerId);
        } else {
            groups.put(playerId, groupId);
        }
    }

    @Override
    public @Nullable UUID groupOf(@Nullable UUID playerId) {
        return playerId == null ? null : groups.get(playerId);
    }

    @Override
    public synchronized void freeze() {
        this.frozen = true;
    }

    @Override
    public boolean isFrozen() {
        return frozen;
    }

    /** Snapshot/restore seam for provider-scoped registration transactions. */
    public synchronized RegistrationSnapshot snapshotForTransaction() {
        return new RegistrationSnapshot(
                new LinkedHashMap<>(voices),
                new LinkedHashMap<>(chats),
                frozen);
    }

    /** Restores declarative policies without disturbing runtime adapter/group state. */
    public synchronized void restoreTransactionSnapshot(RegistrationSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        voices.clear();
        voices.putAll(snapshot.voices());
        chats.clear();
        chats.putAll(snapshot.chats());
        frozen = snapshot.frozen();
    }

    public synchronized void clear() {
        voices.clear();
        chats.clear();
        adapters.clear();
        groups.clear();
        frozen = false;
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("voice=" + status(RoleCapabilityKey.VOICE)
                + " policies=" + voices.size());
        lines.add("chat=" + status(RoleCapabilityKey.CHAT)
                + " policies=" + chats.size());
        for (RoleVoicePolicy policy : voices.values()) {
            lines.add("  voice " + policy.id() + " role=" + policy.role()
                    + (policy.muteSend() ? " muteSend" : "")
                    + (policy.muteReceive() ? " muteReceive" : "")
                    + (policy.isolateGroup() ? " isolate" : ""));
        }
        for (RoleChatPolicy policy : chats.values()) {
            lines.add("  chat " + policy.id() + " role=" + policy.role()
                    + (policy.muteSend() ? " muteSend" : "")
                    + (policy.muteReceive() ? " muteReceive" : ""));
        }
        return lines;
    }

    private void rejectIfFrozen() {
        if (frozen) {
            throw new IllegalStateException("Role capability registry is frozen");
        }
    }

    private RoleCapabilityContext withStoredGroups(RoleCapabilityContext ctx) {
        if (ctx == null) {
            return null;
        }
        UUID speakerGroup = ctx.speakerGroup() != null ? ctx.speakerGroup() : groupOf(ctx.speakerId());
        UUID listenerGroup = ctx.listenerGroup() != null ? ctx.listenerGroup() : groupOf(ctx.listenerId());
        if (speakerGroup == ctx.speakerGroup() && listenerGroup == ctx.listenerGroup()) {
            return ctx;
        }
        return ctx.withGroups(speakerGroup, listenerGroup);
    }

    private static <T> List<T> filterByRole(Collection<T> values, RoleKey role,
                                            java.util.function.Function<T, RoleKey> roleOf) {
        if (role == null) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        for (T value : values) {
            if (role.equals(roleOf.apply(value))) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    /** Public rollback token used only while provider declarations are loading. */
    public record RegistrationSnapshot(
            Map<ResourceLocation, RoleVoicePolicy> voices,
            Map<ResourceLocation, RoleChatPolicy> chats,
            boolean frozen) {}
}
