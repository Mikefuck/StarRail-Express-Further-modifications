package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.modifier.PrivateModifierSync;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.spongepowered.asm.mixin.Mixin;

/** Overrides CCA's inherited default packet writer with a recipient-filtered NBT snapshot. */
@Mixin(value = WorldModifierComponent.class, remap = false)
public abstract class WorldModifierPrivacyMixin implements AutoSyncedComponent {
    @Override
    public void writeSyncPacket(RegistryFriendlyByteBuf buf, ServerPlayer recipient) {
        CompoundTag tag = new CompoundTag();
        ((WorldModifierComponent) (Object) this).writeToNbt(tag, buf.registryAccess());
        PrivateModifierSync.filter(tag, recipient);
        buf.writeNbt(tag);
    }
}
