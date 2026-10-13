package com.habitrain.core.role.behavior;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.behavior.*;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real adapters used by Fabric/SRE, with only role lookup substituted. */
class RoleParticipantDispatchTest {
    private static final RoleKey A = RoleKey.of("habitrain_core", "participant_a");
    private static final RoleKey B = RoleKey.of("habitrain_core", "participant_b");
    private static final ResourceLocation REASON = ResourceLocation.parse("sre:gun");
    private final RoleEventDispatcher dispatcher = RoleEventDispatcher.INSTANCE;
    private final IdentityHashMap<Player, RoleKey> roles = new IdentityHashMap<>();
    private final List<RoleHookContext> seen = new ArrayList<>();
    private ServerPlayer attacker;
    private ServerPlayer target;

    @BeforeEach void prepare() throws Exception {
        RoleHookRegistry.INSTANCE.clear();
        com.habitrain.core.role.snapshot.RoleSnapshotManager.INSTANCE.clear();
        dispatcher.resetCircuits();
        dispatcher.setHookGates(RoleScopeEvaluator.LENIENT);
        attacker = emptyPlayer();
        target = emptyPlayer();
        roles.put(attacker, A);
        roles.put(target, B);
        Field lookup = RoleEventDispatcher.class.getDeclaredField("roleLookup");
        lookup.setAccessible(true);
        lookup.set(dispatcher, (Function<Player, RoleEventDispatcher.CurrentRoleLookup>)
                player -> RoleEventDispatcher.CurrentRoleLookup.ok(roles.get(player)));
    }

    @AfterEach void release() throws Exception {
        Field lookup = RoleEventDispatcher.class.getDeclaredField("roleLookup");
        lookup.setAccessible(true);
        lookup.set(dispatcher, (Function<Player, RoleEventDispatcher.CurrentRoleLookup>) RoleEventDispatcher::lookupCurrentRole);
        RoleHookRegistry.INSTANCE.clear();
        dispatcher.resetCircuits();
        dispatcher.setHookGates(RoleScopeEvaluator.LENIENT);
    }

    // Allocation avoids a server/world launch; adapters only read nullable world fields.
    private static ServerPlayer emptyPlayer() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (ServerPlayer) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ServerPlayer.class);
    }

    private void attackHook(RoleKey role, RoleScope scope, String id, InteractionResult result) {
        RoleHookRegistry.INSTANCE.register(role, scope, "habitrain_core", id, PatchPriority.NORMAL,
                RoleHooks.builder().interaction(new RoleInteractionHooks() {
                    @Override public InteractionResult attackEntity(ServerPlayer player, Entity entity,
                                                                   InteractionHand hand, RoleHookContext context) {
                        assertSame(entity instanceof ServerPlayer ? entity : null, context.target());
                        seen.add(context);
                        return result;
                    }
                }).build());
    }

    @Test void targetScopeResolvesTheTargetEvenWithDifferentOrSameRole() {
        attackHook(B, RoleScope.TARGET, "target", InteractionResult.PASS);
        dispatcher.attackEntity(attacker, target, InteractionHand.MAIN_HAND);
        assertEquals(1, seen.size());
        assertSame(target, seen.getFirst().holder());
        roles.put(attacker, B);
        seen.clear();
        dispatcher.attackEntity(attacker, target, InteractionHand.MAIN_HAND);
        assertEquals(1, seen.size());
        assertSame(target, seen.getFirst().holder());
        seen.clear();
        roles.put(target, A);
        dispatcher.attackEntity(target, attacker, InteractionHand.MAIN_HAND);
        assertEquals(1, seen.size());
        assertSame(attacker, seen.getFirst().holder());
    }

    @Test void holderAndTargetAreDistinctSubscriptionsAndNoImplicitTargetHolderDispatchOccurs() {
        attackHook(A, RoleScope.HOLDER, "holder", InteractionResult.PASS);
        attackHook(B, RoleScope.HOLDER, "other_holder", InteractionResult.PASS);
        attackHook(B, RoleScope.TARGET, "target", InteractionResult.FAIL);
        assertEquals(InteractionResult.FAIL, dispatcher.attackEntity(attacker, target, InteractionHand.MAIN_HAND));
        assertEquals(2, seen.size());
        assertSame(attacker, seen.get(0).holder());
        assertSame(target, seen.get(1).holder());
        seen.clear();
        dispatcher.attackEntity(attacker, null, InteractionHand.MAIN_HAND);
        assertEquals(1, seen.size());
    }

    @Test void nonPlayerTargetNeverMatchesTargetScope() throws Exception {
        attackHook(A, RoleScope.HOLDER, "holder", InteractionResult.PASS);
        attackHook(B, RoleScope.TARGET, "target", InteractionResult.PASS);
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Entity nonPlayer = (Entity) ((sun.misc.Unsafe) field.get(null))
                .allocateInstance(net.minecraft.world.entity.item.ItemEntity.class);
        dispatcher.attackEntity(attacker, nonPlayer, InteractionHand.MAIN_HAND);
        assertEquals(1, seen.size());
        assertSame(attacker, seen.getFirst().holder());
        assertNull(seen.getFirst().target());
    }

    @Test void killerAndVictimScopesRunAcrossBothRoleSidesAndDenyDominatesAllow() {
        RoleCombatHooks hooks = new RoleCombatHooks() {
            @Override public Decision allowDeathByKiller(ServerPlayer victim, ServerPlayer killer,
                                                         ResourceLocation reason, RoleHookContext context) {
                seen.add(context);
                assertSame(killer, context.killer());
                assertSame(victim, context.victim());
                return context.holder() == target ? Decision.DENY : Decision.ALLOW;
            }
        };
        RoleHookRegistry.INSTANCE.register(A, RoleScope.KILLER, "habitrain_core", "killer", PatchPriority.NORMAL,
                RoleHooks.builder().combat(hooks).build());
        RoleHookRegistry.INSTANCE.register(B, RoleScope.VICTIM, "habitrain_core", "victim", PatchPriority.NORMAL,
                RoleHooks.builder().combat(hooks).build());
        assertFalse(dispatcher.allowDeathWithKiller(target, attacker, REASON));
        assertEquals(2, seen.size());
        assertSame(attacker, seen.get(0).holder());
        assertSame(target, seen.get(1).holder());
        seen.clear();
        assertFalse(dispatcher.allowDeathWithKiller(target, null, REASON));
        assertEquals(1, seen.size());
        assertSame(target, seen.getFirst().holder());
    }

    @Test void corpseDualHolderDispatchDoesNotDropSameRoleSecondPlayer() {
        roles.put(target, A);
        RoleHookRegistry.INSTANCE.register(A, RoleHooks.builder().combat(new RoleCombatHooks() {
            @Override public void onDeathWithBody(ServerPlayer victim, ServerPlayer killer, ResourceLocation reason,
                                                   io.wifi.starrailexpress.content.entity.PlayerBodyEntity body,
                                                   RoleHookContext context) { seen.add(context); }
        }).build());
        dispatcher.dispatchOnDeathWithBody(A, target, attacker, REASON, null);
        assertEquals(2, seen.size());
        assertSame(target, seen.get(0).holder());
        assertSame(attacker, seen.get(1).holder());
    }

    @Test void meaninglessScopeTypesAreDiagnosedAndOmittedAtRegistration() {
        attackHook(A, RoleScope.TARGET, "target", InteractionResult.PASS);
        assertTrue(RoleHookRegistry.INSTANCE.entries(A, HookType.INTERACTION_ATTACK_BLOCK).isEmpty());
        assertEquals(1, RoleHookRegistry.INSTANCE.entries(A, HookType.INTERACTION_ATTACK_ENTITY).size());
    }
}
