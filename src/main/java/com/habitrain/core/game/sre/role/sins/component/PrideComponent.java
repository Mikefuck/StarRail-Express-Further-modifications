package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.RoleComponent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

/**
 * 傲慢：P0 空状态机；P1 补商店拷贝 / 破甲免疫等。
 */
public final class PrideComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<PrideComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_pride"), PrideComponent.class);

    private final Player player;
    private long breakImmuneUntilGameTime;
    private ResourceLocation copiedShopRoleId;

    public PrideComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public long getBreakImmuneUntilGameTime() {
        return breakImmuneUntilGameTime;
    }

    public ResourceLocation getCopiedShopRoleId() {
        return copiedShopRoleId;
    }

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        breakImmuneUntilGameTime = 0;
        copiedShopRoleId = null;
    }

    @Override
    public void serverTick() {
        // P1
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putLong("BreakImmuneUntil", breakImmuneUntilGameTime);
        if (copiedShopRoleId != null) {
            tag.putString("CopiedShopRoleId", copiedShopRoleId.toString());
        }
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        breakImmuneUntilGameTime = tag.getLong("BreakImmuneUntil");
        if (tag.contains("CopiedShopRoleId")) {
            copiedShopRoleId = ResourceLocation.tryParse(tag.getString("CopiedShopRoleId"));
        } else {
            copiedShopRoleId = null;
        }
    }

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        writeToSyncNbt(tag, registryLookup);
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        readFromSyncNbt(tag, registryLookup);
    }
}
