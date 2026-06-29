package com.habitrain.core.game.blackout.sre;

import io.wifi.starrailexpress.api.SREGameModes;
import io.wifi.starrailexpress.api.TMMRoles;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.modes.SREMurderGameMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        game.clearRoleMap();
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
