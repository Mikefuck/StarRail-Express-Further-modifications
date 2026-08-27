package com.habitrain.core.game.sre;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConsumableMixinTargetContractTest {

    @Test
    void itemStackExposesTheUnifiedFinishUsingItemHook() throws Exception {
        assertNotNull(ItemStack.class.getDeclaredMethod(
                "finishUsingItem", Level.class, LivingEntity.class));
    }
}
