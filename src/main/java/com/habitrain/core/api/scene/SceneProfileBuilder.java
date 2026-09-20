package com.habitrain.core.api.scene;

import com.habitrain.core.api.scene.model.SceneBounds;
import com.habitrain.core.api.scene.model.SceneLoopDistanceMode;
import com.habitrain.core.api.scene.model.SceneLoopSettings;
import com.habitrain.core.api.scene.model.SceneMotionMode;
import com.habitrain.core.api.scene.model.SceneOrbitAxis;
import com.habitrain.core.api.scene.model.SceneOrbitCenterMode;
import com.habitrain.core.api.scene.model.SceneOrbitSettings;
import com.habitrain.core.api.scene.model.SceneProfile;
import com.habitrain.core.api.scene.model.SceneRenderSettings;
import com.habitrain.core.api.scene.model.SceneRotation;
import com.habitrain.core.api.scene.model.SceneShakeSettings;
import com.habitrain.core.api.scene.model.SceneSoundSettings;
import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 移动场景运动参数（{@link SceneProfile}）的完整流式构建器。
 *
 * <p>移动场景系统的每一个可配置字段都在这里有一一对应的入口：源选区、显示原点、旋转枢轴、
 * 运动方向/速度、欧拉角旋转、循环平铺、运动模式（直线 / 环绕）、环绕参数（中心、轴、速度、
 * 扫掠角、多副本、垂直/径向浮动、模型随轨旋转）、渲染距离与半透明、车外环境音与镜头微震。</p>
 *
 * <p>与 GUI 配置页不同，构建器<b>不做"地图键"无关的裁剪</b>：它只做字段级合法化（NaN 兜底、
 * 区间夹取、方向归一化），任何超出 UI 范围但底层支持的组合都能通过 API 表达。</p>
 *
 * <h2>最小示例</h2>
 * <pre>{@code
 * SceneProfile profile = SceneProfileBuilder.create()
 *         .bounds(-40, 64, -40, 40, 96, 40)          // 源选区（模板几何）
 *         .displayOrigin(0, 64, 300)                  // 在世界上出现的原点
 *         .direction(-1, 0, 0)                        // 沿 -X 移动
 *         .speed(24.0)                                // 24 格/秒
 *         .rotation(0, 0, 0)                          // 模型自身旋转
 *         .loopCustom(true, 512.0)                    // 每 512 格无缝循环
 *         .render(r -> r.maxDistance(256.0).translucent(true))
 *         .sound(s -> s.enabled(false))
 *         .shake(s -> s.intense())
 *         .build();
 * }</pre>
 */
public final class SceneProfileBuilder {
    private final SceneProfile profile;

    private SceneProfileBuilder(SceneProfile source, boolean forceEnabled) {
        this.profile = source != null ? source.copy() : SceneProfile.createDefault();
        if (forceEnabled) {
            // 新建场景的调用方默认希望它是"开"的：显式 enabled(false) 才能关掉。
            this.profile.setEnabled(true);
        }
    }

    /** 从系统默认值开始构建（默认已启用）。 */
    public static SceneProfileBuilder create() {
        return new SceneProfileBuilder(null, true);
    }

    /** 从一份既有 profile 复制后继续构建（<b>保留</b>其 enabled 状态）。 */
    public static SceneProfileBuilder from(SceneProfile profile) {
        return new SceneProfileBuilder(profile, false);
    }

    /**
     * 从某张地图/资产键当前保存的 profile 复制后继续构建（保留其 enabled 状态）。
     * 键不存在时回退到 {@code __default__}。
     */
    public static SceneProfileBuilder fromMapKey(String mapKey) {
        return new SceneProfileBuilder(SceneMotionApi.instance().getProfile(mapKey), false);
    }

    // ------------------------------------------------------------------
    // 基础开关
    // ------------------------------------------------------------------

    /** 是否启用该 profile。禁用后即使实例存在也不会渲染几何。 */
    public SceneProfileBuilder enabled(boolean enabled) {
        profile.setEnabled(enabled);
        return this;
    }

    /** 源选区所在维度（仅用于配置页展示与自动生成，运行时不参与裁剪）。 */
    public SceneProfileBuilder dimension(String dimension) {
        profile.setDimension(dimension);
        return this;
    }

    // ------------------------------------------------------------------
    // 几何来源
    // ------------------------------------------------------------------

    /** 源选区：{@code [minX, minY, minZ] -> [maxX, maxY, maxZ]} 闭区间（内部转为 maxExclusive）。 */
    public SceneProfileBuilder bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        profile.setSourceBounds(new SceneBounds(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1));
        return this;
    }

    /** 源选区：直接给出 maxExclusive 形式的边界。 */
    public SceneProfileBuilder boundsExclusive(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        profile.setSourceBounds(new SceneBounds(minX, minY, minZ, maxX, maxY, maxZ));
        return this;
    }

    /** 源选区：两个方块点（含两端）圈定的长方体。 */
    public SceneProfileBuilder boundsFromPoints(BlockPos first, BlockPos second) {
        profile.setSourceBounds(SceneBounds.fromPoints(first, second));
        return this;
    }

    /** 源选区：直接给定边界对象。 */
    public SceneProfileBuilder bounds(SceneBounds bounds) {
        profile.setSourceBounds(bounds);
        return this;
    }

    /** 显示原点：场景局部坐标 (0,0,0) 在世界中的落点。 */
    public SceneProfileBuilder displayOrigin(double x, double y, double z) {
        profile.setDisplayOrigin(x, y, z);
        return this;
    }

    /** 旋转枢轴（局部坐标）：旋转围绕该点进行，{@code (0,0,0)} 表示绕局部原点旋转。 */
    public SceneProfileBuilder pivotLocal(double x, double y, double z) {
        profile.setPivotLocal(x, y, z);
        return this;
    }

    /** 运动方向：会被自动归一化；零向量退化为 {@code (-1,0,0)}。 */
    public SceneProfileBuilder direction(double x, double y, double z) {
        profile.setDirection(x, y, z);
        return this;
    }

    // ------------------------------------------------------------------
    // 运动
    // ------------------------------------------------------------------

    /** 运动速度（格/秒），内部夹取到 {@code [0, 64]}。 */
    public SceneProfileBuilder speed(double blocksPerSecond) {
        profile.setSpeedBlocksPerSecond(blocksPerSecond);
        return this;
    }

    /** 模型自身欧拉角旋转（度）。 */
    public SceneProfileBuilder rotation(double yaw, double pitch, double roll) {
        profile.setRotationDegrees(new SceneRotation(yaw, pitch, roll));
        return this;
    }

    /** 模型自身旋转（{@link SceneRotation}）。 */
    public SceneProfileBuilder rotation(SceneRotation rotation) {
        profile.setRotationDegrees(rotation);
        return this;
    }

    /**
     * 相位偏移（格）：叠加在运动相位上，用来错开多个同向场景的相对位置。
     * 与"提前起跑"的区别是它不改变时间轴。
     */
    public SceneProfileBuilder phaseOffset(double blocks) {
        profile.setPhaseOffsetBlocks(blocks);
        return this;
    }

    /** 开启循环平铺，并把循环距离交给系统自动推荐。 */
    public SceneProfileBuilder loopAuto(boolean enabled) {
        profile.setLoop(new SceneLoopSettings(enabled, SceneLoopDistanceMode.AUTO,
                profile.getLoop().getDistanceBlocks(), 2));
        return this;
    }

    /** 开启循环平铺并使用手动循环距离（格），内部夹取到 {@code [1, 4096]}。 */
    public SceneProfileBuilder loopCustom(boolean enabled, double distanceBlocks) {
        profile.setLoop(new SceneLoopSettings(enabled, SceneLoopDistanceMode.CUSTOM, distanceBlocks, 2));
        return this;
    }

    /** 直接设置循环设置对象。 */
    public SceneProfileBuilder loop(SceneLoopSettings loop) {
        profile.setLoop(loop);
        return this;
    }

    /** 直线运动模式（默认）。 */
    public SceneProfileBuilder linear() {
        profile.setMotionMode(SceneMotionMode.LINEAR);
        return this;
    }

    // ------------------------------------------------------------------
    // 环绕（ORBIT）
    // ------------------------------------------------------------------

    /** 切换为环绕运动模式，并可通过回调细调所有环绕参数。 */
    public SceneProfileBuilder orbit(Consumer<Orbit> configurator) {
        profile.setMotionMode(SceneMotionMode.ORBIT);
        SceneOrbitSettings settings = profile.getOrbit();
        if (configurator != null) {
            configurator.accept(new Orbit(settings));
        }
        profile.setOrbit(settings);
        return this;
    }

    /** 切回直线运动模式。 */
    public SceneProfileBuilder straight() {
        profile.setMotionMode(SceneMotionMode.LINEAR);
        return this;
    }

    /** 环绕参数细分构建器。 */
    public static final class Orbit {
        private final SceneOrbitSettings settings;

        private Orbit(SceneOrbitSettings settings) {
            this.settings = settings;
        }

        /** 环绕中心取自写死的世界坐标。 */
        public Orbit centerWorld(double x, double y, double z) {
            settings.setCenterMode(SceneOrbitCenterMode.WORLD_BLOCK);
            settings.setCenterWorld(x, y, z);
            return this;
        }

        /** 环绕中心取自模型自身的几何中心。 */
        public Orbit centerModel() {
            settings.setCenterMode(SceneOrbitCenterMode.MODEL_CENTER);
            return this;
        }

        /** 环绕轴：Y（水平绕圈，默认）、X、Z。 */
        public Orbit axis(SceneOrbitAxis axis) {
            settings.setAxis(axis);
            return this;
        }

        /** 起始角度（度），内部取模到 {@code [0, 360)}。 */
        public Orbit startAngle(double degrees) {
            settings.setStartAngleDegrees(degrees);
            return this;
        }

        /** 扫掠角度（度），{@code 360} 表示整圈，内部夹取到 {@code [0, 360]}。 */
        public Orbit sweep(double degrees) {
            settings.setSweepDegrees(degrees);
            return this;
        }

        /** 是否顺时针。 */
        public Orbit clockwise(boolean clockwise) {
            settings.setClockwise(clockwise);
            return this;
        }

        /** 角速度（度/秒），内部夹取到 {@code [0, 720]}。 */
        public Orbit angularSpeed(double degreesPerSecond) {
            settings.setAngularSpeedDegreesPerSecond(degreesPerSecond);
            return this;
        }

        /** 垂直浮动振幅（格），内部夹取到 {@code [0, 64]}。 */
        public Orbit verticalBob(double amplitudeBlocks) {
            settings.setVerticalBobAmplitudeBlocks(amplitudeBlocks);
            return this;
        }

        /** 径向浮动振幅（格），内部夹取到 {@code [0, 64]}。 */
        public Orbit radialBob(double amplitudeBlocks) {
            settings.setRadialBobAmplitudeBlocks(amplitudeBlocks);
            return this;
        }

        /** 浮动频率（次/秒），内部夹取到 {@code [0, 10]}。 */
        public Orbit bobCycles(double cyclesPerSecond) {
            settings.setBobCyclesPerSecond(cyclesPerSecond);
            return this;
        }

        /** 同时环绕的副本数量，内部夹取到 {@code [1, 16]}。 */
        public Orbit instances(int count) {
            settings.setInstanceCount(count);
            return this;
        }

        /** 多副本之间的角度间隔（度），内部夹取到 {@code [0, 360]}。 */
        public Orbit instanceSpread(double degrees) {
            settings.setInstanceSpreadDegrees(degrees);
            return this;
        }

        /** 副本是否随轨道一起转向。 */
        public Orbit rotateModelWithOrbit(boolean rotate) {
            settings.setRotateModelWithOrbit(rotate);
            return this;
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /** 渲染细分设置。 */
    public SceneProfileBuilder render(Consumer<Render> configurator) {
        Render builder = new Render(profile.getRender());
        if (configurator != null) {
            configurator.accept(builder);
        }
        profile.setRender(builder.build());
        return this;
    }

    /** 只改渲染距离（格），内部夹取到 {@code [32, 512]}。 */
    public SceneProfileBuilder renderDistance(double blocks) {
        profile.setRender(new SceneRenderSettings(blocks, profile.getRender().isRenderTranslucent()));
        return this;
    }

    /** 只改半透明层开关。 */
    public SceneProfileBuilder translucent(boolean renderTranslucent) {
        profile.setRender(new SceneRenderSettings(
                profile.getRender().getMaxDistanceBlocks(), renderTranslucent));
        return this;
    }

    /** 渲染参数细分构建器。 */
    public static final class Render {
        private double maxDistance;
        private boolean translucent;

        private Render(SceneRenderSettings settings) {
            this.maxDistance = settings.getMaxDistanceBlocks();
            this.translucent = settings.isRenderTranslucent();
        }

        /** 最远显示距离（格），内部夹取到 {@code [32, 512]}。 */
        public Render maxDistance(double blocks) {
            this.maxDistance = blocks;
            return this;
        }

        /** 是否渲染半透明/裁切层。 */
        public Render translucent(boolean enabled) {
            this.translucent = enabled;
            return this;
        }

        private SceneRenderSettings build() {
            return new SceneRenderSettings(maxDistance, translucent);
        }
    }

    // ------------------------------------------------------------------
    // 声音与抖动
    // ------------------------------------------------------------------

    /** 车外环境音细分设置。 */
    public SceneProfileBuilder sound(Consumer<Sound> configurator) {
        SceneSoundSettings settings = profile.getOutsideSound();
        Sound builder = new Sound(settings);
        if (configurator != null) {
            configurator.accept(builder);
        }
        profile.setOutsideSound(builder.build());
        return this;
    }

    /** 镜头微震细分设置。 */
    public SceneProfileBuilder shake(Consumer<Shake> configurator) {
        SceneShakeSettings settings = profile.getShake();
        Shake builder = new Shake(settings);
        if (configurator != null) {
            configurator.accept(builder);
        }
        profile.setShake(builder.build());
        return this;
    }

    /** 车外环境音细分构建器。 */
    public static final class Sound {
        private boolean enabled;
        private String soundId;
        private float volume;
        private float pitch;
        private int fadeTicks;

        private Sound(SceneSoundSettings settings) {
            this.enabled = settings.isEnabled();
            this.soundId = settings.getSoundId();
            this.volume = (float) settings.getVolume();
            this.pitch = (float) settings.getPitch();
            this.fadeTicks = settings.getFadeTicks();
        }

        /** 是否播放车外环境音。 */
        public Sound enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** 音效 ID（如 {@code mymod:train_rumble}）。 */
        public Sound soundId(String soundId) {
            this.soundId = soundId;
            return this;
        }

        /** 音量，内部夹取到 {@code [0, 1]}。 */
        public Sound volume(double volume) {
            this.volume = (float) volume;
            return this;
        }

        /** 音高，内部夹取到 {@code [0.5, 2]}。 */
        public Sound pitch(double pitch) {
            this.pitch = (float) pitch;
            return this;
        }

        /** 淡入淡出时长（tick），内部夹取到 {@code [0, 200]}。 */
        public Sound fadeTicks(int fadeTicks) {
            this.fadeTicks = fadeTicks;
            return this;
        }

        /** 使用 {@link SceneSoundSettings} 的默认列车行驶音效并启用。 */
        public Sound defaultTrainSound() {
            this.enabled = true;
            this.soundId = SceneSoundSettings.DEFAULT_SOUND_ID;
            return this;
        }

        private SceneSoundSettings build() {
            return new SceneSoundSettings(enabled, soundId, volume, pitch, fadeTicks);
        }
    }

    /** 镜头微震细分构建器。 */
    public static final class Shake {
        private boolean enabled;
        private double translation;
        private double rotation;
        private double frequency;

        private Shake(SceneShakeSettings settings) {
            this.enabled = settings.isEnabled();
            this.translation = settings.getTranslationAmplitudeBlocks();
            this.rotation = settings.getRotationAmplitudeDegrees();
            this.frequency = settings.getFrequencyHz();
        }

        public Shake enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** 位移振幅（格），内部夹取到 {@code [0, 0.15]}。 */
        public Shake translation(double amplitudeBlocks) {
            this.translation = amplitudeBlocks;
            return this;
        }

        /** 旋转振幅（度），内部夹取到 {@code [0, 1.5]}。 */
        public Shake rotation(double amplitudeDegrees) {
            this.rotation = amplitudeDegrees;
            return this;
        }

        /** 频率（Hz），内部夹取到 {@code [0.1, 8]}。 */
        public Shake frequency(double hertz) {
            this.frequency = hertz;
            return this;
        }

        /** 预设：轻微。 */
        public Shake subtle() {
            SceneShakeSettings preset = SceneShakeSettings.createSubtlePreset();
            return apply(preset);
        }

        /** 预设：标准。 */
        public Shake standard() {
            return apply(SceneShakeSettings.createStandardPreset());
        }

        /** 预设：强烈。 */
        public Shake intense() {
            return apply(SceneShakeSettings.createIntensePreset());
        }

        private Shake apply(SceneShakeSettings preset) {
            this.enabled = preset.isEnabled();
            this.translation = preset.getTranslationAmplitudeBlocks();
            this.rotation = preset.getRotationAmplitudeDegrees();
            this.frequency = preset.getFrequencyHz();
            return this;
        }

        private SceneShakeSettings build() {
            return new SceneShakeSettings(enabled, translation, rotation, frequency);
        }
    }

    /** 直接整体替换渲染设置对象。 */
    public SceneProfileBuilder render(SceneRenderSettings render) {
        profile.setRender(render);
        return this;
    }

    /** 直接整体替换声音设置对象。 */
    public SceneProfileBuilder sound(SceneSoundSettings sound) {
        profile.setOutsideSound(sound);
        return this;
    }

    /** 直接整体替换微震设置对象。 */
    public SceneProfileBuilder shake(SceneShakeSettings shake) {
        profile.setShake(shake);
        return this;
    }

    /** 直接整体替换环绕设置对象（并把运动模式切到环绕）。 */
    public SceneProfileBuilder orbit(SceneOrbitSettings orbit) {
        profile.setMotionMode(SceneMotionMode.ORBIT);
        profile.setOrbit(orbit);
        return this;
    }

    /** 只替换环绕参数，<b>不改变</b>当前运动模式。 */
    public SceneProfileBuilder orbitSettings(SceneOrbitSettings orbit) {
        profile.setOrbit(orbit);
        return this;
    }

    /** 直接设置运动模式。 */
    public SceneProfileBuilder motionMode(SceneMotionMode mode) {
        profile.setMotionMode(Objects.requireNonNullElse(mode, SceneMotionMode.LINEAR));
        return this;
    }

    /** 产出最终 profile（返回内部副本，构建器可继续复用）。 */
    public SceneProfile build() {
        return profile.copy();
    }
}
