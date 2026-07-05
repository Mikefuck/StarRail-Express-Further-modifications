package com.habitrain.core.client.mixin;

import com.habitrain.core.client.gui.BlackoutHudOverlay;
import com.habitrain.core.client.gui.BlackoutRoleIntroData;
import com.habitrain.core.client.gui.BlackoutWelcomeRenderer;
import com.habitrain.core.game.blackout.BlackoutRoleDefinition;
import com.habitrain.core.game.blackout.BlackoutRoleRegistry;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.client.util.PinYinUtils;
import org.agmas.noellesroles.client.screen.RoleIntroduceScreen;
import org.agmas.noellesroles.utils.RoleUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Locale;

@Mixin(RoleIntroduceScreen.class)
public abstract class RoleIntroduceScreenMixin {

    @Shadow
    @Final
    private List<Object> filteredItems;

    @Shadow
    private Object selectedRole;

    @Shadow
    private int listScrollOffset;

    @Shadow
    private int maxListScroll;

    @Shadow
    private String searchContent;

    @Shadow
    protected abstract int listAreaH();

    @Inject(method = "init", at = @At("HEAD"))
    private void habiTrain$resetSelectionForBlackout(CallbackInfo ci) {
        if (BlackoutHudOverlay.isBlackoutModeActive()) {
            this.selectedRole = resolveBlackoutRole();
            this.listScrollOffset = 0;
        }
    }

    @Inject(method = "refreshFilter", at = @At("HEAD"), cancellable = true)
    private void habiTrain$replaceRoleDataInBlackout(CallbackInfo ci) {
        if (!BlackoutHudOverlay.isBlackoutModeActive()) {
            return;
        }

        this.filteredItems.clear();
        String query = this.searchContent == null ? "" : this.searchContent.trim();
        String lowerQuery = query.toLowerCase(Locale.ROOT);

        for (SRERole role : BlackoutRoleIntroData.getRoles()) {
            if (matchesBlackoutRole(role, query, lowerQuery)) {
                this.filteredItems.add(role);
            }
        }

        this.maxListScroll = Math.max(0, this.filteredItems.size() * (42 + 4) - 4 - listAreaH());
        this.listScrollOffset = Math.max(0, Math.min(this.listScrollOffset, this.maxListScroll));
        if (!this.filteredItems.contains(this.selectedRole)) {
            this.selectedRole = this.filteredItems.isEmpty() ? null : this.filteredItems.get(0);
        }
        ci.cancel();
    }

    private static SRERole resolveBlackoutRole() {
        String roleName = BlackoutWelcomeRenderer.getRoleName();
        if (roleName == null || roleName.isBlank()) {
            return BlackoutRoleIntroData.getCivilianRole();
        }

        String normalized = roleName.toLowerCase(Locale.ROOT);
        for (BlackoutRoleDefinition definition : BlackoutRoleRegistry.getAll()) {
            if (normalized.contains(definition.displayName().toLowerCase(Locale.ROOT))
                    || normalized.contains(definition.announcementName().toLowerCase(Locale.ROOT))
                    || normalized.contains(definition.identifier().getPath().toLowerCase(Locale.ROOT))) {
                return definition.sreRole();
            }
        }

        if (normalized.contains("sheriff") || roleName.contains("警长")) {
            SRERole sheriff = BlackoutRoleIntroData.getSheriffRole();
            if (sheriff != null) return sheriff;
        }
        if (normalized.contains("killer") || roleName.contains("杀手")) {
            SRERole killer = BlackoutRoleIntroData.getKillerRole();
            if (killer != null) return killer;
        }
        SRERole civilian = BlackoutRoleIntroData.getCivilianRole();
        return civilian != null ? civilian : null;
    }

    private static boolean matchesBlackoutRole(SRERole role, String query, String lowerQuery) {
        if (query.isEmpty()) {
            return true;
        }

        BlackoutRoleDefinition definition = BlackoutRoleRegistry.findBySreRole(role).orElse(null);
        String name = definition != null ? definition.displayName() : RoleUtils.getRoleName(role).getString();
        String id = role.getIdentifier().toString();
        return name.toLowerCase(Locale.ROOT).contains(lowerQuery)
                || id.toLowerCase(Locale.ROOT).contains(lowerQuery)
                || PinYinUtils.contains(query, name);
    }
}
