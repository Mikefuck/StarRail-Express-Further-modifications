package com.habitrain.core.role.change;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.agmas.noellesroles.game.roles.neutral.monokuma.MonokumaPlayerComponent;
import org.agmas.noellesroles.game.roles.neutral.panda.PandaComponent;
import org.jetbrains.annotations.Nullable;
import pro.fazeclan.river.stupid_express.constants.SEModifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fail-closed safety policy for forced random role rewrites.
 *
 * <p>Upstream role removal only invokes the role component's {@code clear()}.
 * It cannot prove that arbitrary effects, secondary components or global state
 * owned by a future role were released. Core-owned roles use the managed v2
 * lifecycle; unknown upstream roles are therefore accepted only when they are
 * plain, componentless, randomizable {@link NormalRole} instances or have been
 * explicitly audited.
 */
public final class ForcedRandomRoleChangePolicy {
    public static final String REASON_SAFE = "safe";
    public static final String REASON_NO_CURRENT_ROLE = "no_current_role";
    public static final String REASON_INSPECTION_FAILED = "inspection_failed";
    public static final String REASON_MONOKUMA_LIFECYCLE = "monokuma_lifecycle";
    public static final String REASON_UNAUDITED_UPSTREAM_STATE = "unaudited_upstream_state";

    private static final ResourceLocation MONOKUMA_ROLE_ID =
            ResourceLocation.fromNamespaceAndPath("noellesroles", "monokuma");
    private static final String CORE_NAMESPACE = HabiTrainCore.MOD_ID;

    /** Add IDs here only after their complete removal lifecycle has been reviewed. */
    private static final Set<ResourceLocation> AUDITED_SAFE_ROLE_IDS = ConcurrentHashMap.newKeySet();

    private ForcedRandomRoleChangePolicy() {}

    /** Assesses the player's live outgoing role and all known cross-role state. */
    public static Assessment assess(ServerPlayer player) {
        if (player == null) {
            return Assessment.denied(REASON_NO_CURRENT_ROLE, null, List.of("missing_player"));
        }
        SRERole current = null;
        try {
            SREGameWorldComponent game = SREGameWorldComponent.KEY.get(player.level());
            current = game == null ? null : game.getRole(player);
        } catch (Throwable t) {
            return Assessment.denied(REASON_INSPECTION_FAILED, null,
                    List.of("current_role_lookup_failed"));
        }
        return assess(player, current);
    }

    /** Assesses a known outgoing role while still checking player-bound runtime state. */
    public static Assessment assess(ServerPlayer player, @Nullable SRERole currentRole) {
        if (currentRole == null || currentRole.identifier() == null) {
            return Assessment.denied(REASON_NO_CURRENT_ROLE, null, List.of("missing_current_role"));
        }

        ResourceLocation roleId = currentRole.identifier();
        List<String> lifecycleSignals = new ArrayList<>();
        boolean inspectionFailed = false;

        if (MONOKUMA_ROLE_ID.equals(roleId)) {
            lifecycleSignals.add("monokuma_role");
        }

        if (player != null) {
            try {
                WorldModifierComponent modifiers = WorldModifierComponent.KEY.get(player.level());
                if (modifiers != null && SEModifiers.BLACK_WHITE != null
                        && modifiers.isModifier(player, SEModifiers.BLACK_WHITE)) {
                    lifecycleSignals.add("black_white_modifier");
                }
            } catch (Throwable t) {
                inspectionFailed = true;
            }
            try {
                MonokumaPlayerComponent component = MonokumaPlayerComponent.KEY.maybeGet(player).orElse(null);
                if (component != null && component.phase != 0) {
                    lifecycleSignals.add("monokuma_phase_" + component.phase);
                }
            } catch (Throwable t) {
                inspectionFailed = true;
            }
            try {
                PandaComponent panda = PandaComponent.KEY.maybeGet(player).orElse(null);
                if (panda != null && panda.isPanda) {
                    lifecycleSignals.add("panda_form");
                }
            } catch (Throwable t) {
                inspectionFailed = true;
            }
        }

        return assessSignals(new RiskSignals(
                roleId,
                CORE_NAMESPACE.equals(roleId.getNamespace()),
                AUDITED_SAFE_ROLE_IDS.contains(roleId),
                currentRole.getComponentKey() != null,
                currentRole.canBeRandomedDefination(),
                currentRole.getClass() == NormalRole.class,
                !lifecycleSignals.isEmpty(),
                inspectionFailed,
                lifecycleSignals
        ));
    }

    /** Pure classifier used by tests and future compatibility audits. */
    static Assessment assessSignals(RiskSignals signals) {
        if (signals == null || signals.roleId() == null) {
            return Assessment.denied(REASON_NO_CURRENT_ROLE, null, List.of("missing_current_role"));
        }
        if (signals.knownUnsafeLifecycle()) {
            return Assessment.denied(REASON_MONOKUMA_LIFECYCLE, signals.roleId(),
                    signals.lifecycleSignals());
        }
        if (signals.inspectionFailed()) {
            return Assessment.denied(REASON_INSPECTION_FAILED, signals.roleId(),
                    List.of("runtime_state_inspection_failed"));
        }
        if (signals.coreOwned() || signals.auditedSafe()) {
            return Assessment.allowed(signals.roleId());
        }

        List<String> risks = new ArrayList<>();
        if (signals.componentBacked()) {
            risks.add("component_backed");
        }
        if (!signals.randomizableByOtherRoles()) {
            risks.add("not_randomizable_by_other_roles");
        }
        if (!signals.plainNormalRole()) {
            risks.add("custom_role_implementation");
        }
        if (!risks.isEmpty()) {
            return Assessment.denied(REASON_UNAUDITED_UPSTREAM_STATE, signals.roleId(), risks);
        }
        return Assessment.allowed(signals.roleId());
    }

    /** Internal audit seam; callers must review every persistent state owner first. */
    static void registerAuditedSafeRole(ResourceLocation roleId) {
        if (roleId != null) {
            AUDITED_SAFE_ROLE_IDS.add(roleId);
        }
    }

    public static void logDenial(String source, ServerPlayer player, Assessment assessment) {
        if (assessment == null || assessment.allowed()) {
            return;
        }
        HabiTrainCore.LOGGER.warn(
                "[ForcedRandomRoleChange] source={} player={} role={} reason={} signals={}",
                source,
                player == null ? "unknown" : player.getGameProfile().getName(),
                assessment.roleId(),
                assessment.reasonCode(),
                assessment.riskSignals());
    }

    public record RiskSignals(ResourceLocation roleId,
                              boolean coreOwned,
                              boolean auditedSafe,
                              boolean componentBacked,
                              boolean randomizableByOtherRoles,
                              boolean plainNormalRole,
                              boolean knownUnsafeLifecycle,
                              boolean inspectionFailed,
                              List<String> lifecycleSignals) {
        public RiskSignals {
            lifecycleSignals = lifecycleSignals == null ? List.of() : List.copyOf(lifecycleSignals);
        }
    }

    public record Assessment(boolean allowed, String reasonCode,
                             @Nullable ResourceLocation roleId, List<String> riskSignals) {
        public Assessment {
            riskSignals = riskSignals == null ? List.of() : List.copyOf(riskSignals);
        }

        static Assessment allowed(ResourceLocation roleId) {
            return new Assessment(true, REASON_SAFE, roleId, List.of());
        }

        static Assessment denied(String reasonCode, @Nullable ResourceLocation roleId,
                                 List<String> riskSignals) {
            return new Assessment(false, reasonCode, roleId, riskSignals);
        }

        public String diagnosticMessage() {
            return "forced random role change denied: " + reasonCode
                    + (riskSignals.isEmpty() ? "" : " (" + String.join(",", riskSignals) + ")");
        }
    }
}
