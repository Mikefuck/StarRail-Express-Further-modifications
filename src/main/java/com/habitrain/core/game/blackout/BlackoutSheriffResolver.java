package com.habitrain.core.game.blackout;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.network.BlackoutAnnouncePayload;
import com.habitrain.core.util.SubtitleNotifier;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class BlackoutSheriffResolver {
    void applyVoteResult(ServerLevel level, BlackoutSheriffVoteManager.VoteResolution resolution) {
        if (level == null || resolution == null) return;
        if (resolution.winnerIds().isEmpty()) return;

        try {
            java.util.Random random = new java.util.Random(level.getRandom().nextLong());
            Map<UUID, ServerPlayer> playerMap = new HashMap<>();
            for (ServerPlayer player : level.players()) {
                playerMap.put(player.getUUID(), player);
            }

            var gameWorld = SREGameWorldComponent.KEY.get(level);

            for (int i = 0; i < resolution.winnerIds().size(); i++) {
                UUID winnerId = resolution.winnerIds().get(i);
                boolean wasKiller = resolution.winnerWasKillers().get(i);
                ServerPlayer player = playerMap.get(winnerId);
                if (player == null) continue;

                BlackoutRoleManager.Faction currentFaction =
                        BlackoutRoleManager.getFaction(level, player.getUUID());

                if (wasKiller || currentFaction == BlackoutRoleManager.Faction.BAD) {
                    BlackoutRoleManager.setSheriff(level, player.getUUID());

                    var revolverItem = BuiltInRegistries.ITEM
                            .get(ResourceLocation.parse("trainmurdermystery:revolver"));
                    if (revolverItem != null && revolverItem != net.minecraft.world.item.Items.AIR) {
                        ItemStack gun = new ItemStack(revolverItem, 1);
                        boolean added = player.getInventory().add(gun);
                        if (!added) {
                            player.drop(gun, false);
                        }
                        SubtitleNotifier.sendTop(player,
                                Component.literal("§6警长入场"),
                                Component.literal("§6你被票选为警长，获得了一把左轮手枪。"),
                                80);
                    }
                    HabiTrainCore.LOGGER.info("[SheriffVote] killer {} voted as sheriff, kept killer identity + given revolver",
                            player.getName().getString());
                } else {
                    io.wifi.starrailexpress.api.SRERole policeRole = BlackoutRoleManager.getRandomPoliceRole(random);
                    if (policeRole == null) continue;
                    BlackoutRoleManager.setSheriff(level, player.getUUID(), policeRole, null);

                    String roleName = policeRole.getName().getString();
                    String subtitle = policeRole.getDescription().getString();
                    String goal = policeRole.getGoal().getString();
                    ServerPlayNetworking.send(player, new BlackoutAnnouncePayload(
                            roleName,
                            subtitle,
                            goal,
                            BlackoutRoleManager.getRemainingBad(level),
                            BlackoutRoleManager.getRemainingGood(level)
                    ));

                    var shop = SREPlayerShopComponent.KEY.get(player);
                    if (shop != null) {
                        shop.addToBalance(200);
                    }
                    SubtitleNotifier.sendTop(player,
                            Component.literal("§6警长入场"),
                            Component.literal("§6你因为被票选为警长获得了 200 金币。"),
                            80);
                }
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("Failed to grant sheriff vote reward", e);
        }
    }
}
