package com.habitrain.core.role.book;

import com.habitrain.core.api.role.book.RoleBookPage;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.book.RoleBookPatch;
import com.habitrain.core.api.role.v2.book.RoleBookView;
import com.habitrain.core.api.role.v2.definition.ListOp;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.role.extension.ManagedSRERole;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Resolves the v2 role-book view for a surfaced role id.
 *
 * <p>ADD content (if any) is the baseline. {@code MODIFY} patches targeting
 * the same id are folded in registration order. {@link ListOp#REPLACE_ALL}
 * owns the complete tab set; otherwise the result is an appendix.
 */
public final class RoleBookResolver {

    private RoleBookResolver() {}

    public static RoleBookView resolve(@Nullable ResourceLocation roleId) {
        if (roleId == null) {
            return RoleBookView.none();
        }
        RoleExtensionRegistry registry = RoleExtensionRegistry.INSTANCE;
        List<RoleBookPage> pages = List.of();
        boolean replaceAll = false;

        ManagedSRERole managed = registry.getManagedRole(roleId);
        if (managed == null) {
            var replacement = registry.replacementFor(roleId);
            if (replacement != null) {
                managed = registry.compiledReplacement(replacement);
            } else {
                for (ManagedSRERole compiled : registry.getCompiledReplacements().values()) {
                    if (roleId.equals(compiled.identifier())) {
                        managed = compiled;
                        break;
                    }
                }
            }
        }
        if (managed != null && managed.book() != null && isManagedRoleEnabled(managed, roleId)) {
            pages = managed.book().pages();
            replaceAll = true;
        }

        for (var configured : registry.configuredPatchesFor(roleId)) {
            RolePatch patch = configured.patch();
            RoleBookPatch book = patch.book();
            if (book == null) {
                continue;
            }
            pages = book.apply(pages);
            if (book.op() == ListOp.REPLACE_ALL) {
                replaceAll = true;
            }
        }
        if (pages.isEmpty()) {
            return RoleBookView.none();
        }
        return replaceAll ? RoleBookView.replaceAll(pages) : RoleBookView.append(pages);
    }

    /** ADD book declarations are owned by the role's provider/id gate. */
    private static boolean isManagedRoleEnabled(ManagedSRERole managed, ResourceLocation roleId) {
        if (managed == null || roleId == null) {
            return false;
        }
        return RoleExtensionConfigService.INSTANCE.gateFor(roleId.getNamespace(), roleId.toString())
                == RoleExtensionConfigService.EntryGate.ENABLED;
    }

    public static RoleBookView resolve(@Nullable RoleKey key) {
        return key == null ? RoleBookView.none() : resolve(key.location());
    }
}
