package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.LustRestrictionService;
import io.wifi.starrailexpress.content.command.SetDeathPenaltyCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.agmas.noellesroles.component.DeathPenaltyComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

/** Keep the existing command tree and permissions; specialize only its successful stop branch. */
@Mixin(value = SetDeathPenaltyCommand.class, remap = false)
public abstract class LustPenaltyCommandMixin {
    @Redirect(method = "lambda$register$3", at = @At(value = "INVOKE",
            target = "Lorg/agmas/noellesroles/component/DeathPenaltyComponent;init()V"))
    private static void habitrain$exemptOnlyLust(DeathPenaltyComponent penalty) {
        LustRestrictionService.adminExempt(penalty);
    }

    @Redirect(method = "lambda$register$3", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/CommandSourceStack;sendSuccess(Ljava/util/function/Supplier;Z)V", remap = true))
    private static void habitrain$reportRemainingRestrictions(CommandSourceStack source,
                                                             Supplier<Component> original, boolean broadcast) {
        source.sendSuccess(() -> LustRestrictionService.adminFeedback(source.getPlayer()), broadcast);
    }
}
