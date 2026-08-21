package com.habitrain.core.client.mvp;

import dev.kosmx.playerAnim.api.IPlayable;
import dev.kosmx.playerAnim.api.layered.AnimationStack;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.AbstractClientPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MVP 结算页 Player Animator 控制器。
 * 负责管理预览玩家的动画层挂载、淡入播放、逐客户端 tick 推进与安全卸载。
 */
@Environment(EnvType.CLIENT)
public final class MvpAnimationController {
    private static final Logger LOGGER = LoggerFactory.getLogger(MvpAnimationController.class.getSimpleName());

    private final Map<UUID, ModifierLayer<IAnimation>> layers = new HashMap<>();
    private final Map<UUID, SpeedModifier> speedModifiers = new HashMap<>();
    private final Map<UUID, MvpAnimationDefinition> playingDefinitions = new HashMap<>();
    private final Set<UUID> started = new HashSet<>();

    /**
     * 为预览玩家挂载独立动画层。
     */
    public void attach(AbstractClientPlayer player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        if (layers.containsKey(id)) {
            return;
        }
        try {
            AnimationStack stack = PlayerAnimationAccess.getPlayerAnimLayer(player);
            if (stack == null) {
                LOGGER.warn("Player animation stack is null for player {}", id);
                return;
            }
            ModifierLayer<IAnimation> layer = new ModifierLayer<>();
            SpeedModifier speedModifier = new SpeedModifier(1.0f);
            layer.addModifierLast(speedModifier);
            stack.addAnimLayer(1000, layer);
            layers.put(id, layer);
            speedModifiers.put(id, speedModifier);
        } catch (Throwable t) {
            LOGGER.warn("Failed to attach Player Animator layer for player {}: {}", id, t.getMessage());
        }
    }

    /**
     * 播放指定 MVP 动作。
     */
    public boolean play(AbstractClientPlayer player, MvpAnimationDefinition definition, float speed) {
        if (player == null || definition == null) {
            return false;
        }
        UUID id = player.getUUID();
        ModifierLayer<IAnimation> layer = layers.get(id);
        if (layer == null) {
            attach(player);
            layer = layers.get(id);
        }
        if (layer == null) {
            return false;
        }

        try {
            IPlayable playable = PlayerAnimationRegistry.getAnimation(definition.animationId());
            if (playable == null) {
                LOGGER.warn("MVP animation resource not found in registry: {}", definition.animationId());
                return false;
            }
            SpeedModifier speedModifier = speedModifiers.get(id);
            if (speedModifier != null) {
                speedModifier.speed = Math.max(0.5f, Math.min(1.5f, speed));
            }
            layer.replaceAnimationWithFade(
                    AbstractFadeModifier.standardFadeIn(6, Ease.INOUTSINE),
                    playable.playAnimation()
            );
            playingDefinitions.put(id, definition);
            started.add(id);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Failed to play MVP animation {} for player {}: {}", definition.id(), id, t.getMessage(), t);
            return false;
        }
    }

    public boolean isPlaying(UUID id) {
        if (id == null || !started.contains(id)) {
            return false;
        }
        ModifierLayer<IAnimation> layer = layers.get(id);
        return layer != null && layer.isActive();
    }

    public MvpAnimationDefinition getPlayingDefinition(UUID id) {
        return id != null ? playingDefinitions.get(id) : null;
    }

    /**
     * 结算页 tick 时推进动画。
     */
    public void tick() {
        for (ModifierLayer<IAnimation> layer : layers.values()) {
            if (layer != null && layer.isActive()) {
                try {
                    layer.tick();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 退出结算页时清理所有动画层与引用。
     */
    public void clear() {
        for (ModifierLayer<IAnimation> layer : layers.values()) {
            if (layer != null) {
                try {
                    layer.setAnimation(null);
                } catch (Throwable ignored) {
                }
            }
        }
        layers.clear();
        speedModifiers.clear();
        playingDefinitions.clear();
        started.clear();
    }

    // --- 诊断支持 ---

    public static boolean isPlayerAnimatorLoaded() {
        try {
            Class.forName("dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int getRegisteredBuiltinCount() {
        int count = 0;
        try {
            for (MvpAnimationDefinition def : MvpAnimationDefinition.BUILT_INS) {
                if (PlayerAnimationRegistry.getAnimation(def.animationId()) != null) {
                    count++;
                }
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    public static List<String> getMissingBuiltinIds() {
        List<String> missing = new ArrayList<>();
        try {
            for (MvpAnimationDefinition def : MvpAnimationDefinition.BUILT_INS) {
                if (PlayerAnimationRegistry.getAnimation(def.animationId()) == null) {
                    missing.add(def.id());
                }
            }
        } catch (Throwable ignored) {
        }
        return missing;
    }
}
