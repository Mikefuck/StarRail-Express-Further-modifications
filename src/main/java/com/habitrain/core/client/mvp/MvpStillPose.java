package com.habitrain.core.client.mvp;

import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.AbstractClientPlayer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结算转场里 MVP 人像的固定姿势：身体略侧、双臂垂落，头低着歪向一侧、转过来盯住镜头。
 *
 * <p>以 Player Animator 动画层的形式覆盖原版站姿（包括手臂随时间的轻微摆动），整段转场一动不动。</p>
 */
@Environment(EnvType.CLIENT)
public final class MvpStillPose implements IAnimation {
    private static final Logger LOGGER = LoggerFactory.getLogger(MvpStillPose.class.getSimpleName());
    /** 身体侧转的角度；头部反向转回同样的角度，正对镜头。 */
    public static final float BODY_TURN_DEGREES = 24.0f;

    private static final Map<String, Vec3f> ROTATIONS = Map.of(
            "head", new Vec3f(0.22f, -(float) Math.toRadians(BODY_TURN_DEGREES), 0.26f),
            "torso", new Vec3f(0.07f, 0.0f, 0.0f),
            "rightArm", new Vec3f(-0.06f, 0.0f, 0.05f),
            "leftArm", new Vec3f(0.04f, 0.0f, -0.04f),
            "rightLeg", new Vec3f(0.0f, 0.0f, 0.03f),
            "leftLeg", new Vec3f(0.0f, 0.0f, -0.03f));

    private MvpStillPose() {
    }

    /** 给预览玩家挂上固定姿势；Player Animator 不可用时保持原版站姿。 */
    public static void apply(AbstractClientPlayer player) {
        if (player == null) {
            return;
        }
        try {
            AnimationStack stack = PlayerAnimationAccess.getPlayerAnimLayer(player);
            if (stack != null) {
                stack.addAnimLayer(1000, new MvpStillPose());
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to pose MVP preview player {}: {}", player.getUUID(), t.getMessage());
        }
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public @NotNull Vec3f get3DTransform(@NotNull String modelName, @NotNull TransformType type, float tickDelta,
                                         @NotNull Vec3f value0) {
        if (type == TransformType.ROTATION) {
            Vec3f rotation = ROTATIONS.get(modelName);
            if (rotation != null) {
                return rotation;
            }
        }
        return value0;
    }

    @Override
    public void setupAnim(float tickDelta) {
    }
}
