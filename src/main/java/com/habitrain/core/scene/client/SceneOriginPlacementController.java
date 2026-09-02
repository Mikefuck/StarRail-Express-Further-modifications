package com.habitrain.core.scene.client;

import com.habitrain.core.client.gui.menu.ConfigMenuScreen;
import com.habitrain.core.client.gui.menu.page.SceneMotionPage;
import com.habitrain.core.scene.item.HabiAdminItems;
import com.habitrain.core.scene.model.SceneBounds;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
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
    private static final SceneOriginPlacementController INSTANCE = new SceneOriginPlacementController();

    public static SceneOriginPlacementController getInstance() {
        return INSTANCE;
    }

    private SceneMotionPage returnPage;
    private ConfigMenuScreen returnScreen;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private boolean active;

    private SceneOriginPlacementController() {}

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            SceneOriginPlacementController controller = INSTANCE;
            if (!level.isClientSide() || !controller.isActive() || !controller.isHoldingTool()) {
                return InteractionResult.PASS;
            }
            if (!player.isShiftKeyDown()) {
                player.displayClientMessage(Component.literal("§e请按住 Shift 再右键确认显示原点"), true);
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
            player.displayClientMessage(Component.literal("§e请将准星对准一个方块，再按 Shift+右键确认"), true);
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        });
    }

    public synchronized void start(ConfigMenuScreen screen, SceneMotionPage page, SceneBounds sourceBounds) {
        if (screen == null || page == null || sourceBounds == null || sourceBounds.isEmpty()) return;
        this.returnScreen = screen;
        this.returnPage = page;
        this.sizeX = sourceBounds.sizeX();
        this.sizeY = sourceBounds.sizeY();
        this.sizeZ = sourceBounds.sizeZ();
        this.active = true;
    }

    public synchronized boolean isActive() {
        return active;
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
    }

    private boolean isHoldingTool() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.player.getMainHandItem().is(HabiAdminItems.SCENE_CONFIGURATOR)
                || mc.player.getOffhandItem().is(HabiAdminItems.SCENE_CONFIGURATOR));
    }

    private void confirm(BlockPos origin) {
        SceneMotionPage page;
        ConfigMenuScreen screen;
        synchronized (this) {
            if (!active || origin == null) return;
            page = returnPage;
            screen = returnScreen;
            active = false;
            returnPage = null;
            returnScreen = null;
        }

        if (page != null) page.applyPlacedOrigin(origin);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§a已自动填入显示原点: ("
                    + origin.getX() + ", " + origin.getY() + ", " + origin.getZ() + ")"), true);
        }
        if (screen != null) mc.setScreen(screen);
    }
}
