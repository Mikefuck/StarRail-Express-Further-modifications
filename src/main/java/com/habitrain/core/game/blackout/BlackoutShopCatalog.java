package com.habitrain.core.game.blackout;

import dev.doctor4t.wathe.util.ShopEntry;

import java.util.List;

public final class BlackoutShopCatalog {
    private static final String REVOLVER_KEY = "revolver";
    public static final String REVOLVER_NAME = "左轮手枪";
    public static final String REVOLVER_ITEM_ID = "trainmurdermystery:revolver";
    public static final int REVOLVER_PRICE = 200;

    private static final String HANDCUFFS_KEY = "handcuffs";
    public static final String HANDCUFFS_NAME = "手铐";
    public static final String HANDCUFFS_ITEM_ID = "noellesroles:handcuffs";
    public static final int HANDCUFFS_PRICE = 80;

    public static final BlackoutShopDefinition REVOLVER = new BlackoutShopDefinition(
            REVOLVER_KEY,
            REVOLVER_NAME,
            REVOLVER_ITEM_ID,
            1,
            REVOLVER_PRICE,
            ShopEntry.Type.WEAPON,
            true
    );

    public static final BlackoutShopDefinition HANDCUFFS = new BlackoutShopDefinition(
            HANDCUFFS_KEY,
            HANDCUFFS_NAME,
            HANDCUFFS_ITEM_ID,
            1,
            HANDCUFFS_PRICE,
            ShopEntry.Type.TOOL,
            true
    );

    public static final String KILLER_REVOLVER_KEY = "killer_revolver";
    public static final String KILLER_REVOLVER_NAME = "左轮手枪";
    public static final int KILLER_REVOLVER_PRICE = 300;

    public static final BlackoutShopDefinition KILLER_REVOLVER = new BlackoutShopDefinition(
            KILLER_REVOLVER_KEY,
            KILLER_REVOLVER_NAME,
            REVOLVER_ITEM_ID,
            1,
            KILLER_REVOLVER_PRICE,
            ShopEntry.Type.WEAPON,
            true
    );

    private static final String ACID_BUCKET_KEY = "acid_bucket";
    public static final String ACID_BUCKET_NAME = "硫酸桶";
    public static final String ACID_BUCKET_ITEM_ID = "noellesroles:bucket_of_h2so4";
    public static final int ACID_BUCKET_PRICE = 30;

    public static final BlackoutShopDefinition ACID_BUCKET = new BlackoutShopDefinition(
            ACID_BUCKET_KEY,
            ACID_BUCKET_NAME,
            ACID_BUCKET_ITEM_ID,
            1,
            ACID_BUCKET_PRICE,
            ShopEntry.Type.WEAPON,
            true
    );

    private static final String KNIFE_KEY = "knife";
    public static final String KNIFE_NAME = "刀";
    public static final String KNIFE_ITEM_ID = "noellesroles:throwing_knife";
    public static final int KNIFE_PRICE = 130;

    public static final BlackoutShopDefinition KNIFE = new BlackoutShopDefinition(
            KNIFE_KEY,
            KNIFE_NAME,
            KNIFE_ITEM_ID,
            1,
            KNIFE_PRICE,
            ShopEntry.Type.WEAPON,
            false
    );

    private static final String LOCKPICK_KEY = "lockpick";
    public static final String LOCKPICK_NAME = "撬锁器";
    public static final String LOCKPICK_ITEM_ID = "trainmurdermystery:lockpick";
    public static final int LOCKPICK_PRICE = 80;

    public static final BlackoutShopDefinition LOCKPICK = new BlackoutShopDefinition(
            LOCKPICK_KEY,
            LOCKPICK_NAME,
            LOCKPICK_ITEM_ID,
            1,
            LOCKPICK_PRICE,
            ShopEntry.Type.TOOL,
            false
    );

    private static final String PSYCHO_MODE_KEY = "psycho_mode";
    public static final String PSYCHO_MODE_NAME = "疯狂模式";
    public static final String PSYCHO_MODE_ITEM_ID = "trainmurdermystery:psycho_mode";
    public static final int PSYCHO_MODE_PRICE = 400;

    /**
     * 疯狂模式：购买后由 {@code SREPlayerShopComponent.usePsychoMode} 触发 SRE 原版狂暴逻辑
     *（30 秒球棒、隐身份、1 层护盾、5 分钟冷却）。不能简单以物品堆形式发放，
     * 故购买逻辑在 {@code BlackoutPsychoModeShopEntry} 中特判。
     */
    public static final BlackoutShopDefinition PSYCHO_MODE = new BlackoutShopDefinition(
            PSYCHO_MODE_KEY,
            PSYCHO_MODE_NAME,
            PSYCHO_MODE_ITEM_ID,
            1,
            PSYCHO_MODE_PRICE,
            ShopEntry.Type.WEAPON,
            true
    );

    private BlackoutShopCatalog() {
    }

    public static List<BlackoutShopDefinition> sheriffShop() {
        return List.of(HANDCUFFS, REVOLVER);
    }

    public static List<BlackoutShopDefinition> killerShop() {
        return List.of(KILLER_REVOLVER, KNIFE, LOCKPICK, PSYCHO_MODE, ACID_BUCKET);
    }
}
