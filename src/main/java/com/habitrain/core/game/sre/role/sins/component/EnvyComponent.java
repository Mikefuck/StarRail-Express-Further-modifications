package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.RoleComponent;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;


/**
 * 嫉妒的兼容组件。旧版标记状态已完全移除；击杀奖励由托管战斗钩子处理。
 * 组件 ID 保留，避免旧客户端/世界在协议与 CCA 注册阶段出现缺失组件。
 */
public final class EnvyComponent implements RoleComponent {
    public static final ComponentKey<EnvyComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_envy"), EnvyComponent.class);

    /** Every confirmed Envy kill grants this many bonus coins. */
    public static final int KILL_BONUS_COINS = 100;

    private final Player player;

    public EnvyComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        // No per-round state remains after removing Envy's mark mechanic.
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        // Compatibility component has no synchronized state.
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        // Ignore old mark/balance payload fields.
    }

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        // Round-only compatibility component is not persisted.
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        // Ignore old playerdata.
    }
}
