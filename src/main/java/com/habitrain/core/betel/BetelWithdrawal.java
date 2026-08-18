package com.habitrain.core.betel;

import com.habitrain.core.misc.EffectOwnershipTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.UUID;

public class BetelWithdrawal {

    public static void applyHeavyAddictionEffects(ServerPlayer player, BetelQuestState.PlayerBetelData data) {
        data.addictionStage = BetelQuestState.AddictionStage.SEVERE;
        if (data.effectState != BetelQuestState.EffectState.WITHDRAWAL_ACTIVE) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 100, 1, false, true, true));
            EffectOwnershipTracker.claim(player.getUUID(), MobEffects.MOVEMENT_SLOWDOWN, "betel_quest");
            // 戒断路径本 tick 已施加更长时长（600t）的 DARKNESS 并置 DARKNESS_APPLIED：
            // 不再叠加 100t 短效果干扰其节奏（review L39）。
            if (data.effectState != BetelQuestState.EffectState.DARKNESS_APPLIED) {
                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, true, true));
                EffectOwnershipTracker.claim(player.getUUID(), MobEffects.DARKNESS, "betel_quest");
            }
        }
    }

    public static void removeHeavyAddictionEffects(ServerPlayer player) {
        UUID pUuid = player.getUUID();
        if (EffectOwnershipTracker.release(pUuid, MobEffects.MOVEMENT_SLOWDOWN, "betel_quest")) {
            player.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
        if (EffectOwnershipTracker.release(pUuid, MobEffects.DARKNESS, "betel_quest")) {
            player.removeEffect(MobEffects.DARKNESS);
        }
    }

    /** 吃槟榔缓解戒断：记录缓解窗口，窗口到期后 effectState 自动回 NONE 以重新应用戒断效果（P2-9）。 */
    public static void enterWithdrawalRelief(ServerPlayer player, BetelQuestState.PlayerBetelData data, long reliefTicks) {
        data.effectState = BetelQuestState.EffectState.WITHDRAWAL_ACTIVE;
        data.withdrawalReliefUntilTick = player.serverLevel().getGameTime() + reliefTicks;
    }
}
