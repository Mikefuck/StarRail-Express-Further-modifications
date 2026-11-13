package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.block.SettlementSummaryBoard;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * Purely local, zero-network view options toggled by right-clicking a board with an empty hand:
 * <ul>
 *     <li>right-click: follow the server-synchronised playback / freeze locally to study a frame;</li>
 *     <li>sneak + right-click: creative table editor, otherwise viewer-facing avatar fans / full car bodies.</li>
 * </ul>
 * These toggles consume the interaction locally, without sending a block interaction packet.
 */
final class ReplayBoardLocalState {
    private static boolean paused;
    private static long pausedTick;
    private static float pausedPartial;
    private static boolean fullBody;

    private ReplayBoardLocalState() {}

    static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || player.isSpectator()) return InteractionResult.PASS;
            if (!level.getBlockState(hit.getBlockPos()).is(SettlementSummaryBoard.BLOCK)) return InteractionResult.PASS;
            Minecraft client = Minecraft.getInstance();
            if (player != client.player) return InteractionResult.PASS;
            // Creative configuration works even while holding a block or tool: consume before placement.
            if (player.isCreative() && player.isShiftKeyDown()
                    && level.getBlockEntity(hit.getBlockPos()) instanceof SettlementSummaryBoard.Marker marker) {
                client.setScreen(new ReplayBoardSettingsScreen(level.dimension().location(), hit.getBlockPos(), marker.settings()));
                return InteractionResult.CONSUME;
            }
            if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return InteractionResult.PASS;
            if (player.isShiftKeyDown()) {
                toggleRoof();
                player.displayClientMessage(ReplayBoardTexts.tr(fullBody ? "toggle.roof_full" : "toggle.roof_cut"), true);
            } else {
                if (!paused) {
                    paused = true;
                    pausedTick = level.getGameTime();
                    pausedPartial = client.getTimer().getGameTimeDeltaPartialTick(true);
                } else {
                    paused = false;
                }
                player.displayClientMessage(ReplayBoardTexts.tr(paused ? "toggle.paused" : "toggle.resumed"), true);
            }
            return InteractionResult.CONSUME;
        });
    }

    static boolean paused() { return paused; }
    static boolean fullBody() { return fullBody; }
    static void toggleRoof() { fullBody = !fullBody; }
    static long tick(long live) { return paused ? pausedTick : live; }
    static float partial(float live) { return paused ? pausedPartial : live; }

    static void reset() {
        paused = false;
        fullBody = false;
    }
}
