package com.habitrain.core.client.gui.menu.ui;

import com.habitrain.core.client.gui.menu.MenuTheme;
import com.habitrain.core.scene.model.SceneOrbitAxis;
import com.habitrain.core.scene.model.SceneOrbitMath;
import com.habitrain.core.scene.model.SceneOrbitSettings;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * 像素级交互式旋转路径方向盘（用于围绕中心旋转的起始方向与圆弧可视化调节）。
 */
public final class SceneAngleDial {
    public static final int HEIGHT = 104;
    public static final int DIAL_RADIUS = 30;

    private SceneAngleDial() {}

    public static void render(GuiGraphics graphics, Font font, int x, int y, int width,
                              SceneOrbitAxis axis, double startAngle, double sweepDegrees,
                              boolean clockwise, boolean editable, boolean isModelCenter,
                              boolean focused) {
        render(graphics, font, x, y, width, axis, startAngle, sweepDegrees,
                clockwise, 1, SceneOrbitSettings.DEFAULT_SPREAD,
                editable, isModelCenter, focused);
    }

    public static void render(GuiGraphics graphics, Font font, int x, int y, int width,
                              SceneOrbitAxis axis, double startAngle, double sweepDegrees,
                              boolean clockwise, int instanceCount, double instanceSpreadDegrees,
                              boolean editable, boolean isModelCenter, boolean focused) {
        int safeWidth = Math.max(120, width);
        int cx = x + safeWidth / 2;
        int cy = y + 50;

        // 背景与外边框
        graphics.fill(x, y, x + safeWidth, y + HEIGHT, MenuTheme.BG_ELEVATED);
        MenuTheme.outline(graphics, x, y, safeWidth, HEIGHT,
                focused ? MenuTheme.ACCENT_MINT : MenuTheme.BORDER_SOFT);

        // 四方向世界标签
        String topLabel = getLocalizedLabel(axis, 0);
        String rightLabel = getLocalizedLabel(axis, 90);
        String bottomLabel = getLocalizedLabel(axis, 180);
        String leftLabel = getLocalizedLabel(axis, 270);

        graphics.drawString(font, topLabel, cx - font.width(topLabel) / 2, cy - DIAL_RADIUS - 12,
                MenuTheme.TEXT_SECONDARY, false);
        graphics.drawString(font, bottomLabel, cx - font.width(bottomLabel) / 2, cy + DIAL_RADIUS + 4,
                MenuTheme.TEXT_SECONDARY, false);
        graphics.drawString(font, leftLabel, cx - DIAL_RADIUS - font.width(leftLabel) - 6, cy - 4,
                MenuTheme.TEXT_SECONDARY, false);
        graphics.drawString(font, rightLabel, cx + DIAL_RADIUS + 6, cy - 4,
                MenuTheme.TEXT_SECONDARY, false);

        // 完整圆周刻度圈（细虚线点阵）
        int segments = 36;
        for (int i = 0; i < segments; i++) {
            double ang = Math.toRadians(i * 10.0);
            int px = cx + (int) Math.round(DIAL_RADIUS * Math.sin(ang));
            int py = cy - (int) Math.round(DIAL_RADIUS * Math.cos(ang));
            graphics.fill(px, py, px + 1, py + 1, MenuTheme.BORDER);
        }

        // 中心方块原点
        graphics.fill(cx - 2, cy - 2, cx + 3, cy + 3, MenuTheme.TEXT_PRIMARY);

        // 运动圆弧
        double normStart = SceneOrbitMath.normalizeAngle(startAngle);
        double normSweep = Math.max(0.0, Math.min(360.0, sweepDegrees));
        int arcColor = editable ? MenuTheme.ACCENT_BLUE : MenuTheme.TEXT_DIM;

        if (normSweep >= 360.0) {
            // 整圈绘制
            drawArc(graphics, cx, cy, DIAL_RADIUS, 0.0, 360.0, true, arcColor);
        } else if (normSweep > 0.5) {
            drawArc(graphics, cx, cy, DIAL_RADIUS, normStart, normSweep, clockwise, arcColor);
        }

        // 多副本的开局位置；整圈分布使用 N 作除数，避免首尾 0°/360° 重合。
        List<Double> markerAngles = getInstanceMarkerAngles(
                normStart, instanceCount, instanceSpreadDegrees, clockwise);
        for (int i = 1; i < markerAngles.size(); i++) {
            double rad = Math.toRadians(markerAngles.get(i));
            int markerRadius = DIAL_RADIUS - 7;
            int px = cx + (int) Math.round(markerRadius * Math.sin(rad));
            int py = cy - (int) Math.round(markerRadius * Math.cos(rad));
            graphics.fill(px - 2, py - 2, px + 3, py + 3, MenuTheme.ACCENT_VIOLET);
            MenuTheme.outline(graphics, px - 2, py - 2, 5, 5, MenuTheme.BG_DARK);
        }

        // 起点手柄
        double radStart = Math.toRadians(normStart);
        int sx = cx + (int) Math.round(DIAL_RADIUS * Math.sin(radStart));
        int sy = cy - (int) Math.round(DIAL_RADIUS * Math.cos(radStart));
        int handleColor = isModelCenter ? MenuTheme.TEXT_DIM
                : (editable ? MenuTheme.ACCENT_AMBER : MenuTheme.BORDER);
        graphics.fill(sx - 3, sy - 3, sx + 4, sy + 4, handleColor);
        MenuTheme.outline(graphics, sx - 3, sy - 3, 7, 7, MenuTheme.BG_DARK);

        // 终点标记（非 360° 模式）
        if (normSweep < 360.0 && normSweep > 1.0) {
            double endAngle = SceneOrbitMath.normalizeAngle(normStart + (clockwise ? normSweep : -normSweep));
            double radEnd = Math.toRadians(endAngle);
            int ex = cx + (int) Math.round(DIAL_RADIUS * Math.sin(radEnd));
            int ey = cy - (int) Math.round(DIAL_RADIUS * Math.cos(radEnd));
            graphics.fill(ex - 2, ey - 2, ex + 3, ey + 3, MenuTheme.ACCENT_MINT);
        }

        // 顺/逆时针箭头与文字标记
        String dirSymbol = (clockwise ? "↻ " : "↺ ") + Component.translatable(clockwise
                ? "screen.habitrain_core.scene_motion.orbit_clockwise"
                : "screen.habitrain_core.scene_motion.orbit_counter_clockwise").getString();
        graphics.drawString(font, dirSymbol, x + 6, y + 6, MenuTheme.ACCENT_BLUE, false);

        String sweepInfo = Component.translatable("screen.habitrain_core.scene_motion.orbit_arc_degrees",
                String.format(Locale.ROOT, "%.1f", normSweep)).getString();
        graphics.drawString(font, sweepInfo, x + safeWidth - font.width(sweepInfo) - 6, y + 6,
                MenuTheme.TEXT_SECONDARY, false);

        // 模型中心模式只读提示
        if (isModelCenter) {
            String lockedHint = Component.translatable(
                    "screen.habitrain_core.scene_motion.orbit_start_angle_locked_short").getString();
            graphics.drawString(font, lockedHint, cx - font.width(lockedHint) / 2, y + HEIGHT - 12,
                    MenuTheme.TEXT_DIM, false);
        }
    }

    private static void drawArc(GuiGraphics graphics, int cx, int cy, int r,
                                double startAngle, double sweep, boolean clockwise, int color) {
        int steps = Math.max(4, (int) Math.ceil(sweep / 3.0));
        double stepDeg = sweep / steps;
        for (int i = 0; i <= steps; i++) {
            double a = startAngle + (clockwise ? 1.0 : -1.0) * (i * stepDeg);
            double rad = Math.toRadians(a);
            int px = cx + (int) Math.round(r * Math.sin(rad));
            int py = cy - (int) Math.round(r * Math.cos(rad));
            graphics.fill(px - 1, py - 1, px + 2, py + 2, color);
        }
    }

    public static List<Double> getInstanceMarkerAngles(double startAngle, int instanceCount,
                                                        double spreadDegrees, boolean clockwise) {
        int count = Math.max(SceneOrbitSettings.MIN_INSTANCES,
                Math.min(SceneOrbitSettings.MAX_INSTANCES, instanceCount));
        List<Double> angles = new ArrayList<>(count);
        double sign = clockwise ? 1.0 : -1.0;
        for (int i = 0; i < count; i++) {
            angles.add(SceneOrbitMath.normalizeAngle(startAngle
                    + sign * SceneOrbitMath.instanceOffsetAngle(i, count, spreadDegrees)));
        }
        return angles;
    }

    public static boolean isMouseOverDial(double mx, double my, int x, int y, int width) {
        int cx = x + width / 2;
        int cy = y + 50;
        double dx = mx - cx;
        double dy = my - cy;
        double distSq = dx * dx + dy * dy;
        return distSq <= (DIAL_RADIUS + 12) * (DIAL_RADIUS + 12);
    }

    public static boolean handleClick(double mx, double my, int x, int y, int width,
                                      boolean editable, boolean isModelCenter,
                                      DoubleConsumer onAngleChanged) {
        if (!editable || isModelCenter || onAngleChanged == null) return false;
        if (!isMouseOverDial(mx, my, x, y, width)) return false;
        applyMouseAngle(mx, my, x, y, width, onAngleChanged);
        return true;
    }

    public static void applyMouseAngle(double mx, double my, int x, int y, int width,
                                       DoubleConsumer onAngleChanged) {
        int cx = x + width / 2;
        int cy = y + 50;
        double dx = mx - cx;
        double dy = my - cy;
        double rad = Math.atan2(dx, -dy);
        double deg = SceneOrbitMath.normalizeAngle(Math.toDegrees(rad));
        onAngleChanged.accept(deg);
    }

    public static String getLabel0(SceneOrbitAxis axis) {
        if (axis == null) axis = SceneOrbitAxis.Y;
        return switch (axis) {
            case Y -> "北 0°";
            case X, Z -> "上 0°";
        };
    }

    public static String getLabel90(SceneOrbitAxis axis) {
        if (axis == null) axis = SceneOrbitAxis.Y;
        return switch (axis) {
            case Y, Z -> "东 90°";
            case X -> "南 90°";
        };
    }

    public static String getLabel180(SceneOrbitAxis axis) {
        if (axis == null) axis = SceneOrbitAxis.Y;
        return switch (axis) {
            case Y -> "南 180°";
            case X, Z -> "下 180°";
        };
    }

    public static String getLabel270(SceneOrbitAxis axis) {
        if (axis == null) axis = SceneOrbitAxis.Y;
        return switch (axis) {
            case Y, Z -> "西 270°";
            case X -> "北 270°";
        };
    }

    public static String getDirectionName(SceneOrbitAxis axis, double angle) {
        double a = SceneOrbitMath.normalizeAngle(angle);
        if (axis == null) axis = SceneOrbitAxis.Y;
        if (axis == SceneOrbitAxis.Y) {
            if (a >= 337.5 || a < 22.5) return "北";
            if (a < 67.5) return "东北";
            if (a < 112.5) return "东";
            if (a < 157.5) return "东南";
            if (a < 202.5) return "南";
            if (a < 247.5) return "西南";
            if (a < 292.5) return "西";
            return "西北";
        } else if (axis == SceneOrbitAxis.X) {
            if (a >= 337.5 || a < 22.5) return "上 (+Y)";
            if (a < 67.5) return "上南";
            if (a < 112.5) return "南 (+Z)";
            if (a < 157.5) return "下南";
            if (a < 202.5) return "下 (-Y)";
            if (a < 247.5) return "下北";
            if (a < 292.5) return "北 (-Z)";
            return "上北";
        } else {
            if (a >= 337.5 || a < 22.5) return "上 (+Y)";
            if (a < 67.5) return "上东";
            if (a < 112.5) return "东 (+X)";
            if (a < 157.5) return "下东";
            if (a < 202.5) return "下 (-Y)";
            if (a < 247.5) return "下西";
            if (a < 292.5) return "西 (-X)";
            return "上西";
        }
    }

    /** Localized direction name used by visible UI and narration. */
    public static Component getDirectionNameComponent(SceneOrbitAxis axis, double angle) {
        SceneOrbitAxis safeAxis = axis != null ? axis : SceneOrbitAxis.Y;
        double normalized = SceneOrbitMath.normalizeAngle(angle);
        int sector = Math.floorMod((int) Math.floor((normalized + 22.5) / 45.0), 8);
        return Component.translatable("screen.habitrain_core.scene_motion.orbit_direction_name."
                + safeAxis.name().toLowerCase(Locale.ROOT) + "." + sector);
    }

    private static String getLocalizedLabel(SceneOrbitAxis axis, int angle) {
        return Component.translatable("screen.habitrain_core.scene_motion.orbit_dial_axis_label",
                getDirectionNameComponent(axis, angle), angle).getString();
    }

    public static Component getPathSummaryComponent(SceneOrbitAxis axis, double startAngle, double sweep, boolean clockwise) {
        double normStart = SceneOrbitMath.normalizeAngle(startAngle);
        double normSweep = Math.max(0.0, Math.min(360.0, sweep));
        Component dir = Component.translatable(clockwise
                ? "screen.habitrain_core.scene_motion.orbit_clockwise"
                : "screen.habitrain_core.scene_motion.orbit_counter_clockwise");
        if (normSweep >= 360.0) {
            return Component.translatable("screen.habitrain_core.scene_motion.orbit_path_summary_loop",
                    dir, String.format(Locale.ROOT, "%.1f", normSweep));
        }
        Component startName = getDirectionNameComponent(axis, normStart);
        double endAngle = SceneOrbitMath.normalizeAngle(normStart + (clockwise ? normSweep : -normSweep));
        Component endName = getDirectionNameComponent(axis, endAngle);
        return Component.translatable("screen.habitrain_core.scene_motion.orbit_path_summary_pingpong",
                startName, String.format(Locale.ROOT, "%.1f", normStart),
                dir, String.format(Locale.ROOT, "%.1f", normSweep),
                endName, String.format(Locale.ROOT, "%.1f", endAngle));
    }

    public static String getPathSummary(SceneOrbitAxis axis, double startAngle, double sweep, boolean clockwise) {
        return getPathSummaryComponent(axis, startAngle, sweep, clockwise).getString();
    }
}
