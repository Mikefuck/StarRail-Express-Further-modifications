package com.habitrain.core.scene.item;

import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.MenuGateService;
import com.habitrain.core.game.sre.scene.SreSceneContextResolver;
import com.habitrain.core.scene.asset.SceneAssetDescriptor;
import com.habitrain.core.scene.model.SceneBounds;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.network.SceneEditorOpenS2C;
import com.habitrain.core.scene.network.SceneAssetManifestS2C;
import com.habitrain.core.scene.network.SceneSelectionStateS2C;
import com.habitrain.core.scene.server.SceneAssetStore;
import com.habitrain.core.scene.server.SceneSelectionSessionManager;
import com.habitrain.core.scene.server.SceneTransferService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 场景配置器管理道具（Shift+右键选点 A/B、右键空气/方块打开移动场景配置 GUI）。
 */
public final class SceneConfiguratorItem extends Item {
    public SceneConfiguratorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        if (!serverPlayer.hasPermissions(2)) {
            serverPlayer.sendSystemMessage(Component.literal("§c需要 OP2 权限才能使用移动场景配置器"));
            return InteractionResult.FAIL;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        String dimKey = serverLevel.dimension().location().toString();
        String mapKey = resolveToolMapKey(serverPlayer, serverLevel, dimKey);
        BlockPos clickedPos = context.getClickedPos();

        if (serverPlayer.isShiftKeyDown()) {
            // Shift + 右键方块：选点 A/B
            var res = SceneSelectionSessionManager.getInstance()
                    .handleShiftRightClickBlock(serverPlayer.getUUID(), dimKey, mapKey, clickedPos);

            switch (res.step()) {
                case SET_POINT_A -> {
                    serverPlayer.sendSystemMessage(Component.literal("§a[场景配置] 已设置选区点 A: ("
                            + clickedPos.getX() + ", " + clickedPos.getY() + ", " + clickedPos.getZ() + ")"));
                }
                case SET_POINT_B_COMPLETED -> {
                    SceneBounds bounds = res.bounds();
                    serverPlayer.sendSystemMessage(Component.literal("§a[场景配置] 已设置选区点 B: ("
                            + clickedPos.getX() + ", " + clickedPos.getY() + ", " + clickedPos.getZ() + ") - 选区已完成！大小: "
                            + bounds.sizeX() + "×" + bounds.sizeY() + "×" + bounds.sizeZ()
                            + " (" + bounds.totalSections() + " sections)"));
                }
                case RESET_AND_SET_POINT_A -> {
                    serverPlayer.sendSystemMessage(Component.literal("§e[场景配置] 重新开始选区，已设置新点 A: ("
                            + clickedPos.getX() + ", " + clickedPos.getY() + ", " + clickedPos.getZ() + ")"));
                }
                default -> {}
            }

            // 同步选区状态给客户端用于 HUD 与发光框渲染
            long blockCount = res.bounds().isEmpty() ? 0L : res.bounds().volume();
            ServerPlayNetworking.send(serverPlayer, SceneSelectionStateS2C.fromBounds(dimKey, mapKey, res.bounds(), blockCount));
            return InteractionResult.SUCCESS;
        } else {
            // 普通右键方块：打开配置 GUI
            openEditorScreen(serverPlayer, serverLevel, dimKey, mapKey);
            return InteractionResult.SUCCESS;
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        if (!serverPlayer.hasPermissions(2)) {
            serverPlayer.sendSystemMessage(Component.literal("§c需要 OP2 权限才能使用移动场景配置器"));
            return InteractionResultHolder.fail(stack);
        }

        ServerLevel serverLevel = (ServerLevel) level;
        String dimKey = serverLevel.dimension().location().toString();
        String mapKey = resolveToolMapKey(serverPlayer, serverLevel, dimKey);

        if (serverPlayer.isShiftKeyDown()) {
            // Shift + 右键空气：清除选区
            SceneSelectionSessionManager.getInstance().clear(serverPlayer.getUUID());
            ServerPlayNetworking.send(serverPlayer, SceneSelectionStateS2C.cleared(dimKey, mapKey));
            serverPlayer.sendSystemMessage(Component.literal("§e[场景配置] 已清除临时选区"));
            return InteractionResultHolder.success(stack);
        } else {
            // 普通右键空气：打开配置 GUI
            openEditorScreen(serverPlayer, serverLevel, dimKey, mapKey);
            return InteractionResultHolder.success(stack);
        }
    }

    private void openEditorScreen(ServerPlayer player, ServerLevel level, String dimKey, String mapKey) {
        SceneProfile profile = ConfigManager.getInstance().getSceneMotionSettings().getProfile(mapKey);
        SceneBounds sessionBounds = SceneSelectionSessionManager.getInstance().getBounds(player.getUUID());
        SceneAssetDescriptor descriptor = SceneAssetStore.getInstance().getDescriptor(mapKey);

        // The page can switch between every configured map, so it needs a manifest for every
        // existing map asset rather than only the map that happened to be selected on open.
        // Sending metadata does not download the asset; download remains on-demand at preview.
        for (var entry : SceneAssetStore.getInstance().getAllDescriptors().entrySet()) {
            SceneAssetDescriptor mapDescriptor = entry.getValue();
            if (mapDescriptor == null || !mapDescriptor.isValid()) continue;
            SceneTransferService.getInstance().authorize(
                    player.getUUID(), mapDescriptor.sha256(), mapDescriptor.compressedSize());
            ServerPlayNetworking.send(player, new SceneAssetManifestS2C(entry.getKey(), mapDescriptor));
        }

        SceneEditorOpenS2C payload = new SceneEditorOpenS2C(
                mapKey,
                1,
                profile.toJson().toString(),
                sessionBounds,
                descriptor
        );

        ServerPlayNetworking.send(player, payload);
    }

    /** In an active match the real map wins; in the lobby retain the map selected in the tool UI. */
    private String resolveToolMapKey(ServerPlayer player, ServerLevel level, String dimension) {
        var runtimeState = com.habitrain.core.scene.server.SceneRuntimeCoordinator.getInstance()
                .getRuntimeState(level);
        if (runtimeState != null && runtimeState.isActive()
                && runtimeState.getMapKey() != null && !runtimeState.getMapKey().isBlank()) {
            return runtimeState.getMapKey();
        }
        String resolvedMapKey = SreSceneContextResolver.INSTANCE.resolve(level).mapKey();
        if (!com.habitrain.core.config.SceneMotionSettings.DEFAULT_MAP_KEY.equals(resolvedMapKey)) {
            return resolvedMapKey;
        }
        var session = SceneSelectionSessionManager.getInstance().getSession(player.getUUID());
        if (session != null && dimension.equals(session.getDimension())
                && session.getMapKey() != null && !session.getMapKey().isBlank()) {
            return session.getMapKey();
        }
        return resolvedMapKey;
    }
}
