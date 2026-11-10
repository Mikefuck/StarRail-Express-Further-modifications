package com.habitrain.core.game.sre.role.idiot;

import com.habitrain.core.HabiTrainCore;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;

public final class IdiotContent {
    public static final Item EGG = Registry.register(BuiltInRegistries.ITEM, HabiTrainCore.id("idiot_egg"), new IdiotEggItem());
    public static final EntityType<IdiotEggEntity> PROJECTILE = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            HabiTrainCore.id("idiot_egg"), EntityType.Builder.<IdiotEggEntity>of(IdiotEggEntity::new, MobCategory.MISC)
                    .sized(.25F, .25F).clientTrackingRange(4).updateInterval(2).noSave().build("habitrain_core:idiot_egg"));
    public static final EntityType<IdiotChickEntity> CHICK = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            HabiTrainCore.id("idiot_chick"), EntityType.Builder.of(IdiotChickEntity::new, MobCategory.MISC)
                    .sized(.4F, .7F).clientTrackingRange(4).updateInterval(3).fireImmune().noSave()
                    .build("habitrain_core:idiot_chick"));
    private IdiotContent() {}
}
