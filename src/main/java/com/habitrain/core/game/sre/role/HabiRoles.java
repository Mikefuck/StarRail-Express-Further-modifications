package com.habitrain.core.game.sre.role;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.component.CrimeScapegoatComponent;
import com.habitrain.core.game.sre.role.component.FlowerGirlComponent;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.component.SwiftWindComponent;
import com.habitrain.core.game.sre.role.skill.MikeCodeEditSkill;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * habitrain_core 投稿职业注册入口。
 * 全部注册进 {@link TMMRoles}，blackout 通过 canUseKiller 映射 GOOD/BAD。
 *
 * <p>专属商店必须通过角色 {@link SRERole#getShopEntries()} 覆盖提供
 * （见 {@link HabiRoleShops}），不要只依赖 {@code ShopContent.customEntries}——
 * noelles {@code RoleShopHandler.shopRegister()} 会 clear 该 map。
 */
public final class HabiRoles {
    private HabiRoles() {}

    public static final ResourceLocation CRIME_SCAPEGOAT_ID = HabiTrainCore.id("crime_scapegoat");
    public static final ResourceLocation FLOWER_GIRL_ID = HabiTrainCore.id("flower_girl");
    public static final ResourceLocation SWIFT_WIND_ID = HabiTrainCore.id("swift_wind");
    public static final ResourceLocation MIME_KILLER_ID = HabiTrainCore.id("mime_killer");
    public static final ResourceLocation MIKE_ID = HabiTrainCore.id("mike");

    public static SRERole CRIME_SCAPEGOAT;
    public static SRERole FLOWER_GIRL;
    public static SRERole SWIFT_WIND;
    public static SRERole MIME_KILLER;
    public static SRERole MIKE;

    public static void init() {
        registerRoles();
        registerSkills();
        // Seven sins first so sin shop customEntries can be pinned after roles exist.
        com.habitrain.core.game.sre.role.sins.SevenSins.init();
        HabiRoleShops.register();
        HabiRoleEvents.init();
        HabiTrainCore.LOGGER.info("[HabiRoles] registered 5 roles: crime_scapegoat, flower_girl, swift_wind, mime_killer, mike");
    }

    private static void registerRoles() {
        // 凶案替罪羊：平民；≥10 人；近距死亡发刀 10s，被杀转杀手 / 存活得左轮
        CRIME_SCAPEGOAT = TMMRoles.registerRole(new NormalRole(
                CRIME_SCAPEGOAT_ID,
                new Color(120, 90, 60).getRGB(),
                true,
                false,
                SRERole.MoodType.REAL,
                TMMRoles.CIVILIAN.getMaxSprintTime(),
                false
        ).setComponentKey(CrimeScapegoatComponent.KEY)
                .setCanSeeCoin(true)
                .setCanPickUpRevolver(true)
                .setDefaultMax(1)
                .setDefaultEnableNeededPlayerCount(10));

        // 卖花女：专属商店走 getShopEntries（防 customEntries 被 clear）
        FLOWER_GIRL = TMMRoles.registerRole(new NormalRole(
                FLOWER_GIRL_ID,
                new Color(255, 105, 180).getRGB(),
                true,
                false,
                SRERole.MoodType.REAL,
                TMMRoles.CIVILIAN.getMaxSprintTime(),
                false
        ) {
            @Override
            public List<ItemStack> getDefaultItems() {
                List<ItemStack> items = new ArrayList<>();
                items.add(HabiRoleItems.createBouquet(1));
                return items;
            }

            @Override
            public List<ShopEntry> getShopEntries() {
                return HabiRoleShops.flowerGirlShop();
            }
        }.setComponentKey(FlowerGirlComponent.KEY)
                .setCanSeeCoin(true)
                .setDefaultMax(1));

        // 捷风
        SWIFT_WIND = TMMRoles.registerRole(new NormalRole(
                SWIFT_WIND_ID,
                new Color(70, 200, 230).getRGB(),
                false,
                true,
                SRERole.MoodType.FAKE,
                Integer.MAX_VALUE,
                true
        ) {
            @Override
            public List<ShopEntry> getShopEntries() {
                return HabiRoleShops.swiftWindShop();
            }
        }.setComponentKey(SwiftWindComponent.KEY)
                .setCanSeeCoin(true)
                .setDefaultMax(1));

        // 默剧杀手：商店仅刀 + 狂暴（任务折扣）
        MIME_KILLER = TMMRoles.registerRole(new NormalRole(
                MIME_KILLER_ID,
                new Color(40, 40, 40).getRGB(),
                false,
                true,
                SRERole.MoodType.FAKE,
                Integer.MAX_VALUE,
                true
        ) {
            @Override
            public List<ShopEntry> getShopEntries() {
                return HabiRoleShops.mimeKillerShop();
            }
        }.setComponentKey(MimeKillerComponent.KEY)
                .setCanSeeCoin(true)
                .setDefaultMax(1)
                .setDefaultEnableNeededPlayerCount(12));

        // Mike：平民；G 键「代码修改」强制准星玩家随机转职
        MIKE = TMMRoles.registerRole(new NormalRole(
                MIKE_ID,
                new Color(78, 201, 176).getRGB(),
                true,
                false,
                SRERole.MoodType.REAL,
                TMMRoles.CIVILIAN.getMaxSprintTime(),
                false
        ).setCanSeeCoin(true)
                .setDefaultMax(1));
    }

    private static void registerSkills() {
        // 卖花女：赠送花束
        RoleSkill.register(FLOWER_GIRL,
                RoleSkill.skill(
                        HabiTrainCore.id("flower_girl_gift"),
                        "skill.habitrain_core.flower_girl.gift",
                        ctx -> FlowerGirlComponent.useGift(ctx)
                ).cooldownSeconds(30).showOnHud(true).announceToSelf(true).build()
        );

        // 捷风：逐风 + 瞬云(shift)
        RoleSkill.register(SWIFT_WIND,
                RoleSkill.skill(
                        HabiTrainCore.id("swift_wind_dash"),
                        "skill.habitrain_core.swift_wind.dash",
                        ctx -> SwiftWindComponent.useDash(ctx)
                ).cooldownSeconds(20).showOnHud(true).announceToSelf(true).build(),
                RoleSkill.skill(
                        HabiTrainCore.id("swift_wind_smoke"),
                        "skill.habitrain_core.swift_wind.smoke",
                        ctx -> SwiftWindComponent.useSmoke(ctx)
                ).cooldownSeconds(120).shifted(true).showOnHud(true).announceToSelf(true).build()
        );

        // 默剧杀手：人像默演
        RoleSkill.register(MIME_KILLER,
                RoleSkill.skill(
                        HabiTrainCore.id("mime_killer_mime"),
                        "skill.habitrain_core.mime_killer.mime",
                        ctx -> MimeKillerComponent.useMime(ctx)
                ).cooldownSeconds(30).showOnHud(true).announceToSelf(true).build()
        );

        // Mike：代码修改
        RoleSkill.register(MIKE,
                RoleSkill.skill(
                        HabiTrainCore.id("mike_code_edit"),
                        "skill.habitrain_core.mike.code_edit",
                        MikeCodeEditSkill::use
                ).cooldownSeconds(120).showOnHud(true).announceToSelf(true).build()
        );
    }

    public static boolean isHabiRole(Player player, SRERole role) {
        if (player == null || role == null || player.level() == null) return false;
        var game = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(player.level());
        return game != null && game.isRole(player, role);
    }

}
