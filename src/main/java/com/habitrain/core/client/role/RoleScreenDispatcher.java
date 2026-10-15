package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public final class RoleScreenDispatcher implements RoleScreenClientApi {
    public static final RoleScreenDispatcher INSTANCE = new RoleScreenDispatcher();
    private RoleScreenDispatcher() {}

    @Override
    public boolean open(ResourceLocation id, List<RoleScreenOption> options, Consumer<RoleScreenResult> callback) {
        Objects.requireNonNull(id, "screenId");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(callback, "callback");
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) throw new IllegalStateException("Role screens must open on the client thread");
        RoleKey role = RoleClientExtensionHooks.currentRole(mc.player);
        if (role == null || mc.level == null) return false;
        RoleScreenSpec spec = RoleClientExtensionApi.instance().screensFor(role).stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
        if (spec == null) return false;
        if (spec.kind() == RoleScreenKind.PLAYER_PICK && options.isEmpty()) {
            options = mc.level.players().stream()
                    .map(player -> RoleScreenOption.player(player.getUUID(), player.getDisplayName()))
                    .sorted(java.util.Comparator.comparing(option -> option.label().getString()))
                    .toList();
        }
        RoleScreenSession session = new RoleScreenSession(spec.kind(), options);
        String title = spec.titleKey().isBlank() ? "habitrain_core.role_screen.title" : spec.titleKey();
        var world = mc.level;
        var parent = mc.screen instanceof RoleScreen current ? current.parentScreen() : mc.screen;
        mc.setScreen(new RoleScreen(Component.translatable(title), parent, session, callback,
                () -> mc.level == world && role.equals(RoleClientExtensionHooks.currentRole(mc.player))
                        && RoleClientExtensionApi.instance().screensFor(role).stream().anyMatch(s -> s.id().equals(id))));
        return true;
    }

    /** Legacy internal trigger; downstream code should use the public ID/result API. */
    public static void openForRole(@Nullable RoleKey role) {
        if (role == null) return;
        var specs = RoleClientExtensionApi.instance().screensFor(role);
        if (!specs.isEmpty()) INSTANCE.open(specs.getFirst().id(), result -> {});
    }
}
