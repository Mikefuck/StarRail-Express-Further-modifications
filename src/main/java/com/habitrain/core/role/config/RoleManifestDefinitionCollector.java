package com.habitrain.core.role.config;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.action.RoleActionApi;
import com.habitrain.core.api.role.v2.action.RoleActionSpec;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleChatPolicy;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.definition.RoleAlias;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.skill.RoleSkillPatch;
import com.habitrain.core.api.role.v2.skill.RoleSkillSpec;
import com.habitrain.core.api.role.v2.state.RoleStateApi;
import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import com.habitrain.core.role.behavior.HookType;
import com.habitrain.core.role.behavior.RoleHookRegistry;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import io.wifi.starrailexpress.api.RoleSkill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Collects the canonical rows behind the §14.2 definition hash (audit P1-4).
 *
 * <p>The old hash covered only {@code entryId:status:target}, so two builds
 * with identical entry ids but different fields, skills, hooks, actions or
 * state schemas produced the same hash. This collector folds the whole
 * declarative contract — compiled entries with their declaration content,
 * behavior hooks, action schemas, state schemas and voice/chat policies — into
 * sorted, stable rows. Executable handler implementations are deliberately NOT
 * hashed (lambda identity is unstable); behavioral differences remain the
 * responsibility of provider version + required-client checks.
 *
 * <p>Rows are prefixed {@code role-definition-v2} so a future format change
 * cannot collide with old hashes.
 */
final class RoleManifestDefinitionCollector {

    private static final String PREFIX = "role-definition-v2";

    private RoleManifestDefinitionCollector() {}

    /** SHA-256 over the sorted canonical rows of every registered declaration. */
    static String definitionHash() {
        List<String> rows = canonicalRows();
        Collections.sort(rows);
        return RoleManifestHashes.sha256(PREFIX + "\n" + String.join("\n", rows));
    }

    static List<String> canonicalRows() {
        List<String> rows = new ArrayList<>();
        for (ManagedRoleEntry<?> entry : RoleExtensionRegistry.INSTANCE.getCompiledEntries()) {
            rows.add(entryRow(entry));
        }
        for (var byRole : RoleHookRegistry.INSTANCE.entryView().values()) {
            for (var byType : byRole.values()) {
                for (var hook : byType) {
                    rows.add("hook=" + hook.providerId() + ":" + hook.entryId() + ":"
                            + hook.role() + ":" + hook.type() + ":" + hook.scope() + ":"
                            + hook.priority().value());
                }
            }
        }
        for (RoleActionSpec spec : RoleActionApi.instance().specs()) {
            rows.add("action=" + spec.id() + ":" + spec.role() + ":" + spec.direction() + ":"
                    + spec.maxBytes() + ":" + spec.ratePerSecond() + ":" + spec.cooldownTicks() + ":"
                    + spec.requireCurrentRole() + ":" + spec.requireAlive() + ":"
                    + spec.requireTargetAlive() + ":" + spec.maxDistance() + ":"
                    + spec.requireLineOfSight() + ":" + spec.targetDecoder());
        }
        for (RoleStateSpec<?> spec : RoleStateApi.instance().specs()) {
            rows.add("state=" + spec.id() + ":" + spec.role() + ":" + spec.scope() + ":"
                    + spec.persistence() + ":" + spec.sync() + ":" + spec.resetOn() + ":"
                    + spec.dataVersion() + ":" + spec.maxSerializedBytes() + ":"
                    + spec.type().getName());
        }
        for (RoleVoicePolicy policy : RoleCapabilityApi.instance().voices()) {
            rows.add("voice=" + policy.id() + ":" + policy.role() + ":"
                    + policy.muteSend() + ":" + policy.muteReceive() + ":"
                    + policy.isolateGroup() + ":" + policy.hearWorld() + ":"
                    + policy.maxDistance());
        }
        for (RoleChatPolicy policy : RoleCapabilityApi.instance().chats()) {
            rows.add("chat=" + policy.id() + ":" + policy.role() + ":"
                    + policy.muteSend() + ":" + policy.muteReceive());
        }
        return rows;
    }

    private static String entryRow(ManagedRoleEntry<?> entry) {
        StringBuilder sb = new StringBuilder("entry=")
                .append(entry.providerId()).append(':')
                .append(entry.entryId()).append(':')
                .append(entry.entryKey() == null ? "" : entry.entryKey()).append(':')
                .append(entry.operation()).append(':')
                .append(entry.priority() == null ? "" : entry.priority().value()).append(':')
                .append(entry.status()).append(':')
                .append(entry.target() == null ? "" : entry.target().location());
        Object declaration = entry.declaration();
        if (declaration instanceof RolePatch patch) {
            sb.append(':').append(patchDescriptor(patch));
        } else if (declaration instanceof RoleDefinition def) {
            sb.append(':').append(definitionDescriptor(def));
        } else if (declaration instanceof RoleReplacement repl) {
            sb.append(':').append(repl.identity()).append(':')
                    .append(repl.replacement().key().location());
        } else if (declaration instanceof RoleAlias alias) {
            sb.append(':').append(alias.from().location()).append("->").append(alias.to().location());
        }
        return sb.toString();
    }

    private static String definitionDescriptor(RoleDefinition def) {
        List<String> parts = new ArrayList<>();
        var presentation = def.presentation();
        var faction = def.faction();
        var spawn = def.spawn();
        parts.add("color=" + (presentation == null ? 0 : presentation.color()));
        parts.add("mood=" + (presentation == null ? null : presentation.moodType()));
        if (presentation != null && presentation.nameKey() != null) {
            parts.add("nameKey=" + presentation.nameKey());
        }
        if (presentation != null && presentation.descriptionKey() != null) {
            parts.add("descriptionKey=" + presentation.descriptionKey());
        }
        if (presentation != null && presentation.simpleDescriptionKey() != null) {
            parts.add("simpleDescriptionKey=" + presentation.simpleDescriptionKey());
        }
        if (presentation != null && presentation.objectivesKey() != null) {
            parts.add("objectivesKey=" + presentation.objectivesKey());
        }
        if (presentation != null && presentation.icon() != null) {
            parts.add("icon=" + presentation.icon());
        }
        parts.add("innocent=" + (faction != null && faction.innocent()));
        parts.add("canUseKiller=" + (faction != null && faction.canUseKiller()));
        parts.add("spawn=" + (spawn == null ? "none"
                : spawn.defaultMaxCount() + "/" + spawn.defaultEnableChance() + "/"
                + spawn.defaultEnableNeedPlayerCount() + "/" + spawn.defaultEnableMaxPlayerCount()));
        parts.add("maxSprintTime=" + def.maxSprintTime());
        parts.add("canSeeTime=" + def.canSeeTime());
        parts.add("skills=" + sortedSkillIds(def.skills()));
        parts.add("relations=" + relationDescriptor(def.relations()));
        if (def.book() != null) {
            parts.add("book=" + def.book().pages().stream()
                    .map(p -> String.valueOf(p.title())).sorted().toList());
        }
        Collections.sort(parts);
        return String.join("&", parts);
    }

    private static String patchDescriptor(RolePatch patch) {
        List<String> parts = new ArrayList<>();
        if (patch.color() != null) parts.add("color=" + patch.color().color());
        if (patch.mood() != null) parts.add("mood=" + patch.mood().mood());
        addBoolean(parts, "innocent", patch.innocent());
        addBoolean(parts, "canUseKiller", patch.canUseKiller());
        addBoolean(parts, "neutral", patch.neutral());
        addBoolean(parts, "vigilanteTeam", patch.vigilanteTeam());
        addInt(parts, "defaultMax", patch.defaultMax());
        addInt(parts, "enableChance", patch.enableChance());
        addInt(parts, "needPlayerCount", patch.needPlayerCount());
        addInt(parts, "maxPlayerCount", patch.maxPlayerCount());
        addBoolean(parts, "canSeeCoin", patch.canSeeCoin());
        addBoolean(parts, "canPickUpRevolver", patch.canPickUpRevolver());
        addBoolean(parts, "canBeRandomed", patch.canBeRandomed());
        addInt(parts, "maxSprintTime", patch.maxSprintTime());
        addBoolean(parts, "canSeeTime", patch.canSeeTime());
        addBoolean(parts, "neutralForKiller", patch.neutralForKiller());
        addBoolean(parts, "neutralForInnocent", patch.neutralForInnocent());
        addBoolean(parts, "mafiaTeam", patch.mafiaTeam());
        addBoolean(parts, "canUseInstinct", patch.canUseInstinct());
        addBoolean(parts, "instinctNightVision", patch.instinctNightVision());
        addBoolean(parts, "canSeeTeammateKiller", patch.canSeeTeammateKiller());
        addBoolean(parts, "otherModeRole", patch.otherModeRole());
        addBoolean(parts, "hiddenForRotation", patch.hiddenForRotation());
        addInt(parts, "occupiedRoleCount", patch.occupiedRoleCount());
        if (patch.specialMapRole() != null) parts.add("specialMapRole=" + patch.specialMapRole().map());
        addKeys(parts, "occupation", patch.occupation());
        addKeys(parts, "opposing", patch.opposing());
        addKeys(parts, "related", patch.related());
        if (patch.skills() != null) {
            parts.add("skills=" + patch.skills().op() + ":"
                    + sortedSkillIds(patch.skills().skills()));
        }
        if (patch.book() != null) {
            parts.add("book=" + patch.book().op() + ":"
                    + patch.book().pages().stream().map(p -> String.valueOf(p.title())).sorted().toList());
        }
        Collections.sort(parts);
        return String.join("&", parts);
    }

    private static void addBoolean(List<String> parts, String name, RolePatch.BooleanPatch p) {
        if (p != null) {
            parts.add(name + "=" + p.op() + ":" + p.value());
        }
    }

    private static void addInt(List<String> parts, String name, RolePatch.IntPatch p) {
        if (p != null) {
            parts.add(name + "=" + p.op() + ":" + p.value());
        }
    }

    private static void addKeys(List<String> parts, String name, RolePatch.RoleKeyListPatch p) {
        if (p != null) {
            List<String> keys = p.keys().stream().map(k -> k.toString()).sorted().toList();
            parts.add(name + "=" + p.op() + ":" + keys);
        }
    }

    private static String sortedSkillIds(List<RoleSkillSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return "[]";
        }
        List<String> ids = new ArrayList<>();
        for (RoleSkillSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            if (spec.definition() != null && spec.definition().id() != null) {
                ids.add(spec.definition().id().toString());
            } else if (spec.id() != null) {
                ids.add(spec.id().toString());
            }
        }
        ids.sort(Comparator.naturalOrder());
        return String.valueOf(ids);
    }

    private static String relationDescriptor(com.habitrain.core.api.role.v2.definition.RoleRelationProfile rel) {
        if (rel == null) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        parts.add("occupation=" + sortedKeys(rel.occupation()));
        parts.add("opposing=" + sortedKeys(rel.opposing()));
        parts.add("related=" + sortedKeys(rel.related()));
        parts.add("twoWay=" + rel.opposingTwoWay());
        Collections.sort(parts);
        return String.join("&", parts);
    }

    private static String sortedKeys(List<RoleKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return "[]";
        }
        return String.valueOf(keys.stream().map(Object::toString).sorted().toList());
    }
}
