package com.habitrain.core.role.state;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class S2OpaqueStateNbtTest {
    @Test void unknownTagTypesAndUnsupportedEnvelopesRoundTripWithoutCoercion() {
        CompoundTag root = new CompoundTag();
        root.putString("future/string", "keep me");
        root.putInt("future/int", 123);
        CompoundTag unknown = new CompoundTag(); unknown.putString("format", "v-next");
        root.put("future/object", unknown);
        Map<String, StoredState> slots = new HashMap<>(); Map<String, Tag> raw = new HashMap<>();
        RoleStateNbtUtil.fromTag(root, slots, raw);
        assertTrue(slots.isEmpty());
        assertEquals(root, RoleStateNbtUtil.toTag(slots, raw));
    }
    @Test void recognizedSlotRetainsFutureMetadataButScopeRemovalDoesNotResurrectIt() {
        CompoundTag root = new CompoundTag();
        CompoundTag known = new CompoundTag();
        known.putInt("v", 3); known.putByteArray("d", new byte[]{1, 2});
        known.putString("futureMetadata", "preserve");
        root.put("provider/slot", known);
        Map<String, StoredState> slots = new HashMap<>(); Map<String, Tag> raw = new HashMap<>();
        RoleStateNbtUtil.fromTag(root, slots, raw);
        assertEquals(root, RoleStateNbtUtil.toTag(slots, raw));
        slots.put("provider/slot", StoredState.of(4, new byte[]{3}));
        CompoundTag changed = RoleStateNbtUtil.toTag(slots, raw).getCompound("provider/slot");
        assertEquals("preserve", changed.getString("futureMetadata"));
        assertArrayEquals(new byte[]{3}, changed.getByteArray("d"));
        slots.clear();
        assertTrue(RoleStateNbtUtil.toTag(slots, raw).isEmpty());
    }
    @Test void mismatchedVersionOrPayloadTypeIsKeptOpaque() {
        CompoundTag root = new CompoundTag();
        CompoundTag entry = new CompoundTag();
        entry.putString("v", "future"); entry.putString("d", "not a byte array");
        root.put("provider/opaque", entry);
        Map<String, StoredState> slots = new HashMap<>(); Map<String, Tag> raw = new HashMap<>();
        RoleStateNbtUtil.fromTag(root, slots, raw);
        assertTrue(slots.isEmpty());
        assertEquals(root, RoleStateNbtUtil.toTag(slots, raw));
    }
}
