package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import io.wifi.starrailexpress.event.OnShieldBroken;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;

/**
 * Global adapters required by Sloth's induced-sleep state.
 *
 * <p>The target may have any role, so these locks cannot be registered only
 * against Sloth's provider-scoped hooks. All callbacks are server-authoritative
 * and no-op for players who are not in induced sleep.</p>
 */
public final class SevenSinEvents {
    private SevenSinEvents() {}

    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;

        io.wifi.starrailexpress.event.OnGameStarted.EVENT.register(SlothComponent::resetRound);
        io.wifi.starrailexpress.event.OnGameEnd.EVENT.register((level, game) -> SlothComponent.resetRound(level));

        try {
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
                if (SlothComponent.isSleepingSloth(sender)) {
                    sender.displayClientMessage(
                            Component.translatable("message.habitrain_core.sin_sloth.chat_locked"),
                            true
                    );
                    return false;
                }
                return true;
            });
        } catch (Throwable t) {
            HabiTrainCore.LOGGER.warn("[SevenSins] ServerMessageEvents.ALLOW_CHAT_MESSAGE unavailable", t);
        }

        OnShieldBroken.EVENT.register((victim, killer) -> SlothComponent.onShieldBroken(victim));

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClientSide && SlothComponent.isSleepingSloth(player)) {
                notifyInputLocked(player);
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide && SlothComponent.isSleepingSloth(player)) {
                notifyInputLocked(player);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide && SlothComponent.isSleepingSloth(player)) {
                notifyInputLocked(player);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide && SlothComponent.isSleepingSloth(player)) {
                notifyInputLocked(player);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide && SlothComponent.isSleepingSloth(player)) {
                notifyInputLocked(player);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }

    private static void notifyInputLocked(net.minecraft.world.entity.player.Player player) {
        player.displayClientMessage(
                Component.translatable("message.habitrain_core.sin_sloth.input_locked"), true);
    }
}
