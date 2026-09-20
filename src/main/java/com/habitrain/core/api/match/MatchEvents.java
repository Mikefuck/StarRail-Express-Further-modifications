package com.habitrain.core.api.match;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core match lifecycle events. Lottery and other addons subscribe here instead of
 * SRE {@code OnGameEnd} / round-end CCA.
 *
 * <h2>注册约定（审核 B21）</h2>
 * <p>Fabric 的 {@link Event} <b>不提供注销/清空</b>，本类也没有 clear/reset 入口。
 * 因此监听器<b>只允许在 {@code ModInitializer.onInitialize()}（客户端为
 * {@code ClientModInitializer.onInitializeClient()}）里注册一次</b>。
 * 若写在 {@code SERVER_STARTED} 里，集成服务器反复重启的同一个 JVM 会不断累积监听器，
 * 导致每局重复发奖 / 重复重置。
 *
 * <h2>覆盖范围（审核 A7）</h2>
 * <p>2.0.11 起，{@link com.habitrain.core.api.GameModeRegistry#start}/{@code stop}
 * 启动与结束的对局也会触发这两个事件（此前只有上游 SRE 原生对局会触发）。
 * 注册表模式的 {@link MatchSettlement} 只携带模式 ID 与结果原因，
 * {@code winners}/{@code participants} 均为空集——请用它做跨局状态重置，不要据此发奖。
 */
public final class MatchEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|MatchEvents");

    private MatchEvents() {}

    public static final Event<Started> STARTED = EventFactory.createArrayBacked(
            Started.class,
            listeners -> level -> {
                for (Started listener : listeners) {
                    try {
                        listener.onMatchStarted(level);
                    } catch (Throwable t) {
                        LOGGER.error("MatchEvents.STARTED listener failed", t);
                    }
                }
            });

    public static final Event<RoundEnded> ROUND_ENDED = EventFactory.createArrayBacked(
            RoundEnded.class,
            listeners -> (level, settlement) -> {
                for (RoundEnded listener : listeners) {
                    try {
                        listener.onRoundEnded(level, settlement);
                    } catch (Throwable t) {
                        LOGGER.error("MatchEvents.ROUND_ENDED listener failed", t);
                    }
                }
            });

    @FunctionalInterface
    public interface Started {
        void onMatchStarted(ServerLevel level);
    }

    @FunctionalInterface
    public interface RoundEnded {
        void onRoundEnded(ServerLevel level, MatchSettlement settlement);
    }
}
