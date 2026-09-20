package com.habitrain.core.api.spi;

import com.habitrain.core.api.GameMode;
import com.habitrain.core.api.match.MatchPhase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 上游 SRE 运行时的桥接接口（SPI）。
 *
 * <p>{@code api} 公开层需要向 SRE 询问「对局是否占用了该维度」「某玩家的活跃模式」
 * 「对局阶段 / 模式 ID」。把这些能力定义为接口、由 {@code game.sre} 在初始化时注册实现
 * （见 {@link CoreSpi#installSreRuntime}），即可彻底去掉
 * {@code api → game.sre} 的编译期反向依赖——公开层只依赖本接口。
 *
 * <p><b>异常策略（审核 C9）</b>：实现<b>可以自由抛出</b>——{@link CoreSpi} 在每次调用外层
 * 统一 {@code try/catch(Throwable)} 并按各方法的保守语义降级（例如
 * {@link CoreSpi#isSreGameBlocking} 读失败时按「已占用」拒绝启动）。旧 javadoc 写成
 * 「所有实现都必须自行 try/catch，不得抛出异常」，与仓内唯一实现（{@code SreRuntimeBridgeImpl}，
 * 内部并无 try/catch）以及 CoreSpi 的兜底策略都不一致，会误导第三方实现者以为异常会被吞掉。
 */
public interface SreRuntimeBridge {

    /**
     * 该维度是否已被 SRE 对局占用（STARTING / RUNNING，或状态读取失败）。
     * <p>读取失败按 {@code true}（拒绝启动）处理，与
     * {@code SREModeStartAdapter#isSreGameBlocking} 的历史语义一致。
     */
    boolean isSreGameBlocking(ServerLevel level);

    /**
     * 该玩家当前所属的活跃 GameMode：优先所在维度，其次「本局包含该 UUID 的维度」，
     * 最后是服务器上唯一的活跃模式。
     */
    Optional<GameMode> resolveActiveForPlayer(ServerPlayer player);

    /** 对局阶段（SRE GameStatus 映射），读不到时返回 {@link MatchPhase#UNKNOWN}。 */
    MatchPhase matchPhase(@Nullable Level level);

    /** 该维度的规范模式 ID；读不到返回空串。 */
    String matchModeId(@Nullable ServerLevel level);
}
