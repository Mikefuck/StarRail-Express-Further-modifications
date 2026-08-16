package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.RepairModeManager;
import net.exmo.sre.repair.logic.RepairRoleSelection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Mixin - 修机模式选角/角色分配的维修员保护（根治）。
 *
 * <p>上游 {@link RepairRoleSelection} 开局后有两处遍历 {@code serverWorld.players()}（全体）
 * 而不是开局名单：</p>
 * <ul>
 *   <li>{@code reopenRoleSelection} 给全体玩家发选角界面；</li>
 *   <li>{@code finalizeSelectedRoles} 对每个玩家 {@code addRole}、发技能物品、按阵营传送出生点。
 *       维修员不在开局名单里却会被这条路重新拉进对局。</li>
 * </ul>
 * <p>另有 {@code begin} 里 {@code new ArrayList<>(players)} 构造洗牌列表，之后会把全体洗牌玩家
 * 送进选角房间（{@code RepairArenaBuilder.prepare}）。本 mixin 用 {@code @Redirect} 把这两处
 * 遍历/构造替换为过滤掉维修员后的列表；维修员保持创造模式、不被分配修机角色/物品/出生点。
 * 非维修员行为不变。</p>
 */
@Mixin(RepairRoleSelection.class)
public abstract class SRERepairRoleSelectionMixin {

    /** 过滤维修员后的玩家列表（保持原顺序）。 */
    private static List<ServerPlayer> habitrain$withoutRepairers(Collection<ServerPlayer> players) {
        List<ServerPlayer> out = new ArrayList<>(players.size());
        for (ServerPlayer p : players) {
            if (p != null && !RepairModeManager.isRepairer(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** begin：洗牌列表只保留非维修员，避免把维修员送进选角房间/发初始物资。 */
    @Redirect(
            method = "begin",
            at = @At(
                    value = "NEW",
                    target = "Ljava/util/ArrayList;<init>(Ljava/util/Collection;)V"),
            remap = false)
    private static ArrayList<ServerPlayer> habitrain$filterRepairersFromShuffle(Collection<ServerPlayer> source) {
        return new ArrayList<>(habitrain$withoutRepairers(source));
    }

    /** reopenRoleSelection：只给非维修员发选角界面。 */
    @Redirect(
            method = "reopenRoleSelection",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"))
    private static List<ServerPlayer> habitrain$filterRepairersReopen(ServerLevel level) {
        return habitrain$withoutRepairers(level.players());
    }

    /** finalizeSelectedRoles：不给维修员分配角色/技能物品/出生点传送。 */
    @Redirect(
            method = "finalizeSelectedRoles",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"))
    private static List<ServerPlayer> habitrain$filterRepairersFinalize(ServerLevel level) {
        return habitrain$withoutRepairers(level.players());
    }
}
