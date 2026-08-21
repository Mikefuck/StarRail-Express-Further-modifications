package com.habitrain.core.client.mvp;

import com.habitrain.core.config.MvpAnimationSettings;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MvpAnimationSelectorTest {

    @Test
    void returnsNullWhenGlobalSwitchDisabled() {
        MvpAnimationSettings settings = MvpAnimationSettings.createDefault();
        settings.enabled = false;

        UUID uuid = UUID.randomUUID();
        MvpAnimationDefinition result = MvpAnimationSelector.select(settings, uuid, 0, 1, 12345L, false);
        assertNull(result);
    }

    @Test
    void returnsNullWhenAllAnimationsDisabled() {
        MvpAnimationSettings settings = MvpAnimationSettings.createDefault();
        for (String id : MvpAnimationSettings.DEFAULT_ANIMATION_IDS) {
            settings.setAnimationEnabled(id, false);
        }

        UUID uuid = UUID.randomUUID();
        MvpAnimationDefinition result = MvpAnimationSelector.select(settings, uuid, 0, 1, 12345L, false);
        assertNull(result);
    }

    @Test
    void nonRandomSelectionPicksFirstEnabled() {
        MvpAnimationSettings settings = MvpAnimationSettings.createDefault();
        settings.randomSelection = false;
        settings.setAnimationEnabled("victory_bow", false);

        UUID uuid = UUID.randomUUID();
        MvpAnimationDefinition result = MvpAnimationSelector.select(settings, uuid, 0, 1, 12345L, false);
        assertNotNull(result);
        assertEquals("penguin_dance", result.id());
    }

    @Test
    void squadModeFiltersNonSquadSafeAnimations() {
        MvpAnimationSettings settings = MvpAnimationSettings.createDefault();
        // cool_sit, victory_floss, meditation_fly are not squadSafe
        for (String id : MvpAnimationSettings.DEFAULT_ANIMATION_IDS) {
            settings.setAnimationEnabled(id, false);
        }
        settings.setAnimationEnabled("cool_sit", true);
        settings.setAnimationEnabled("victory_floss", true);
        settings.setAnimationEnabled("meditation_fly", true);

        UUID uuid = UUID.randomUUID();
        // In solo mode, cool_sit is allowed
        MvpAnimationDefinition soloResult = MvpAnimationSelector.select(settings, uuid, 0, 1, 12345L, false);
        assertNotNull(soloResult);

        // In squad mode, all 3 enabled animations are rejected, so returns null
        MvpAnimationDefinition squadResult = MvpAnimationSelector.select(settings, uuid, 0, 4, 12345L, true);
        assertNull(squadResult);
    }

    @Test
    void deterministicSelectionWithSameSeed() {
        MvpAnimationSettings settings = MvpAnimationSettings.createDefault();
        UUID uuid = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        long seed = 987654321L;

        MvpAnimationDefinition res1 = MvpAnimationSelector.select(settings, uuid, 0, 1, seed, false);
        MvpAnimationDefinition res2 = MvpAnimationSelector.select(settings, uuid, 0, 1, seed, false);

        assertNotNull(res1);
        assertEquals(res1, res2);
    }

    @Test
    void squadAvoidDuplicatesSelectsDistinctAnimationsForFourPlayers() {
        MvpAnimationSettings settings = MvpAnimationSettings.createDefault();
        settings.avoidDuplicates = true;
        settings.randomSelection = true;

        long seed = 42L;
        Set<String> chosen = new HashSet<>();

        for (int rank = 0; rank < 4; rank++) {
            UUID uuid = UUID.randomUUID();
            MvpAnimationDefinition def = MvpAnimationSelector.select(settings, uuid, rank, 4, seed, true);
            assertNotNull(def);
            assertTrue(def.squadSafe(), "Squad selected animation must be squad safe");
            chosen.add(def.id());
        }

        assertEquals(4, chosen.size(), "4 squad members must receive 4 distinct animations when pool is sufficient");
    }

    @Test
    void poolSmallerThanSquadCountDoesNotCrashOrInfiniteLoop() {
        MvpAnimationSettings settings = MvpAnimationSettings.createDefault();
        for (String id : MvpAnimationSettings.DEFAULT_ANIMATION_IDS) {
            settings.setAnimationEnabled(id, false);
        }
        // Enable only 2 squadSafe animations
        settings.setAnimationEnabled("victory_bow", true);
        settings.setAnimationEnabled("champion_tpose", true);

        long seed = 123L;
        for (int rank = 0; rank < 4; rank++) {
            UUID uuid = UUID.randomUUID();
            MvpAnimationDefinition def = MvpAnimationSelector.select(settings, uuid, rank, 4, seed, true);
            assertNotNull(def);
            assertTrue("victory_bow".equals(def.id()) || "champion_tpose".equals(def.id()));
        }
    }
}
