package com.habitrain.core.game.blackout.sre;

import com.habitrain.core.game.blackout.BlackoutRoleManager;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.agmas.harpymodloader.Harpymodloader;

import java.util.List;

/**
 * 停电模式专用的 SRE GameMode。
 *
 * 所有玩家分配为 CIVILIAN（普通乘客），不赋予任何杀手能力/商店权限。
 * 阵营分配由 BlackoutRoleManager 独立完成。
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
        // 必须执行 SRE 标准初始化流程
        Harpymodloader.refreshRoles();
        game.clearRoleMap();

        // 将玩家加入游戏队伍（否则 SRE 游戏会立即结束）
        addPlayersToTeam(world.getServer().createCommandSourceStack(), players, "harpymodloader_game");

        // 执行游戏启动函数
        executeFunction(world.getServer().createCommandSourceStack(), "harpymodloader:start_game");

        // ==== 在此处分配 Blackout 角色（SRE 检查胜利条件之前） ====
        BlackoutRoleManager.initRandomAssignment(players);

        // 所有玩家分配为 CIVILIAN, 不赋予任何特殊能力
        for (ServerPlayer player : players) {
            game.addRole(player, TMMRoles.CIVILIAN, false);
        }
        game.syncRoles();
    }

    @Override
    public boolean shouldRecordPlayerStats() {
        return false;
    }

    @Override
    public boolean hasSafeTime() {
        return false;
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
