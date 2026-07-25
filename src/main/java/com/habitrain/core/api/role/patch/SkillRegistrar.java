package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;

@FunctionalInterface
public interface SkillRegistrar {
    void register(SRERole original);
}
