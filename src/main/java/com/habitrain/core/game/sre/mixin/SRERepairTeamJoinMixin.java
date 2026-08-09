package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.RepairModeManager;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mixin - 维修员不入对局队伍（{@code harpymodloader_game}）。
 *
 * <p>上游 {@code SREMurderGameMode.initializeGame} 会把开局名单加入队伍，但
 * {@link SREMurderGameMode#addPlayersToTeam} 实现里完全无视 {@code players} 参数，
 * 直接执行 {@code team join harpymodloader_game @a}——{@code @a} 覆盖全体在线玩家，
 * 维修员（已通过 {@link ParticipationComponent} 标记为不参与）也会被拉进对局队伍，
 * 聊天栏可见「已将N名成员加入队伍」，且可能被依赖队伍身份的后续逻辑当作参战成员。
 * 修机模式（{@code RepairGameSetup}）之外，谋杀/传统/发现/鹅鸭杀等全部继承
 * {@code SREMurderGameMode} 的玩法都走这一入口（含停电模式 {@code SREBlackoutGameMode}）。</p>
 *
 * <p>当名单里存在维修员时，本 mixin 在 {@code HEAD} 短路并按「过滤后的玩家名列表」重放
 * 上游三条命令（建队/入队/隐身可见关闭），其余情况（无维修员）完全保持上游原逻辑。
 * 目标方法为实例方法、回调声明为 static——Mixin 允许 static 回调注入实例方法。</p>
 */
@Mixin(SREMurderGameMode.class)
public abstract class SRERepairTeamJoinMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|SRERepairTeamJoinMixin");

    /** 名单含维修员时按过滤名单重放入队命令，维修员不入队；无维修员时走原逻辑。 */
    @Inject(method = "addPlayersToTeam", at = @At("HEAD"), cancellable = true)
    private static void habitrain$filterRepairersFromTeamJoin(CommandSourceStack source,
            List<ServerPlayer> players, String teamName, CallbackInfo ci) {
        List<ServerPlayer> nonRepairers = players.stream()
                .filter(p -> p != null && !RepairModeManager.isRepairer(p))
                .collect(Collectors.toList());
        if (nonRepairers.size() == players.size()) {
            return; // 无维修员：与上游行为完全一致
        }
        try {
            var cmd = source.getServer().getCommands();
            cmd.performPrefixedCommand(source, "team add " + teamName);
            if (!nonRepairers.isEmpty()) {
                String names = nonRepairers.stream()
                        .map(p -> p.getGameProfile().getName())
                        .collect(Collectors.joining(" "));
                cmd.performPrefixedCommand(source, "team join " + teamName + " " + names);
            }
            cmd.performPrefixedCommand(source, "team modify " + teamName + " seeFriendlyInvisibles false");
        } catch (Exception e) {
            LOGGER.warn("filtered team join failed for {}: {}", teamName, e.toString());
        }
        ci.cancel();
    }
}
