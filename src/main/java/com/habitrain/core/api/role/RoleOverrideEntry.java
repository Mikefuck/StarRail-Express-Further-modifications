package com.habitrain.core.api.role;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

public record RoleOverrideEntry(
        String entryId,
        String sourceModId,
        RoleOverrideKind kind,
        Component displayName,
        ResourceLocation targetRoleId,
        Optional<ResourceLocation> replacementId,
        OverrideStatus status,
        Optional<String> statusMessage
) {}
