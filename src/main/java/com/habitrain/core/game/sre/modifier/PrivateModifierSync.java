package com.habitrain.core.game.sre.modifier;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Filters only wire snapshots, never the authoritative modifier map or saved NBT. */
public final class PrivateModifierSync {
    private PrivateModifierSync() {}

    public static void filter(CompoundTag tag, UUID owner, UUID observed) {
        for (String key : new String[]{"habitrain_core:virtue_mercy", "habitrain_core:lust_charmed"}) {
            ListTag visible = new ListTag();
            for (Tag entry : tag.getList(key, Tag.TAG_INT_ARRAY)) {
                UUID id = NbtUtils.loadUUID(entry);
                if (id.equals(owner) || id.equals(observed)) visible.add(entry.copy());
            }
            tag.remove(key);
            if (!visible.isEmpty()) tag.put(key, visible);
        }
    }

    public static void filter(CompoundTag tag, ServerPlayer recipient) {
        UUID owner = recipient == null ? null : recipient.getUUID();
        UUID observed = recipient != null && recipient.isSpectator()
                && recipient.getCamera() instanceof ServerPlayer target
                && target.level() == recipient.level() ? target.getUUID() : null;
        filter(tag, owner, observed);
    }
}
