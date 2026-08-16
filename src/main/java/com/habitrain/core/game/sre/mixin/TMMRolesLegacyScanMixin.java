package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.role.legacy.LegacyRoleScan;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes {@link TMMRoles#registerRole(SRERole)} so unmanaged
 * registrations surface as {@code LEGACY_UNMANAGED}. Does not cancel.
 */
@Mixin(value = TMMRoles.class, remap = false)
public class TMMRolesLegacyScanMixin {

    @Inject(
            method = "registerRole(Lio/wifi/starrailexpress/api/SRERole;)Lio/wifi/starrailexpress/api/SRERole;",
            at = @At("RETURN"),
            remap = false)
    private static void habitrain$recordLegacy(SRERole role, CallbackInfoReturnable<SRERole> cir) {
        if (role == null || role.identifier() == null) {
            return;
        }
        String source = "unknown";
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (StackTraceElement frame : stack) {
                String name = frame.getClassName();
                if (name.startsWith("com.habitrain.core.game.sre.mixin.TMMRolesLegacyScanMixin")) {
                    continue;
                }
                if (name.startsWith("io.wifi.starrailexpress.api.TMMRoles")) {
                    continue;
                }
                if (name.startsWith("java.") || name.startsWith("jdk.")) {
                    continue;
                }
                source = name;
                break;
            }
        } catch (Throwable ignored) {
        }
        LegacyRoleScan.INSTANCE.record(role.identifier(), source);
    }
}
