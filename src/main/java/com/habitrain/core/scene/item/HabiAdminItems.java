package com.habitrain.core.scene.item;

import com.habitrain.core.HabiTrainCore;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
    public static final ResourceLocation ITEMS_TAB_ID = HabiTrainCore.id("items");
    private static final ResourceLocation DAILY_TASK_BOARD_ID =
            ResourceLocation.fromNamespaceAndPath("habitrain_lottery", "daily_task_board");
    public static final Item SCENE_CONFIGURATOR = new SceneConfiguratorItem(
            new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

    private HabiAdminItems() {}

    public static void init() {
        Registry.register(BuiltInRegistries.ITEM, SCENE_CONFIGURATOR_ID, SCENE_CONFIGURATOR);
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, ITEMS_TAB_ID,
                FabricItemGroup.builder()
                        .title(Component.translatable("itemGroup.habitrain_core.items"))
                        .icon(() -> SCENE_CONFIGURATOR.getDefaultInstance())
                        .displayItems((parameters, entries) -> {
                            entries.accept(SCENE_CONFIGURATOR);
                            // The lottery mod is optional for core. Resolve its item only when
                            // the creative tab is populated, after mod item registration.
                            if (BuiltInRegistries.ITEM.containsKey(DAILY_TASK_BOARD_ID)) {
                                entries.accept(BuiltInRegistries.ITEM.get(DAILY_TASK_BOARD_ID));
                            }
                        })
                        .build());
        LOGGER.info("已注册哈比列车 API 物品标签页");
    }
}
