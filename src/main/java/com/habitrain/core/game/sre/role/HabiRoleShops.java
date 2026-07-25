package com.habitrain.core.game.sre.role;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.game.sre.role.sins.shop.SevenSinShops;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.KillerKnifeShopEntry;
import io.wifi.starrailexpress.game.ShopContent;
import io.wifi.starrailexpress.index.TMMItems;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 投稿职业专属商店。
 *
 * <p><b>优先路径：</b>在角色上 override {@code getShopEntries()} 返回这里的列表
 * （见 {@link HabiRoles}）。这是 {@link ShopContent#getShopEntries} 的第一优先级，
 * 不会被 noelles {@code RoleShopHandler.shopRegister()} 的
 * {@code ShopContent.customEntries.clear()} 清掉。
 *
 * <p><b>次要路径：</b>{@link #register()} 仍写入 {@link ShopContent#customEntries}
 * 作为兼容；若 DLC 晚于本 mod 初始化并 clear 了 map，角色 override 仍生效。
 *
 * <p><b>狂暴折扣：</b>UI 与 {@code tryBuy} 都用 {@code DynamicShopComponent.effectivePrice}，
 * 必须在任务完成时写 {@code setFlatReduction(psychoId, discount)}，不能只改 MimeKillerComponent。
 *
 * <p><b>杀手刀耐久：</b>必须用 {@link KillerKnifeShopEntry}，不能用普通
 * {@code new ShopEntry(TMMItems.KNIFE...)}。后者不会 stamp {@code MAX_DAMAGE}/{@code DAMAGE}，
 * murder 模式下会变成无限耐久刀；原版默认商店见 {@link ShopContent#register()}。
 */
public final class HabiRoleShops {
    private HabiRoleShops() {}

    /** 捷风商店刀价（保持 130，不与默剧杀手共用）。 */
    public static final int DEFAULT_KNIFE_PRICE = 130;

    /** 捷风商店：开锁器。 */
    public static final int SWIFT_WIND_LOCKPICK_PRICE = 100;

    /** 捷风商店：撬棍。 */
    public static final int SWIFT_WIND_CROWBAR_PRICE = 150;

    /** 默剧杀手商店刀价。 */
    public static final int MIME_KILLER_KNIFE_PRICE = 200;

    public static void register() {
        ShopContent.customEntries.put(HabiRoles.FLOWER_GIRL_ID, flowerGirlShop());
        ShopContent.customEntries.put(HabiRoles.SWIFT_WIND_ID, swiftWindShop());
        ShopContent.customEntries.put(HabiRoles.MIME_KILLER_ID, mimeKillerShop());
        // Also pin seven-sin shops in customEntries as a secondary path
        // (role getShopEntries remains authoritative).
        if (SevenSins.ENVY_ID != null) {
            ShopContent.customEntries.put(SevenSins.ENVY_ID, SevenSinShops.envyShop());
        }
        if (SevenSins.PRIDE_ID != null) {
            ShopContent.customEntries.put(SevenSins.PRIDE_ID, SevenSinShops.prideShop());
        }
        if (SevenSins.GREED_ID != null) {
            ShopContent.customEntries.put(SevenSins.GREED_ID, SevenSinShops.greedShop());
        }
        if (SevenSins.GLUTTONY_ID != null) {
            ShopContent.customEntries.put(SevenSins.GLUTTONY_ID, SevenSinShops.gluttonyShop());
        }
        if (SevenSins.LUST_ID != null) {
            ShopContent.customEntries.put(SevenSins.LUST_ID, SevenSinShops.lustShop());
        }
        HabiTrainCore.LOGGER.info("[HabiRoleShops] custom shop entries registered (role getShopEntries is authoritative)");
    }

    public static List<ShopEntry> flowerGirlShop() {
        List<ShopEntry> shop = new ArrayList<>();
        shop.add(new ShopEntry(HabiRoleItems.createBouquet(1), 150, ShopEntry.Type.TOOL) {
            @Override
            public boolean onBuy(@NotNull Player player) {
                ItemStack stack = HabiRoleItems.createBouquet(1);
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
                return true;
            }
        });
        shop.add(new ShopEntry(HabiRoleItems.createPepperSpray(), 75, ShopEntry.Type.TOOL) {
            @Override
            public boolean onBuy(@NotNull Player player) {
                ItemStack stack = HabiRoleItems.createPepperSpray();
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
                return true;
            }
        });
        return shop;
    }

    public static List<ShopEntry> swiftWindShop() {
        List<ShopEntry> shop = new ArrayList<>();
        shop.add(new ShopEntry(TMMItems.BLACKOUT.getDefaultInstance(), 100, ShopEntry.Type.TOOL) {
            @Override
            public boolean onBuy(@NotNull Player player) {
                return SREPlayerShopComponent.useBlackout(player);
            }
        });
        // 必须用 KillerKnifeShopEntry：murder 模式下 stamp 3 点耐久 + 耗尽刀刷新 + 首购折扣
        shop.add(new KillerKnifeShopEntry(DEFAULT_KNIFE_PRICE));
        ItemStack throwing = HabiRoleItems.lookupItem(HabiRoleItems.THROWING_KNIFE_ID, 1);
        if (!throwing.isEmpty()) {
            shop.add(new ShopEntry(throwing, 100, ShopEntry.Type.WEAPON));
        }
        shop.add(new ShopEntry(Items.WIND_CHARGE.getDefaultInstance(), 50, ShopEntry.Type.TOOL));
        shop.add(new ShopEntry(TMMItems.LOCKPICK.getDefaultInstance(), SWIFT_WIND_LOCKPICK_PRICE, ShopEntry.Type.TOOL));
        shop.add(new ShopEntry(TMMItems.CROWBAR.getDefaultInstance(), SWIFT_WIND_CROWBAR_PRICE, ShopEntry.Type.TOOL));
        return shop;
    }

    /**
     * 默剧杀手：仅刀 200 + 狂暴（基础 500，折扣由 DynamicShop flatReduction 驱动）。
     */
    public static List<ShopEntry> mimeKillerShop() {
        List<ShopEntry> shop = new ArrayList<>();
        // 必须用 KillerKnifeShopEntry，不能 new ShopEntry(KNIFE...) —— 否则无耐久标记
        shop.add(new KillerKnifeShopEntry(MIME_KILLER_KNIFE_PRICE));
        shop.add(new ShopEntry(TMMItems.PSYCHO_MODE.getDefaultInstance(), MimeKillerComponent.BASE_PSYCHO_PRICE, ShopEntry.Type.WEAPON) {
            @Override
            public boolean canBuy(@NotNull Player player) {
                if (player.getCooldowns().isOnCooldown(TMMItems.PSYCHO_MODE)) return false;
                // 余额检查交给 tryBuy + DynamicShop.effectivePrice；这里只拦 CD
                return true;
            }

            @Override
            public boolean onBuy(@NotNull Player player) {
                // tryBuy 在 onBuy 成功后按 DynamicShop.effectivePrice 扣款；这里不要重复扣/退款
                if (player.getCooldowns().isOnCooldown(TMMItems.PSYCHO_MODE)) return false;
                boolean ok = SREPlayerShopComponent.usePsychoMode(player);
                if (ok) {
                    MimeKillerComponent.KEY.maybeGet(player).ifPresent(MimeKillerComponent::resetPsychoDiscount);
                }
                return ok;
            }
        });
        return shop;
    }
}
