package com.habitrain.core.scene.client;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.page.SceneMotionPage;
import com.habitrain.core.scene.item.HabiAdminItems;
import com.habitrain.core.scene.model.SceneBounds;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 客户端显示原点摆放会话。
 *
 * <p>配置页进入摆放模式后，预览框以准星命中的方块为局部 (0,0,0)，
 * 尺寸与源选区完全一致。Shift+右键只在客户端确认，避免这次点击同时被
 * 服务端场景配置器解释成新的 A/B 选区点。</p>
 */
public final class SceneOriginPlacementController {
    public enum TargetType {
        PLACE_ORIGIN,
        ORBIT_CENTER
    }

    private static final SceneOriginPlacementController INSTANCE = new SceneOriginPlacementController();

    public static SceneOriginPlacementController getInstance() {
        return INSTANCE;
    }

    private TargetType targetType = TargetType.PLACE_ORIGIN;
    private SceneMotionPage returnPage;
    private ConfigMenuScreen returnScreen;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private double[] displayOrigin = new double[]{0.0, 64.0, 0.0};
    private com.habitrain.core.scene.model.SceneOrbitAxis orbitAxis = com.habitrain.core.scene.model.SceneOrbitAxis.Y;
    private boolean active;

    private SceneOriginPlacementController() {}

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            SceneOriginPlacementController controller = INSTANCE;
            if (!level.isClientSide() || !controller.isActive() || !controller.isHoldingTool()) {
                return InteractionResult.PASS;
            }
            if (!player.isShiftKeyDown()) {
                String key = controller.getTargetType() == TargetType.ORBIT_CENTER
                        ? "hud.habitrain_core.scene_tool.hold_shift_center"
                        : "hud.habitrain_core.scene_tool.hold_shift_origin";
                player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.YELLOW), true);
                return InteractionResult.FAIL;
            }

            controller.confirm(hitResult.getBlockPos());
            player.swing(hand);
            return InteractionResult.FAIL;
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            SceneOriginPlacementController controller = INSTANCE;
            if (!level.isClientSide() || !controller.isActive() || !controller.isHoldingTool()) {
                return InteractionResultHolder.pass(player.getItemInHand(hand));
            }
            String key = controller.getTargetType() == TargetType.ORBIT_CENTER
                    ? "hud.habitrain_core.scene_tool.aim_center"
                    : "hud.habitrain_core.scene_tool.aim_origin";
            player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.YELLOW), true);
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        });
    }

    public synchronized void start(ConfigMenuScreen screen, SceneMotionPage page, SceneBounds sourceBounds) {
        startPlacement(screen, page, sourceBounds);
    }

    public synchronized void startPlacement(ConfigMenuScreen screen, SceneMotionPage page, SceneBounds sourceBounds) {
        if (screen == null || page == null || sourceBounds == null || sourceBounds.isEmpty()) return;
        this.targetType = TargetType.PLACE_ORIGIN;
        this.returnScreen = screen;
        this.returnPage = page;
        this.sizeX = sourceBounds.sizeX();
        this.sizeY = sourceBounds.sizeY();
        this.sizeZ = sourceBounds.sizeZ();
        this.active = true;
    }

    public synchronized void startOrbitCenter(ConfigMenuScreen screen, SceneMotionPage page,
                                             double[] displayOrigin, com.habitrain.core.scene.model.SceneOrbitAxis axis) {
        if (screen == null || page == null) return;
        this.targetType = TargetType.ORBIT_CENTER;
        this.returnScreen = screen;
        this.returnPage = page;
        this.displayOrigin = displayOrigin != null ? java.util.Arrays.copyOf(displayOrigin, 3) : new double[]{0.0, 64.0, 0.0};
        this.orbitAxis = axis != null ? axis : com.habitrain.core.scene.model.SceneOrbitAxis.Y;
        this.sizeX = 1;
        this.sizeY = 1;
        this.sizeZ = 1;
        this.active = true;
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized TargetType getTargetType() {
        return targetType != null ? targetType : TargetType.PLACE_ORIGIN;
    }

    public synchronized double[] getDisplayOrigin() {
        return displayOrigin;
    }

    public synchronized com.habitrain.core.scene.model.SceneOrbitAxis getOrbitAxis() {
        return orbitAxis != null ? orbitAxis : com.habitrain.core.scene.model.SceneOrbitAxis.Y;
    }

    public synchronized SceneBounds getPreviewBounds() {
        BlockPos target = getTargetBlock();
        if (!active || target == null) return SceneBounds.EMPTY;
        return new SceneBounds(
                target.getX(), target.getY(), target.getZ(),
                target.getX() + sizeX, target.getY() + sizeY, target.getZ() + sizeZ
        );
    }

    public synchronized BlockPos getTargetBlock() {
        if (!active) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    public synchronized int sizeX() { return sizeX; }
    public synchronized int sizeY() { return sizeY; }
    public synchronized int sizeZ() { return sizeZ; }

    public synchronized void reset() {
        active = false;
        returnPage = null;
        returnScreen = null;
        sizeX = 0;
        sizeY = 0;
        sizeZ = 0;
        targetType = TargetType.PLACE_ORIGIN;
    }

    private boolean isHoldingTool() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.player.getMainHandItem().is(HabiAdminItems.SCENE_CONFIGURATOR)
                || mc.player.getOffhandItem().is(HabiAdminItems.SCENE_CONFIGURATOR));
    }

    private void confirm(BlockPos targetPos) {
        SceneMotionPage page;
        ConfigMenuScreen screen;
        TargetType type;
        synchronized (this) {
            if (!active || targetPos == null) return;
            page = returnPage;
            screen = returnScreen;
            type = targetType;
            active = false;
            returnPage = null;
            returnScreen = null;
        }

        Minecraft mc = Minecraft.getInstance();
        if (type == TargetType.PLACE_ORIGIN) {
            if (page != null) page.applyPlacedOrigin(targetPos);
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable(
                        "hud.habitrain_core.scene_tool.origin_confirmed",
                        targetPos.getX(), targetPos.getY(), targetPos.getZ())
                        .withStyle(ChatFormatting.GREEN), true);
            }
        } else {
            double cx = targetPos.getX() + 0.5;
            double cy = targetPos.getY() + 0.5;
            double cz = targetPos.getZ() + 0.5;
            if (page != null) page.applyPlacedOrbitCenter(cx, cy, cz);
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable(
                        "hud.habitrain_core.scene_tool.center_confirmed", cx, cy, cz)
                        .withStyle(ChatFormatting.GREEN), true);
            }
        }
        if (screen != null) mc.setScreen(screen);
    }
}
