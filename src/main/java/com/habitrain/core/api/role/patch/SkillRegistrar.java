package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;

@FunctionalInterface
@Deprecated
public interface SkillRegistrar {
    /**
     * Legacy one-way registration callback. Upstream exposes no per-definition
     * unregister operation, so integrations must guard handlers with
     * {@code RoleOverrideApi.isModified(targetId)}.
     */
    void register(SRERole original);
}
