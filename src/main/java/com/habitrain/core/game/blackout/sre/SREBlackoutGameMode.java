package com.habitrain.core.game.blackout.sre;

import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutRoleManager.Faction;
import com.habitrain.core.game.blackout.BlackoutRoleManager.RoleType;
import com.habitrain.core.network.BlackoutAnnouncePayload;
import com.habitrain.core.network.BlackoutTimerPayload;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.Harpymodloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 停电模式专用的 SRE GameMode。
 *
 * 坏人玩家分配为 KILLER（杀手阵营），好人玩家分配为 CIVILIAN（平民阵营），
 * 使得 SRE 看到两个不同阵营（杀手 vs 平民），不会触发"全员同阵营→结束游戏"。
 * 实际阵营分配由 BlackoutRoleManager 独立完成，SRE 的 KILLER 角色仅用于
 * 维持 SRE 游戏运行，不给玩家实际的 SRE 杀手能力。
 *
 * 在 {@link #register()} 中通过 SREGameModes.registerGameMode() 注册到 SRE，
 * 由 HabiTrainCore.onInitialize() 在模组初始化时调用。
 */
public class SREBlackoutGameMode extends SREMurderGameMode {

    private static final Logger LOGGER = LoggerFactory.getLogger("SREBlackoutGameMode");

    public static final ResourceLocation MODE_ID =
            ResourceLocation.fromNamespaceAndPath("sre", "blackout");

    /** 是否已完成注册 */
    private static boolean registered = false;

    public SREBlackoutGameMode() {
        super(MODE_ID, 10, 1);
    }

    @Override
    public void initializeGame(ServerLevel world, SREGameWorldComponent game,
                               List<ServerPlayer> players) {
        // 1. 标准 SRE 初始化
        Harpymodloader.refreshRoles();
        game.clearRoleMap();

        // 2. 将玩家加入游戏队伍
        addPlayersToTeam(world.getServer().createCommandSourceStack(), players, "harpymodloader_game");

        // 3. 分配 Blackout 阵营 + 警长
        BlackoutRoleManager.initRandomAssignment(players);
        BlackoutRoleManager.assignSheriffs();

        // 4. 分配 SRE 角色：好人=CIVILIAN（平民），坏人=KILLER（杀手阵营）
        //    SRE 看到 CIVILIAN + KILLER 两个经典对抗阵营 → 不会结束游戏
        for (ServerPlayer player : players) {
            boolean isBad = BlackoutRoleManager.getFaction(player.getUUID()) == Faction.BAD;
            game.addRole(player, isBad ? TMMRoles.KILLER : TMMRoles.CIVILIAN, false);
        }
        game.syncRoles();

        // 5. 向每位玩家发送 Blackout 阵营公告（角色介绍 + 目标）
        int killerCount = BlackoutRoleManager.getRemainingBad();
        int goodCount = players.size() - killerCount;
        for (ServerPlayer player : players) {
            var pid = player.getUUID();
            var role = BlackoutRoleManager.getRole(pid);
            boolean isBad = BlackoutRoleManager.getFaction(pid) == Faction.BAD;
            String roleName;
            String subtitle;
            String goal;
            if (role == RoleType.KILLER) {
                roleName = "黑化杀手";
                subtitle = "§c坏人阵营 — 破坏列车，消灭好人";
                goal = "消灭所有好人，不要让列车恢复供电！";
            } else if (role == RoleType.SHERIFF) {
                roleName = "警长";
                subtitle = "§b好人阵营 — 找出并制裁杀手";
                goal = "暗中调查可疑玩家，用枪维护秩序！";
            } else {
                roleName = "黑化平民";
                subtitle = "§a好人阵营 — 完成任务，存活到最后";
                goal = "完成好人任务，活下去！";
            }
            ServerPlayNetworking.send(player,
                    new BlackoutAnnouncePayload(roleName, subtitle, goal, killerCount, goodCount));
        }

        // 6. 广播初始 HUD 计时器 (5 分钟, 2 分钟后首次停电)
        BlackoutTimerPayload.broadcastToAll(world.getServer(), 300, 120, false, 0);

        // 7. 最后启动 SRE 游戏（角色已分配完毕，阵营已同步，公告已发送）
        executeFunction(world.getServer().createCommandSourceStack(), "harpymodloader:start_game");
    }

    @Override
    public boolean shouldRecordPlayerStats() {
        return false;
    }

    @Override
    public boolean hasSafeTime() {
        return true;
    }

    @Override
    public boolean requiresAssignedRole() {
        return false;
    }

    /**
     * 注册此 GameMode 到 SREGameModes。
     * 仅在模组初始化期间调用一次。
     */
    public static void register() {
        if (registered) return;
        registered = true;
        SREGameModes.registerGameMode(new SREBlackoutGameMode());
        LOGGER.info("SREBlackoutGameMode registered: {}", MODE_ID);
    }
}
