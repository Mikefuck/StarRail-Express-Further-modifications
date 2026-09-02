package com.habitrain.core.scene.item;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 哈比列车管理员物品注册器。
 */
public final class HabiAdminItems {
    private static final Logger LOGGER = LoggerFactory.getLogger(HabiAdminItems.class.getSimpleName());

    public static final ResourceLocation SCENE_CONFIGURATOR_ID = HabiTrainCore.id("scene_configurator");
    public static final Item SCENE_CONFIGURATOR = new SceneConfiguratorItem(
            new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

    private HabiAdminItems() {}

    public static void init() {
        Registry.register(BuiltInRegistries.ITEM, SCENE_CONFIGURATOR_ID, SCENE_CONFIGURATOR);

        // 依据设计指南 §10.1 加入原版管理员标签页 (CreativeModeTabs.OP_BLOCKS)
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.OP_BLOCKS).register(entries -> {
            entries.accept(SCENE_CONFIGURATOR);
        });

        LOGGER.info("已注册管理员道具: habitrain_core:scene_configurator 并加入操作员标签页 (OP_BLOCKS)");
    }
}
