package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.api.role.v2.client.RoleScreenSpec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Runtime dispatcher for v2 {@link RoleScreenSpec} (audit P1-2 / P1-5).
 *
 * <p>Opens the stock {@link RoleScreen} for the first screen specs declared by
 * the current role. The API is deliberately small: providers that need a
 * specific trigger (action, meeting, skill) call
 * {@link RoleClientExtensionHooks#openRoleScreen()} from their own client code.
 */
@Environment(EnvType.CLIENT)
public final class RoleScreenDispatcher {

    private RoleScreenDispatcher() {}

    public static void openForRole(@Nullable RoleKey role) {
        if (role == null || Minecraft.getInstance().player == null) {
            return;
        }
        List<RoleScreenSpec> specs = RoleClientExtensionApi.instance().screensFor(role);
        if (specs.isEmpty()) {
            return;
        }
        Minecraft.getInstance().setScreen(new RoleScreen(
                Component.translatable("habitrain_core.role_screen.title"), specs));
    }
}
