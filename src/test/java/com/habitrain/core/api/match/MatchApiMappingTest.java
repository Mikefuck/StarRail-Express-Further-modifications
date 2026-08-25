package com.habitrain.core.api.match;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchApiMappingTest {

    @Test
    void phaseAlignsWithSreStatusNames() {
        assertEquals(MatchPhase.INACTIVE, MatchPhase.fromStatusName("INACTIVE"));
        assertEquals(MatchPhase.STARTING, MatchPhase.fromStatusName("STARTING"));
        assertEquals(MatchPhase.INITIATING, MatchPhase.fromStatusName("initiating"));
        assertEquals(MatchPhase.ACTIVE, MatchPhase.fromStatusName("ACTIVE"));
        assertEquals(MatchPhase.STOPPING, MatchPhase.fromStatusName("STOPPING"));
        assertEquals(MatchPhase.UNKNOWN, MatchPhase.fromStatusName("nope"));
        assertFalse(MatchPhase.INACTIVE.hasLeftLobby());
        assertTrue(MatchPhase.STARTING.hasLeftLobby());
        assertTrue(MatchPhase.UNKNOWN.hasLeftLobby());
    }

    @Test
    void winKindMapsSreWinStatusNames() {
        assertEquals(MatchWinKind.KILLER, MatchWinKind.fromWinStatusName("KILLERS"));
        assertEquals(MatchWinKind.INNOCENT, MatchWinKind.fromWinStatusName("PASSENGERS"));
        assertEquals(MatchWinKind.INNOCENT, MatchWinKind.fromWinStatusName("TIME"));
        assertEquals(MatchWinKind.CUSTOM, MatchWinKind.fromWinStatusName("LOVERS"));
        assertEquals(MatchWinKind.NONE, MatchWinKind.fromWinStatusName("NONE"));
        assertEquals(MatchWinKind.NO_PLAYER, MatchWinKind.fromWinStatusName("NO_PLAYER"));
    }

    @Test
    void winFactionRulesMatchLotteryBuckets() {
        assertEquals(MatchWinFaction.KILLER, MatchWinFaction.fromBlackoutName("BAD", false));
        assertEquals(MatchWinFaction.KILLER, MatchWinFaction.fromBlackoutName("SIN_KILLER_SHARE", true));
        assertEquals(MatchWinFaction.NEUTRAL, MatchWinFaction.fromBlackoutName("SIN_KILLER_SHARE", false));
        assertEquals(MatchWinFaction.PASSENGER, MatchWinFaction.fromBlackoutName("GOOD", true));
        assertEquals(MatchWinFaction.NEUTRAL, MatchWinFaction.fromRoleFlags(
                true, false, false, false, false, false, false, false));
        assertEquals(MatchWinFaction.KILLER, MatchWinFaction.fromRoleFlags(
                false, false, false, false, true, false, true, false));
        assertEquals(MatchWinFaction.PASSENGER, MatchWinFaction.fromRoleFlags(
                false, false, false, false, false, false, false, false));
    }

    @Test
    void settlementCopiesCollections() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Set<UUID> source = new java.util.HashSet<>();
        source.add(a);
        MatchSettlement settlement = new MatchSettlement(
                "habitrain:blackout",
                MatchWinKind.KILLER,
                "minecraft:overworld|1|2",
                source,
                source,
                java.util.Map.of(a, MatchWinFaction.KILLER));
        source.clear();
        assertEquals(Set.of(a), settlement.participants());
        assertEquals(MatchWinFaction.KILLER, settlement.factionOf(a));
        assertEquals("habitrain:blackout", settlement.modeId());
    }
}
