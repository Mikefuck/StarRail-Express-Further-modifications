package com.habitrain.core.role.capability;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.capability.VoiceDecision;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoicePermissionSnapshotTest {
    private static final RoleKey ROLE = RoleKey.of("test", "speaker");
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), GROUP = UUID.randomUUID();
    private static VoicePermissionSnapshot.Participant player(RoleKey role, UUID group, String dim, double x) {
        return new VoicePermissionSnapshot.Participant(role, group, dim, x, 0, 0);
    }
    @Test void publicationCopiesBothCollectionsAndContainsNoLivePlayerObjects() {
        var players = new HashMap<UUID, VoicePermissionSnapshot.Participant>();
        players.put(A, player(ROLE, null, "a", 0));
        var policies = new ArrayList<RoleVoicePolicy>();
        VoicePermissionSnapshot snapshot = new VoicePermissionSnapshot(players, policies);
        players.clear();
        policies.add(RoleVoicePolicy.of("test", "mute").role(ROLE).muteSend().build());
        assertEquals(VoiceDecision.PASS, snapshot.evaluate(A, null, null, null));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.players().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.policies().clear());
    }
    @Test void missingParticipantsAndSendMuteBlockButReceiverPolicyDoesNotBlockMicrophone() {
        assertEquals(VoiceDecision.BLOCK, VoicePermissionSnapshot.EMPTY.evaluate(A, null, null, null));
        var players = Map.of(A, player(ROLE, null, "a", 0), B, player(null, null, "a", 1));
        var receive = RoleVoicePolicy.of("test", "receive").role(ROLE).muteReceive().build();
        var snapshot = new VoicePermissionSnapshot(players, List.of(receive));
        assertEquals(VoiceDecision.PASS, snapshot.evaluate(A, null, null, null));
        assertEquals(VoiceDecision.BLOCK, snapshot.evaluate(B, A, null, null));
        assertEquals(VoiceDecision.BLOCK, snapshot.evaluate(A, UUID.randomUUID(), null, null));
        var send = RoleVoicePolicy.of("test", "send").role(ROLE).muteSend().build();
        assertEquals(VoiceDecision.BLOCK, new VoicePermissionSnapshot(players, List.of(send)).evaluate(A, null, null, null));
    }
    @Test void storedGroupsTakePriorityAndDistancesComeFromThePublishedWorldView() {
        var policy = RoleVoicePolicy.of("test", "isolation").role(ROLE).isolateGroup().hearWorld(false).maxDistance(5).build();
        var players = Map.of(A, player(ROLE, GROUP, "a", 0), B, player(null, GROUP, "a", 3));
        var snapshot = new VoicePermissionSnapshot(players, List.of(policy));
        assertEquals(VoiceDecision.PASS, snapshot.evaluate(A, B, UUID.randomUUID(), UUID.randomUUID()));
        var otherDimension = new VoicePermissionSnapshot(
                Map.of(A, players.get(A), B, player(null, GROUP, "b", 0)), List.of(policy));
        assertEquals(VoiceDecision.BLOCK, otherDimension.evaluate(A, B, GROUP, GROUP));
    }
}
