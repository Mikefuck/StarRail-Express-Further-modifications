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
import net.minecraft.world.entity.player.Player;
import org.ladysnake.cca.api.v3.entity.EntityComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentInitializer;
import org.ladysnake.cca.api.v3.entity.RespawnCopyStrategy;

/**
 * Cardinal Components 入口：注册投稿职业 + 七宗罪玩家组件。
 */
public final class HabiComponents implements EntityComponentInitializer {
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
        HabiTrainCore.LOGGER.info("[HabiComponents] registered 11 player CCA keys (4 roles + 7 sins)");
    }

    public static void clearAll(Player player) {
        if (player == null) return;
        try { CrimeScapegoatComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
        try { FlowerGirlComponent.KEY.get(player).clear(); } catch (Throwable ignored) {}
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
}
