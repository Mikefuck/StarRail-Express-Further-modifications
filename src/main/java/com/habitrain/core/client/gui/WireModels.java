package com.habitrain.core.client.gui;

import net.minecraft.util.Mth;

/**
 * 背包车票页的线框模型：两侧的蒸汽机车与星穹仪，以及邮票里的小图标。
 *
 * <p>静态部分只建一次；车轮、连杆、枕木、星穹仪的环之类随时间变化的部分每帧写进调用方给的
 * {@link Wire3D.Mesh}。坐标单位随意，只要与 {@link Wire3D.View} 的缩放、半径一致。</p>
 */
final class WireModels {
    private WireModels() {
    }

    // ==================== 蒸汽机车 ====================

    /** 机车模型的中心（煤水车尾到排障器的中点）与包围半径。 */
    static final float LOCO_CENTER_X = -1.6f;
    static final float LOCO_CENTER_Y = 2.0f;
    static final float LOCO_RADIUS = 7.6f;
    /** 车灯与烟囱口的位置（发光与烟圈用）。 */
    static final float LAMP_X = 4.62f;
    static final float LAMP_Y = 3.25f;
    static final float CHIMNEY_X = 3.75f;
    static final float CHIMNEY_TOP_Y = 4.25f;

    private static final float[] DRIVER_X = {-1.25f, 0.55f, 2.35f};
    private static final float DRIVER_R = 0.82f;
    private static final float RAIL_Z = 1.42f;

    static final Wire3D.Mesh LOCOMOTIVE = buildLocomotive();

    private static Wire3D.Mesh buildLocomotive() {
        Wire3D.Mesh m = new Wire3D.Mesh();
        // 车架 / 走板
        m.box(-4.7f, 0.95f, -1.38f, 4.45f, 1.22f, 1.38f);
        // 锅炉 + 两道箍 + 扶手
        m.cylinderX(-1.6f, 3.35f, 2.15f, 0.0f, 1.0f, 16, 8, 0.0f, 1.7f);
        m.line(-1.4f, 2.62f, 1.12f, 3.3f, 2.62f, 1.12f);
        m.line(-1.4f, 2.62f, -1.12f, 3.3f, 2.62f, -1.12f);
        // 烟箱与烟箱门
        m.cylinderX(3.35f, 4.25f, 2.15f, 0.0f, 1.08f, 16, 8);
        m.circleX(4.27f, 2.15f, 0.0f, 0.74f, 14);
        m.circleX(4.29f, 2.15f, 0.0f, 0.15f, 8);
        m.line(4.29f, 2.15f, -0.74f, 4.29f, 2.15f, 0.74f);
        // 烟囱（上口外翻）、汽包、砂箱
        m.lathe(CHIMNEY_X, 3.05f, 0.0f, new float[] {0.34f, 0.0f, 0.3f, 0.62f, 0.4f, 1.05f, 0.46f, 1.2f}, 12, 6);
        m.lathe(1.0f, 3.02f, 0.0f, new float[] {0.56f, 0.0f, 0.5f, 0.24f, 0.32f, 0.48f, 0.0f, 0.58f}, 12, 6);
        m.lathe(-0.55f, 3.02f, 0.0f, new float[] {0.42f, 0.0f, 0.36f, 0.2f, 0.0f, 0.34f}, 10, 4);
        // 车头灯
        m.box(4.2f, 3.05f, -0.22f, 4.62f, 3.45f, 0.22f);
        // 驾驶室 + 外挑的车顶 + 侧窗 + 前窗
        m.box(-4.7f, 1.22f, -1.3f, -1.6f, 4.05f, 1.3f);
        m.box(-5.0f, 4.05f, -1.52f, -1.3f, 4.28f, 1.52f);
        m.rectZ(1.3f, -3.95f, 2.75f, -2.45f, 3.65f);
        m.rectZ(-1.3f, -3.95f, 2.75f, -2.45f, 3.65f);
        m.rectX(-1.6f, 3.0f, 0.55f, 3.75f, 1.1f);
        m.rectX(-1.6f, 3.0f, -1.1f, 3.75f, -0.55f);
        m.line(-2.15f, 1.22f, 1.3f, -2.15f, 3.65f, 1.3f);
        // 气缸
        for (int side = -1; side <= 1; side += 2) {
            float z = side * 1.48f;
            m.cylinderX(2.85f, 3.85f, 1.02f, z, 0.32f, 10, 4);
        }
        // 排障器：楔形 + 横档
        float px0 = 4.45f;
        float px1 = 5.4f;
        m.path(false, px0, 1.0f, -1.25f, px1, 0.16f, -0.42f, px1, 0.16f, 0.42f, px0, 1.0f, 1.25f);
        m.line(px0, 0.16f, -1.25f, px1, 0.16f, -0.42f);
        m.line(px0, 0.16f, 1.25f, px1, 0.16f, 0.42f);
        for (int k = 1; k <= 3; k++) {
            float t = k / 4.0f;
            float x = Mth.lerp(t, px0, px1);
            float y = Mth.lerp(t, 1.0f, 0.16f);
            float z = Mth.lerp(t, 1.25f, 0.42f);
            m.line(x, y, -z, x, y, z);
        }
        // 缓冲梁
        m.box(4.45f, 0.7f, -1.38f, 4.58f, 1.22f, 1.38f);
        // 煤水车：车体 + 煤堆脊线 + 边沿
        m.box(-9.1f, 0.95f, -1.3f, -5.2f, 3.1f, 1.3f);
        m.box(-9.1f, 0.75f, -1.38f, -5.2f, 0.95f, 1.38f);
        m.path(false, -8.8f, 3.1f, 0.0f, -8.0f, 3.55f, 0.0f, -6.6f, 3.7f, 0.0f, -5.5f, 3.1f, 0.0f);
        m.line(-8.0f, 3.55f, 0.0f, -8.4f, 3.1f, 1.0f);
        m.line(-6.6f, 3.7f, 0.0f, -6.1f, 3.1f, -1.0f);
        m.line(-5.2f, 1.6f, 0.0f, -4.7f, 1.6f, 0.0f);
        return m;
    }

    /**
     * 机车的运动部分：车轮辐条、连杆、钢轨与滚动的枕木。
     *
     * @param wheel   车轮转角（弧度）
     * @param travel  已「行驶」的距离（枕木随之向后滚动）
     */
    static void locomotiveMotion(Wire3D.Mesh m, float wheel, float travel) {
        for (int side = -1; side <= 1; side += 2) {
            float z = side * RAIL_Z;
            for (float x : DRIVER_X) {
                wheel(m, x, DRIVER_R, z, DRIVER_R, wheel, 8);
            }
            // 导轮、从轮、煤水车轮转得更快（半径小）
            wheel(m, 3.75f, 0.5f, z, 0.5f, wheel * DRIVER_R / 0.5f, 4);
            wheel(m, -3.6f, 0.55f, z, 0.55f, wheel * DRIVER_R / 0.55f, 4);
            for (float x : new float[] {-8.3f, -7.15f, -6.0f}) {
                wheel(m, x, 0.5f, z, 0.5f, wheel * DRIVER_R / 0.5f, 4);
            }
            // 连杆：三个曲柄销连成一线，主连杆通到十字头
            float crank = 0.42f;
            float zr = z + side * 0.12f;
            float cx = Mth.cos(wheel) * crank;
            float cy = Mth.sin(wheel) * crank;
            m.line(DRIVER_X[0] + cx, DRIVER_R + cy, zr, DRIVER_X[2] + cx, DRIVER_R + cy, zr);
            float crossX = 3.0f + Mth.cos(wheel) * 0.25f;
            m.line(DRIVER_X[1] + cx, DRIVER_R + cy, zr + side * 0.04f, crossX, 1.02f, zr + side * 0.04f);
        }
        // 钢轨（两条，各画轨头一道）与枕木
        float railStart = -12.0f;
        float railEnd = 8.5f;
        m.line(railStart, 0.0f, RAIL_Z, railEnd, 0.0f, RAIL_Z);
        m.line(railStart, 0.0f, -RAIL_Z, railEnd, 0.0f, -RAIL_Z);
        float pitch = 1.05f;
        float phase = travel % pitch;
        if (phase < 0.0f) {
            phase += pitch;
        }
        for (float x = railStart + pitch - phase; x < railEnd; x += pitch) {
            m.line(x, -0.08f, -2.0f, x, -0.08f, 2.0f);
        }
    }

    private static void wheel(Wire3D.Mesh m, float x, float y, float z, float r, float angle, int spokes) {
        m.circle(x, y, z, r, 1, 0, 0, 0, 1, 0, 18);
        m.circle(x, y, z, r * 0.22f, 1, 0, 0, 0, 1, 0, 6);
        for (int i = 0; i < spokes; i++) {
            float a = angle + Mth.TWO_PI * i / spokes;
            m.line(x + Mth.cos(a) * r * 0.22f, y + Mth.sin(a) * r * 0.22f, z,
                    x + Mth.cos(a) * r * 0.92f, y + Mth.sin(a) * r * 0.92f, z);
        }
    }

    /** 一圈烟：水平椭圆环，随 age 0→1 上升、变大、后飘。 */
    static void smokeRing(Wire3D.Mesh m, float age, float wobble) {
        float y = CHIMNEY_TOP_Y + age * 2.8f;
        float x = CHIMNEY_X - age * 3.2f;
        float r = 0.32f + age * 1.15f;
        float tilt = 0.25f * Mth.sin(wobble);
        float c = Mth.cos(tilt);
        float s = Mth.sin(tilt);
        m.circle(x, y, 0.0f, r, c, s, 0, 0, 0, 1, 14);
        m.circle(x, y + r * 0.18f, 0.0f, r * 0.62f, c, s, 0, 0, 0, 1, 10);
    }

    // ==================== 星穹仪 ====================

    static final float ARMILLARY_RADIUS = 2.05f;
    /** 黄赤交角。 */
    private static final float TILT = 0.41f;

    private static final Wire3D.Mesh ARMILLARY_STAND = buildArmillaryStand();

    private static Wire3D.Mesh buildArmillaryStand() {
        Wire3D.Mesh m = new Wire3D.Mesh();
        // 地平环 + 刻度
        m.circleY(0.0f, 0.0f, 0.0f, 1.12f, 40);
        m.circleY(0.0f, 0.0f, 0.0f, 1.22f, 40);
        for (int i = 0; i < 24; i++) {
            float a = Mth.TWO_PI * i / 24.0f;
            float in = i % 6 == 0 ? 1.04f : 1.12f;
            m.line(Mth.cos(a) * in, 0.0f, Mth.sin(a) * in, Mth.cos(a) * 1.22f, 0.0f, Mth.sin(a) * 1.22f);
        }
        // 子午环
        m.circle(0.0f, 0.0f, 0.0f, 1.12f, 1, 0, 0, 0, 1, 0, 44);
        // 底座：立柱 + 托架 + 底盘
        m.line(0.0f, -1.12f, 0.0f, 0.0f, -1.5f, 0.0f);
        m.lathe(0.0f, -1.95f, 0.0f, new float[] {0.62f, 0.0f, 0.6f, 0.08f, 0.32f, 0.2f, 0.1f, 0.34f, 0.08f, 0.45f}, 16, 8);
        m.line(-1.22f, 0.0f, 0.0f, -0.6f, -1.5f, 0.0f);
        m.line(1.22f, 0.0f, 0.0f, 0.6f, -1.5f, 0.0f);
        m.line(-0.6f, -1.5f, 0.0f, 0.6f, -1.5f, 0.0f);
        return m;
    }

    /**
     * 星穹仪的转动部分：绕倾斜极轴自转的赤道环、二分二至圈、回归线与黄道环。
     *
     * @param spin 自转角（弧度）
     */
    static void armillary(Wire3D.Mesh m, float spin) {
        m.append(ARMILLARY_STAND);
        // 极轴 a 与赤道面基向量 e1、e2（随自转）
        float ax = Mth.sin(TILT);
        float ay = Mth.cos(TILT);
        float bx = Mth.cos(TILT);
        float by = -Mth.sin(TILT);
        float c = Mth.cos(spin);
        float s = Mth.sin(spin);
        // e1 = b·cos + z·sin, e2 = -b·sin + z·cos
        float e1x = bx * c;
        float e1y = by * c;
        float e1z = s;
        float e2x = -bx * s;
        float e2y = -by * s;
        float e2z = c;
        float r = 0.95f;
        m.line(-ax * 1.45f, -ay * 1.45f, 0.0f, ax * 1.45f, ay * 1.45f, 0.0f);
        // 赤道
        m.circle(0, 0, 0, r, e1x, e1y, e1z, e2x, e2y, e2z, 36);
        // 二分圈与二至圈（含极轴）
        m.circle(0, 0, 0, r, e1x, e1y, e1z, ax, ay, 0, 36);
        m.circle(0, 0, 0, r, e2x, e2y, e2z, ax, ay, 0, 36);
        // 南北回归线
        for (int k = -1; k <= 1; k += 2) {
            float h = 0.4f * r;
            float rr = Mth.sqrt(r * r - h * h);
            m.circle(ax * h * k, ay * h * k, 0, rr, e1x, e1y, e1z, e2x, e2y, e2z, 28);
        }
        // 黄道：赤道面绕 e1 再倾 23°
        float t = 0.41f;
        float ct = Mth.cos(t);
        float st = Mth.sin(t);
        m.circle(0, 0, 0, r * 0.99f, e1x, e1y, e1z, e2x * ct + ax * st, e2y * ct + ay * st, e2z * ct, 36);
        // 中心的小天球
        m.circle(0, 0, 0, 0.16f, 1, 0, 0, 0, 1, 0, 10);
        m.circle(0, 0, 0, 0.16f, 0, 0, 1, 0, 1, 0, 10);
    }

    /** 黄道上第 i 颗行星在角 angle 处的模型坐标（写入 out[0..2]）。 */
    static void eclipticPoint(float spin, float angle, float[] out) {
        float ax = Mth.sin(TILT);
        float ay = Mth.cos(TILT);
        float bx = Mth.cos(TILT);
        float by = -Mth.sin(TILT);
        float c = Mth.cos(spin);
        float s = Mth.sin(spin);
        float e1x = bx * c;
        float e1y = by * c;
        float e1z = s;
        float ct = Mth.cos(0.41f);
        float st = Mth.sin(0.41f);
        float vx = -bx * s * ct + ax * st;
        float vy = -by * s * ct + ay * st;
        float vz = c * ct;
        float r = 0.94f;
        float ca = Mth.cos(angle);
        float sa = Mth.sin(angle);
        out[0] = (e1x * ca + vx * sa) * r;
        out[1] = (e1y * ca + vy * sa) * r;
        out[2] = (e1z * ca + vz * sa) * r;
    }

    // ==================== 邮票图标 ====================

    /** 邮票图标：按菜单项的翻译键挑选，未知菜单项用水晶。模型都缩放到半径约 1。 */
    enum Icon {
        TOP_HAT, GLOBE, TURNTABLE, TROPHY, DRESS_FORM, SUITCASE, GEAR, STOPWATCH, CARDS, MAGNIFIER, GIFT,
        ENVELOPE, CRYSTAL;

        private Wire3D.Mesh mesh;

        Wire3D.Mesh mesh() {
            if (mesh == null) {
                mesh = buildIcon(this);
            }
            return mesh;
        }
    }

    private static Wire3D.Mesh buildIcon(Icon icon) {
        Wire3D.Mesh m = new Wire3D.Mesh();
        switch (icon) {
            case TOP_HAT -> {
                m.lathe(0, -0.62f, 0, new float[] {1.0f, 0.0f, 0.6f, 0.1f, 0.55f, 1.0f, 0.6f, 1.12f, 0.0f, 1.12f}, 18, 8);
                m.circleY(0, -0.3f, 0, 0.575f, 18);
            }
            case GLOBE -> {
                float tilt = 0.4f;
                float ax = Mth.sin(tilt);
                float ay = Mth.cos(tilt);
                for (int k = -1; k <= 1; k++) {
                    float h = k * 0.5f;
                    float rr = Mth.sqrt(1.0f - h * h) * 0.85f;
                    m.circle(ax * h * 0.85f, ay * h * 0.85f, 0, rr, Mth.cos(tilt), -Mth.sin(tilt), 0, 0, 0, 1, 20);
                }
                for (int i = 0; i < 4; i++) {
                    float a = Mth.PI * i / 4.0f;
                    m.circle(0, 0, 0, 0.85f, Mth.cos(tilt) * Mth.cos(a), -Mth.sin(tilt) * Mth.cos(a), Mth.sin(a),
                            ax, ay, 0, 22);
                }
                m.line(-ax * 1.1f, -ay * 1.1f, 0, ax * 1.1f, ay * 1.1f, 0);
                m.arc(0, 0, 0, 1.02f, Mth.cos(tilt), -Mth.sin(tilt), 0, ax, ay, 0, -0.2f, Mth.PI + 0.2f, 16);
            }
            case TURNTABLE -> {
                m.circleY(0, 0, 0, 1.0f, 28);
                m.circleY(0, -0.18f, 0, 0.88f, 28);
                m.circleY(0, -0.18f, 0, 0.12f, 8);
                for (int side = -1; side <= 1; side += 2) {
                    m.line(-0.88f, -0.05f, side * 0.14f, 0.88f, -0.05f, side * 0.14f);
                }
                for (int i = -2; i <= 2; i++) {
                    m.line(i * 0.35f, -0.08f, -0.24f, i * 0.35f, -0.08f, 0.24f);
                }
                for (int i = 0; i < 3; i++) {
                    float a = Mth.PI * 0.35f + i * 0.55f;
                    float c = Mth.cos(a);
                    float s = Mth.sin(a);
                    for (int side = -1; side <= 1; side += 2) {
                        float ox = -s * side * 0.14f;
                        float oz = c * side * 0.14f;
                        m.line(c * 1.0f + ox, 0, s * 1.0f + oz, c * 1.35f + ox, 0, s * 1.35f + oz);
                    }
                }
            }
            case TROPHY -> {
                m.lathe(0, -1.0f, 0, new float[] {0.55f, 0.0f, 0.55f, 0.14f, 0.2f, 0.24f, 0.1f, 0.62f, 0.26f, 0.78f,
                        0.6f, 1.12f, 0.66f, 1.62f}, 16, 8);
                for (int side = -1; side <= 1; side += 2) {
                    m.arc(side * 0.66f, 0.38f, 0, 0.3f, side, 0, 0, 0, 1, 0, -Mth.HALF_PI * 1.1f, Mth.HALF_PI * 1.1f, 10);
                }
            }
            case DRESS_FORM -> m.lathe(0, -1.05f, 0, new float[] {0.5f, 0.0f, 0.06f, 0.1f, 0.06f, 0.62f, 0.44f, 0.72f,
                    0.36f, 1.05f, 0.5f, 1.4f, 0.42f, 1.72f, 0.13f, 1.82f, 0.1f, 2.0f, 0.0f, 2.04f}, 16, 8);
            case SUITCASE -> {
                m.box(-1.0f, -0.62f, -0.32f, 1.0f, 0.62f, 0.32f);
                m.path(false, -0.32f, 0.62f, 0, -0.28f, 0.92f, 0, 0.28f, 0.92f, 0, 0.32f, 0.62f, 0);
                for (int side = -1; side <= 1; side += 2) {
                    float x = side * 0.55f;
                    m.path(true, x, -0.62f, -0.33f, x, 0.62f, -0.33f, x, 0.62f, 0.33f, x, -0.62f, 0.33f);
                }
                m.line(-1.0f, 0.0f, -0.33f, 1.0f, 0.0f, -0.33f);
            }
            case GEAR -> {
                int teeth = 9;
                float[] outline = new float[teeth * 8];
                for (int i = 0; i < teeth; i++) {
                    float a = Mth.TWO_PI * i / teeth;
                    float w = Mth.TWO_PI / teeth;
                    float[] angles = {a, a + w * 0.18f, a + w * 0.32f, a + w * 0.68f};
                    float[] radii = {0.72f, 1.0f, 1.0f, 0.72f};
                    for (int k = 0; k < 4; k++) {
                        outline[i * 8 + k * 2] = Mth.cos(angles[k] + w * 0.1f) * radii[k];
                        outline[i * 8 + k * 2 + 1] = Mth.sin(angles[k] + w * 0.1f) * radii[k];
                    }
                }
                m.prism(outline, -0.22f, 0.22f);
                m.circleZ(0, 0, -0.22f, 0.32f, 12);
                m.circleZ(0, 0, 0.22f, 0.32f, 12);
                for (int i = 0; i < 4; i++) {
                    float a = Mth.HALF_PI * i + 0.4f;
                    m.line(Mth.cos(a) * 0.32f, Mth.sin(a) * 0.32f, -0.22f, Mth.cos(a) * 0.32f, Mth.sin(a) * 0.32f, 0.22f);
                }
            }
            case STOPWATCH -> {
                m.cylinderZ(-0.2f, 0.2f, 0, -0.08f, 0.88f, 24, 8);
                m.circleZ(0, -0.08f, -0.22f, 0.72f, 24);
                for (int i = 0; i < 12; i++) {
                    float a = Mth.TWO_PI * i / 12.0f;
                    float in = i % 3 == 0 ? 0.56f : 0.64f;
                    m.line(Mth.cos(a) * in, -0.08f + Mth.sin(a) * in, -0.22f, Mth.cos(a) * 0.72f,
                            -0.08f + Mth.sin(a) * 0.72f, -0.22f);
                }
                m.line(0, -0.08f, -0.24f, 0.0f, 0.46f, -0.24f);
                m.line(0, -0.08f, -0.24f, 0.36f, 0.12f, -0.24f);
                m.box(-0.12f, 0.8f, -0.12f, 0.12f, 1.02f, 0.12f);
                m.box(-0.2f, 1.02f, -0.15f, 0.2f, 1.1f, 0.15f);
            }
            case CARDS -> {
                for (int i = -1; i <= 1; i++) {
                    float a = i * 0.32f;
                    float c = Mth.cos(a);
                    float s = Mth.sin(a);
                    float[] corners = {-0.55f, -0.8f, 0.55f, -0.8f, 0.55f, 0.8f, -0.55f, 0.8f};
                    float[] rot = new float[8];
                    for (int k = 0; k < 4; k++) {
                        float x = corners[k * 2];
                        float y = corners[k * 2 + 1] + 0.15f;
                        rot[k * 2] = x * c - y * s + i * 0.18f;
                        rot[k * 2 + 1] = x * s + y * c - 0.15f;
                    }
                    m.prism(rot, i * 0.14f - 0.02f, i * 0.14f + 0.02f);
                }
            }
            case MAGNIFIER -> {
                m.circleZ(-0.25f, 0.25f, -0.08f, 0.62f, 22);
                m.circleZ(-0.25f, 0.25f, 0.08f, 0.62f, 22);
                m.circleZ(-0.25f, 0.25f, 0.0f, 0.5f, 20);
                float hx = -0.25f + 0.62f * 0.707f;
                float hy = 0.25f - 0.62f * 0.707f;
                m.line(hx, hy, -0.06f, hx + 0.62f, hy - 0.62f, -0.06f);
                m.line(hx, hy, 0.06f, hx + 0.62f, hy - 0.62f, 0.06f);
                m.line(hx + 0.62f, hy - 0.62f, -0.06f, hx + 0.62f, hy - 0.62f, 0.06f);
            }
            case GIFT -> {
                m.box(-0.8f, -0.8f, -0.8f, 0.8f, 0.4f, 0.8f);
                m.box(-0.9f, 0.4f, -0.9f, 0.9f, 0.62f, 0.9f);
                m.line(0, -0.8f, -0.81f, 0, 0.62f, -0.81f);
                m.line(-0.81f, -0.8f, 0, -0.81f, 0.62f, 0);
                m.circle(-0.28f, 0.82f, 0, 0.26f, 1, 0, 0, 0, 1, 0.4f, 10);
                m.circle(0.28f, 0.82f, 0, 0.26f, 1, 0, 0, 0, 1, -0.4f, 10);
            }
            case ENVELOPE -> {
                m.box(-1.0f, -0.62f, -0.06f, 1.0f, 0.62f, 0.06f);
                m.path(false, -1.0f, 0.62f, -0.07f, 0.0f, -0.05f, -0.07f, 1.0f, 0.62f, -0.07f);
                m.line(-1.0f, -0.62f, -0.07f, -0.3f, 0.0f, -0.07f);
                m.line(1.0f, -0.62f, -0.07f, 0.3f, 0.0f, -0.07f);
            }
            default -> {
                // 水晶：拉长的八面体 + 腰线
                float h = 1.1f;
                float r = 0.62f;
                float[][] ring = {{r, 0}, {0, r}, {-r, 0}, {0, -r}};
                for (int i = 0; i < 4; i++) {
                    float[] a = ring[i];
                    float[] b = ring[(i + 1) % 4];
                    m.line(a[0], 0.15f, a[1], b[0], 0.15f, b[1]);
                    m.line(a[0], 0.15f, a[1], 0, h, 0);
                    m.line(a[0], 0.15f, a[1], 0, -h * 0.9f, 0);
                }
                m.circleY(0, 0.15f, 0, r * 0.72f, 12);
            }
        }
        return m;
    }
}
