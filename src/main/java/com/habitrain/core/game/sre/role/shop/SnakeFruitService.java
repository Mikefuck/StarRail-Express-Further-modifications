package com.habitrain.core.game.sre.role.shop;

import com.habitrain.core.game.sre.modifier.virtue.TemperanceVirtue;
import com.habitrain.core.game.sre.role.HabiRoleItems;
import com.habitrain.core.game.sre.role.HabiRoles;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.event.OnGameEnd;
import io.wifi.starrailexpress.event.OnGameStarted;
import io.wifi.starrailexpress.event.OnPlayerDeath;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.game.ShopContent;
import io.wifi.starrailexpress.util.ShopEntry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.events.ModdedRoleAssigned;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import static com.habitrain.core.game.sre.role.shop.SnakeFruitPricePolicy.Faction;

/** Round census and red-apple application; all rolls and validation run on the server. */
public final class SnakeFruitService {
    private static final Map<ServerLevel, Map<UUID, Faction>> OPENING = new WeakHashMap<>();
    private static final Map<ServerLevel, ApplePurchaseCooldown> PURCHASES = new WeakHashMap<>();
    private static boolean registered;

    private SnakeFruitService() {}

    public static void init() {
        if (registered) return;
        registered = true;
        OnGameStarted.EVENT.register(level -> {
            clearTargets(level);
            PURCHASES.remove(level);
            Map<UUID, Faction> roster = new HashMap<>();
            SREGameWorldComponent.KEY.get(level).getRoles().forEach((id, role) -> roster.put(id, faction(role)));
            OPENING.put(level, Map.copyOf(roster));
        });
        OnGameEnd.EVENT.register((level, game) -> {
            clearTargets(level);
            PURCHASES.remove(level);
            OPENING.remove(level);
        });
        OnPlayerDeath.EVENT.register((victim, reason) -> AppleShopComponent.KEY.get(victim).clear());
        ModdedRoleAssigned.EVENT.register((player, role) -> AppleShopComponent.KEY.get(player).clear());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            OPENING.clear();
            PURCHASES.clear();
        });

        // Client SUCCESS sends exactly one entity interaction; PASS would also fall through to eating.
        UseEntityCallback.EVENT.register((player, world, hand, target, hit) ->
                world.isClientSide && target instanceof net.minecraft.world.entity.player.Player
                        && HabiRoleItems.isRedApple(player.getItemInHand(hand))
                        ? InteractionResult.SUCCESS : InteractionResult.PASS);
        // Tagged apples are tools, including when dropped or held by another role.
        UseItemCallback.EVENT.register((player, world, hand) -> HabiRoleItems.isRedApple(player.getItemInHand(hand))
                ? InteractionResultHolder.fail(player.getItemInHand(hand))
                : InteractionResultHolder.pass(player.getItemInHand(hand)));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> HabiRoleItems.isRedApple(player.getItemInHand(hand))
                ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    public static long purchaseCooldownTicks(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return 0;
        var cooldown = PURCHASES.get(level);
        return cooldown == null ? 0 : cooldown.remainingTicks(player.getUUID(), level.getGameTime());
    }

    public static void recordApplePurchase(Player player) {
        if (player.level() instanceof ServerLevel level) {
            PURCHASES.computeIfAbsent(level, ignored -> new ApplePurchaseCooldown())
                    .recordPurchase(player.getUUID(), level.getGameTime());
        }
    }

    public static Faction faction(SRERole role) {
        if (role == null || role.isNeutrals() || role.isNeutralForKiller()) return Faction.NEUTRAL;
        if (role.canUseKiller()) return Faction.KILLER;
        return role.isInnocent() ? Faction.GOOD : Faction.NEUTRAL;
    }

    public static InteractionResult use(ServerPlayer user, Entity entity, InteractionHand hand) {
        if (!HabiRoleItems.isRedApple(user.getItemInHand(hand))) return InteractionResult.PASS;
        if (!(entity instanceof ServerPlayer target) || user == target || user.level() != target.level()
                || !HabiRoles.isHabiRole(user, HabiRoles.SNAKE_FRUIT_ID)
                || !GameUtils.isPlayerAliveAndSurvival(user) || !GameUtils.isPlayerAliveAndSurvival(target)
                || !user.canInteractWithEntity(target.getBoundingBox(), 0.0) || !user.hasLineOfSight(target)) {
            return fail(user, "invalid_target");
        }
        ServerLevel level = user.serverLevel();
        var game = SREGameWorldComponent.KEY.get(level);
        var role = game.getRole(target);
        if (!game.isRunning() || role == null) return fail(user, "invalid_target");
        List<ShopEntry> entries = ShopContent.getShopEntries(role.identifier());
        if (entries == null || entries.isEmpty()) return fail(user, "empty_shop");
        double chance = chance(level, faction(role));
        Map<Integer, ApplePriceState.Quote<AppleShopSignature>> prices = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            ShopEntry entry = entries.get(i);
            if (entry.price() < 0 || entry.stack().isEmpty() || entry.currency() != ShopEntry.Currency.MONEY) continue;
            int normal = TemperanceVirtue.effectiveBuyPrice(target, entry);
            if (normal < 0) continue;
            int price = SnakeFruitPricePolicy.rollPrice(normal, chance, target.getRandom().nextDouble(),
                    target.getRandom()::nextInt);
            prices.put(i, new ApplePriceState.Quote<>(AppleShopSignature.of(entry), normal, price));
        }
        if (prices.isEmpty()) return fail(user, "empty_shop");
        AppleShopComponent.KEY.get(target).apply(role.identifier().toString(), entries, prices);
        user.getItemInHand(hand).shrink(1);
        target.displayClientMessage(Component.translatable("message.habitrain_core.snake_fruit.affected"), true);
        return InteractionResult.SUCCESS;
    }

    private static double chance(ServerLevel level, Faction target) {
        Map<UUID, Faction> roster = OPENING.get(level);
        if (roster == null || target == Faction.NEUTRAL) return 0.5;
        int goodInitial = 0, killerInitial = 0, goodAlive = 0, killerAlive = 0;
        for (var member : roster.entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(member.getKey());
            boolean alive = player != null && player.level() == level && GameUtils.isPlayerAliveAndSurvival(player);
            if (member.getValue() == Faction.GOOD) {
                goodInitial++;
                if (alive) goodAlive++;
            } else if (member.getValue() == Faction.KILLER) {
                killerInitial++;
                if (alive) killerAlive++;
            }
        }
        return SnakeFruitPricePolicy.increaseChance(target, goodAlive, goodInitial, killerAlive, killerInitial);
    }

    private static InteractionResult fail(ServerPlayer player, String reason) {
        player.displayClientMessage(Component.translatable("message.habitrain_core.snake_fruit." + reason)
                .withStyle(ChatFormatting.RED), true);
        return InteractionResult.FAIL;
    }

    private static void clearTargets(ServerLevel level) {
        for (ServerPlayer player : level.players()) AppleShopComponent.KEY.get(player).clear();
    }
}
