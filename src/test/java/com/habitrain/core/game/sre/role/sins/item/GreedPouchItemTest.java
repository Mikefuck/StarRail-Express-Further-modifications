package com.habitrain.core.game.sre.role.sins.item;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GreedPouchItemTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void holds32UnstackableItemsAndReturnsTheirComponents() {
        ItemStack pouch = GreedPouchItem.createBoundPouch(null);
        for (int i = 0; i < 32; i++) {
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.set(DataComponents.CUSTOM_NAME, Component.literal("Sword " + i));
            assertEquals(1, GreedPouchItem.insert(pouch, sword));
            assertTrue(sword.isEmpty());
        }
        ItemStack extra = new ItemStack(Items.DIAMOND_SWORD);
        assertEquals(0, GreedPouchItem.insert(pouch, extra));
        assertEquals(1, extra.getCount());
        for (int i = 31; i >= 0; i--) {
            assertEquals("Sword " + i, GreedPouchItem.removeFirst(pouch).getHoverName().getString());
        }
        assertEquals(0, GreedPouchItem.storedCount(pouch));
        assertTrue(GreedPouchItem.removeFirst(pouch).isEmpty());
    }

    @Test
    void stackedItemsUseOnePositionPerItemAndOverflowStaysOutside() {
        ItemStack pouch = GreedPouchItem.createBoundPouch(null);
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 64);
        assertEquals(32, GreedPouchItem.insert(pouch, diamonds));
        assertEquals(32, diamonds.getCount());
        assertEquals(32, GreedPouchItem.storedCount(pouch));
        assertEquals(1, GreedPouchItem.removeFirst(pouch).getCount());
        assertEquals(1, GreedPouchItem.insert(pouch, diamonds));
        assertEquals(31, diamonds.getCount());
    }
}
