package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.game.sre.role.sins.component.EnvyComponent;
import com.habitrain.core.game.sre.role.sins.component.GluttonyComponent;
import com.habitrain.core.game.sre.role.sins.component.GreedComponent;
import com.habitrain.core.game.sre.role.sins.component.LustComponent;
import com.habitrain.core.game.sre.role.sins.component.PrideComponent;
import com.habitrain.core.game.sre.role.sins.component.SlothComponent;
import com.habitrain.core.game.sre.role.sins.component.WrathComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.agmas.harpymodloader.events.ModdedRoleAssigned;

/**
 * 七宗罪职业事件：分配时初始化 CCA 状态机。
 */
public final class SevenSinEvents {
    private SevenSinEvents() {}

    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;

        ModdedRoleAssigned.EVENT.register((player, role) -> {
            if (player == null || role == null) return;
            if (!(player instanceof ServerPlayer sp)) return;
            ResourceLocation id = role.identifier();
            if (SevenSins.PRIDE_ID.equals(id)) {
                PrideComponent.KEY.get(sp).init();
            } else if (SevenSins.ENVY_ID.equals(id)) {
                EnvyComponent.KEY.get(sp).init();
            } else if (SevenSins.WRATH_ID.equals(id)) {
                WrathComponent.KEY.get(sp).init();
            } else if (SevenSins.GREED_ID.equals(id)) {
                GreedComponent.KEY.get(sp).init();
            } else if (SevenSins.GLUTTONY_ID.equals(id)) {
                GluttonyComponent.KEY.get(sp).init();
            } else if (SevenSins.LUST_ID.equals(id)) {
                LustComponent.KEY.get(sp).init();
            } else if (SevenSins.SLOTH_ID.equals(id)) {
                SlothComponent.KEY.get(sp).init();
            }
        });
    }
}
