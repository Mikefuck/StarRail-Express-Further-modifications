package com.habitrain.taskapi.impl.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin - 静默 ServerReplay 的 OP 广播通知
 *
 * <p>当 ServerReplay 完成录制时，会通过 {@code RecorderNotifier} 向所有 OP
 * 广播大量信息（"正在保存...", "已保存到 xxx，压缩为 yyy", "已关闭..."），
 * 这些消息直接通过 {@code MinecraftServer#playerList}{@code .players.ops().broadcast()} 发送，
 * 完全绕过了 {@code CommandSourceStack}，因此使用静默命令源无效。</p>
 *
 * <p>此 Mixin 在 {@code broadcastToOps(MinecraftServer, Component)} 层面拦截，
 * 该方法是所有 OP 广播的唯一出口。取消此方法即可静默所有录制通知。</p>
 *
 * <p>API 模组随后会在合适的时机统一发送简洁提示（如"录制完成"）。</p>
 *
 * <hr>
 * <h3>★ @Pseudo 说明</h3>
 * <p>
 * ServerReplay 在 Fabric 的类加载层级中位于 API 模组之后，
 * 且非 API 模组的编译依赖。常规 {@code @Mixin(targets = "...")}
 * 会在 mixin 预 备阶段尝试加载目标类，此时类加载器尚未暴露
 * ServerReplay 的类，导致 ClassNotFoundException。
 * </p>
 * <p>
 * {@code @Pseudo} 告知 Mixin「假设目标类存在，不做编译期校验」，
 * 将类解析推迟到运行时。当 {@code habitrain_taskapi.replay.mixins.json}
 * 的 {@code "required": false} 配合此注解，即可在类可用时正常注入，
 * 不可用时静默跳过。
 * </p>
 */
@Pseudo
@Mixin(targets = {"me.senseiwells.replay.processor.RecorderNotifier"}, remap = false)
public class ServerReplayRecorderMixin {

    /**
     * 拦截 {@code broadcastToOps(MinecraftServer, Component)}。
     *
     * <p>广播链路：</p>
     * <pre>
     *   onReplayRecorderSaving  ─┐
     *   onReplayRecorderSaved   ─┤
     *   onReplayRecorderClose   ─┤
     *   onReplayRecorderStart   ─┘
     *       → broadcastToOpsAndConsole → {@link #broadcastToOps}
     *                                       → ops().broadcast() (聊天栏输出)
     * </pre>
     *
     * <p>由于 {@code broadcastToOpsAndConsole} 也调用 {@code broadcastToOps}，
     * 此单一注入点可同时抑制保存中、已保存、已关闭等所有录制通知。</p>
     *
     * <p>{@code require = 0}: ServerReplay 不存在或方法名变动时不导致报错。</p>
     */
    @Inject(
            method = "broadcastToOps",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void habitrain$suppressBroadcast(MinecraftServer server, Component message, CallbackInfo ci) {
        ci.cancel();
    }
}
