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
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, true, true));
            EffectOwnershipTracker.claim(player.getUUID(), MobEffects.DARKNESS, "betel_quest");
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
}
