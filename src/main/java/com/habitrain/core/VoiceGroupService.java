package com.habitrain.core;

import com.mojang.brigadier.context.CommandContext;
import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import io.wifi.starrailexpress.compat.TrainVoicePlugin;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 语音群组服务 — 处理 /instantgroup 命令的临时语音群组创建与玩家加入逻辑。
 * <p>由 {@link CommandRegistrar} 中的命令回调调用。</p>
 */
public final class VoiceGroupService {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|VoiceGroupService");

    private VoiceGroupService() {}

    /**
     * 执行 /instantgroup 命令：将范围内玩家加入临时语音群组。
     */
    public static int executeInstantGroup(CommandContext<CommandSourceStack> context, int range) {
        CommandSourceStack source = context.getSource();
        ServerPlayer sender = source.getPlayer();
        if (sender == null) { source.sendFailure(Component.literal("§c此命令只能由玩家执行")); return 0; }
        if (TrainVoicePlugin.isVoiceChatMissing() || TrainVoicePlugin.SERVER_API == null) {
            source.sendFailure(Component.literal("§c语音聊天系统未就绪")); return 0;
        }
        VoicechatServerApi api = TrainVoicePlugin.SERVER_API;
        if (api.getConnectionOf(sender.getUUID()) == null) {
            source.sendFailure(Component.literal("§c你的语音连接尚未就绪")); return 0;
        }
        MinecraftServer srv = source.getServer();
        Vec3 senderPos = sender.position();
        List<ServerPlayer> nearby = new ArrayList<>();
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (p.getUUID().equals(sender.getUUID())) continue;
            if (!p.level().dimension().equals(sender.level().dimension())) continue;
            if (p.distanceToSqr(sender) <= (double) range * range) nearby.add(p);
        }
        if (nearby.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§e附近 " + range + " 格内没有其他玩家"), true);
            return 0;
        }
        Group tempGroup;
        try {
            tempGroup = api.groupBuilder()
                    .setId(UUID.randomUUID()).setName("临时群组")
                    .setPersistent(false).setType(Group.Type.OPEN).setHidden(false).build();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c创建语音群组失败")); return 0;
        }
        try {
            VoicechatConnection conn = api.getConnectionOf(sender.getUUID());
            if (conn != null) conn.setGroup(tempGroup);
        } catch (Exception e) {
            LOGGER.warn("将发送者加入临时语音群组时失败", e);
        }
        int count = 0;
        for (ServerPlayer p : nearby) {
            try {
                VoicechatConnection conn = api.getConnectionOf(p.getUUID());
                if (conn != null) { conn.setGroup(tempGroup); count++; }
            } catch (Exception e) {
                LOGGER.warn("将附近玩家加入临时语音群组时失败", e);
            }
        }
        final int finalCount = count;
        source.sendSuccess(() -> Component.literal("§a已将 §e" + finalCount + " §a名附近玩家加入临时语音群组（范围: " + range + " 格）"), true);
        Component notify = Component.literal("§7[语音] §a你已被加入临时语音群组");
        for (ServerPlayer p : nearby) {
            if (api.getConnectionOf(p.getUUID()) != null) p.sendSystemMessage(notify);
        }
        LOGGER.info("[InstantGroup] {} 执行 /instantgroup {}，{} 名玩家", sender.getName().getString(), range, count);
        return count;
    }
}
