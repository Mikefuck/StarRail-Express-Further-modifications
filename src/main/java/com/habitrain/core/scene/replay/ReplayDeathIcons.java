package com.habitrain.core.scene.replay;

import java.util.List;
import java.util.Locale;

/**
 * Visual-only classification of raw death-reason / weapon identifiers. Exact gameplay rules live in
 * {@code SinDeathReasons}; this mapper is deliberately tolerant (token based) because third-party
 * roles keep inventing new reason ids and a sensible icon beats a blank one.
 */
public final class ReplayDeathIcons {
    private ReplayDeathIcons() {}

    public enum Icon {
        KNIFE(0xFFE8E4DA), THROWING_KNIFE(0xFFE8E4DA), REVOLVER(0xFFF4D894), DERRINGER(0xFFF4D894),
        SNIPER(0xFFF4D894), SHOTGUN(0xFFF4D894), BAT(0xFFC9A47A), GRENADE(0xFFFF9A3C), EXPLOSION(0xFFFF7A2E),
        POISON(0xFF7BE36A), ARROW(0xFFD8C8A8), SHURIKEN(0xFFB8C4D8), FALL(0xFF9EA7BE), SIN(0xFFD04AE0),
        BACKFIRE(0xFFFF8A3C), MAGIC(0xFFB57BFF), SKULL(0xFFFF5A64);

        private final int color;
        Icon(int color) { this.color = color; }
        public int color() { return color; }
    }

    public static Icon classify(String reasonId) {
        if (reasonId == null || reasonId.isBlank()) return Icon.SKULL;
        String id = reasonId.toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        String ns = colon >= 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        if (path.contains("backfire") || path.contains("mistake")) return Icon.BACKFIRE;
        if (path.contains("throwing_knife") || path.contains("throwing_axe")) return Icon.THROWING_KNIFE;
        if (path.contains("shuriken")) return Icon.SHURIKEN;
        if (path.contains("knife") || path.contains("stab") || path.contains("execution")) return Icon.KNIFE;
        if (path.contains("derringer")) return Icon.DERRINGER;
        if (path.contains("sniper")) return Icon.SNIPER;
        if (path.contains("shotgun")) return Icon.SHOTGUN;
        if (path.contains("revolver") || path.contains("gun") || path.endsWith("_shot") || path.startsWith("shot")) return Icon.REVOLVER;
        if (path.contains("grenade")) return Icon.GRENADE;
        if (path.contains("bomb") || path.contains("explo") || path.contains("c4") || path.contains("creeper")
                || path.contains("detonat")) return Icon.EXPLOSION;
        if (path.contains("poison") || path.contains("toxic") || path.contains("gas")) return Icon.POISON;
        if (path.contains("bat") || path.contains("nunchuck") || path.contains("axe") || path.contains("crush")) return Icon.BAT;
        if (path.contains("arrow") || path.contains("trident")) return Icon.ARROW;
        if (path.contains("fell") || path.contains("fall")) return Icon.FALL;
        if (ns.equals("habitrain_core") || path.contains("sin_") || path.contains("wrath") || path.contains("lust")
                || path.contains("envy") || path.contains("greed") || path.contains("pride") || path.contains("sloth")
                || path.contains("gluttony")) return Icon.SIN;
        if (path.contains("voodoo") || path.contains("curse") || path.contains("heart_attack") || path.contains("god")
                || path.contains("magic") || path.contains("usurp")) return Icon.MAGIC;
        return Icon.SKULL;
    }

    /** Item ids to try, in order, for a floating 3D weapon icon. The last entry is always vanilla. */
    public static List<String> itemCandidates(Icon icon) {
        return switch (icon) {
            case KNIFE -> List.of("starrailexpress:knife", "minecraft:iron_sword");
            case THROWING_KNIFE -> List.of("noellesroles:throwing_knife", "starrailexpress:knife", "minecraft:iron_sword");
            case REVOLVER -> List.of("starrailexpress:revolver", "minecraft:crossbow");
            case DERRINGER -> List.of("starrailexpress:derringer", "noellesroles:vengeance_derringer", "starrailexpress:revolver", "minecraft:crossbow");
            case SNIPER -> List.of("starrailexpress:sniper_rifle", "minecraft:spyglass");
            case SHOTGUN -> List.of("noellesroles:short_shotgun", "starrailexpress:revolver", "minecraft:crossbow");
            case BAT -> List.of("starrailexpress:bat", "noellesroles:fire_axe", "minecraft:stick");
            case GRENADE -> List.of("starrailexpress:grenade", "minecraft:fire_charge");
            case EXPLOSION -> List.of("noellesroles:c4", "noellesroles:bomb", "minecraft:tnt");
            case POISON -> List.of("starrailexpress:poison_vial", "noellesroles:toilet_poison", "minecraft:potion");
            case ARROW -> List.of("minecraft:arrow");
            case SHURIKEN -> List.of("noellesroles:ninja_shuriken", "minecraft:nether_star");
            case FALL -> List.of("minecraft:feather");
            case SIN -> List.of("minecraft:wither_rose");
            case BACKFIRE -> List.of("starrailexpress:revolver", "minecraft:flint_and_steel");
            case MAGIC -> List.of("minecraft:amethyst_shard");
            case SKULL -> List.of("minecraft:skeleton_skull");
        };
    }
}
