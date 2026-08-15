package com.habitrain.core.game.sre.role.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import com.habitrain.core.game.sre.roleoverride.SreRolePoolFilter;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * 凶案替罪羊（平民）：
 * 身边 4 格有玩家死亡 → 发假刀并强制手持 10 秒；
 * 10 秒内被击杀 → 转随机杀手；
 * 10 秒未被击杀 → 收回假刀并发一次性左轮；
 * 机制内置 CD 30 秒。
 */
public final class CrimeScapegoatComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<CrimeScapegoatComponent> KEY = ComponentRegistry.getOrCreate(
            HabiTrainCore.id("crime_scapegoat"), CrimeScapegoatComponent.class);

    public static final double NEARBY_DEATH_RANGE = 4.0;
    public static final int KNIFE_WINDOW_SECONDS = 10;
    public static final int MECHANISM_CD_SECONDS = 30;

    private final Player player;

    /** 强制持刀剩余 tick；&gt;0 表示窗口中 */
    private int knifeWindowTicks;
    /** 机制冷却剩余 tick */
    private int mechanismCooldownTicks;
    /** 已转职为杀手，不再触发机制 */
    private boolean converted;
    /** 强制持刀的热键栏槽位（0–8） */
    private int forcedHotbarSlot = -1;

    public CrimeScapegoatComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public boolean isInKnifeWindow() {
        return knifeWindowTicks > 0;
    }

    public boolean isConverted() {
        return converted;
    }

    public int getKnifeWindowTicks() {
        return knifeWindowTicks;
    }

    public int getMechanismCooldownTicks() {
        return mechanismCooldownTicks;
    }

    public void markConverted() {
        this.converted = true;
        this.knifeWindowTicks = 0;
        this.forcedHotbarSlot = -1;
        HabiRoleItems.reclaimScapegoatKnives(player);
        sync();
    }

    /**
     * 附近有玩家死亡时尝试启动机制。
     * @return 是否成功启动
     */
    public boolean tryTriggerFromNearbyDeath(ServerPlayer self) {
        if (self == null || self.isSpectator() || converted) return false;
        if (knifeWindowTicks > 0) return false;
        if (mechanismCooldownTicks > 0) return false;

        ItemStack knife = HabiRoleItems.createScapegoatKnife();
        if (knife.isEmpty()) {
            HabiTrainCore.LOGGER.warn("[Scapegoat] noellesroles:fake_knife missing");
            return false;
        }

        int slot = placeKnifeInHotbar(self, knife);
        if (slot < 0) {
            // 热键栏满：仍尝试塞进背包再选中
            if (!self.getInventory().add(knife.copy())) {
                self.drop(knife.copy(), false);
            }
            slot = findKnifeHotbarSlot(self);
            if (slot < 0) {
                // 强制放进当前选中槽
                slot = Math.max(0, Math.min(8, self.getInventory().selected));
                self.getInventory().setItem(slot, knife.copy());
            }
        }

        forcedHotbarSlot = slot;
        self.getInventory().selected = slot;
        knifeWindowTicks = KNIFE_WINDOW_SECONDS * 20;
        // CD 从触发时起算，窗口期间 + 结束后合计至少 30s；窗口结束时若已不足则补足
        mechanismCooldownTicks = MECHANISM_CD_SECONDS * 20;

        self.level().playSound(
                null,
                self.getX(), self.getY(), self.getZ(),
                SoundEvents.ITEM_PICKUP,
                SoundSource.PLAYERS,
                1.0f,
                0.8f
        );
        self.displayClientMessage(
                Component.literal("§c[凶案替罪羊] 附近有人倒下！你被迫握起了假刀——10 秒内若被击杀将变为杀手。"),
                true
        );
        sync();
        HabiTrainCore.LOGGER.info("[Scapegoat] {} entered knife window (slot={})",
                self.getName().getString(), slot);
        return true;
    }

    /** 窗口自然结束：收回假刀并发一次性左轮 */
    private void onKnifeWindowExpire(ServerPlayer self) {
        forcedHotbarSlot = -1;
        HabiRoleItems.reclaimScapegoatKnives(self);
        ItemStack gun = HabiRoleItems.lookupItem(HabiRoleItems.ONCE_REVOLVER_ID, 1);
        if (!gun.isEmpty()) {
            if (!self.getInventory().add(gun)) {
                self.drop(gun, false);
            }
            self.displayClientMessage(
                    Component.literal("§a[凶案替罪羊] 危机过去，假刀已收回，你获得了一把一次性左轮。"),
                    true
            );
        } else {
            HabiTrainCore.LOGGER.warn("[Scapegoat] once_revolver missing for {}", self.getName().getString());
            self.displayClientMessage(
                    Component.literal("§a[凶案替罪羊] 危机过去，假刀已收回。"),
                    true
            );
        }
        // 确保 CD 至少还剩 0；触发时已设 30s，窗口 10s 后还剩约 20s
        if (mechanismCooldownTicks < 0) {
            mechanismCooldownTicks = 0;
        }
        sync();
    }

    @Override
    public void serverTick() {
        if (!(player instanceof ServerPlayer self) || self.level().isClientSide) return;
        if (converted) return;

        if (mechanismCooldownTicks > 0) {
            mechanismCooldownTicks--;
        }

        if (knifeWindowTicks > 0) {
            knifeWindowTicks--;
            forceHoldKnife(self);
            if (knifeWindowTicks <= 0) {
                onKnifeWindowExpire(self);
            } else if (knifeWindowTicks % 20 == 0) {
                // 每秒同步一次剩余时间给客户端 HUD（可选）
                sync();
            }
        }
    }

    private void forceHoldKnife(ServerPlayer self) {
        if (forcedHotbarSlot < 0 || forcedHotbarSlot > 8) {
            forcedHotbarSlot = findKnifeHotbarSlot(self);
        }
        if (forcedHotbarSlot < 0) {
            // 刀丢了：补一把假刀
            ItemStack knife = HabiRoleItems.createScapegoatKnife();
            if (knife.isEmpty()) return;
            int slot = placeKnifeInHotbar(self, knife);
            if (slot < 0) {
                slot = Math.max(0, Math.min(8, self.getInventory().selected));
                self.getInventory().setItem(slot, knife);
            }
            forcedHotbarSlot = slot;
        } else {
            ItemStack inSlot = self.getInventory().getItem(forcedHotbarSlot);
            if (inSlot.isEmpty() || !HabiRoleItems.isScapegoatKnife(inSlot)) {
                ItemStack knife = HabiRoleItems.createScapegoatKnife();
                if (!knife.isEmpty()) {
                    self.getInventory().setItem(forcedHotbarSlot, knife);
                }
            }
        }
        if (self.getInventory().selected != forcedHotbarSlot) {
            self.getInventory().selected = forcedHotbarSlot;
        }
    }

    private static int placeKnifeInHotbar(ServerPlayer self, ItemStack knife) {
        // 优先当前手持槽
        int selected = self.getInventory().selected;
        if (selected >= 0 && selected <= 8) {
            ItemStack cur = self.getInventory().getItem(selected);
            if (cur.isEmpty()) {
                self.getInventory().setItem(selected, knife.copy());
                return selected;
            }
        }
        // 找空热键栏
        for (int i = 0; i < 9; i++) {
            if (self.getInventory().getItem(i).isEmpty()) {
                self.getInventory().setItem(i, knife.copy());
                return i;
            }
        }
        // 覆盖当前手持
        if (selected >= 0 && selected <= 8) {
            ItemStack old = self.getInventory().getItem(selected);
            self.getInventory().setItem(selected, knife.copy());
            if (!old.isEmpty() && !self.getInventory().add(old)) {
                self.drop(old, false);
            }
            return selected;
        }
        return -1;
    }

    private static int findKnifeHotbarSlot(ServerPlayer self) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = self.getInventory().getItem(i);
            if (!s.isEmpty() && HabiRoleItems.isScapegoatKnife(s)) {
                return i;
            }
        }
        return -1;
    }

    /** 转职用杀手池（上游杀手逻辑：canUseKiller && !isInnocent；排除其他模式/禁用/不可随机/七宗罪角色）。 */
    public static List<SRERole> randomKillerPool() {
        List<SRERole> killers = new ArrayList<>();
        // 池来自 v2 目录（v1 回退），使 v2 ADD 角色能进入转职杀手池（audit P2-1）。
        for (SRERole role :
                com.habitrain.core.role.catalog.RoleCatalogConsumer.visiblePool()) {
            if (role == null) continue;
            if (!SreRolePoolFilter.isCurrentModeRandomizable(role)) continue;
            if (!role.canUseKiller()) continue;
            if (role.isVigilanteTeam()) continue;
            if (role.isNeutrals()) continue;
            if (role.isInnocent()) continue;           // 对齐上游杀手池 (canUseKiller && !isInnocent)
            if (SevenSins.isSin(role)) continue;       // 排除七宗罪：互斥仅开局执行，局中转罪会绕开一局一罪
            killers.add(role);
        }
        // 空池回退：只接收通过过滤的替换杀手，否则留空（调用方已 warn+return）
        if (killers.isEmpty() && TMMRoles.KILLER != null) {
            SRERole fallback = com.habitrain.core.role.catalog.RoleCatalogConsumer
                    .resolveOrOriginal(TMMRoles.KILLER);
            if (fallback != null && SreRolePoolFilter.isCurrentModeRandomizable(fallback)) {
                killers.add(fallback);
            }
        }
        SreRolePoolFilter.warnIfLeaky("ScapegoatKiller", killers);
        return killers;
    }

    @Override
    public void init() {
        knifeWindowTicks = 0;
        mechanismCooldownTicks = 0;
        converted = false;
        forcedHotbarSlot = -1;
        sync();
    }

    @Override
    public void clear() {
        init();
    }

    public void sync() {
        KEY.sync(player);
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putInt("KnifeWindowTicks", knifeWindowTicks);
        tag.putInt("MechanismCooldownTicks", mechanismCooldownTicks);
        tag.putBoolean("Converted", converted);
        tag.putInt("ForcedHotbarSlot", forcedHotbarSlot);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        knifeWindowTicks = tag.getInt("KnifeWindowTicks");
        mechanismCooldownTicks = tag.getInt("MechanismCooldownTicks");
        converted = tag.getBoolean("Converted");
        forcedHotbarSlot = tag.contains("ForcedHotbarSlot") ? tag.getInt("ForcedHotbarSlot") : -1;
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