package com.habitrain.core.betel;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 槟榔叶交互处理器
 * 玩家右键槟榔叶后给予缓慢3效果，3秒后解除缓慢并获得一颗槟榔（100%）
 */
public class BetelLeafHandler {
    private static final Map<UUID, HarvestTask> activeHarvests = new HashMap<>();
    private static final int HARVEST_TICKS = 60; // 3秒 (20 ticks/秒 × 3)

    private static final String BETEL_LEAF_ID = "betel-nut-mod:betel_palm_leaves";
    private static final String BETEL_NUT_ID = "betel-nut-mod:roasted_betel_nut";

    private static Block betelLeafBlock = null;
    private static boolean blockChecked = false;

    public static void register() {
        UseBlockCallback.EVENT.register(BetelLeafHandler::onUseBlock);
        // 注册END_SERVER_TICK重施缓慢
        // betel-nut-mod 每tick会清除MOVEMENT_SLOWDOWN，必须在实体同步前重新施加
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (activeHarvests.isEmpty()) return;
            for (net.minecraft.server.level.ServerLevel world : server.getAllLevels()) {
                applyHarvestSlowness(world);
            }
        });
    }

    /**
     * 对活跃采集任务的玩家重施缓慢（应对槟榔mod清除SLOWNESS）
     */
    private static void applyHarvestSlowness(Level world) {
        long currentTick = world.getGameTime();
        for (HarvestTask task : activeHarvests.values()) {
            // 只处理属于这个世界的任务
            if (!task.worldKey.equals(world.dimension())) continue;

            // 还没到完成时间的，需要保持缓慢效果
            if (currentTick - task.startTick < HARVEST_TICKS) {
                Player player = world.getPlayerByUUID(task.playerUuid);
                if (player instanceof ServerPlayer serverPlayer) {
                    // 重施缓慢3（持续70ticks，比采集时间略长作为缓冲）
                    serverPlayer.addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SLOWDOWN, HARVEST_TICKS + 10, 2, false, true, true));
                }
            }
        }
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand,
                                                 BlockHitResult hitResult) {
        if (world.isClientSide()) return InteractionResult.PASS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

        BlockPos pos = hitResult.getBlockPos();
        Block block = world.getBlockState(pos).getBlock();

        if (!isBetelLeafBlock(block)) return InteractionResult.PASS;

        UUID uuid = player.getUUID();

        // 检查玩家本局是否已刷出过槟榔任务，只有刷新过才能采集
        // 一旦本局刷新过，即使任务已完成也可在任何时候采集槟榔（游戏结束重置）
        if (!BetelQuestState.hasQuestBeenAssigned(uuid)) {
            return InteractionResult.PASS;
        }

        // 检查背包中槟榔数量是否已满
        if (getBetelNutCount(serverPlayer) >= 5) {
            serverPlayer.displayClientMessage(Component.literal("§c你手上已经拿满了槟榔了"), true);
            return InteractionResult.FAIL;
        }

        // 防止重复点击采集
        if (activeHarvests.containsKey(uuid)) {
            serverPlayer.displayClientMessage(Component.literal("§7正在采集中，请稍候..."), true);
            return InteractionResult.FAIL;
        }

        // 给予缓慢3效果（持续70ticks，比采集时间略长作为缓冲）
        serverPlayer.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, HARVEST_TICKS + 10, 2, false, true, true));
        serverPlayer.displayClientMessage(Component.literal("§7正在采集槟榔叶... 3秒后完成"), true);

        // 记录采集任务（包含世界key，用于tick时匹配正确世界）
        activeHarvests.put(uuid, new HarvestTask(uuid, pos, world.dimension(), world.getGameTime()));

        return InteractionResult.FAIL;
    }

    /**
     * 每tick检查采集任务是否完成
     */
    public static void tickHarvests(Level world) {
        if (activeHarvests.isEmpty()) return;

        long currentTick = world.getGameTime();
        Iterator<Map.Entry<UUID, HarvestTask>> it = activeHarvests.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, HarvestTask> entry = it.next();
            UUID uuid = entry.getKey();
            HarvestTask task = entry.getValue();

            // 只处理属于这个世界的任务
            if (!task.worldKey.equals(world.dimension())) continue;

            if (currentTick - task.startTick < HARVEST_TICKS) continue;

            it.remove();

            Player player = world.getPlayerByUUID(uuid);
            if (!(player instanceof ServerPlayer serverPlayer)) continue;
            if (!player.isAlive()) continue;

            // 检查槟榔叶是否还在
            if (!world.getBlockState(task.leafPos).is(betelLeafBlock)) {
                serverPlayer.displayClientMessage(Component.literal("§7采集被中断：槟榔叶已消失"), true);
                serverPlayer.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                continue;
            }

            // 检查玩家是否离得太远
            if (!player.blockPosition().closerThan(task.leafPos, 5.0)) {
                serverPlayer.displayClientMessage(Component.literal("§7采集被中断：你离槟榔叶太远了"), true);
                serverPlayer.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                continue;
            }

            // 解除缓慢效果
            serverPlayer.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);

            // 检查背包中槟榔数量是否已满
            if (getBetelNutCount(serverPlayer) >= 5) {
                serverPlayer.displayClientMessage(Component.literal("§c你手上已经拿满了槟榔了，无法继续采集"), true);
                continue;
            }

            // 100%获得槟榔
            ItemStack betelNut = createBetelNut();
            if (betelNut.isEmpty()) {
                HabiTrainCore.LOGGER.error("无法创建槟榔物品，物品ID可能未注册: {}", BETEL_NUT_ID);
                continue;
            }
            if (!serverPlayer.getInventory().add(betelNut)) {
                serverPlayer.drop(betelNut, false);
            }
            // 播放获得槟榔音效（玩家位置，周围玩家可听到）
            serverPlayer.serverLevel().playSound(
                    null,
                    serverPlayer.blockPosition(),
                    HabiTrainCore.BETEL_NUT_GET_SOUND,
                    SoundSource.PLAYERS,
                    1.0f,
                    1.0f
            );
            serverPlayer.displayClientMessage(Component.literal("§a§o你从槟榔叶中获取了一颗槟榔"), true);
            HabiTrainCore.LOGGER.info("玩家 {} 从槟榔叶中获得了一颗槟榔", player.getName().getString());
        }
    }

    public static void clearAllHarvests() {
        activeHarvests.clear();
    }

    private static boolean isBetelLeafBlock(Block block) {
        if (!blockChecked) {
            try {
                betelLeafBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(BETEL_LEAF_ID));
                if (betelLeafBlock == null || betelLeafBlock == Blocks.AIR) {
                    HabiTrainCore.LOGGER.warn("槟榔叶方块未找到: {}", BETEL_LEAF_ID);
                    betelLeafBlock = null;
                }
            } catch (Exception e) {
                HabiTrainCore.LOGGER.error("查找槟榔叶方块时出错: {}", BETEL_LEAF_ID, e);
                betelLeafBlock = null;
            }
            blockChecked = true;
        }
        return betelLeafBlock != null && block == betelLeafBlock;
    }

    private static ItemStack createBetelNut() {
        try {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(BETEL_NUT_ID));
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return new ItemStack(item);
            }
            HabiTrainCore.LOGGER.warn("槟榔物品未注册，检查ID: {}", BETEL_NUT_ID);
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("查找槟榔物品时出错: {}", BETEL_NUT_ID, e);
        }
        return ItemStack.EMPTY;
    }

    /**
     * 统计玩家背包中 roasted_betel_nut 的数量
     */
    private static int getBetelNutCount(ServerPlayer player) {
        try {
            var betelNutItem = BuiltInRegistries.ITEM.get(ResourceLocation.parse(BETEL_NUT_ID));
            if (betelNutItem == null || betelNutItem == net.minecraft.world.item.Items.AIR) return 0;

            int count = 0;
            for (var stack : player.getInventory().items) {
                if (!stack.isEmpty() && stack.getItem() == betelNutItem) {
                    count += stack.getCount();
                }
            }
            return count;
        } catch (Exception e) {
            HabiTrainCore.LOGGER.error("统计槟榔数量时出错", e);
            return 0;
        }
    }

    private static class HarvestTask {
        final UUID playerUuid;
        final BlockPos leafPos;
        final ResourceKey<Level> worldKey;
        final long startTick;

        HarvestTask(UUID playerUuid, BlockPos leafPos, ResourceKey<Level> worldKey, long startTick) {
            this.playerUuid = playerUuid;
            this.leafPos = leafPos;
            this.worldKey = worldKey;
            this.startTick = startTick;
        }
    }
}
