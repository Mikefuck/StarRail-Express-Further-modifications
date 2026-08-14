package com.habitrain.core.role.config;

import com.habitrain.core.api.role.v2.EffectiveRole;
import com.habitrain.core.api.role.v2.RoleSnapshot;
import com.habitrain.core.role.extension.ManagedRoleEntry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic SHA-256 fingerprints for the §14.2 manifest. Both hashes are
 * stable across the wire and unit-testable without a game.
 */
public final class RoleManifestHashes {

    private RoleManifestHashes() {}

    /** The gameplay definition hash: SHA-256 over sorted {@code entryId:status:target} rows. */
    public static String definitionHash(List<ManagedRoleEntry<?>> entries) {
        List<String> rows = new ArrayList<>();
        for (ManagedRoleEntry<?> entry : entries) {
            rows.add(entry.entryId() + ":" + entry.status().name() + ":"
                    + (entry.target() == null ? entry.entryId() : entry.target().toString()));
        }
        Collections.sort(rows);
        return sha256(String.join("\n", rows));
    }

    /** The presentation hash: SHA-256 over sorted effective-role presentation rows. */
    public static String presentationHash(RoleSnapshot snapshot) {
        List<String> rows = new ArrayList<>();
        if (snapshot != null) {
            for (EffectiveRole er : snapshot.effectiveRoles()) {
                var profile = er.profile();
                rows.add(profile.key() + "=" + profile.color() + ":"
                        + profile.mood() + ":" + profile.maxSprintTime() + ":"
                        + profile.innocent());
            }
        }
        Collections.sort(rows);
        return sha256(String.join("\n", rows));
    }

    public static String sha256(String input) {
        if (input == null) {
            input = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
