package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.ItemReclaimHelper;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.content.item.IronDoorKeyItem;
import io.wifi.starrailexpress.content.item.KeyItem;
import io.wifi.starrailexpress.index.SREDataComponentTypes;
import io.wifi.starrailexpress.index.TMMItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.Locale;
import java.util.UUID;

/**
 * 嫉妒：G 标记准星玩家；标记击杀金币门槛与掠夺由 {@code SevenSinEvents} 处理。
 */
public final class EnvyComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<EnvyComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_envy"), EnvyComponent.class);

    public static final int MARK_CD_SECONDS = 90;
    public static final double MARK_RANGE = 16.0;
    public static final int COIN_STEAL_MAX = 100;

    private final Player player;
    private @Nullable UUID markedUuid;

    public EnvyComponent(Player player) {
        this.player = player;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public @Nullable UUID getMarkedUuid() {
        return markedUuid;
    }

    public void setMarkedUuid(@Nullable UUID uuid) {
        this.markedUuid = uuid;
        KEY.sync(player);
    }

    public boolean isMark(Player target) {
        return target != null && markedUuid != null && markedUuid.equals(target.getUUID());
    }

    public static boolean useMark(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!(self.level() instanceof ServerLevel level)) return false;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || SevenSins.ENVY == null || !game.isRole(self, SevenSins.ENVY)) {
            return false;
        }

        ServerPlayer target = resolveTarget(self, ctx.target());
        if (target == null || target.getUUID().equals(self.getUUID())) {
            self.displayClientMessage(Component.literal("§c[嫉妒] 未找到可标记的目标。"), true);
            return false;
        }

        EnvyComponent c = KEY.get(self);
        c.setMarkedUuid(target.getUUID());
        self.displayClientMessage(
                Component.literal("§a[嫉妒] 已标记 " + target.getGameProfile().getName() + "。"),
                true
        );
        target.displayClientMessage(Component.literal("§c你感到被嫉妒盯上了……"), true);
        HabiTrainCore.LOGGER.debug("[Envy] {} marked {}",
                self.getGameProfile().getName(), target.getGameProfile().getName());
        return true;
    }

    private static ServerPlayer resolveTarget(ServerPlayer self, UUID targetId) {
        if (targetId != null) {
            Player p = self.level().getPlayerByUUID(targetId);
            if (p instanceof ServerPlayer sp && !sp.isSpectator()) return sp;
        }
        Vec3 eye = self.getEyePosition();
        Vec3 look = self.getLookAngle();
        double range = MARK_RANGE;
        AABB box = self.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        ServerPlayer best = null;
        double bestDist = range * range;
        for (Player p : self.level().getEntitiesOfClass(Player.class, box)) {
            if (p == self || p.isSpectator()) continue;
            if (!(p instanceof ServerPlayer sp)) continue;
            Vec3 to = p.getEyePosition().subtract(eye);
            double proj = to.dot(look);
            if (proj <= 0 || proj > range) continue;
            double distSq = to.lengthSqr();
            if (distSq < bestDist) {
                bestDist = distSq;
                best = sp;
            }
        }
        return best;
    }

    /**
     * 可被嫉妒掠夺的物品：空/钥匙/任务发放物/灵魂绑定 OWNER 不匹配/明显不可转移 → false。
     */
    public static boolean isTransferable(ItemStack stack, @Nullable Player recipient) {
        if (stack == null || stack.isEmpty()) return false;

        Item item = stack.getItem();
        if (item instanceof KeyItem || item instanceof IronDoorKeyItem) {
            return false;
        }
        if (item == TMMItems.KEY || item == TMMItems.IRON_DOOR_KEY) {
            return false;
        }

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id != null) {
            String path = id.getPath().toLowerCase(Locale.ROOT);
            String full = id.toString().toLowerCase(Locale.ROOT);
            if (path.contains("key") || path.contains("keyblank") || path.contains("master_key")
                    || path.contains("artisan_key") || full.contains("key_blank")) {
                return false;
            }
            // Role-bound / non-physical shop consumables
            if (item == TMMItems.PSYCHO_MODE || item == TMMItems.BLACKOUT) {
                return false;
            }
        }

        // Task-granted items (habitrain_grant in CUSTOM_DATA)
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom != null) {
            CompoundTag tag = custom.copyTag();
            if (tag.contains(ItemReclaimHelper.GRANT_TAG_KEY)) {
                return false;
            }
        }

        // Soulbound-style OWNER string: only transferable if matches recipient UUID
        try {
            if (stack.has(SREDataComponentTypes.OWNER)) {
                String owner = stack.get(SREDataComponentTypes.OWNER);
                if (owner != null && !owner.isEmpty()) {
                    if (recipient == null) return false;
                    String recip = recipient.getUUID().toString();
                    String recipName = recipient.getGameProfile().getName();
                    if (!owner.equals(recip) && !owner.equalsIgnoreCase(recipName)) {
                        return false;
                    }
                }
            }
        } catch (Throwable ignored) {
            // component may be unavailable mid-load
        }

        return true;
    }

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        markedUuid = null;
    }

    @Override
    public void serverTick() {
        // mark is sticky until re-marked or clear
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        if (markedUuid != null) {
            tag.putUUID("Marked", markedUuid);
        }
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        if (tag.hasUUID("Marked")) {
            markedUuid = tag.getUUID("Marked");
        } else {
            markedUuid = null;
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
