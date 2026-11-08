package com.habitrain.core;

import com.habitrain.core.game.sre.task.LootHelper;
import com.habitrain.core.api.ItemReclaimHelper;
import com.habitrain.core.api.MatchRestStateApi;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.task.BackpackSearchHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class BuiltinTaskRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|BuiltinTaskRegistrar");

    private static final int CAT_BLOCK_TYPE_ID = 13;
    private static final int BACKPACK_TYPE_ID = 15;
    private static final int NO_BLOCK_TYPE_ID = -1;
    private static final ResourceLocation BACKPACK_BLOCK_ID =
            ResourceLocation.fromNamespaceAndPath("decocraft", "backpack_red");

    /**
     * Yuushya Townscape 2.3.0 {@code DecoBlock} cats from
     * {@code data/yuushya/register/block_living_being.json}.
     * These are named blocks ({@code yuushya:british_shorthair}), not numeric
     * {@code yuushya:442} IDs from older wiki pages.
     */
    public static final String[] CAT_BLOCK_IDS = {
        "yuushya:british_shorthair",
        "yuushya:orange_cat",
        "yuushya:white_cat",
        "yuushya:black_cat",
        "yuushya:ragdoll",
        "yuushya:calico",
        "yuushya:jellie",
        "yuushya:siamese",
        "yuushya:tabby"
    };

    private static volatile Set<Block> cachedCatBlocks = null;
    private static volatile Boolean cachedBackpackBlockPresent = null;

    public static void register() {
        // test_grass 已于 2026-07-10 从任务池移除（调试任务误入派发）

        TaskRegistry.register(HabiTrainCore.MOD_ID, "pet_cat", builder -> builder
            .displayName("摸猫猫")
            .category(TaskCategory.MURDER)
            .weight(1.0f)
            .blockTypeId(CAT_BLOCK_TYPE_ID)
            .instinctColor(255, 182, 193, 200)
            .scanBlockIds(CAT_BLOCK_IDS)
            // CORE-05：当 yuushya 猫方块未安装时禁止派发该任务
            .canAssignWithoutInstance(player -> !resolveCatBlocks().isEmpty())
            .canAssign((player, task) -> !resolveCatBlocks().isEmpty())
            .onAssign((player, task) -> {
                task.setMaxProgress(100);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;
                if (player.tickCount % 2 != 0) return;

                Set<Block> currentCatBlocks = resolveCatBlocks();
                if (currentCatBlocks.isEmpty()) return;

                Vec3 eyePos = player.getEyePosition();
                Vec3 lookVec = player.getLookAngle();
                double reach = 5.0;
                Vec3 targetPos = eyePos.add(
                    lookVec.x * reach,
                    lookVec.y * reach,
                    lookVec.z * reach
                );

                BlockHitResult hitResult = player.level().clip(
                    new ClipContext(
                        eyePos, targetPos,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        player
                    )
                );

                if (hitResult.getType() == HitResult.Type.BLOCK) {
                    Block lookedBlock = player.level().getBlockState(hitResult.getBlockPos()).getBlock();
                    if (currentCatBlocks.contains(lookedBlock)) {
                        task.setProgress(Math.min(task.getProgress() + 2, task.getMaxProgress()));
                        return;
                    }
                }

                if (task.getProgress() > 0) {
                    task.setProgress(Math.max(0, task.getProgress() - 4));
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                // no-op: remove completion toast
            })
        );

        TaskRegistry.register(HabiTrainCore.MOD_ID, "search_backpack", builder -> builder
            .displayName("翻找一下自己的背包...")
            .category(TaskCategory.MURDER)
            .weight(1.0f)
            .blockTypeId(BACKPACK_TYPE_ID)
            .instinctColor(139, 90, 43, 200)
            .scanBlockIds("decocraft:backpack_red")
            // CORE-05：当 decocraft:backpack_red 未安装时禁止派发该任务
            .canAssignWithoutInstance(player ->
                isBackpackBlockRegistered() && (player == null || !BackpackQuestState.hasCompleted(player.getUUID())))
            .canAssign((player, task) ->
                isBackpackBlockRegistered() && (player == null || !BackpackQuestState.hasCompleted(player.getUUID())))
            .onAssign((player, task) -> {
                task.setMaxProgress(120);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;

                if (BackpackSearchHandler.isSearching(player.getUUID())) {
                    task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
            .onComplete((player, task) -> {
                if (!(player instanceof ServerPlayer serverPlayer)) return;

                BackpackQuestState.markCompleted(serverPlayer.getUUID());
                BackpackSearchHandler.stopSearching(serverPlayer);
                LootHelper.giveRandomBackpackItem(serverPlayer, "habitrain_core:search_backpack", task);
            })
            .onRemove((player, task) -> {
                if (player instanceof ServerPlayer serverPlayer) {
                    BackpackSearchHandler.stopSearching(serverPlayer);
                } else if (player != null) {
                    BackpackSearchHandler.stopSearching(player.getUUID());
                }
            })
            .onReclaim((player, task) -> ItemReclaimHelper.reclaim(player, "habitrain_core:search_backpack"))
        );

        TaskRegistry.register(HabiTrainCore.MOD_ID, "look_my_eyes", builder -> builder
            .displayName("LOOK MY EYES")
            .category(TaskCategory.MURDER)
            .weight(1.0f)
            .blockTypeId(NO_BLOCK_TYPE_ID)
            .instinctColor(255, 105, 180, 200)
            .onAssign((player, task) -> {
                // 7 秒对视：maxProgress=140；下方每 5 tick 检测时 +5，等效每 tick +1
                task.setMaxProgress(140);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;
                if (!(player instanceof ServerPlayer serverPlayer)) return;

                // Throttle: only run the expensive AABB + getEntitiesOfClass every 5 ticks
                if (serverPlayer.tickCount % 5 != 0) return;

                Vec3 eyePos = serverPlayer.getEyePosition();
                AABB searchBox = new AABB(eyePos.x - 3.0, eyePos.y - 3.0, eyePos.z - 3.0,
                                           eyePos.x + 3.0, eyePos.y + 3.0, eyePos.z + 3.0);
                // CORE-15：排除旁观者、隐身玩家与淘汰休息区玩家，并要求无方块视线遮挡
                List<ServerPlayer> nearby = serverPlayer.serverLevel()
                        .getEntitiesOfClass(ServerPlayer.class, searchBox,
                                p -> p != serverPlayer
                                        && p.isAlive()
                                        && !p.isSpectator()
                                        && !p.isInvisible()
                                        && !MatchRestStateApi.isResting(p));

                Vec3 lookVec = serverPlayer.getLookAngle();
                boolean eyeContact = false;

                for (ServerPlayer otherPlayer : nearby) {
                    Vec3 toOther = otherPlayer.getEyePosition().subtract(eyePos);
                    double distance = toOther.length();
                    if (distance <= 1.0e-4 || distance > 3.0) continue;

                    Vec3 dirToOther = toOther.scale(1.0 / distance);
                    Vec3 otherLookVec = otherPlayer.getLookAngle();
                    Vec3 dirToThis = dirToOther.scale(-1.0);

                    double dotThis = lookVec.dot(dirToOther);
                    double dotOther = otherLookVec.dot(dirToThis);

                    if (dotThis > 0.8 && dotOther > 0.8 && serverPlayer.hasLineOfSight(otherPlayer)) {
                        eyeContact = true;
                        break;
                    }
                }

                if (eyeContact) {
                    // +5 抵消 5-tick 节流，使有效进度速率 = 1/tick → 140 tick = 7 秒
                    task.setProgress(Math.min(task.getProgress() + 5, task.getMaxProgress()));
                } else {
                    if (task.getProgress() > 0) {
                        task.setProgress(0);
                    }
                }
            })
            .completionChecker((player, task) ->
                task.getProgress() >= task.getMaxProgress())
            // onComplete: no sound (look_my_eyes SFX removed)
        );
    }

    public static boolean isBackpackBlockRegistered() {
        Boolean cached = cachedBackpackBlockPresent;
        if (cached != null) {
            return cached;
        }
        Block block = BuiltInRegistries.BLOCK.get(BACKPACK_BLOCK_ID);
        boolean present = block != null && block != Blocks.AIR;
        if (!present) {
            LOGGER.warn("decocraft:backpack_red block not registered; search_backpack task will be disabled");
        }
        cachedBackpackBlockPresent = present;
        return present;
    }

    public static Set<Block> resolveCatBlocks() {
        Set<Block> cached = cachedCatBlocks;
        if (cached != null) {
            return cached;
        }
        Set<Block> blocks = Collections.unmodifiableSet(Arrays.stream(CAT_BLOCK_IDS)
            .map(id -> BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)))
            .filter(block -> block != null && block != Blocks.AIR)
            .collect(Collectors.toSet()));
        if (blocks.isEmpty()) {
            LOGGER.warn("yuushya mod not installed or cat block ids unresolved, cat task will have no scan blocks");
        }
        // CORE-05：即使为空集也写入缓存，避免未安装 yuushya 时每 2 tick 刷屏打印 WARN
        cachedCatBlocks = blocks;
        return blocks;
    }
}
