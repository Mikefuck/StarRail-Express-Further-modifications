package com.habitrain.core.client.mvp;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MVP 动画元数据定义。
 */
public record MvpAnimationDefinition(
        String id,
        ResourceLocation animationId,
        boolean squadSafe,
        boolean prefersHiddenItem,
        String nameKey,
        String descKey
) {
    private static MvpAnimationDefinition def(String id, boolean squadSafe, boolean hiddenItem) {
        return new MvpAnimationDefinition(
                id,
                ResourceLocation.fromNamespaceAndPath("habitrain_core", id),
                squadSafe,
                hiddenItem,
                "habitrain_core.mvp_anim." + id + ".name",
                "habitrain_core.mvp_anim." + id + ".desc"
        );
    }

    public static final List<MvpAnimationDefinition> BUILT_INS = List.of(
            def("victory_bow", true, false),
            def("penguin_dance", true, true),
            def("cool_sit", false, true),
            def("victory_dab", true, true),
            def("victory_floss", false, true),
            def("grace_pose", true, false),
            def("heart_pose", true, true),
            def("victory_jump", true, true),
            def("meditation_fly", false, true),
            def("champion_tpose", true, false),
            def("victory_backflip", false, true),
            def("champion_clap", true, true),
            def("come_here", true, true),
            def("kazotsky_victory", false, true),
            def("victory_palm", true, true),
            def("victory_point", true, true),
            def("potion_dance", false, true),
            def("champion_wave", true, true),
            def("royal_salute", true, true),
            def("fist_pump", true, true),
            def("power_pose", true, false),
            def("star_pose", true, true),
            def("cross_arms", true, true),
            def("double_cheer", true, true),
            def("disco_point", false, true),
            def("victory_spin", false, true),
            def("humble_thanks", true, true),
            def("shoulder_dance", false, true),
            def("sky_punch", true, true),
            def("hero_landing", false, true)
    );

    public static final Map<String, MvpAnimationDefinition> BY_ID;

    static {
        Map<String, MvpAnimationDefinition> map = new LinkedHashMap<>();
        for (MvpAnimationDefinition def : BUILT_INS) {
            map.put(def.id(), def);
        }
        BY_ID = Collections.unmodifiableMap(map);
    }

    public static MvpAnimationDefinition get(String id) {
        return BY_ID.get(id);
    }
}
