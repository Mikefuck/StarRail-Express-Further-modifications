package com.habitrain.core.game.sre.role.sins.win;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.role.v2.RoleExtensionRegistrar;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.behavior.Decision;
import com.habitrain.core.api.role.v2.behavior.RoleHookContext;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleWinHooks;
import com.habitrain.core.api.role.v2.behavior.WinPatch;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * v2 {@link RoleWinHooks} for the seven sins. Replaces the
 * {@code AllowGameEnd} first-wins listener in {@link SinVictoryHooks} so
 * pride/sloth/lust participate in the central dispatcher fold. Registered
 * through the provider-scoped {@link RoleExtensionRegistrar} of
 * {@link com.habitrain.core.role.extension.CoreRoleExtensionProvider} (audit
 * P1-1: no process-global write path).
 *
 * <p>Helpers stay on {@link SinVictoryHooks}. {@code CustomWinnerRole.checkWin}
 * remains as the SRE framework fallback. Blackout still has its explicit
 * pride/sloth/greed path; these hooks also answer {@code proposed=BLACKOUT}.
 */
public final class SevenSinV2Hooks {

    private SevenSinV2Hooks() {}

    public static void registerWith(RoleExtensionRegistrar registrar) {
        if (registrar == null) {
            throw new IllegalArgumentException("registrar must not be null");
        }
        registrar.hooks(RoleKey.of(SevenSins.SLOTH_ID), RoleHooks.builder().win(SLOTH).build());
        registrar.hooks(RoleKey.of(SevenSins.LUST_ID), RoleHooks.builder().win(LUST).build());
        registrar.hooks(RoleKey.of(SevenSins.GREED_ID), RoleHooks.builder().win(GREED).build());
        // Pride last so evaluateWin DECLARE_CUSTOM overwrites sloth when only pride remains.
        registrar.hooks(RoleKey.of(SevenSins.PRIDE_ID), RoleHooks.builder().win(PRIDE).build());
        HabiTrainCore.LOGGER.info("[SevenSins] v2 RoleWinHooks registered (pride/sloth/lust/greed)");
    }

    private static boolean factionProposal(@Nullable String proposed) {
        return "KILLERS".equals(proposed) || "PASSENGERS".equals(proposed) || "BLACKOUT".equals(proposed);
    }

    private static final RoleWinHooks PRIDE = new RoleWinHooks() {
        @Override
        public Decision allowGameEnd(@Nullable ServerLevel level, @Nullable String proposed,
                                     boolean loose, RoleHookContext ctx) {
            if (level == null || !factionProposal(proposed)) {
                return Decision.PASS;
            }
            if (SinVictoryHooks.isPrideBlocking(level) && !SinVictoryHooks.isOnlyPrideAlive(level)) {
                return Decision.DENY;
            }
            return Decision.PASS;
        }

        @Override
        public WinPatch evaluateWin(@Nullable ServerLevel level, @Nullable String proposed,
                                    boolean loose, RoleHookContext ctx) {
            if (level == null || !SinVictoryHooks.isOnlyPrideAlive(level)) {
                return WinPatch.noChange();
            }
            if (!factionProposal(proposed) && !"TIME".equals(proposed)) {
                return WinPatch.noChange();
            }
            ServerPlayer pride = SinVictoryHooks.findAlivePridePlayer(level);
            return WinPatch.declareCustom("sin_pride",
                    pride == null ? List.of() : List.of(pride.getUUID()),
                    "傲慢成为最后的幸存者");
        }
    };

    private static final RoleWinHooks SLOTH = new RoleWinHooks() {
        @Override
        public WinPatch evaluateWin(@Nullable ServerLevel level, @Nullable String proposed,
                                    boolean loose, RoleHookContext ctx) {
            if (level == null || !SinVictoryHooks.isSlothAlive(level)) {
                return WinPatch.noChange();
            }
            if (SinVictoryHooks.isPrideBlocking(level) || SinVictoryHooks.isOnlyPrideAlive(level)) {
                return WinPatch.noChange();
            }
            if (!factionProposal(proposed) && !"TIME".equals(proposed)) {
                return WinPatch.noChange();
            }
            ServerPlayer sloth = SinVictoryHooks.findAliveSlothPlayer(level);
            return WinPatch.declareCustom("sin_sloth",
                    sloth == null ? List.of() : List.of(sloth.getUUID()),
                    "懒惰劫持了结算");
        }
    };

    private static final RoleWinHooks LUST = new RoleWinHooks() {
        @Override
        public WinPatch evaluateWin(@Nullable ServerLevel level, @Nullable String proposed,
                                    boolean loose, RoleHookContext ctx) {
            if (loose || level == null || !"LOVERS".equals(proposed)) {
                return WinPatch.noChange();
            }
            if (!SinVictoryHooks.isLustAlive(level)) {
                return WinPatch.noChange();
            }
            ServerPlayer lust = SinVictoryHooks.findAliveLustPlayer(level);
            if (lust != null) {
                SinVictoryHooks.stealLoversWinForLust(level, lust);
            }
            return WinPatch.declareCustom("sin_lust",
                    lust == null ? List.of() : List.of(lust.getUUID()),
                    "色欲夺走了恋人的胜利");
        }
    };

    private static final RoleWinHooks GREED = new RoleWinHooks() {
        @Override
        public WinPatch evaluateWin(@Nullable ServerLevel level, @Nullable String proposed,
                                    boolean loose, RoleHookContext ctx) {
            if (level == null || !SinVictoryHooks.isGreedWinReady(level)) {
                return WinPatch.noChange();
            }
            ServerPlayer greed = SinVictoryHooks.findAliveGreedPlayer(level);
            return WinPatch.declareCustom("sin_greed",
                    greed == null ? List.of() : List.of(greed.getUUID()),
                    "贪婪集齐了目标");
        }
    };
}