package com.habitrain.core.game.blackout.sre;

import com.habitrain.core.game.blackout.BlackoutRoleManager;
import com.habitrain.core.game.blackout.BlackoutRoleManager.Faction;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.SRERole.MoodType;
import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
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
 * 所有玩家分配为 CIVILIAN（普通乘客）或自定义 BLACKOUT_BAD 角色（中立阵营），
 * 使得 SRE 看到两个不同阵营，不会触发"全员同阵营→结束游戏"。
 * 阵营分配由 BlackoutRoleManager 独立完成。
 *
 * 在 {@link #register()} 中通过 SREGameModes.registerGameMode() 注册到 SRE，
 * 由 HabiTrainCore.onInitialize() 在模组初始化时调用。
 */
public class SREBlackoutGameMode extends SREMurderGameMode {

    private static final Logger LOGGER = LoggerFactory.getLogger("SREBlackoutGameMode");

    public static final ResourceLocation MODE_ID =
            ResourceLocation.fromNamespaceAndPath("sre", "blackout");

    /** 自定义 Blackout 坏人角色 — 中立阵营，无 SRE 杀手能力 */
    private static final NormalRole BLACKOUT_BAD_ROLE = createBadRole();

    /** 是否已完成注册 */
    private static boolean registered = false;

    public SREBlackoutGameMode() {
        super(MODE_ID, 10, 1);
    }

    private static NormalRole createBadRole() {
        NormalRole role = new NormalRole(
                ResourceLocation.fromNamespaceAndPath("habitrain", "blackout_bad"),
                0xAA0000,                       // 颜色：暗红
                false,                          // isInnocent — 不在平民阵营
                false,                          // canUseKiller — 无 SRE 杀手能力
                MoodType.NONE,
                100,                            // maxSprintTime
                false                           // canSeeTime
        );
        role.setNeutrals(true);
        role.setCanPickUpRevolver(false);
        role.setCanUseInstinct(false);
        role.setCanAutoAddMoney(false);
        role.setCanHavePassiveIncome(false);
        TMMRoles.registerRole(role);
        LOGGER.info("Registered Blackout BAD role: {} ({})",
                role.getIdentifier(), "NEUTRAL faction, no SRE killer abilities");
        return role;
    }

    @Override
    public void initializeGame(ServerLevel world, SREGameWorldComponent game,
                               List<ServerPlayer> players) {
        // 1. 标准 SRE 初始化
        Harpymodloader.refreshRoles();
        game.clearRoleMap();

        // 2. 将玩家加入游戏队伍
        addPlayersToTeam(world.getServer().createCommandSourceStack(), players, "harpymodloader_game");

        // 3. 分配 Blackout 阵营（记录到 BlackoutRoleManager）
        BlackoutRoleManager.initRandomAssignment(players);

        // 4. 分配 SRE 角色：好人=平民阵营，坏人=中立阵营
        //    → SRE 看到两个不同阵营，不会触发"全员同阵营→结束游戏"
        for (ServerPlayer player : players) {
            boolean isBad = BlackoutRoleManager.getFaction(player.getUUID()) == Faction.BAD;
            game.addRole(player, isBad ? BLACKOUT_BAD_ROLE : TMMRoles.CIVILIAN, false);
        }
        game.syncRoles();

        // 5. 最后启动 SRE 游戏（角色已分配完毕，阵营已同步）
        executeFunction(world.getServer().createCommandSourceStack(), "harpymodloader:start_game");
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
