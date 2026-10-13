package com.habitrain.core.client.gui.menu.page;

import com.habitrain.core.client.gui.menu.MenuTheme;
import net.minecraft.network.chat.Component;

/** Shared geometry records and preset data for the scene motion editor. */
final class SceneMotionPageLayout {
    static final DirectionPreset[] DIRECTION_PRESETS = {
            new DirectionPreset("+X", 1.0, 0.0, 0.0),
            new DirectionPreset("-X", -1.0, 0.0, 0.0),
            new DirectionPreset("+Y", 0.0, 1.0, 0.0),
            new DirectionPreset("-Y", 0.0, -1.0, 0.0),
            new DirectionPreset("+Z", 0.0, 0.0, 1.0),
            new DirectionPreset("-Z", 0.0, 0.0, -1.0)
    };

    static final OrbitDirPreset[] ORBIT_DIR_PRESETS = {
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_n", 0.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_ne", 45.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_e", 90.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_se", 135.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_s", 180.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_sw", 225.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_w", 270.0),
            new OrbitDirPreset("screen.habitrain_core.scene_motion.orbit_dir_nw", 315.0)
    };

    static final double[] ORBIT_SWEEP_PRESETS = {0.0, 90.0, 180.0, 270.0, 360.0};

    record DirectionPreset(String label, double x, double y, double z) {}

    record OrbitDirPreset(String labelKey, double angle) {}

    record TooltipTarget(HitBox bounds, Component text) {}

    record HitBox(int x, int y, int width, int height) {
        static final HitBox EMPTY = new HitBox(0, 0, 0, 0);

        boolean contains(double mx, double my) {
            return width > 0 && height > 0 && MenuTheme.inBounds(mx, my, x, y, width, height);
        }
    }

    private SceneMotionPageLayout() {}
}
