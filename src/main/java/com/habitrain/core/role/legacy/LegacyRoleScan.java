package com.habitrain.core.role.legacy;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records {@code TMMRoles.registerRole} calls that are not owned by the
 * v2 ADD / REPLACE compiler (design §10.2). Observation only — never
 * blocks or deletes the registration.
 */
public final class LegacyRoleScan {

    public static final LegacyRoleScan INSTANCE = new LegacyRoleScan();

    private final Map<ResourceLocation, LegacyHit> hits = new LinkedHashMap<>();
    /** On by default so the mixin records registrations that happen during class init. */
    private volatile boolean capturing = true;

    private LegacyRoleScan() {}

    /** Starts capturing. Safe to call more than once. */
    public void start() {
        this.capturing = true;
    }

    public boolean isCapturing() {
        return capturing;
    }

    /**
     * Records one registration. Called from the {@code TMMRoles} mixin
     * after the role is already in the map.
     */
    public synchronized void record(@Nullable ResourceLocation id, @Nullable String source) {
        if (!capturing || id == null) {
            return;
        }
        if (RoleExtensionRegistry.INSTANCE.isAdded(id)
                || RoleExtensionRegistry.INSTANCE.getCompiledReplacements().containsKey(id)) {
            return;
        }
        hits.putIfAbsent(id, new LegacyHit(RoleKey.of(id), source == null ? "unknown" : source));
    }

    public synchronized Collection<LegacyHit> hits() {
        return Collections.unmodifiableCollection(new ArrayList<>(hits.values()));
    }

    public synchronized List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("legacy " + hits.size());
        if (hits.isEmpty()) {
            lines.add("  (none)");
            return lines;
        }
        for (LegacyHit hit : hits.values()) {
            lines.add("  " + hit.key() + " [" + hit.source() + "]");
        }
        return lines;
    }

    public synchronized void clear() {
        hits.clear();
        capturing = false;
    }

    public record LegacyHit(RoleKey key, String source) {}
}
