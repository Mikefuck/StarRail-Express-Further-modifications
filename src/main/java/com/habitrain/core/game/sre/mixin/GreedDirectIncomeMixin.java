package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.role.sins.component.GreedEconomy;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Upstream role rewards that write the public balance field directly. */
@Mixin(targets = {
        "org.agmas.noellesroles.game.roles.neutral.thief.ThiefPlayerComponent",
        "org.agmas.noellesroles.game.roles.killer.bandit.BanditPlayerComponent",
        "org.agmas.noellesroles.game.roles.killer.nostalgist.NostalgistPlayerComponent",
        "org.agmas.noellesroles.game.roles.killer.imitator.ImitatorSkillRegistry",
        "org.agmas.noellesroles.game.roles.innocence.accountant.AccountantPlayerComponent"
}, remap = false)
public abstract class GreedDirectIncomeMixin {
    @Redirect(method = "*", at = @At(value = "FIELD", opcode = 181,
            target = "Lio/wifi/starrailexpress/cca/SREPlayerShopComponent;balance:I"))
    private static void habitrain$shareDirectReward(SREPlayerShopComponent shop, int amount) {
        shop.balance = shop.getPlayer() instanceof ServerPlayer player
                ? GreedEconomy.interceptIncome(player, shop.balance, amount) : amount;
    }
}
