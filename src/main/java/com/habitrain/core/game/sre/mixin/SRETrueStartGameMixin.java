package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.game.sre.MapVoteLoadCoordinator;
import io.wifi.starrailexpress.api.GameMode;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端开局确认 mixin。
 *
 * <p>SRE 的 {@code GameUtils.trueStartGame} 只有在整列地图重置任务跑完 + 5 tick 调度后才执行，
 * 是「开局真正开始」的权威信号。在其 {@code RETURN}（而非 HEAD）通知
 * {@link MapVoteLoadCoordinator}：此时 SRE 已完成参与人数门槛判断——</p>
 * <ul>
 *   <li>人数足够：SRE 已把 {@code gameStatus} 置为 {@code STARTING}；这里只记录成功，
 *       等待 SRE 与 API 的对局环境都应用完成后再广播扫场亮标题动画；</li>
 *   <li>人数不足：SRE 在 {@code trueStartGame} 内直接中止，{@code gameStatus} 仍为
 *       {@code INACTIVE}，此时广播 {@code MapVoteLaunchAbortPayload}，客户端立即交还画面，
 *       避免旧实现那样在加载/扫场画面无限等待一个永远不会到达的 ACTIVE。</li>
 * </ul>
 */
@Mixin(value = GameUtils.class, remap = false)
public abstract class SRETrueStartGameMixin {
    @Inject(method = "trueStartGame", at = @At("RETURN"))
    private static void habitrain$confirmGameStart(ServerLevel world, GameMode gameMode, int time,
                                                   CallbackInfo ci) {
        try {
            // 成功路径 SRE 已把 gameStatus 置为 STARTING；人数不足 abort 则仍为 INACTIVE。
            boolean started = false;
            var gw = SREGameWorldComponent.KEY.get(world);
            if (gw != null && gw.getGameStatus()
                    == SREGameWorldComponent.GameStatus.STARTING) {
                started = true;
            }
            MapVoteLoadCoordinator.onGameStartConfirmed(world, started);
        } catch (Throwable t) {
            // 不因 mixin 失败阻断对局启动。
        }
    }
}
