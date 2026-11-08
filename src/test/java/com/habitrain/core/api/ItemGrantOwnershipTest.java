package com.habitrain.core.api;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ItemGrantOwnershipTest {
    private static final String TASK = "habitrain_core:search_backpack";
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ASSIGNMENT = UUID.randomUUID();

    @Test void anotherPlayersGrantIsNeverReclaimedEvenFromTheCurrentPlayersInventory() {
        CompoundTag tag = grant(UUID.randomUUID(), ASSIGNMENT);
        assertFalse(ItemReclaimHelper.matchesReclaimTag(tag, TASK, OWNER, ASSIGNMENT, true));
        assertFalse(ItemReclaimHelper.matchesReclaimTag(tag, TASK, OWNER, ASSIGNMENT, false));
    }

    @Test void anEarlierSameTypeTaskRewardSurvivesCancellationOfTheNewAssignment() {
        CompoundTag tag = grant(OWNER, UUID.randomUUID());
        assertFalse(ItemReclaimHelper.matchesReclaimTag(tag, TASK, OWNER, ASSIGNMENT, true));
        assertTrue(ItemReclaimHelper.matchesReclaimTag(grant(OWNER, ASSIGNMENT), TASK, OWNER, ASSIGNMENT, false));
    }

    @Test void unownedLegacyTagsCanOnlyBeReclaimedFromPersonalSlots() {
        CompoundTag legacy = new CompoundTag();
        legacy.putString(ItemReclaimHelper.GRANT_TAG_KEY, TASK);
        assertTrue(ItemReclaimHelper.matchesReclaimTag(legacy, TASK, OWNER, ASSIGNMENT, true));
        assertFalse(ItemReclaimHelper.matchesReclaimTag(legacy, TASK, OWNER, ASSIGNMENT, false));
    }

    private static CompoundTag grant(UUID owner, UUID assignment) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ItemReclaimHelper.GRANT_TAG_KEY, TASK);
        tag.putUUID(ItemReclaimHelper.GRANT_OWNER_TAG_KEY, owner);
        tag.putUUID(ItemReclaimHelper.GRANT_INSTANCE_TAG_KEY, assignment);
        return tag;
    }
}
