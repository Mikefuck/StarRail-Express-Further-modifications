package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.game.sre.replay.MatchReplayRecorder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.wifi.starrailexpress.api.RoleSkill;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = RoleSkill.class, remap = false)
abstract class ReplayRoleSkillMixin {
    @WrapOperation(method = "useUnified", at = @At(value = "INVOKE",
            target = "Lio/wifi/starrailexpress/api/RoleSkill$Handler;use(Lio/wifi/starrailexpress/api/RoleSkill$RoleSkillContext;)Z"))
    private static boolean habitrain$captureSuccessfulSkill(RoleSkill.Handler handler, RoleSkill.RoleSkillContext context,
                                                            Operation<Boolean> original) {
        boolean used = original.call(handler, context);
        if (used && context.phase() == RoleSkill.Phase.PRESS) {
            var player = context.player();
            var level = player.serverLevel();
            var target = context.target() == null ? null : level.getPlayerByUUID(context.target());
            MatchReplayRecorder.spatial(level, LastMatchReplay.Kind.SKILL_RELEASE, player.getUUID(), context.target(),
                    player.position(), target == null ? null : target.position(), context.skillId().toString(), LastMatchReplay.SUCCESS);
        }
        return used;
    }
}
