package com.habitrain.core.game.sre.role;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.role.component.CrimeScapegoatComponent;
import com.habitrain.core.game.sre.role.component.FlowerGirlComponent;
import com.habitrain.core.game.sre.role.component.MimeKillerComponent;
import com.habitrain.core.game.sre.role.component.SwiftWindComponent;
import com.habitrain.core.game.sre.role.sins.component.EnvyComponent;
import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import com.habitrain.core.game.sre.role.sins.component.GreedComponent;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.game.sre.role.sins.component.PrideComponent;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import com.habitrain.core.role.state.HabiRolePlayerStateComponent;
import com.habitrain.core.role.state.HabiRoleWorldStateComponent;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import net.minecraft.world.entity.player.Player;
import org.ladysnake.cca.api.v3.entity.EntityComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentInitializer;
import org.ladysnake.cca.api.v3.entity.RespawnCopyStrategy;
import org.ladysnake.cca.api.v3.world.WorldComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.world.WorldComponentInitializer;

/**
 * Cardinal Components 入口：注册投稿职业 + 七宗罪玩家组件，以及 v2 角色状态
 * 固定容器（player/world）。
 */
public final class HabiComponents implements EntityComponentInitializer, WorldComponentInitializer {
    @Override
    public void registerEntityComponentFactories(EntityComponentFactoryRegistry registry) {
        registry.registerForPlayers(
                CrimeScapegoatComponent.KEY,
                CrimeScapegoatComponent::new,
                RespawnCopyStrategy.NEVER_COPY);
        registry.registerForPlayers(
                FlowerGirlComponent.KEY,
                FlowerGirlComponent::new,
                RespawnCopyStrategy.NEVER_COPY);
        registry.registerForPlayers(
                SwiftWindComponent.KEY,
                SwiftWindComponent::new,
                RespawnCopyStrategy.NEVER_COPY);
        registry.registerForPlayers(
                MimeKillerComponent.KEY,
                MimeKillerComponent::new,
                RespawnCopyStrategy.NEVER_COPY);

        registry.registerForPlayers(
                PrideComponent.KEY,
                PrideComponent::new,
                RespawnCopyStrategy.NEVER_COPY);
        registry.registerForPlayers(
                EnvyComponent.KEY,
                EnvyComponent::new,
                RespawnCopyStrategy.NEVER_COPY);
        registry.registerForPlayers(
                WrathComponent.KEY,
                WrathComponent::new,
                RespawnCopyStrategy.NEVER_COPY);
        registry.registerForPlayers(
                GreedComponent.KEY,
                GreedComponent::new,
                RespawnCopyStrategy.NEVER_COPY);
        registry.registerForPlayers(
                GluttonyComponent.KEY,
                GluttonyComponent::new,
                RespawnCopyStrategy.NEVER_COPY);
        registry.registerForPlayers(
                LustComponent.KEY,
                LustComponent::new,
                RespawnCopyStrategy.NEVER_COPY);
        registry.registerForPlayers(
                SlothComponent.KEY,
                SlothComponent::new,
                RespawnCopyStrategy.NEVER_COPY);
        // v2 角色状态固定容器（PLAYER scope 持久槽）：死亡重生必须拷贝，
        // 否则 PERMANENT PLAYER 槽会在写出空 bag 时被抹掉。
        registry.registerForPlayers(
                HabiRolePlayerStateComponent.KEY,
                HabiRolePlayerStateComponent::new,
                RespawnCopyStrategy.ALWAYS_COPY);
        HabiTrainCore.LOGGER.info("[HabiComponents] registered 12 player CCA keys (4 roles + 7 sins + role state)");
    }

    @Override
    public void registerWorldComponentFactories(WorldComponentFactoryRegistry registry) {
        // v2 角色状态固定容器（WORLD scope 持久槽，每维度独立）
        registry.register(HabiRoleWorldStateComponent.KEY, HabiRoleWorldStateComponent::new);
        HabiTrainCore.LOGGER.info("[HabiComponents] registered world CCA key role_state_world");
    }

    public static void clearAll(Player player) {
        clearRoundComponents(player, true);
    }

    /**
     * JOIN：无条件清掉上一局/崩溃残留的局内 CCA，再按本局角色 init。
     * 卖花女在对局进行中重连时保留已持久的 {@code stillTicks}/{@code rewarded}。
     */
    public static void clearLeftoverRoundStateOnJoin(Player player) {
        if (player == null) return;
        boolean keepFlowerGirl = isActiveFlowerGirl(player);
        clearRoundComponents(player, !keepFlowerGirl);
        if (isActiveRound(player)) {
            initCurrentRoundRole(player, keepFlowerGirl);
        }
    }

    private static void clearRoundComponents(Player player, boolean includeFlowerGirl) {
        if (player == null) return;
        try { CrimeScapegoatComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        if (includeFlowerGirl) {
            try { FlowerGirlComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        }
        try { SwiftWindComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        try { MimeKillerComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        try { PrideComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        try { EnvyComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        try { WrathComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        try { GreedComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        try { GluttonyComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        try { LustComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        try { SlothComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
    }

    private static boolean isActiveRound(Player player) {
        if (player == null || player.level() == null) return false;
        try {
            SREGameWorldComponent gw = SREGameWorldComponent.KEY.get(player.level());
            return gw != null && gw.getGameStatus() == SREGameWorldComponent.GameStatus.ACTIVE;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isActiveFlowerGirl(Player player) {
        return isActiveRound(player) && HabiRoles.isHabiRole(player, HabiRoles.FLOWER_GIRL);
    }

    private static void initCurrentRoundRole(Player player, boolean skipFlowerGirl) {
        try {
            if (HabiRoles.isHabiRole(player, HabiRoles.CRIME_SCAPEGOAT)) {
                CrimeScapegoatComponent.KEY.get(player).init();
                return;
            }
            if (HabiRoles.isHabiRole(player, HabiRoles.SWIFT_WIND)) {
                SwiftWindComponent.KEY.get(player).init();
                return;
            }
            if (HabiRoles.isHabiRole(player, HabiRoles.MIME_KILLER)) {
                MimeKillerComponent.KEY.get(player).init();
                return;
            }
            if (!skipFlowerGirl && HabiRoles.isHabiRole(player, HabiRoles.FLOWER_GIRL)) {
                FlowerGirlComponent.KEY.get(player).init();
                return;
            }
            if (HabiRoles.isHabiRole(player, SevenSins.PRIDE)) {
                PrideComponent.KEY.get(player).init();
                return;
            }
            if (HabiRoles.isHabiRole(player, SevenSins.ENVY)) {
                EnvyComponent.KEY.get(player).init();
                return;
            }
            if (HabiRoles.isHabiRole(player, SevenSins.WRATH)) {
                WrathComponent.KEY.get(player).init();
                return;
            }
            if (HabiRoles.isHabiRole(player, SevenSins.GREED)) {
                GreedComponent.KEY.get(player).init();
                return;
            }
            if (HabiRoles.isHabiRole(player, SevenSins.GLUTTONY)) {
                GluttonyComponent.KEY.get(player).init();
                return;
            }
            if (HabiRoles.isHabiRole(player, SevenSins.LUST)) {
                LustComponent.KEY.get(player).init();
                return;
            }
            if (HabiRoles.isHabiRole(player, SevenSins.SLOTH)) {
                SlothComponent.KEY.get(player).init();
            }
        } catch (Exception e) {
            HabiTrainCore.LOGGER.debug("role CCA init on JOIN skipped", e);
        }
    }
}
