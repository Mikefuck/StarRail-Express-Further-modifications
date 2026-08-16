package com.habitrain.core.game.sre.role;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

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
        // 五个投稿职业已由 CoreRoleExtensionProvider 经 v2 ADD 注册并回填字段
        // （须先于本 init 执行）。这里只挂七宗罪、商店次要路径和事件。
        com.habitrain.core.game.sre.role.sins.SevenSins.init();
        HabiRoleShops.register();
        HabiRoleEvents.init();
        HabiTrainCore.LOGGER.info("[HabiRoles] v2 ADD fields ready: crime_scapegoat, flower_girl, swift_wind, mime_killer, mike");
    }

    public static boolean isHabiRole(Player player, SRERole role) {
        if (player == null || role == null || player.level() == null) return false;
        var game = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(player.level());
        return game != null && game.isRole(player, role);
    }

}
