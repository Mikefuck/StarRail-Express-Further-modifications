package com.habitrain.core.client.mixin;

import com.habitrain.core.client.role.RoleIntroduceScreenRefreshAccess;
import com.habitrain.core.client.role.RoleOverrideTextTab;
import com.habitrain.core.game.sre.roleoverride.SreRoleOverrideResolver;
import com.habitrain.core.role.override.RoleOverrideFilter;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import org.agmas.noellesroles.Noellesroles;
import org.agmas.noellesroles.client.screen.RoleIntroduceScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Rebuilds the final-but-mutable role cache of an already-open role book while
 * preserving the screen instance, search box, history, and scroll state.
 */
@Mixin(value = RoleIntroduceScreen.class, remap = false)
public abstract class RoleIntroduceScreenMixin implements RoleIntroduceScreenRefreshAccess {
    @Shadow @Final private List<SRERole> availableRoles;
    @Shadow @Final private List<RoleIntroduceScreen.DetailTab> tabs;
    @Shadow private Object selectedRole;
    @Shadow private RoleIntroduceScreen.IntroductionGameMode currentMode;

    @Shadow
    public abstract void refreshFilter(RoleIntroduceScreen.IntroductionGameMode mode);

    @Invoker("onSelectionChanged")
    protected abstract void habitrain$invokeOnSelectionChanged();

    /**
     * A newly-created upstream screen fills {@code availableRoles} directly
     * from Noellesroles, after the last config-sync refresh has already run.
     * Replace that raw list before its first filter/card render.
     *
     * <p>{@code init()} overrides {@code Screen.init()} and is therefore remapped
     * to {@code method_25404} in the production (intermediary) jar; injecting the
     * literal name "init" with {@code remap = false} is silently dropped there.
     * The private no-arg {@code refreshFilter()} keeps its name in production and
     * is the first place {@code availableRoles} is iterated, so it is the safe
     * injection point.
     */
    @Inject(method = "refreshFilter()V", at = @At("HEAD"), remap = false)
    private void habitrain$prepareInitialRoleList(CallbackInfo ci) {
        habitrain$reloadAvailableRoles();
    }

    /**
     * Normalize stale/history/current-mode selections before upstream derives
     * faction labels, goals and tabs from the selected role instance.
     */
    @Inject(method = "buildTabs", at = @At("HEAD"))
    private void habitrain$resolveSelectedRole(CallbackInfo ci) {
        if (selectedRole instanceof SRERole selected) {
            SRERole resolved = SreRoleOverrideResolver.resolve(selected);
            if (resolved != null) {
                selectedRole = resolved;
            }
        }
    }

    /**
     * REPLACE owns the complete page set. MODIFY preserves upstream pages and
     * appends provider-owned explanation pages after them.
     */
    @Inject(method = "buildTabs", at = @At("RETURN"))
    private void habitrain$applyRoleBookPages(CallbackInfo ci) {
        if (!(selectedRole instanceof SRERole role) || role.identifier() == null) return;

        var snapshot = RoleOverrideEngine.getInstance().getSnapshot();
        for (var definition : snapshot.getActiveReplaces().values()) {
            if (!definition.replacementRole().identifier().equals(role.identifier())) continue;
            definition.roleBookContent().ifPresent(content -> {
                tabs.clear();
                content.pages().forEach(page -> tabs.add(new RoleOverrideTextTab(page)));
            });
            return;
        }

        var modify = snapshot.getActiveModifies().get(role.identifier());
        if (modify != null) {
            modify.roleBookAppendices()
                    .forEach(page -> tabs.add(new RoleOverrideTextTab(page)));
        }

        var v2 = com.habitrain.core.role.book.RoleBookResolver.resolve(role.identifier());
        if (!v2.isEmpty()) {
            if (v2.replaceAll()) {
                tabs.clear();
            }
            v2.pages().forEach(page -> tabs.add(new RoleOverrideTextTab(page)));
        }
    }

    @Override
    public void habitrain$refreshRoleOverrides() {
        habitrain$reloadAvailableRoles();
        refreshFilter(currentMode);
        // Upstream only calls onSelectionChanged/buildTabs when filtering
        // removes the selected role. MODIFY toggles keep the same role selected,
        // so force its normal selection path to rebuild pages and clamp the
        // active tab index after a REPLACE changes the complete tab set.
        habitrain$invokeOnSelectionChanged();
    }

    private void habitrain$reloadAvailableRoles() {
        availableRoles.clear();
        availableRoles.addAll(RoleOverrideFilter.apply(Noellesroles.getAllRolesSorted(true)));

        if (selectedRole instanceof SRERole selected) {
            SRERole resolved = SreRoleOverrideResolver.resolve(selected);
            selectedRole = resolved != null && availableRoles.contains(resolved) ? resolved : null;
        }
    }
}
