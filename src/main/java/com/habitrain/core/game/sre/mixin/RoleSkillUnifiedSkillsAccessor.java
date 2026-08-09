package com.habitrain.core.game.sre.mixin;

import io.wifi.starrailexpress.api.RoleSkill;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

/**
 * Accesses only the unified definition map. The legacy skill map must never be
 * cleared when a managed role override is disabled.
 */
@Mixin(value = RoleSkill.class, remap = false)
public interface RoleSkillUnifiedSkillsAccessor {
    @Accessor("UNIFIED_SKILLS")
    static Map<ResourceLocation, List<RoleSkill.Definition>> habitrain$getUnifiedSkills() {
        throw new AssertionError("mixin accessor not transformed");
    }

    @Accessor("SKILL_REGISTRY")
    static Map<ResourceLocation, RoleSkill.SkillEntry> habitrain$getSkillRegistry() {
        throw new AssertionError("mixin accessor not transformed");
    }
}
