package com.habitrain.core.game.sre.modifier;

import net.minecraft.nbt.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivateModifierSyncTest {
    @Test void ownerAndObservedTargetOnlyAndRevivalRemovesRemoteData() {
        UUID owner = UUID.randomUUID(), observed = UUID.randomUUID(), unrelated = UUID.randomUUID();
        CompoundTag original = new CompoundTag();
        ListTag list = new ListTag();
        for (UUID id : new UUID[]{owner, observed, unrelated}) list.add(NbtUtils.createUUID(id));
        original.put("habitrain_core:virtue_mercy", list);
        original.putString("public-marker", "unchanged");
        CompoundTag spectator = original.copy();
        PrivateModifierSync.filter(spectator, owner, observed);
        assertEquals(2, spectator.getList("habitrain_core:virtue_mercy", Tag.TAG_INT_ARRAY).size());
        CompoundTag revived = original.copy();
        PrivateModifierSync.filter(revived, owner, null);
        assertEquals(1, revived.getList("habitrain_core:virtue_mercy", Tag.TAG_INT_ARRAY).size());
        assertEquals(owner, NbtUtils.loadUUID(revived.getList("habitrain_core:virtue_mercy", Tag.TAG_INT_ARRAY).get(0)));
        assertEquals("unchanged", revived.getString("public-marker"));
        assertEquals(3, original.getList("habitrain_core:virtue_mercy", Tag.TAG_INT_ARRAY).size());
    }
    @Test void unprivilegedRecipientGetsNoPrivateModifierIds() {
        CompoundTag tag = new CompoundTag(); ListTag list = new ListTag(); list.add(NbtUtils.createUUID(UUID.randomUUID()));
        tag.put("habitrain_core:lust_charmed", list);
        PrivateModifierSync.filter(tag, UUID.randomUUID(), null);
        assertFalse(tag.contains("habitrain_core:lust_charmed"));
    }
}
