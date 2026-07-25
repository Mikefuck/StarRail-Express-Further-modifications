package com.habitrain.core;

import com.habitrain.core.api.ItemReclaimHelper;
import com.habitrain.core.api.TaskCategory;
import com.habitrain.core.api.TaskRegistry;
import com.habitrain.core.task.BackpackQuestState;
import com.habitrain.core.task.BackpackSearchHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class BuiltinTaskRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|BuiltinTaskRegistrar");

    private static final int CAT_BLOCK_TYPE_ID = 13;
    private static final int BACKPACK_TYPE_ID = 15;
    private static final int NO_BLOCK_TYPE_ID = -1;

    public static final String[] CAT_BLOCK_IDS = {
        "yuushya:british_shorthair", "yuushya:white_cat", "yuushya:black_cat",
        "yuushya:ragdoll", "yuushya:calico", "yuushya:siamese", "yuushya:tabby"
    };

    private static Set<Block> cachedCatBlocks = null;

    public static void register() {
        // test_grass 已于 2026-07-10 从任务池移除（调试任务误入派发）

        TaskRegistry.register(HabiTrainCore.MOD_ID, "pet_cat", builder -> builder
            .displayName("摸猫猫")
            .category(TaskCategory.MURDER)
            .weight(1.0f)
            .blockTypeId(CAT_BLOCK_TYPE_ID)
            .instinctColor(255, 182, 193, 200)
            .scanBlockIds(CAT_BLOCK_IDS)
            .onAssign((player, task) -> {
                task.setMaxProgress(100);
            })
            .onTick((player, task) -> {
                if (task.getProgress() >= task.getMaxProgress()) return;

                Set<Block> currentCatBlocks = resolveCatBlocks();

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
                        task.setProgress(Math.min(task.getProgress() + 1, task.getMaxProgress()));
                        return;
                    }
                }

                if (task.getProgress() > 0) {
                    task.setProgress(Math.max(0, task.getProgress() - 2));
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
            .canAssign((player, task) ->
                !BackpackQuestState.hasCompleted(player.getUUID()))
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
                BackpackSearchHandler.stopSearching(serverPlayer.getUUID());
                serverPlayer.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
                ItemStack granted = LootHelper.giveRandomBackpackItem(serverPlayer);
                if (granted != null) {
                    ItemReclaimHelper.tagGrantedItem(granted, "habitrain_core:search_backpack");
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
                List<ServerPlayer> nearby = serverPlayer.serverLevel()
                        .getEntitiesOfClass(ServerPlayer.class, searchBox,
                                p -> p != serverPlayer && p.isAlive());

                Vec3 lookVec = serverPlayer.getLookAngle();
                boolean eyeContact = false;

                for (ServerPlayer otherPlayer : nearby) {
                    Vec3 toOther = otherPlayer.getEyePosition().subtract(eyePos);
                    double distance = toOther.length();
                    if (distance > 3.0) continue;

                    Vec3 dirToOther = toOther.normalize();
                    Vec3 otherLookVec = otherPlayer.getLookAngle();
                    Vec3 dirToThis = eyePos.subtract(otherPlayer.getEyePosition()).normalize();

                    double dotThis = lookVec.dot(dirToOther);
                    double dotOther = otherLookVec.dot(dirToThis);

                    if (dotThis > 0.8 && dotOther > 0.8) {
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

    public static Set<Block> resolveCatBlocks() {
        if (cachedCatBlocks != null) {
            return cachedCatBlocks;
        }
        Set<Block> blocks = Arrays.stream(CAT_BLOCK_IDS)
            .map(id -> BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)))
            .filter(block -> block != Blocks.AIR)
            .collect(Collectors.toSet());
        if (blocks.isEmpty()) {
            LOGGER.warn("yuushya mod not installed, cat task will have no scan blocks");
        }
        cachedCatBlocks = blocks;
        return blocks;
    }
}
