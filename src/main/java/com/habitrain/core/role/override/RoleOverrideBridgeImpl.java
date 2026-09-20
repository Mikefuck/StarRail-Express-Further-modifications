package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import com.habitrain.core.api.role.RoleOverrideEntry;
import com.habitrain.core.api.spi.RoleSpi;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * 审核 A2：把 v1 {@code RoleOverrideApi} 的静态实现装配到
 * {@link RoleSpi.RoleOverrideBridge}，使公开层不再反向 import {@code role.override}。
 */
public final class RoleOverrideBridgeImpl implements RoleSpi.RoleOverrideBridge {

    public static final RoleOverrideBridgeImpl INSTANCE = new RoleOverrideBridgeImpl();

    private RoleOverrideBridgeImpl() {}

    @Override
    public void registerReplace(ReplaceRoleDefinition def) {
        RoleOverrideRegistry.INSTANCE.registerReplace(def);
    }

    @Override
    public void registerModify(ModifyRoleDefinition def) {
        RoleOverrideRegistry.INSTANCE.registerModify(def);
    }

    @Override
    public String entryId(ReplaceRoleDefinition def) {
        return RoleOverrideRegistry.entryId(def);
    }

    @Override
    public String entryId(ModifyRoleDefinition def) {
        return RoleOverrideRegistry.entryId(def);
    }

    @Override
    public Collection<RoleOverrideEntry> effectiveEntries() {
        return RoleOverrideEngine.getInstance().getEffectiveEntries();
    }

    @Override
    public boolean isReplaced(ResourceLocation targetRoleId) {
        return RoleOverrideEngine.getInstance().isReplaced(targetRoleId);
    }

    @Override
    public @Nullable SRERole replacement(ResourceLocation targetRoleId) {
        return RoleOverrideEngine.getInstance().getReplacement(targetRoleId);
    }

    @Override
    public boolean isModified(ResourceLocation targetRoleId) {
        return RoleOverrideEngine.getInstance().isModified(targetRoleId);
    }

    @Override
    public @Nullable ModifyRoleDefinition activeModify(ResourceLocation targetRoleId) {
        return RoleOverrideEngine.getInstance().getActiveModify(targetRoleId);
    }
}
