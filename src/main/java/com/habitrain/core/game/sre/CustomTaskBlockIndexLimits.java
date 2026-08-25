package com.habitrain.core.game.sre;

import com.habitrain.core.game.blackout.BlackoutOverlayTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

/**
 * Caps for {@link CustomTaskBlockCache}: global snapshot size and per-block/type ESP index.
 */
public final class CustomTaskBlockIndexLimits {

    public static final int GLOBAL_MAX_ENTRIES = 65_536;
    public static final int BULK_BLOCK_CAP = 48;
    public static final int DEFAULT_TYPE_CAP = 256;

    private CustomTaskBlockIndexLimits() {}

    public static boolean isBulkDecorative(Block block) {
        return block == Blocks.COAL_BLOCK
                || block == Blocks.TNT
                || block == Blocks.REDSTONE_TORCH
                || block == Blocks.REDSTONE_BLOCK
                || block == Blocks.LEVER;
    }

    public static boolean isUncappedType(int typeId) {
        return typeId == BlackoutOverlayTypes.STREET_PHONE
                || typeId == BlackoutOverlayTypes.ROTARY_PHONE_RED
                || typeId == BlackoutOverlayTypes.HORN;
    }

    public static int capFor(Block block, int typeId) {
        if (isUncappedType(typeId)) {
            return Integer.MAX_VALUE;
        }
        if (block != null && isBulkDecorative(block)) {
            return BULK_BLOCK_CAP;
        }
        return DEFAULT_TYPE_CAP;
    }

    public static boolean acceptNewPosition(int currentSize, int globalCap) {
        return currentSize < globalCap;
    }

    public static boolean acceptGroup(int currentCount, int cap) {
        return currentCount < cap;
    }

    /**
     * Spectator/creative ESP: sparse custom overlay points only.
     * Skips SRE vanilla typeIds and bulk decorative blocks (coal / TNT / …).
     * {@code block == null} is allowed (client snapshot cannot restore multi-block types).
     */
    public static boolean shouldDrawSpectatorOverlay(Block block, Set<Integer> typeIds) {
        if (typeIds == null || typeIds.isEmpty()) {
            return false;
        }
        boolean hasCustom = false;
        for (int typeId : typeIds) {
            if (typeId >= BlackoutOverlayTypes.CUSTOM_OVERLAY_MIN_TYPE_ID) {
                hasCustom = true;
                break;
            }
        }
        if (!hasCustom) {
            return false;
        }
        return block == null || !isBulkDecorative(block);
    }
}
