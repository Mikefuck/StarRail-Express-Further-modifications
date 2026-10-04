package com.habitrain.core.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;

/**
 * 结算转场开头陪纪念车票一起飘落的几张残票：泛黄起皱、缺角撕裂、被火燎过，各自像落叶一样左右摆荡着
 * 落下、掉出屏幕底边——只有纪念车票会贴到屏幕上。其中一张从镜头前不远处擦过，像是差一点就贴上来。
 *
 * <p>残票与纪念车票共用同一张离屏画布（飘落期间票面只有底纹、票头与票根），损毁效果全部由
 * {@code ticket_paper} 着色器按每张残票各自的 Seed / Age / Front / Tear / Smolder 完成；每张有一份
 * {@link PaperSheet} 网格。远近按深度排序：比纪念车票远的在它之前画，近的在它之后画。</p>
 */
final class TicketScraps {
    /** 不烧缺时的 Front（远小于燃烧场的任何取值）。 */
    private static final float NO_BURN = -9.0f;
    /** 残票的 Burn 只作开关；取小值让高光基本保留（着色器按 Burn 削弱高光）。 */
    private static final float SCRAP_BURN = 0.12f;

    /**
     * 一张残票。时间为相对转场打开的毫秒（可为负：打开时已经飘到半空）。
     *
     * @param lane      下落时摆荡中心的横坐标（相对屏幕宽）
     * @param sway      左右摆荡幅度（相对屏幕宽）
     * @param cycles    整个下落过程摆荡几个来回
     * @param depthFrom 开始时的距离（越大越远越小，1 = 贴屏）
     * @param age       做旧程度
     * @param front     烧缺的程度（燃烧场前沿），{@link #NO_BURN} 为没被火燎过
     * @param tearAngle 撕掉一角的方向（票面内，弧度，y 向下）
     * @param tearAt    撕口离票面中心的距离（相对票高），≤ 0 为没有撕口
     * @param smolder   烧缺边缘残余火星的亮度
     * @param crumple   褶皱起伏（相对票高）
     */
    private record Scrap(long startAt, long life, float lane, float sway, float cycles, float phase,
                         float depthFrom, float depthTo, float rollBias, float age, float front,
                         float tearAngle, float tearAt, float smolder, float crumple) {
    }

    private static final Scrap[] SCRAPS = {
            // 左侧远处：底边被烧掉一大块，边缘还有零星火星
            new Scrap(-900L, 2_700L, 0.18f, 0.07f, 1.3f, 0.4f, 5.2f, 4.8f, -0.25f,
                    0.55f, 0.42f, 0.0f, 0.0f, 0.35f, 0.008f),
            // 右侧：泛黄，左上角被撕掉
            new Scrap(-400L, 2_500L, 0.84f, 0.06f, 1.1f, 2.2f, 4.0f, 3.6f, 0.30f,
                    0.45f, NO_BURN, -2.3f, 0.60f, 0.0f, 0.006f),
            // 打开时已在半空：右下角被撕掉一大片
            new Scrap(-1_300L, 2_400L, 0.62f, 0.08f, 1.2f, 4.1f, 4.6f, 4.2f, 0.10f,
                    0.35f, NO_BURN, 0.6f, 0.35f, 0.0f, 0.006f),
            // 镜头前擦过：烧缺 + 撕裂，一直比纪念车票更近，很快落出屏幕底边
            new Scrap(200L, 1_000L, 0.38f, 0.10f, 0.8f, 1.0f, 2.4f, 1.7f, -0.15f,
                    0.5f, 0.30f, 0.4f, 0.55f, 0.15f, 0.004f),
            // 中右很远：烧缺、撕裂，火星较亮
            new Scrap(500L, 2_300L, 0.68f, 0.05f, 1.5f, 3.0f, 6.0f, 5.5f, 0.20f,
                    0.6f, 0.55f, 2.6f, 0.60f, 0.50f, 0.008f),
            // 左缘：纪念车票变大后仍从左边露出来
            new Scrap(900L, 2_000L, 0.08f, 0.05f, 1.0f, 5.2f, 3.4f, 3.0f, -0.35f,
                    0.5f, 0.36f, -0.9f, 0.50f, 0.0f, 0.007f),
    };

    private final PaperSheet[] sheets = new PaperSheet[SCRAPS.length];
    private final PaperSheet.Pose[] poses = new PaperSheet.Pose[SCRAPS.length];
    private final int[] order = new int[SCRAPS.length];
    private final float seed;
    private final int serial;
    private int visible;

    TicketScraps(float seed, int serial) {
        this.seed = seed;
        this.serial = serial;
        for (int i = 0; i < SCRAPS.length; i++) {
            this.sheets[i] = new PaperSheet();
        }
    }

    /** 本帧还在空中的残票：算好姿态并由远到近排序。th 为票高。 */
    void update(long elapsed, float screenW, float screenH, float th) {
        this.visible = 0;
        for (int i = 0; i < SCRAPS.length; i++) {
            Scrap s = SCRAPS[i];
            float p = (elapsed - s.startAt()) / (float) s.life();
            if (p < 0.0f || p > 1.0f) {
                this.poses[i] = null;
                continue;
            }
            this.poses[i] = pose(s, i, p, elapsed, screenW, screenH, th);
            this.order[this.visible++] = i;
        }
        // 插入排序：远的在前
        for (int a = 1; a < this.visible; a++) {
            int k = this.order[a];
            int b = a - 1;
            while (b >= 0 && this.poses[this.order[b]].depth() < this.poses[k].depth()) {
                this.order[b + 1] = this.order[b];
                b--;
            }
            this.order[b + 1] = k;
        }
    }

    /**
     * 落叶式下落：摆到两端时稍稍上扬，纸面随摆动侧倾、弯折、起波纹。由屏幕上方之外落到屏幕下方之外，
     * 下落速度近似匀速（纸片很快达到终端速度）。
     */
    private static PaperSheet.Pose pose(Scrap s, int index, float p, long elapsed, float w, float h, float th) {
        float depth = Mth.lerp(p, s.depthFrom(), s.depthTo());
        // 后仰后投影变矮，留足余量免得在边缘突然出现或消失
        float reach = th / depth * 0.75f;
        float phase = s.phase() + Mth.TWO_PI * s.cycles() * p;
        float sway = Mth.sin(phase);
        float swing = Mth.cos(phase);
        float cx = w * (s.lane() + s.sway() * sway);
        float cy = Mth.lerp(p, -reach, h + reach) - h * 0.05f * sway * sway / depth;
        float roll = s.rollBias() + 0.35f * swing;
        float yaw = -0.45f * swing;
        float pitch = -(0.55f + 0.2f * Mth.sin(phase * 2.0f + 0.8f));
        float bendAngle = 0.4f * Mth.sin(phase * 0.5f + 0.6f);
        float bend = 0.2f + 0.1f * Mth.sin(phase * 2.0f + 0.9f);
        float curl = 0.2f * Mth.cos(phase - 0.55f);
        float twist = 0.14f * Mth.sin(phase + 1.4f);
        return new PaperSheet.Pose(cx, cy, depth, roll, pitch, yaw, bendAngle, bend, curl, 0.035f,
                elapsed * 0.011f + index * 1.7f, twist, 0.0f, 1.0f, s.crumple());
    }

    /**
     * 画出比 {@code mainDepth} 远（farther = true）或不远于它的残票。着色器的 TicketRect / TicketSize / Time
     * 由调用方先设好；其余 uniform 每张残票各自设置。cover 为背景压暗程度：残票画在压暗层之上，
     * 跟着一起沉进黑暗，免得在暗背景上抢了纪念车票。
     */
    void draw(GuiGraphics g, int texture, ShaderInstance shader, boolean farther, float mainDepth, float cover,
              float screenW, float screenH, float ticketX, float ticketY, float tw, float th) {
        for (int n = 0; n < this.visible; n++) {
            int i = this.order[n];
            PaperSheet.Pose pose = this.poses[i];
            if ((pose.depth() > mainDepth) != farther) {
                continue;
            }
            Scrap s = SCRAPS[i];
            float scrapSeed = this.seed + 3.7f * (i + 1);
            PaperSheet sheet = this.sheets[i];
            sheet.layout(screenW, screenH, ticketX, ticketY, tw, th, pose, null, this.serial + 977 * (i + 1));
            if (shader != null) {
                boolean burnt = s.front() > NO_BURN;
                shader.safeGetUniform("Age").set(s.age());
                shader.safeGetUniform("Burn").set(burnt ? SCRAP_BURN : 0.0f);
                shader.safeGetUniform("Front").set(s.front());
                shader.safeGetUniform("Seed").set(scrapSeed);
                shader.safeGetUniform("Smolder").set(s.smolder());
                shader.safeGetUniform("Tear").set(Mth.cos(s.tearAngle()), Mth.sin(s.tearAngle()), s.tearAt(),
                        s.tearAt() > 0.0f ? 1.0f : 0.0f);
                shader.safeGetUniform("Lamp").set(0.5f, 0.0f, 0.0f, 0.0f);
                // 越远越暗，拉开层次
                float exposure = (1.0f - 0.6f * cover) / (1.0f + 0.12f * (pose.depth() - 1.0f));
                shader.safeGetUniform("Exposure").set(exposure);
            }
            // 着色器不可用时只剩弯折与褶皱，略压暗以免抢了纪念车票
            sheet.draw(g, texture, shader, shader != null ? 1.0f : 0.8f - 0.4f * cover);
        }
    }
}
