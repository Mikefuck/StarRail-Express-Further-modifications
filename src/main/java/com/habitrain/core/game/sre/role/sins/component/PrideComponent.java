package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.KillerKnifeShopEntry;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 傲慢：复制商店快照、人群光环武器免疫、击杀破防。
 */
public final class PrideComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<PrideComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_pride"), PrideComponent.class);

    public static final int COPY_SHOP_CD_SECONDS = 60;
    public static final double AURA_RANGE = 8.0;
    public static final int AURA_OTHERS_NEEDED = 3;
    public static final int BREAK_IMMUNE_SECONDS = 5;

    /** Max-1 role: active snapshot for {@code PrideRole#getShopEntries()}. */
    private static volatile List<SnapshotLine> ACTIVE_SNAPSHOT = List.of();

    private final Player player;
    private long breakImmuneUntilGameTime;
    private boolean weaponImmune;
    private final List<SnapshotLine> shopSnapshot = new ArrayList<>();
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

    public boolean isWeaponImmune() {
        return weaponImmune;
    }

    public ResourceLocation getCopiedShopRoleId() {
        return copiedShopRoleId;
    }

    public void setBreakImmuneUntil(long gameTimeExclusive) {
        this.breakImmuneUntilGameTime = gameTimeExclusive;
        KEY.sync(player);
    }

    public void onPrideKill(ServerLevel level) {
        if (level == null) return;
        setBreakImmuneUntil(level.getGameTime() + BREAK_IMMUNE_SECONDS * 20L);
        weaponImmune = false;
    }

    public static boolean isPrideWeaponImmune(Player target) {
        if (target == null) return false;
        try {
            PrideComponent c = KEY.get(target);
            return c != null && c.weaponImmune;
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<ShopEntry> getActiveShopEntries() {
        return rebuildShopEntries(ACTIVE_SNAPSHOT);
    }

    public static boolean useCopyShop(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (self == null || self.isSpectator()) return false;
        if (!(self.level() instanceof ServerLevel level)) return false;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        if (game == null || !game.isRole(self, SevenSins.PRIDE)) return false;

        ServerPlayer target = resolveTarget(self, ctx.target());
        if (target == null || target.getUUID().equals(self.getUUID())) {
            self.displayClientMessage(Component.literal("§c[傲慢] 未找到可复制的目标。"), true);
            return false;
        }

        SRERole targetRole = game.getRole(target);
        if (targetRole == null) {
            self.displayClientMessage(Component.literal("§c[傲慢] 目标没有可复制的商店。"), true);
            return false;
        }

        List<ShopEntry> entries;
        try {
            entries = targetRole.getShopEntries();
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[Pride] getShopEntries failed for {}", targetRole.getIdentifier(), t);
            entries = null;
        }
        if (entries == null || entries.isEmpty()) {
            PrideComponent c = KEY.get(self);
            c.applySnapshot(null, List.of());
            self.displayClientMessage(
                    Component.literal("§e[傲慢] 已清空商店快照（目标无基础商店）。"),
                    true
            );
            return true;
        }

        List<SnapshotLine> lines = new ArrayList<>();
        for (ShopEntry entry : entries) {
            if (entry == null) continue;
            ItemStack stack = entry.stack();
            if (stack == null || stack.isEmpty()) continue;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId == null) continue;
            String typeName = "TOOL";
            try {
                if (entry.type() != null) {
                    typeName = entry.type().name();
                }
            } catch (Throwable ignored) {}
            lines.add(new SnapshotLine(itemId.toString(), entry.price(), typeName));
        }

        PrideComponent c = KEY.get(self);
        c.applySnapshot(targetRole.getIdentifier(), lines);
        self.displayClientMessage(
                Component.literal("§a[傲慢] 已复制商店快照（" + lines.size() + " 项）。"),
                true
        );
        return true;
    }

    private void applySnapshot(ResourceLocation roleId, List<SnapshotLine> lines) {
        shopSnapshot.clear();
        if (lines != null) {
            shopSnapshot.addAll(lines);
        }
        copiedShopRoleId = roleId;
        ACTIVE_SNAPSHOT = List.copyOf(shopSnapshot);
        KEY.sync(player);
    }

    private static List<ShopEntry> rebuildShopEntries(List<SnapshotLine> lines) {
        List<ShopEntry> shop = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return shop;
        }
        for (SnapshotLine line : lines) {
            if (line == null || line.itemId == null || line.itemId.isEmpty()) continue;
            ResourceLocation id = ResourceLocation.tryParse(line.itemId);
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null) continue;
            ItemStack stack = item.getDefaultInstance();
            if (stack.isEmpty()) continue;

            String path = id.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("knife") && !path.contains("throwing")) {
                // Preserve murder knife durability stamping when the snapshot was a knife.
                try {
                    shop.add(new KillerKnifeShopEntry(line.price));
                    continue;
                } catch (Throwable ignored) {}
            }

            ShopEntry.Type type = parseType(line.typeName);
            shop.add(new ShopEntry(stack, line.price, type));
        }
        return shop;
    }

    private static ShopEntry.Type parseType(String name) {
        if (name == null || name.isEmpty()) return ShopEntry.Type.TOOL;
        try {
            return ShopEntry.Type.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return ShopEntry.Type.TOOL;
        }
    }

    private static ServerPlayer resolveTarget(ServerPlayer self, UUID targetId) {
        if (targetId != null) {
            Player p = self.level().getPlayerByUUID(targetId);
            if (p instanceof ServerPlayer sp && !sp.isSpectator()) return sp;
        }
        Vec3 eye = self.getEyePosition();
        Vec3 look = self.getLookAngle();
        double range = AURA_RANGE;
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

    @Override
    public void init() {
        clear();
    }

    @Override
    public void clear() {
        breakImmuneUntilGameTime = 0;
        weaponImmune = false;
        shopSnapshot.clear();
        copiedShopRoleId = null;
        ACTIVE_SNAPSHOT = List.of();
    }

    @Override
    public void serverTick() {
        if (player.level().isClientSide) return;
        if (!(player instanceof ServerPlayer self)) return;
        if (!(self.level() instanceof ServerLevel level)) return;

        SREGameWorldComponent game = SREGameWorldComponent.KEY.get(level);
        boolean isPride = game != null && SevenSins.PRIDE != null && game.isRole(self, SevenSins.PRIDE);
        if (!isPride || self.isSpectator()) {
            if (weaponImmune) {
                weaponImmune = false;
            }
            return;
        }

        int others = countOtherAliveNearby(self, level, game);
        boolean aura = others >= AURA_OTHERS_NEEDED;
        long now = level.getGameTime();
        boolean broken = now < breakImmuneUntilGameTime;
        boolean nextImmune = aura && !broken;
        weaponImmune = nextImmune;

        if (aura) {
            // Refresh short glowing so it does not linger after leaving the crowd.
            self.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false, true));
        }
    }

    private static int countOtherAliveNearby(ServerPlayer self, ServerLevel level, SREGameWorldComponent game) {
        double rangeSq = AURA_RANGE * AURA_RANGE;
        int count = 0;
        boolean blackout = !BlackoutRoleManager.getAllAlive(level).isEmpty();
        for (ServerPlayer other : level.players()) {
            if (other == self || other.isSpectator()) continue;
            if (self.distanceToSqr(other) > rangeSq) continue;
            if (!isAliveParticipant(level, game, other, blackout)) continue;
            count++;
        }
        return count;
    }

    private static boolean isAliveParticipant(ServerLevel level, SREGameWorldComponent game,
                                             ServerPlayer p, boolean blackout) {
        if (p == null || p.isSpectator()) return false;
        if (blackout) {
            return BlackoutRoleManager.isAlive(level, p.getUUID());
        }
        if (game == null || !game.isRunning()) return false;
        return game.getRole(p) != null;
    }

    @Override
    public void writeToSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        tag.putLong("BreakImmuneUntil", breakImmuneUntilGameTime);
        tag.putBoolean("WeaponImmune", weaponImmune);
        if (copiedShopRoleId != null) {
            tag.putString("CopiedShopRoleId", copiedShopRoleId.toString());
        }
        ListTag list = new ListTag();
        for (SnapshotLine line : shopSnapshot) {
            CompoundTag e = new CompoundTag();
            e.putString("Id", line.itemId);
            e.putInt("Price", line.price);
            e.putString("Type", line.typeName != null ? line.typeName : "TOOL");
            list.add(e);
        }
        tag.put("ShopSnapshot", list);
    }

    @Override
    public void readFromSyncNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        breakImmuneUntilGameTime = tag.getLong("BreakImmuneUntil");
        weaponImmune = tag.getBoolean("WeaponImmune");
        if (tag.contains("CopiedShopRoleId")) {
            copiedShopRoleId = ResourceLocation.tryParse(tag.getString("CopiedShopRoleId"));
        } else {
            copiedShopRoleId = null;
        }
        shopSnapshot.clear();
        if (tag.contains("ShopSnapshot", Tag.TAG_LIST)) {
            ListTag list = tag.getList("ShopSnapshot", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag e = list.getCompound(i);
                shopSnapshot.add(new SnapshotLine(
                        e.getString("Id"),
                        e.getInt("Price"),
                        e.contains("Type") ? e.getString("Type") : "TOOL"
                ));
            }
        }
        ACTIVE_SNAPSHOT = List.copyOf(shopSnapshot);
    }

    @Override
    public void writeToNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        writeToSyncNbt(tag, registryLookup);
    }

    @Override
    public void readFromNbt(@NotNull CompoundTag tag, HolderLookup.Provider registryLookup) {
        readFromSyncNbt(tag, registryLookup);
    }

    private record SnapshotLine(String itemId, int price, String typeName) {}
}
