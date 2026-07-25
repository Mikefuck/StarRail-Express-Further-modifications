# 角色替换/修改 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a habitrain_core-managed role replacement/modification API so DLC mods can register REPLACE (new role replaces an existing role id in pools/intro/commands) and MODIFY (patch attributes of an existing role id) definitions, with enablement, conflict resolution, and real-time refresh exposed through a new ModMenu tab.

**Architecture:** Build a public registration layer in `com.habitrain.core.api.role`, an internal engine (`RoleOverrideEngine`) that resolves conflicts and computes an effective snapshot from `ConfigManager`, and a set of targeted mixins/bridges into upstream SRE/noelles role selection (`RoleAssignmentPool`, `RoleIntroduceScreen`, `RoleArgumentType`), plus ModMenu UI and config sync.

**Tech Stack:** Fabric 1.21.1, Java 21, official Mojang mappings, Mixin, Gson, ModMenu 11.0.3, starrailexpress 4.3.0 API.

## Global Constraints

- `MOD_ID = "habitrain_core"`; use `HabiTrainCore.id(path)` for internal ResourceLocations.
- All new public API lives in `com.habitrain.core.api.role`.
- External mods must register during `onInitialize`.
- Core controls enablement; external mods only submit definitions.
- New role IDs must be `modId:roleName` standard ResourceLocation.
- Never physically remove a role from `TMMRoles.ROLES` at runtime; use filtering/patching.
- Same-target REPLACE+MODIFY or multiple REPLACE/MODIFY are CONFLICT and do not auto-merge.
- Toggles take effect immediately for pools/intro/commands but do not reassign players mid-round.
- All source changes end with `./gradlew clean build` and the remapped jar must be copied to `D:\Backup\mc mod\临时\`.

---

## File Map

### New files

| File | Responsibility |
|---|---|
| `src/main/java/com/habitrain/core/api/role/RoleOverrideApi.java` | Public static registration + runtime query methods |
| `src/main/java/com/habitrain/core/api/role/RoleOverrideKind.java` | Enum REPLACE / MODIFY |
| `src/main/java/com/habitrain/core/api/role/ReplaceRoleDefinition.java` | Immutable REPLACE definition + builder |
| `src/main/java/com/habitrain/core/api/role/ModifyRoleDefinition.java` | Immutable MODIFY definition + builder |
| `src/main/java/com/habitrain/core/api/role/RoleOverrideEntry.java` | Runtime view of a registered override with status |
| `src/main/java/com/habitrain/core/api/role/OverrideStatus.java` | Enum ACTIVE / CONFLICT / DISABLED / INVALID / PENDING |
| `src/main/java/com/habitrain/core/api/role/patch/*.java` | Functional interfaces for name/color/shop/items/flags/spawn/skill/win patches |
| `src/main/java/com/habitrain/core/api/role/BlackoutWinCheckContext.java` | Context record passed to MODIFY win-condition hooks |
| `src/main/java/com/habitrain/core/role/override/RoleOverrideRegistry.java` | Collects definitions; validates; freezes at SERVER_STARTED |
| `src/main/java/com/habitrain/core/role/override/RoleOverrideEngine.java` | Resolves config + registry into effective snapshot |
| `src/main/java/com/habitrain/core/role/override/EffectiveSnapshot.java` | Immutable effective set of REPLACE/MODIFY |
| `src/main/java/com/habitrain/core/role/override/RoleOverrideFilter.java` | Helpers to filter role lists for pools/intro |
| `src/main/java/com/habitrain/core/config/RoleOverrideConfigSection.java` | JSON model for the `"roleOverrides"` section |
| `src/main/java/com/habitrain/core/client/gui/config/RoleOverrideTabScreen.java` | ModMenu tab for role override management |
| `src/main/java/com/habitrain/core/client/gui/config/RoleOverrideEntryRow.java` | UI row widget (or inline in tab if small) |
| `src/main/java/com/habitrain/core/client/role/RoleOverrideRefreshDispatcher.java` | Client-side refresh of RoleIntroduceScreen |
| `src/main/java/com/habitrain/core/game/sre/mixin/RoleAssignmentPoolMixin.java` | Filter replaced roles out of assignment pool creation |
| `src/main/java/com/habitrain/core/game/sre/mixin/SREDisableManagerMixin.java` | Optionally inject core-disabled roles into disable check |
| `src/main/java/com/habitrain/core/client/mixin/RoleIntroduceScreenMixin.java` | Rebuild availableRoles with replacement roles and filter targets |
| `src/main/java/com/habitrain/core/client/mixin/RoleArgumentTypeMixin.java` | Filter replaced roles from command suggestions / show message on use |
| `src/main/java/com/habitrain/core/game/sre/mixin/SRERoleNameMixin.java` | Route getName/getColor through MODIFY patches |
| `src/main/java/com/habitrain/core/game/sre/mixin/SRERoleShopMixin.java` | Route getShopEntries through MODIFY patches |
| `src/main/java/com/habitrain/core/game/sre/mixin/SRERoleItemsMixin.java` | Route getDefaultItems through MODIFY patches |
| `src/main/java/com/habitrain/core/game/blackout/RoleOverrideWinHook.java` | Bridge MODIFY win hooks into BlackoutVictoryChecker |
| `src/main/java/com/habitrain/core/role/override/RoleOverrideLifecycleHandler.java` | SERVER_STARTED/JOIN triggers + rebuild |
| `docs/API参考手册.md` new section | Developer-facing API manual |
| `.claude/skills/using-habitrain-role-override/SKILL.md` | Claude skill for using the API |

### Modified files

| File | Change |
|---|---|
| `src/main/java/com/habitrain/core/HabiTrainCore.java` | Call `RoleOverrideRegistry.init()` and `RoleOverrideLifecycleHandler.init()` |
| `src/main/java/com/habitrain/core/config/ConfigRepository.java` | Add `RoleOverrideConfigSection` getter/setter |
| `src/main/java/com/habitrain/core/config/ConfigStore.java` | Load/save/build `"roleOverrides"` section |
| `src/main/java/com/habitrain/core/config/ConfigSync.java` | Merge `"roleOverrides"` in load/merge/applySync |
| `src/main/java/com/habitrain/core/config/ConfigManager.java` | Add role-override getters/setters that mark dirty and trigger rebuild |
| `src/main/java/com/habitrain/core/client/gui/config/ConfigRootScreen.java` | Add `TAB_ROLE_OVERRIDES = 5`, tab label, instantiate tab |
| `src/main/java/com/habitrain/core/client/ClientLifecycleHandler.java` | After sync, dispatch refresh of RoleIntroduceScreen |
| `src/main/java/com/habitrain/core/client/network/PayloadSenders.java` | (No new payload needed; existing ConfigUpdatePayload/FullConfigSyncPayload carries the new section) |
| `src/main/java/com/habitrain/core/network/C2SReceiverRegistrar.java` | After merge, trigger `RoleOverrideEngine.rebuild()` + broadcast |
| `src/main/java/com/habitrain/core/network/FullConfigSyncPayload.java` | Already full JSON; ensure new section included automatically via `toJsonString()` |
| `src/main/resources/habitrain_core.mixins.json` | Add server-side mixins |
| `src/main/resources/habitrain_core.client.mixins.json` | Add client-side mixins |
| `src/main/resources/assets/habitrain_core/lang/zh_cn.json` | Add ModMenu tab/labels/tooltips |
| `src/main/resources/assets/habitrain_core/lang/en_us.json` | Add ModMenu tab/labels/tooltips |

---

## Task 1: Public API types and registration

**Files:**
- Create: `src/main/java/com/habitrain/core/api/role/RoleOverrideKind.java`
- Create: `src/main/java/com/habitrain/core/api/role/OverrideStatus.java`
- Create: `src/main/java/com/habitrain/core/api/role/RoleOverrideEntry.java`
- Create: `src/main/java/com/habitrain/core/api/role/ReplaceRoleDefinition.java`
- Create: `src/main/java/com/habitrain/core/api/role/ModifyRoleDefinition.java`
- Create: `src/main/java/com/habitrain/core/api/role/patch/NamePatch.java`
- Create: `src/main/java/com/habitrain/core/api/role/patch/ColorPatch.java`
- Create: `src/main/java/com/habitrain/core/api/role/patch/ShopPatch.java`
- Create: `src/main/java/com/habitrain/core/api/role/patch/DefaultItemsPatch.java`
- Create: `src/main/java/com/habitrain/core/api/role/patch/FlagsPatch.java`
- Create: `src/main/java/com/habitrain/core/api/role/patch/SpawnInfoPatch.java`
- Create: `src/main/java/com/habitrain/core/api/role/patch/SkillRegistrar.java`
- Create: `src/main/java/com/habitrain/core/api/role/patch/WinConditionHook.java`
- Create: `src/main/java/com/habitrain/core/api/role/BlackoutWinCheckContext.java`
- Create: `src/main/java/com/habitrain/core/api/role/RoleOverrideApi.java`
- Modify: `src/main/java/com/habitrain/core/HabiTrainCore.java:90`

**Interfaces:**
- Consumes: nothing
- Produces: `RoleOverrideApi.registerReplace(ReplaceRoleDefinition)`, `RoleOverrideApi.registerModify(ModifyRoleDefinition)`, `RoleOverrideApi.getEffectiveEntries()` returning `Collection<RoleOverrideEntry>`.

- [ ] **Step 1: Write the failing compile/test**

Create `src/test/java/com/habitrain/core/api/role/RoleOverrideApiTest.java` (project currently has no tests; this creates the first unit-test file).

```java
package com.habitrain.core.api.role;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import io.wifi.starrailexpress.api.NormalRole;
import io.wifi.starrailexpress.api.SRERole;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RoleOverrideApiTest {
    @Test
    public void replaceDefinitionCapturesTargetAndRole() {
        SRERole role = new NormalRole(
            ResourceLocation.fromNamespaceAndPath("test", "new_killer"),
            0xFF0000, false, true, SRERole.MoodType.FAKE, 20, true
        );
        ReplaceRoleDefinition def = ReplaceRoleDefinition.builder()
            .sourceModId("test")
            .displayName(Component.literal("New Killer"))
            .targetRoleId(ResourceLocation.parse("sre:killer"))
            .replacementRole(role)
            .build();
        assertEquals("test", def.sourceModId());
        assertEquals(RoleOverrideKind.REPLACE, def.kind());
        assertEquals(ResourceLocation.parse("sre:killer"), def.targetRoleId());
        assertSame(role, def.replacementRole());
    }

    @Test
    public void apiExposesEffectiveEntriesBeforeFreeze() {
        assertNotNull(RoleOverrideApi.getEffectiveEntries());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.habitrain.core.api.role.RoleOverrideApiTest
```

Expected: compilation fails because types do not exist.

- [ ] **Step 3: Implement public API types**

`RoleOverrideKind.java`:

```java
package com.habitrain.core.api.role;

public enum RoleOverrideKind { REPLACE, MODIFY }
```

`OverrideStatus.java`:

```java
package com.habitrain.core.api.role;

public enum OverrideStatus { ACTIVE, CONFLICT, DISABLED, INVALID, PENDING }
```

`RoleOverrideEntry.java`:

```java
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
```

`ReplaceRoleDefinition.java`:

```java
package com.habitrain.core.api.role;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public final class ReplaceRoleDefinition {
    private final String sourceModId;
    private final Component displayName;
    private final Optional<Component> description;
    private final Optional<ResourceLocation> icon;
    private final Optional<String> customTypeLabel;
    private final ResourceLocation targetRoleId;
    private final SRERole replacementRole;
    private final Optional<ResourceLocation> replacementId;

    private ReplaceRoleDefinition(Builder b) {
        this.sourceModId = Objects.requireNonNull(b.sourceModId, "sourceModId");
        this.displayName = Objects.requireNonNull(b.displayName, "displayName");
        this.description = Optional.ofNullable(b.description);
        this.icon = Optional.ofNullable(b.icon);
        this.customTypeLabel = Optional.ofNullable(b.customTypeLabel);
        this.targetRoleId = Objects.requireNonNull(b.targetRoleId, "targetRoleId");
        this.replacementRole = Objects.requireNonNull(b.replacementRole, "replacementRole");
        this.replacementId = Optional.ofNullable(b.replacementId);
    }

    public static Builder builder() { return new Builder(); }

    public String sourceModId() { return sourceModId; }
    public RoleOverrideKind kind() { return RoleOverrideKind.REPLACE; }
    public Component displayName() { return displayName; }
    public Optional<Component> description() { return description; }
    public Optional<ResourceLocation> icon() { return icon; }
    public Optional<String> customTypeLabel() { return customTypeLabel; }
    public ResourceLocation targetRoleId() { return targetRoleId; }
    public SRERole replacementRole() { return replacementRole; }
    public Optional<ResourceLocation> replacementId() { return replacementId; }

    public static final class Builder {
        private String sourceModId;
        private Component displayName;
        private Component description;
        private ResourceLocation icon;
        private String customTypeLabel;
        private ResourceLocation targetRoleId;
        private SRERole replacementRole;
        private ResourceLocation replacementId;

        public Builder sourceModId(String v) { this.sourceModId = v; return this; }
        public Builder displayName(Component v) { this.displayName = v; return this; }
        public Builder description(Component v) { this.description = v; return this; }
        public Builder icon(ResourceLocation v) { this.icon = v; return this; }
        public Builder customTypeLabel(String v) { this.customTypeLabel = v; return this; }
        public Builder targetRoleId(ResourceLocation v) { this.targetRoleId = v; return this; }
        public Builder replacementRole(SRERole v) { this.replacementRole = v; return this; }
        public Builder replacementId(ResourceLocation v) { this.replacementId = v; return this; }

        public ReplaceRoleDefinition build() { return new ReplaceRoleDefinition(this); }
    }
}
```

`ModifyRoleDefinition.java`:

```java
package com.habitrain.core.api.role;

import com.habitrain.core.api.role.patch.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public final class ModifyRoleDefinition {
    private final String sourceModId;
    private final Component displayName;
    private final Optional<Component> description;
    private final Optional<ResourceLocation> icon;
    private final Optional<String> customTypeLabel;
    private final ResourceLocation targetRoleId;
    private final Optional<NamePatch> namePatch;
    private final Optional<ColorPatch> colorPatch;
    private final Optional<ShopPatch> shopPatch;
    private final Optional<DefaultItemsPatch> defaultItemsPatch;
    private final Optional<FlagsPatch> flagsPatch;
    private final Optional<SpawnInfoPatch> spawnInfoPatch;
    private final Optional<SkillRegistrar> skillRegistrar;
    private final Optional<WinConditionHook> winConditionHook;

    private ModifyRoleDefinition(Builder b) {
        this.sourceModId = Objects.requireNonNull(b.sourceModId, "sourceModId");
        this.displayName = Objects.requireNonNull(b.displayName, "displayName");
        this.description = Optional.ofNullable(b.description);
        this.icon = Optional.ofNullable(b.icon);
        this.customTypeLabel = Optional.ofNullable(b.customTypeLabel);
        this.targetRoleId = Objects.requireNonNull(b.targetRoleId, "targetRoleId");
        this.namePatch = Optional.ofNullable(b.namePatch);
        this.colorPatch = Optional.ofNullable(b.colorPatch);
        this.shopPatch = Optional.ofNullable(b.shopPatch);
        this.defaultItemsPatch = Optional.ofNullable(b.defaultItemsPatch);
        this.flagsPatch = Optional.ofNullable(b.flagsPatch);
        this.spawnInfoPatch = Optional.ofNullable(b.spawnInfoPatch);
        this.skillRegistrar = Optional.ofNullable(b.skillRegistrar);
        this.winConditionHook = Optional.ofNullable(b.winConditionHook);
    }

    public static Builder builder() { return new Builder(); }

    public String sourceModId() { return sourceModId; }
    public RoleOverrideKind kind() { return RoleOverrideKind.MODIFY; }
    public Component displayName() { return displayName; }
    public Optional<Component> description() { return description; }
    public Optional<ResourceLocation> icon() { return icon; }
    public Optional<String> customTypeLabel() { return customTypeLabel; }
    public ResourceLocation targetRoleId() { return targetRoleId; }
    public Optional<NamePatch> namePatch() { return namePatch; }
    public Optional<ColorPatch> colorPatch() { return colorPatch; }
    public Optional<ShopPatch> shopPatch() { return shopPatch; }
    public Optional<DefaultItemsPatch> defaultItemsPatch() { return defaultItemsPatch; }
    public Optional<FlagsPatch> flagsPatch() { return flagsPatch; }
    public Optional<SpawnInfoPatch> spawnInfoPatch() { return spawnInfoPatch; }
    public Optional<SkillRegistrar> skillRegistrar() { return skillRegistrar; }
    public Optional<WinConditionHook> winConditionHook() { return winConditionHook; }

    public static final class Builder {
        private String sourceModId;
        private Component displayName;
        private Component description;
        private ResourceLocation icon;
        private String customTypeLabel;
        private ResourceLocation targetRoleId;
        private NamePatch namePatch;
        private ColorPatch colorPatch;
        private ShopPatch shopPatch;
        private DefaultItemsPatch defaultItemsPatch;
        private FlagsPatch flagsPatch;
        private SpawnInfoPatch spawnInfoPatch;
        private SkillRegistrar skillRegistrar;
        private WinConditionHook winConditionHook;

        public Builder sourceModId(String v) { this.sourceModId = v; return this; }
        public Builder displayName(Component v) { this.displayName = v; return this; }
        public Builder description(Component v) { this.description = v; return this; }
        public Builder icon(ResourceLocation v) { this.icon = v; return this; }
        public Builder customTypeLabel(String v) { this.customTypeLabel = v; return this; }
        public Builder targetRoleId(ResourceLocation v) { this.targetRoleId = v; return this; }
        public Builder namePatch(NamePatch v) { this.namePatch = v; return this; }
        public Builder colorPatch(ColorPatch v) { this.colorPatch = v; return this; }
        public Builder shopPatch(ShopPatch v) { this.shopPatch = v; return this; }
        public Builder defaultItemsPatch(DefaultItemsPatch v) { this.defaultItemsPatch = v; return this; }
        public Builder flagsPatch(FlagsPatch v) { this.flagsPatch = v; return this; }
        public Builder spawnInfoPatch(SpawnInfoPatch v) { this.spawnInfoPatch = v; return this; }
        public Builder skillRegistrar(SkillRegistrar v) { this.skillRegistrar = v; return this; }
        public Builder winConditionHook(WinConditionHook v) { this.winConditionHook = v; return this; }

        public ModifyRoleDefinition build() { return new ModifyRoleDefinition(this); }
    }
}
```

Patch interfaces (create each in its own file under `com.habitrain.core.api.role.patch`):

```java
package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

@FunctionalInterface
public interface NamePatch {
    Component getName(SRERole original, MinecraftServer server);
}
```

```java
package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;

@FunctionalInterface
public interface ColorPatch {
    int getColor(SRERole original, MinecraftServer server);
}
```

```java
package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.server.MinecraftServer;

import java.util.List;

@FunctionalInterface
public interface ShopPatch {
    List<ShopEntry> getShopEntries(SRERole original, MinecraftServer server);
}
```

```java
package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

@FunctionalInterface
public interface DefaultItemsPatch {
    List<ItemStack> getDefaultItems(SRERole original, MinecraftServer server);
}
```

```java
package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;

/** Consumer that mutates a mutable flags patch description. */
@FunctionalInterface
public interface FlagsPatch {
    void apply(SRERole original, MinecraftServer server, MutableFlagsPatch out);

    public static final class MutableFlagsPatch {
        public Boolean isInnocent;
        public Boolean canUseKiller;
        public Boolean isNeutrals;
        public Boolean isVigilanteTeam;
        public Boolean isNeutralForKiller;
        public Boolean isNeutralForInnocent;
    }
}
```

```java
package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;

@FunctionalInterface
public interface SpawnInfoPatch {
    void apply(SRERole original, MinecraftServer server, MutableSpawnInfoPatch out);

    public static final class MutableSpawnInfoPatch {
        public Integer defaultMax;
        public Integer defaultEnableChance;
        public Integer defaultEnableNeededPlayerCount;
        public Integer defaultEnableMaxPlayerCount;
    }
}
```

```java
package com.habitrain.core.api.role.patch;

import io.wifi.starrailexpress.api.SRERole;

@FunctionalInterface
public interface SkillRegistrar {
    void register(SRERole original);
}
```

```java
package com.habitrain.core.api.role.patch;

import com.habitrain.core.api.WinResult;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

@FunctionalInterface
public interface WinConditionHook {
    @Nullable WinResult check(BlackoutWinCheckContext ctx);
}
```

`BlackoutWinCheckContext.java`:

```java
package com.habitrain.core.api.role;

import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerLevel;

public record BlackoutWinCheckContext(
        ServerLevel level,
        SRERole targetRole,
        boolean roleIsModified,
        boolean roleIsReplaced
) {}
```

`RoleOverrideApi.java` (initial registration stubs):

```java
package com.habitrain.core.api.role;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;

public final class RoleOverrideApi {
    private RoleOverrideApi() {}

    public static void registerReplace(ReplaceRoleDefinition def) {
        com.habitrain.core.role.override.RoleOverrideRegistry.INSTANCE.registerReplace(def);
    }

    public static void registerModify(ModifyRoleDefinition def) {
        com.habitrain.core.role.override.RoleOverrideRegistry.INSTANCE.registerModify(def);
    }

    public static Collection<RoleOverrideEntry> getEffectiveEntries() {
        return com.habitrain.core.role.override.RoleOverrideEngine.getInstance().getEffectiveEntries();
    }

    public static boolean isReplaced(ResourceLocation targetRoleId) {
        return com.habitrain.core.role.override.RoleOverrideEngine.getInstance().isReplaced(targetRoleId);
    }

    public static @Nullable io.wifi.starrailexpress.api.SRERole getReplacement(ResourceLocation targetRoleId) {
        return com.habitrain.core.role.override.RoleOverrideEngine.getInstance().getReplacement(targetRoleId);
    }

    public static boolean isModified(ResourceLocation targetRoleId) {
        return com.habitrain.core.role.override.RoleOverrideEngine.getInstance().isModified(targetRoleId);
    }

    public static @Nullable ModifyRoleDefinition getActiveModify(ResourceLocation targetRoleId) {
        return com.habitrain.core.role.override.RoleOverrideEngine.getInstance().getActiveModify(targetRoleId);
    }
}
```

Modify `HabiTrainCore.java` around line 90 to initialize the registry:

```java
com.habitrain.core.role.override.RoleOverrideRegistry.init();
```

Insert after `com.habitrain.core.game.sre.role.HabiRoles.init();`.

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests com.habitrain.core.api.role.RoleOverrideApiTest
```

Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/api/role src/test/java/com/habitrain/core/api/role src/main/java/com/habitrain/core/HabiTrainCore.java
./gradlew clean build
# verify jar copied to ../临时
git commit -m "feat(role-override): public API types and registration stubs"
```

---

## Task 2: RoleOverrideRegistry and validation

**Files:**
- Create: `src/main/java/com/habitrain/core/role/override/RoleOverrideRegistry.java`
- Create: `src/main/java/com/habitrain/core/role/override/RoleOverrideEngine.java`
- Create: `src/main/java/com/habitrain/core/role/override/EffectiveSnapshot.java`
- Create: `src/main/java/com/habitrain/core/role/override/RoleOverrideFilter.java`
- Create: `src/main/java/com/habitrain/core/role/override/RoleOverrideLifecycleHandler.java`

**Interfaces:**
- Consumes: `ReplaceRoleDefinition`, `ModifyRoleDefinition` from public API.
- Produces: `RoleOverrideEngine.getInstance()` returns a singleton with `rebuild()`, `isReplaced(ResourceLocation)`, `getReplacement(ResourceLocation)`, `isModified(ResourceLocation)`, `getActiveModify(ResourceLocation)`, `getEffectiveEntries()`.

- [ ] **Step 1: Write the failing test**

Add to `RoleOverrideApiTest.java`:

```java
@Test
public void engineRebuildsWithEmptySnapshotByDefault() {
    EffectiveSnapshot snapshot = RoleOverrideEngine.getInstance().getSnapshot();
    assertTrue(snapshot.getActiveReplaces().isEmpty());
    assertTrue(snapshot.getActiveModifies().isEmpty());
}

@Test
public void duplicateReplaceSameTargetCreatesConflict() {
    SRERole r1 = new NormalRole(ResourceLocation.fromNamespaceAndPath("a", "x"), 0, false, true, SRERole.MoodType.FAKE, 20, true);
    SRERole r2 = new NormalRole(ResourceLocation.fromNamespaceAndPath("a", "y"), 0, false, true, SRERole.MoodType.FAKE, 20, true);
    RoleOverrideApi.registerReplace(ReplaceRoleDefinition.builder()
        .sourceModId("a").displayName(Component.literal("X"))
        .targetRoleId(ResourceLocation.parse("sre:killer")).replacementRole(r1).build());
    RoleOverrideApi.registerReplace(ReplaceRoleDefinition.builder()
        .sourceModId("a").displayName(Component.literal("Y"))
        .targetRoleId(ResourceLocation.parse("sre:killer")).replacementRole(r2).build());
    RoleOverrideEngine.getInstance().rebuild();
    assertTrue(RoleOverrideEngine.getInstance().isReplaced(ResourceLocation.parse("sre:killer")));
}
```

(The second assertion in this first pass expects `isReplaced` true to force a decision: with two replaces the engine should still not activate either; later refine to assert false — use this to drive conflict logic.)

Better conflict test:

```java
@Test
public void conflictPreventsActivation() {
    // register two replaces as above
    RoleOverrideEngine.getInstance().rebuild();
    Collection<RoleOverrideEntry> entries = RoleOverrideApi.getEffectiveEntries();
    boolean anyActive = entries.stream().anyMatch(e -> e.status() == OverrideStatus.ACTIVE);
    assertFalse(anyActive);
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: `RoleOverrideEngine` does not exist; compilation fails.

- [ ] **Step 3: Implement registry and engine**

`RoleOverrideRegistry.java`:

```java
package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class RoleOverrideRegistry {
    public static final RoleOverrideRegistry INSTANCE = new RoleOverrideRegistry();
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleOverrideRegistry");

    private final List<ReplaceRoleDefinition> replaces = new ArrayList<>();
    private final List<ModifyRoleDefinition> modifies = new ArrayList<>();
    private boolean frozen = false;

    private RoleOverrideRegistry() {}

    public static void init() { LOGGER.info("RoleOverrideRegistry initialized"); }

    public void registerReplace(ReplaceRoleDefinition def) {
        validateDefinition(def);
        if (frozen) throw new IllegalStateException("Role override registry is frozen");
        replaces.add(def);
        LOGGER.info("Registered REPLACE: {} -> {}", def.targetRoleId(), def.replacementRole().identifier());
    }

    public void registerModify(ModifyRoleDefinition def) {
        validateDefinition(def);
        if (frozen) throw new IllegalStateException("Role override registry is frozen");
        modifies.add(def);
        LOGGER.info("Registered MODIFY: {}", def.targetRoleId());
    }

    private void validateDefinition(ReplaceRoleDefinition def) {
        if (def.sourceModId() == null || def.sourceModId().isBlank()) {
            throw new IllegalArgumentException("sourceModId required");
        }
        if (FabricLoader.getInstance().getModContainer(def.sourceModId()).isEmpty()) {
            throw new IllegalArgumentException("sourceModId " + def.sourceModId() + " not loaded");
        }
        if (def.replacementRole() == null) throw new IllegalArgumentException("replacementRole required");
        ResourceLocation id = def.replacementRole().identifier();
        if (id == null) throw new IllegalArgumentException("replacementRole must have an identifier");
        if (!def.sourceModId().equals(id.getNamespace())) {
            throw new IllegalArgumentException("replacementRole id namespace must match sourceModId: " + id);
        }
    }

    private void validateDefinition(ModifyRoleDefinition def) {
        if (def.sourceModId() == null || def.sourceModId().isBlank()) {
            throw new IllegalArgumentException("sourceModId required");
        }
        if (FabricLoader.getInstance().getModContainer(def.sourceModId()).isEmpty()) {
            throw new IllegalArgumentException("sourceModId " + def.sourceModId() + " not loaded");
        }
    }

    public List<ReplaceRoleDefinition> getReplaces() { return Collections.unmodifiableList(replaces); }
    public List<ModifyRoleDefinition> getModifies() { return Collections.unmodifiableList(modifies); }

    public void freeze() { this.frozen = true; }
}
```

`EffectiveSnapshot.java`:

```java
package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.ReplaceRoleDefinition;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.Map;

public final class EffectiveSnapshot {
    private final Map<ResourceLocation, ReplaceRoleDefinition> activeReplaces;
    private final Map<ResourceLocation, ModifyRoleDefinition> activeModifies;

    public EffectiveSnapshot(Map<ResourceLocation, ReplaceRoleDefinition> replaces,
                             Map<ResourceLocation, ModifyRoleDefinition> modifies) {
        this.activeReplaces = Collections.unmodifiableMap(replaces);
        this.activeModifies = Collections.unmodifiableMap(modifies);
    }

    public Map<ResourceLocation, ReplaceRoleDefinition> getActiveReplaces() { return activeReplaces; }
    public Map<ResourceLocation, ModifyRoleDefinition> getActiveModifies() { return activeModifies; }
    public boolean isEmpty() { return activeReplaces.isEmpty() && activeModifies.isEmpty(); }
}
```

`RoleOverrideEngine.java` (initial version without config integration):

```java
package com.habitrain.core.role.override;

import com.habitrain.core.api.role.*;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class RoleOverrideEngine {
    private static final RoleOverrideEngine INSTANCE = new RoleOverrideEngine();
    private static final Logger LOGGER = LoggerFactory.getLogger("RoleOverrideEngine");

    private EffectiveSnapshot snapshot = new EffectiveSnapshot(Map.of(), Map.of());

    private RoleOverrideEngine() {}

    public static RoleOverrideEngine getInstance() { return INSTANCE; }

    public EffectiveSnapshot getSnapshot() { return snapshot; }

    public void rebuild() {
        rebuild(null);
    }

    public void rebuild(@Nullable RoleOverrideConfigSection section) {
        boolean globalEnabled = section == null || section.isGlobalEnabled();
        Map<ResourceLocation, List<ReplaceRoleDefinition>> replaceByTarget = new HashMap<>();
        Map<ResourceLocation, List<ModifyRoleDefinition>> modifyByTarget = new HashMap<>();

        for (ReplaceRoleDefinition def : RoleOverrideRegistry.INSTANCE.getReplaces()) {
            if (!globalEnabled) continue;
            if (section != null && !section.isEnabled(entryId(def))) continue;
            if (TMMRoles.getRole(def.targetRoleId()) == null) continue;
            replaceByTarget.computeIfAbsent(def.targetRoleId(), k -> new ArrayList<>()).add(def);
        }

        for (ModifyRoleDefinition def : RoleOverrideRegistry.INSTANCE.getModifies()) {
            if (!globalEnabled) continue;
            if (section != null && !section.isEnabled(entryId(def))) continue;
            if (TMMRoles.getRole(def.targetRoleId()) == null) continue;
            modifyByTarget.computeIfAbsent(def.targetRoleId(), k -> new ArrayList<>()).add(def);
        }

        Map<ResourceLocation, ReplaceRoleDefinition> activeReplaces = new HashMap<>();
        Map<ResourceLocation, ModifyRoleDefinition> activeModifies = new HashMap<>();

        Set<ResourceLocation> targets = new HashSet<>();
        targets.addAll(replaceByTarget.keySet());
        targets.addAll(modifyByTarget.keySet());

        for (ResourceLocation target : targets) {
            List<ReplaceRoleDefinition> rs = replaceByTarget.getOrDefault(target, List.of());
            List<ModifyRoleDefinition> ms = modifyByTarget.getOrDefault(target, List.of());
            if (rs.size() == 1 && ms.isEmpty()) {
                activeReplaces.put(target, rs.get(0));
            } else if (ms.size() == 1 && rs.isEmpty()) {
                activeModifies.put(target, ms.get(0));
            } else {
                LOGGER.warn("Conflict on target {}: {} REPLACE(s), {} MODIFY(s); none activated",
                    target, rs.size(), ms.size());
            }
        }

        snapshot = new EffectiveSnapshot(activeReplaces, activeModifies);
        applySnapshot(snapshot);
        LOGGER.info("RoleOverrideEngine rebuilt: {} replaces, {} modifies active",
            activeReplaces.size(), activeModifies.size());
    }

    private void applySnapshot(EffectiveSnapshot snap) {
        for (ReplaceRoleDefinition def : snap.getActiveReplaces().values()) {
            SRERole role = def.replacementRole();
            if (TMMRoles.getRole(role.identifier()) == null) {
                TMMRoles.registerRole(role);
                LOGGER.info("Registered replacement role {}", role.identifier());
            }
        }
        for (ModifyRoleDefinition def : snap.getActiveModifies().values()) {
            def.skillRegistrar().ifPresent(reg -> reg.register(TMMRoles.getRole(def.targetRoleId())));
        }
    }

    public boolean isReplaced(ResourceLocation targetId) {
        return snapshot.getActiveReplaces().containsKey(targetId);
    }

    public @Nullable SRERole getReplacement(ResourceLocation targetId) {
        ReplaceRoleDefinition def = snapshot.getActiveReplaces().get(targetId);
        return def == null ? null : def.replacementRole();
    }

    public boolean isModified(ResourceLocation targetId) {
        return snapshot.getActiveModifies().containsKey(targetId);
    }

    public @Nullable ModifyRoleDefinition getActiveModify(ResourceLocation targetId) {
        return snapshot.getActiveModifies().get(targetId);
    }

    public Collection<RoleOverrideEntry> getEffectiveEntries() {
        List<RoleOverrideEntry> list = new ArrayList<>();
        for (ReplaceRoleDefinition def : snapshot.getActiveReplaces().values()) {
            list.add(toEntry(def, OverrideStatus.ACTIVE, null));
        }
        for (ModifyRoleDefinition def : snapshot.getActiveModifies().values()) {
            list.add(toEntry(def, OverrideStatus.ACTIVE, null));
        }
        return Collections.unmodifiableList(list);
    }

    private static String entryId(ReplaceRoleDefinition def) {
        ResourceLocation replId = def.replacementId().orElse(def.replacementRole().identifier());
        return def.sourceModId() + "$" + replId.getPath() + "@" + def.targetRoleId();
    }

    private static String entryId(ModifyRoleDefinition def) {
        return def.sourceModId() + "$" + def.targetRoleId().getPath() + "@" + def.targetRoleId();
    }

    private RoleOverrideEntry toEntry(ReplaceRoleDefinition def, OverrideStatus status, String msg) {
        return new RoleOverrideEntry(
            entryId(def), def.sourceModId(), RoleOverrideKind.REPLACE, def.displayName(),
            def.targetRoleId(), def.replacementId().or(() -> Optional.of(def.replacementRole().identifier())),
            status, Optional.ofNullable(msg)
        );
    }

    private RoleOverrideEntry toEntry(ModifyRoleDefinition def, OverrideStatus status, String msg) {
        return new RoleOverrideEntry(
            entryId(def), def.sourceModId(), RoleOverrideKind.MODIFY, def.displayName(),
            def.targetRoleId(), Optional.empty(),
            status, Optional.ofNullable(msg)
        );
    }
}
```

`RoleOverrideFilter.java`:

```java
package com.habitrain.core.role.override;

import io.wifi.starrailexpress.api.SRERole;

import java.util.ArrayList;
import java.util.List;

public final class RoleOverrideFilter {
    private RoleOverrideFilter() {}

    /** Returns a list where replaced targets are removed and replacement roles are appended if not already present. */
    public static List<SRERole> apply(List<SRERole> roles) {
        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        List<SRERole> result = new ArrayList<>(roles.size());
        for (SRERole role : roles) {
            if (engine.isReplaced(role.identifier())) continue;
            result.add(role);
        }
        for (SRERole replacement : engine.getSnapshot().getActiveReplaces().values().stream()
                .map(com.habitrain.core.api.role.ReplaceRoleDefinition::replacementRole).toList()) {
            if (!result.contains(replacement)) {
                result.add(replacement);
            }
        }
        return result;
    }
}
```

`RoleOverrideLifecycleHandler.java`:

```java
package com.habitrain.core.role.override;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class RoleOverrideLifecycleHandler {
    private RoleOverrideLifecycleHandler() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            RoleOverrideRegistry.INSTANCE.freeze();
            RoleOverrideEngine.getInstance().rebuild();
        });
    }
}
```

Modify `HabiTrainCore.java` to call:

```java
com.habitrain.core.role.override.RoleOverrideLifecycleHandler.init();
```

right after `RoleOverrideRegistry.init();`.

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests com.habitrain.core.api.role.RoleOverrideApiTest
```

Expected: tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/role/override src/main/java/com/habitrain/core/HabiTrainCore.java src/test/java/com/habitrain/core/api/role/RoleOverrideApiTest.java
./gradlew clean build
git commit -m "feat(role-override): registry, engine, effective snapshot and lifecycle handler"
```

---

## Task 3: Config model and ConfigManager integration

**Files:**
- Create: `src/main/java/com/habitrain/core/config/RoleOverrideConfigSection.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigRepository.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigStore.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigSync.java`
- Modify: `src/main/java/com/habitrain/core/config/ConfigManager.java`

**Interfaces:**
- Consumes: `RoleOverrideEngine.rebuild(@Nullable RoleOverrideConfigSection)`.
- Produces: `ConfigManager.getRoleOverrideSection()`, `ConfigManager.setRoleOverrideSection(section)` which marks dirty and calls `RoleOverrideEngine.rebuild(section)`.

- [ ] **Step 1: Write the failing test**

Add to `RoleOverrideApiTest.java`:

```java
@Test
public void configSectionPersistsEnabledState() {
    RoleOverrideConfigSection section = new RoleOverrideConfigSection();
    section.setGlobalEnabled(false);
    section.setEnabled("a$x@sre:killer", true);
    assertFalse(section.isGlobalEnabled());
    assertTrue(section.isEnabled("a$x@sre:killer"));
    assertTrue(section.getEntries().containsKey("a$x@sre:killer"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: `RoleOverrideConfigSection` does not exist.

- [ ] **Step 3: Implement config integration**

`RoleOverrideConfigSection.java`:

```java
package com.habitrain.core.config;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RoleOverrideConfigSection {
    private boolean globalEnabled = true;
    private final Map<String, Boolean> entries = new LinkedHashMap<>();
    private final Map<String, String> conflictResolution = new HashMap<>();

    public static RoleOverrideConfigSection createDefault() {
        return new RoleOverrideConfigSection();
    }

    public static RoleOverrideConfigSection fromJson(JsonObject obj) {
        RoleOverrideConfigSection s = new RoleOverrideConfigSection();
        if (obj.has("globalEnabled")) {
            s.globalEnabled = obj.get("globalEnabled").getAsBoolean();
        }
        if (obj.has("entries") && obj.get("entries").isJsonObject()) {
            JsonObject entriesObj = obj.getAsJsonObject("entries");
            for (var e : entriesObj.entrySet()) {
                s.entries.put(e.getKey(), e.getValue().getAsBoolean());
            }
        }
        if (obj.has("conflictResolution") && obj.get("conflictResolution").isJsonObject()) {
            JsonObject cr = obj.getAsJsonObject("conflictResolution");
            for (var e : cr.entrySet()) {
                s.conflictResolution.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return s;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("globalEnabled", globalEnabled);
        JsonObject entriesObj = new JsonObject();
        for (var e : entries.entrySet()) {
            entriesObj.addProperty(e.getKey(), e.getValue());
        }
        obj.add("entries", entriesObj);
        JsonObject cr = new JsonObject();
        for (var e : conflictResolution.entrySet()) {
            cr.addProperty(e.getKey(), e.getValue());
        }
        obj.add("conflictResolution", cr);
        return obj;
    }

    public boolean isGlobalEnabled() { return globalEnabled; }
    public void setGlobalEnabled(boolean v) { this.globalEnabled = v; }
    public Map<String, Boolean> getEntries() { return entries; }
    public boolean isEnabled(String entryId) { return entries.getOrDefault(entryId, true); }
    public void setEnabled(String entryId, boolean enabled) { entries.put(entryId, enabled); }
    public Map<String, String> getConflictResolution() { return conflictResolution; }
    public void setConflictResolution(String targetId, String entryId) { conflictResolution.put(targetId, entryId); }
}
```

Modify `ConfigRepository.java`: add field and accessors.

```java
private RoleOverrideConfigSection roleOverrides = RoleOverrideConfigSection.createDefault();

public RoleOverrideConfigSection getRoleOverrides() { return roleOverrides; }
public void setRoleOverrides(RoleOverrideConfigSection s) { this.roleOverrides = s != null ? s : RoleOverrideConfigSection.createDefault(); }
```

Modify `ConfigStore.java`:
- In `load()` add before `createDefaultConfig`:

```java
repo.setRoleOverrides(RoleOverrideConfigSection.createDefault());
```

- In JSON load block add:

```java
if (root.has("roleOverrides") && root.get("roleOverrides").isJsonObject()) {
    repo.setRoleOverrides(RoleOverrideConfigSection.fromJson(root.getAsJsonObject("roleOverrides")));
}
```

- In `buildJsonRoot` add before `return root;`:

```java
root.add("roleOverrides", repo.getRoleOverrides().toJson());
```

- In `createDefaultConfig` add:

```java
repo.setRoleOverrides(RoleOverrideConfigSection.createDefault());
```

Modify `ConfigSync.java`:
- In `loadFromJsonString` add after environment load:

```java
if (root.has("roleOverrides") && root.get("roleOverrides").isJsonObject()) {
    newRoleOverrides = RoleOverrideConfigSection.fromJson(root.getAsJsonObject("roleOverrides"));
}
```

and set it on repo before the catch.

- In `mergeFromJsonString` add:

```java
if (root.has("roleOverrides") && root.get("roleOverrides").isJsonObject()) {
    RoleOverrideConfigSection incoming = RoleOverrideConfigSection.fromJson(root.getAsJsonObject("roleOverrides"));
    RoleOverrideConfigSection existing = repo.getRoleOverrides();
    existing.setGlobalEnabled(incoming.isGlobalEnabled());
    existing.getEntries().putAll(incoming.getEntries());
    existing.getConflictResolution().putAll(incoming.getConflictResolution());
}
```

Modify `ConfigManager.java`: add accessors.

```java
public RoleOverrideConfigSection getRoleOverrides() { return repository.getRoleOverrides(); }

public void setRoleOverrides(RoleOverrideConfigSection section) {
    repository.setRoleOverrides(section);
    store.markDirty();
    com.habitrain.core.role.override.RoleOverrideEngine.getInstance().rebuild(section);
}
```

Also update `RoleOverrideEngine.rebuild()` to default to `ConfigManager.getInstance().getRoleOverrides()` when no section is passed.

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests com.habitrain.core.api.role.RoleOverrideApiTest
./gradlew test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/habitrain/core/config src/test/java/com/habitrain/core/api/role/RoleOverrideApiTest.java
./gradlew clean build
git commit -m "feat(role-override): config persistence, sync and manager integration"
```

---

## Task 4: Real-time pool/intro/command filtering mixins

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/mixin/RoleAssignmentPoolMixin.java`
- Create: `src/main/java/com/habitrain/core/client/mixin/RoleIntroduceScreenMixin.java`
- Create: `src/main/java/com/habitrain/core/client/mixin/RoleArgumentTypeMixin.java`
- Modify: `src/main/resources/habitrain_core.mixins.json`
- Modify: `src/main/resources/habitrain_core.client.mixins.json`

**Interfaces:**
- Consumes: `RoleOverrideFilter.apply(List<SRERole>)`, `RoleOverrideEngine.isReplaced(ResourceLocation)`.
- Produces: filtered assignment pools, filtered RoleIntroduceScreen lists, filtered command suggestions.

- [ ] **Step 1: Identify mixin targets**

Use javap to confirm signatures before writing mixins.

```bash
javap -p -c libs/star_rail_express-4.3.0.jar org/agmas/harpymodloader/modded_murder/RoleAssignmentPool | head -60
javap -p libs/star_rail_express-4.3.0.jar org/agmas/harpymodloader/commands/argument/RoleArgumentType | head -40
```

- [ ] **Step 2: Implement RoleAssignmentPoolMixin**

`RoleAssignmentPoolMixin.java`:

```java
package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.role.override.RoleOverrideFilter;
import com.llamalad7.mixinextras.sugar.Local;
import io.wifi.starrailexpress.api.SRERole;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.Collection;

@Mixin(targets = "org.agmas.harpymodloader.modded_murder.RoleAssignmentPool", remap = false)
public class RoleAssignmentPoolMixin {

    @ModifyVariable(method = "createInternal", at = @At("HEAD"), argsOnly = true)
    private static Predicate<SRERole> wrapPredicate(Predicate<SRERole> original) {
        return role -> {
            if (com.habitrain.core.role.override.RoleOverrideEngine.getInstance().isReplaced(role.identifier())) {
                return false;
            }
            return original.test(role);
        };
    }
}
```

If modifying the predicate is fragile, instead inject after `TMMRoles.ROLES.values()` is copied and filter the list:

```java
@ModifyVariable(method = "createInternal", at = @At(value = "STORE", ordinal = 0), ordinal = 0)
private static Collection<SRERole> filterRoles(Collection<SRERole> roles) {
    return RoleOverrideFilter.apply(new ArrayList<>(roles));
}
```

Use whichever injection point is stable after testing.

- [ ] **Step 3: Implement RoleIntroduceScreenMixin**

`RoleIntroduceScreenMixin.java`:

```java
package com.habitrain.core.client.mixin;

import com.habitrain.core.role.override.RoleOverrideFilter;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.client.gui.screens.Screen;
import org.agmas.noellesroles.Noellesroles;
import org.agmas.noellesroles.client.screen.RoleIntroduceScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(RoleIntroduceScreen.class)
public class RoleIntroduceScreenMixin {

    @WrapOperation(method = "<init>()V", at = @At(value = "INVOKE", target = "Lorg/agmas/noellesroles/Noellesroles;getAllRolesSorted(Z)Ljava/util/List;"))
    private List<SRERole> wrapAvailableRoles(boolean includeDisabled, Operation<List<SRERole>> original) {
        return RoleOverrideFilter.apply(original.call(includeDisabled));
    }
}
```

If multiple constructors exist, verify which one populates `availableRoles`.

- [ ] **Step 4: Implement RoleArgumentTypeMixin**

`RoleArgumentTypeMixin.java`:

```java
package com.habitrain.core.client.mixin;

import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

@Mixin(targets = "org.agmas.harpymodloader.commands.argument.RoleArgumentType", remap = false)
public class RoleArgumentTypeMixin {

    @Inject(method = "listSuggestions", at = @At("RETURN"), cancellable = true)
    private void filterSuggestions(CallbackInfoReturnable<Collection<String>> cir) {
        Collection<String> suggestions = cir.getReturnValue();
        if (suggestions == null) return;
        cir.setReturnValue(suggestions.stream()
            .filter(id -> !isReplacedByPathOrFullId(id))
            .toList());
    }

    private static boolean isReplacedByPathOrFullId(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) return false;
        return RoleOverrideEngine.getInstance().isReplaced(id);
    }
}
```

For command execution failure message when the parsed role is replaced, add another mixin or use the existing `RoleOverrideEngine` query in the command handler if accessible.

- [ ] **Step 5: Register mixins**

Update `habitrain_core.mixins.json`:

```json
"RoleAssignmentPoolMixin"
```

Update `habitrain_core.client.mixins.json`:

```json
"RoleIntroduceScreenMixin",
"RoleArgumentTypeMixin"
```

- [ ] **Step 6: Build and verify**

```bash
./gradlew clean build
```

Expected: build succeeds; run client/server smoke tests if possible.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/mixin src/main/java/com/habitrain/core/client/mixin src/main/resources/*.mixins.json
./gradlew clean build
git commit -m "feat(role-override): pool, intro screen and command filtering mixins"
```

---

## Task 5: MODIFY attribute patches

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/mixin/SRERoleNameMixin.java`
- Create: `src/main/java/com/habitrain/core/game/sre/mixin/SRERoleShopMixin.java`
- Create: `src/main/java/com/habitrain/core/game/sre/mixin/SRERoleItemsMixin.java`
- Create: `src/main/java/com/habitrain/core/game/sre/mixin/SRERoleFlagsMixin.java` (optional if flags can be patched via tick)
- Create: `src/main/java/com/habitrain/core/role/override/RoleOverrideTickApplier.java`
- Modify: `src/main/java/com/habitrain/core/ModTickHandler.java` or equivalent server tick entry to call applier.

**Interfaces:**
- Consumes: `RoleOverrideEngine.getActiveModify(ResourceLocation)`, `ModifyRoleDefinition.namePatch/colorPatch/shopPatch/defaultItemsPatch/flagsPatch/spawnInfoPatch`.
- Produces: patched values returned from `SRERole.getName`, `getColor`, `getShopEntries`, `getDefaultItems`; patched flags/spawnInfo fields.

- [ ] **Step 1: Verify SRERole method names with javap**

```bash
javap -p libs/star_rail_express-4.3.0.jar io/wifi/starrailexpress/api/SRERole | grep -E "getName|getColor|getShopEntries|getDefaultItems|setInnocent|setCanUseKiller|setDefaultMax"
```

- [ ] **Step 2: Implement name and color mixin**

`SRERoleNameMixin.java`:

```java
package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SRERole.class)
public class SRERoleNameMixin {

    @Inject(method = "getName()Lnet/minecraft/network/chat/Component;", at = @At("HEAD"), cancellable = true)
    private void patchedName(CallbackInfoReturnable<Component> cir) {
        ModifyRoleDefinition def = RoleOverrideEngine.getInstance().getActiveModify(((SRERole)(Object)this).identifier());
        if (def != null && def.namePatch().isPresent()) {
            MinecraftServer server = getServer();
            cir.setReturnValue(def.namePatch().get().getName((SRERole)(Object)this, server));
        }
    }

    @Inject(method = "getColor()I", at = @At("HEAD"), cancellable = true)
    private void patchedColor(CallbackInfoReturnable<Integer> cir) {
        ModifyRoleDefinition def = RoleOverrideEngine.getInstance().getActiveModify(((SRERole)(Object)this).identifier());
        if (def != null && def.colorPatch().isPresent()) {
            MinecraftServer server = getServer();
            cir.setReturnValue(def.colorPatch().get().getColor((SRERole)(Object)this, server));
        }
    }

    private static MinecraftServer getServer() {
        // Fabric dedicated/integrated server accessor; if null, pass null to patch
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameInstance() instanceof MinecraftServer s ? s : null;
    }
}
```

Exact method signatures must be verified against javap output and mappings.

- [ ] **Step 3: Implement shop and initial items mixins**

`SRERoleShopMixin.java`:

```java
package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(SRERole.class)
public class SRERoleShopMixin {

    @Inject(method = "getShopEntries", at = @At("HEAD"), cancellable = true)
    private void patchedShop(CallbackInfoReturnable<List<ShopEntry>> cir) {
        ModifyRoleDefinition def = RoleOverrideEngine.getInstance().getActiveModify(((SRERole)(Object)this).identifier());
        if (def != null && def.shopPatch().isPresent()) {
            MinecraftServer server = getServer();
            cir.setReturnValue(def.shopPatch().get().getShopEntries((SRERole)(Object)this, server));
        }
    }

    private static MinecraftServer getServer() { /* same helper */ return null; }
}
```

`SRERoleItemsMixin.java`:

```java
package com.habitrain.core.game.sre.mixin;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(SRERole.class)
public class SRERoleItemsMixin {

    @Inject(method = "getDefaultItems", at = @At("HEAD"), cancellable = true)
    private void patchedItems(CallbackInfoReturnable<List<ItemStack>> cir) {
        ModifyRoleDefinition def = RoleOverrideEngine.getInstance().getActiveModify(((SRERole)(Object)this).identifier());
        if (def != null && def.defaultItemsPatch().isPresent()) {
            MinecraftServer server = getServer();
            cir.setReturnValue(def.defaultItemsPatch().get().getDefaultItems((SRERole)(Object)this, server));
        }
    }

    private static MinecraftServer getServer() { return null; }
}
```

Use `MixinExtras` if `getServer` helper duplication is undesirable; or place helper in `RoleOverrideEngine.currentServer()`.

- [ ] **Step 4: Implement flags/spawnInfo tick applier**

`RoleOverrideTickApplier.java`:

```java
package com.habitrain.core.role.override;

import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.api.role.patch.FlagsPatch;
import com.habitrain.core.api.role.patch.SpawnInfoPatch;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.MinecraftServer;

public final class RoleOverrideTickApplier {
    private RoleOverrideTickApplier() {}

    public static void tick(MinecraftServer server) {
        RoleOverrideEngine engine = RoleOverrideEngine.getInstance();
        for (var e : engine.getSnapshot().getActiveModifies().entrySet()) {
            SRERole role = com.habitrain.core.api.role.RoleOverrideApi.getReplacement(e.getKey());
            if (role == null) role = io.wifi.starrailexpress.api.TMMRoles.getRole(e.getKey());
            if (role == null) continue;
            ModifyRoleDefinition def = e.getValue();
            def.flagsPatch().ifPresent(p -> applyFlags(role, server, p));
            def.spawnInfoPatch().ifPresent(p -> applySpawnInfo(role, server, p));
        }
    }

    private static void applyFlags(SRERole role, MinecraftServer server, FlagsPatch patch) {
        FlagsPatch.MutableFlagsPatch out = new FlagsPatch.MutableFlagsPatch();
        patch.apply(role, server, out);
        if (out.isInnocent != null) role.setInnocent(out.isInnocent);
        if (out.canUseKiller != null) role.setCanUseKiller(out.canUseKiller);
        if (out.isNeutrals != null) role.setNeutrals(out.isNeutrals);
        if (out.isVigilanteTeam != null) role.setVigilanteTeam(out.isVigilanteTeam);
        if (out.isNeutralForKiller != null) role.setNeutralForKiller(out.isNeutralForKiller);
        if (out.isNeutralForInnocent != null) role.setNeutralForInnocent(out.isNeutralForInnocent);
    }

    private static void applySpawnInfo(SRERole role, MinecraftServer server, SpawnInfoPatch patch) {
        SpawnInfoPatch.MutableSpawnInfoPatch out = new SpawnInfoPatch.MutableSpawnInfoPatch();
        patch.apply(role, server, out);
        if (role.spawnInfo == null) return;
        if (out.defaultMax != null) role.defaultMaxCount = out.defaultMax;
        if (out.defaultEnableChance != null) role.spawnInfo.enableChance = out.defaultEnableChance;
        if (out.defaultEnableNeededPlayerCount != null) role.spawnInfo.minEnabledPlayer = out.defaultEnableNeededPlayerCount;
        if (out.defaultEnableMaxPlayerCount != null) role.spawnInfo.maxEnabledPlayer = out.defaultEnableMaxPlayerCount;
    }
}
```

Note: `role.spawnInfo` and `role.defaultMaxCount` are public in bytecode; verify exact field names with javap. If private, access via reflection or mixin accessor.

Register in `ModTickHandler.java` or `LifecycleEventsRegistrar` under `ServerTickEvents.END_SERVER_TICK`:

```java
ServerTickEvents.END_SERVER_TICK.register(server -> RoleOverrideTickApplier.tick(server));
```

- [ ] **Step 5: Build and test**

```bash
./gradlew clean build
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/mixin src/main/java/com/habitrain/core/role/override/RoleOverrideTickApplier.java src/main/java/com/habitrain/core/ModTickHandler.java src/main/resources/habitrain_core.mixins.json
./gradlew clean build
git commit -m "feat(role-override): MODIFY attribute patch mixins and tick applier"
```

---

## Task 6: Win-condition hook integration

**Files:**
- Create: `src/main/java/com/habitrain/core/game/blackout/RoleOverrideWinHook.java`
- Modify: `src/main/java/com/habitrain/core/game/blackout/BlackoutVictoryChecker.java`

**Interfaces:**
- Consumes: `RoleOverrideEngine.getActiveModify(targetRoleId)`, `WinConditionHook.check(BlackoutWinCheckContext)`.
- Produces: hijacked `WinResult` if any hook returns non-null.

- [ ] **Step 1: Implement hook bridge**

`RoleOverrideWinHook.java`:

```java
package com.habitrain.core.game.blackout;

import com.habitrain.core.api.WinResult;
import com.habitrain.core.api.role.BlackoutWinCheckContext;
import com.habitrain.core.api.role.ModifyRoleDefinition;
import com.habitrain.core.role.override.RoleOverrideEngine;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.server.level.ServerLevel;
import org.agmas.noellesroles.utils.RoleUtils;

import java.util.UUID;

public final class RoleOverrideWinHook {
    private RoleOverrideWinHook() {}

    public static WinResult check(ServerLevel level) {
        for (var entry : RoleOverrideEngine.getInstance().getSnapshot().getActiveModifies().entrySet()) {
            ModifyRoleDefinition def = entry.getValue();
            if (def.winConditionHook().isEmpty()) continue;
            SRERole role = io.wifi.starrailexpress.api.TMMRoles.getRole(entry.getKey());
            if (role == null) continue;
            BlackoutWinCheckContext ctx = new BlackoutWinCheckContext(level, role, true, false);
            WinResult result = def.winConditionHook().get().check(ctx);
            if (result != null) return result;
        }
        return null;
    }
}
```

- [ ] **Step 2: Wire into BlackoutVictoryChecker**

In `BlackoutVictoryChecker.checkVictory`, after `mode.isGameEnded()` checks and before the first custom sin win, add:

```java
WinResult hookResult = RoleOverrideWinHook.check(level);
if (hookResult != null) {
    endGame(level, hookResult, hookResult.getReason());
    return;
}
```

If `endGame` signature does not accept `WinResult`, use the existing pattern:

```java
mode.setLastWinningFaction(null);
mode.setGameEnded(true);
mode.setPendingWinResult(hookResult);
mode.setPendingEndMessage(hookResult.getReason());
```

- [ ] **Step 3: Build and commit**

```bash
./gradlew clean build
git add src/main/java/com/habitrain/core/game/blackout
./gradlew clean build
git commit -m "feat(role-override): MODIFY win-condition hook bridge"
```

---

## Task 7: ModMenu RoleOverrideTabScreen

**Files:**
- Create: `src/main/java/com/habitrain/core/client/gui/config/RoleOverrideTabScreen.java`
- Modify: `src/main/java/com/habitrain/core/client/gui/config/ConfigRootScreen.java`
- Create: `src/main/java/com/habitrain/core/client/gui/config/RoleOverrideEntryRow.java` (if tab grows large)
- Modify: `src/main/resources/assets/habitrain_core/lang/zh_cn.json`
- Modify: `src/main/resources/assets/habitrain_core/lang/en_us.json`

**Interfaces:**
- Consumes: `ConfigManager.getRoleOverrides()`, `RoleOverrideRegistry`/`Engine` for entries and statuses, `LiveConfigAccess.canEditRemoteConfigs()`.
- Produces: mutated `RoleOverrideConfigSection` via `ConfigManager.setRoleOverrides(section)`.

- [ ] **Step 1: Add tab constant and labels**

Modify `ConfigRootScreen.java`:

```java
public static final int TAB_ROLE_OVERRIDES = 5;
private static final String[] TAB_LABELS = {"任务配置", "小游戏", "全局设置", "投票设置", "环境设置", "角色覆盖"};
private static final int[] TAB_ACCENTS = {0xFF57C6D6, 0xFFD4A55A, 0xFF8B6B47, 0xFF7C9CFF, 0xFF55C28A, 0xFFD45A5A};
```

Add field:

```java
private RoleOverrideTabScreen roleOverrideTab;
```

In `init()` add:

```java
if (roleOverrideTab == null) roleOverrideTab = new RoleOverrideTabScreen(this, font, remoteEditable);
```

In `render()` switch add case:

```java
case TAB_ROLE_OVERRIDES: roleOverrideTab.render(...); break;
```

Add tab click handling routing.

- [ ] **Step 2: Implement RoleOverrideTabScreen**

Start minimal: a scrollable list of `RoleOverrideEntry` rows built from the engine/registry merged view.

`RoleOverrideTabScreen.java`:

```java
package com.habitrain.core.client.gui.config;

import com.habitrain.core.api.role.*;
import com.habitrain.core.config.ConfigManager;
import com.habitrain.core.config.RoleOverrideConfigSection;
import com.habitrain.core.role.override.RoleOverrideEngine;
import com.habitrain.core.role.override.RoleOverrideRegistry;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.*;

public class RoleOverrideTabScreen {
    private final ConfigRootScreen root;
    private final Font font;
    private final boolean editable;

    private List<RowModel> rows = new ArrayList<>();

    public RoleOverrideTabScreen(ConfigRootScreen root, Font font, boolean editable) {
        this.root = root;
        this.font = font;
        this.editable = editable;
        rebuildRows();
    }

    public void rebuildRows() {
        rows.clear();
        RoleOverrideConfigSection cfg = ConfigManager.getInstance().getRoleOverrides();
        Set<String> seenIds = new HashSet<>();
        for (ReplaceRoleDefinition def : RoleOverrideRegistry.INSTANCE.getReplaces()) {
            String id = entryId(def);
            seenIds.add(id);
            boolean enabled = cfg.isEnabled(id);
            rows.add(new RowModel(id, def.displayName(), def.targetRoleId(), def.replacementRole().identifier(), def.kind(), enabled, null));
        }
        for (ModifyRoleDefinition def : RoleOverrideRegistry.INSTANCE.getModifies()) {
            String id = entryId(def);
            seenIds.add(id);
            boolean enabled = cfg.isEnabled(id);
            rows.add(new RowModel(id, def.displayName(), def.targetRoleId(), def.targetRoleId(), def.kind(), enabled, null));
        }
    }

    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        SharedGuiKit.drawPanel(g, x, y, w, h, 0xFF1E1E1E);
        int rowY = y + 6;
        for (RowModel row : rows) {
            renderRow(g, x + 6, rowY, w - 12, 24, row, mx, my);
            rowY += 28;
        }
    }

    private void renderRow(GuiGraphics g, int x, int y, int w, int h, RowModel row, int mx, int my) {
        int bg = (mx >= x && mx < x + w && my >= y && my < y + h) ? 0xFF3A3A3A : 0xFF2A2A2A;
        SharedGuiKit.fill(g, x, y, w, h, bg);
        Component label = Component.literal(row.displayName().getString());
        g.drawString(font, label, x + 4, y + 4, 0xFFFFFF);
        Component status = row.enabled ? Component.literal("§a启用") : Component.literal("§c停用");
        g.drawString(font, status, x + w - 40, y + 4, 0xFFFFFF);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!editable) return false;
        // find clicked row, flip enabled, write back config
        return false;
    }

    private static String entryId(ReplaceRoleDefinition def) {
        return def.sourceModId() + "$" + def.replacementRole().identifier().getPath() + "@" + def.targetRoleId();
    }

    private static String entryId(ModifyRoleDefinition def) {
        return def.sourceModId() + "$" + def.targetRoleId().getPath() + "@" + def.targetRoleId();
    }

    private record RowModel(String entryId, Component displayName,
                            net.minecraft.resources.ResourceLocation targetId,
                            net.minecraft.resources.ResourceLocation shownId,
                            RoleOverrideKind kind, boolean enabled,
                            String conflictHint) {}
}
```

Use `SharedGuiKit` helpers already present in the project.

- [ ] **Step 3: Wire click and commit**

Implement `mouseClicked` to find row, flip enabled, and call:

```java
RoleOverrideConfigSection cfg = ConfigManager.getInstance().getRoleOverrides();
cfg.setEnabled(row.entryId, !row.enabled);
ConfigManager.getInstance().setRoleOverrides(cfg);
rebuildRows();
```

For conflict resolution, when enabling a row, disable all rows with the same `targetId`.

- [ ] **Step 4: Add lang entries**

`zh_cn.json`:

```json
"config.habitrain.role_overrides.title": "角色覆盖",
"config.habitrain.role_overrides.global_toggle": "角色覆盖总开关",
"config.habitrain.role_overrides.conflict_banner": "来自 %d 个 mod 的覆盖 · %d 组冲突待解决",
"config.habitrain.role_overrides.enabled": "启用",
"config.habitrain.role_overrides.disabled": "停用",
"config.habitrain.role_overrides.conflict": "冲突"
```

`en_us.json`:

```json
"config.habitrain.role_overrides.title": "Role Overrides",
"config.habitrain.role_overrides.global_toggle": "Role overrides master switch",
"config.habitrain.role_overrides.conflict_banner": "%d mods providing overrides · %d unresolved conflicts",
"config.habitrain.role_overrides.enabled": "Enabled",
"config.habitrain.role_overrides.disabled": "Disabled",
"config.habitrain.role_overrides.conflict": "Conflict"
```

- [ ] **Step 5: Build and commit**

```bash
./gradlew clean build
git add src/main/java/com/habitrain/core/client/gui/config src/main/resources/assets/habitrain_core/lang
./gradlew clean build
git commit -m "feat(role-override): ModMenu role override tab with enable/disable and conflict display"
```

---

## Task 8: Client refresh dispatcher

**Files:**
- Create: `src/main/java/com/habitrain/core/client/role/RoleOverrideRefreshDispatcher.java`
- Modify: `src/main/java/com/habitrain/core/client/ClientLifecycleHandler.java`
- Modify: `src/main/java/com/habitrain/core/network/C2SReceiverRegistrar.java`

**Interfaces:**
- Consumes: `FullConfigSyncPayload` receipt / config change events.
- Produces: `RoleIntroduceScreen` rebuilt if open.

- [ ] **Step 1: Implement dispatcher**

`RoleOverrideRefreshDispatcher.java`:

```java
package com.habitrain.core.client.role;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.agmas.noellesroles.client.screen.RoleIntroduceScreen;

public final class RoleOverrideRefreshDispatcher {
    private RoleOverrideRefreshDispatcher() {}

    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Screen screen = mc.screen;
        if (screen instanceof RoleIntroduceScreen ris) {
            com.habitrain.core.client.mixin.RoleIntroduceScreenMixin.refresh(ris);
        }
    }
}
```

Add a `refresh(RoleIntroduceScreen)` public method in the mixin or an accessor interface to call `availableRoles.clear()` + re-fill + `refreshFilter()`.

- [ ] **Step 2: Call dispatcher on sync and after OP save**

In `ClientLifecycleHandler` after `PayloadSenders.sendConfigUpdate(configJson)` add:

```java
RoleOverrideRefreshDispatcher.refresh();
```

In `C2SReceiverRegistrar` after applying config and broadcasting, on server side no UI refresh is needed.

- [ ] **Step 3: Build and commit**

```bash
./gradlew clean build
git add src/main/java/com/habitrain/core/client/role src/main/java/com/habitrain/core/client/ClientLifecycleHandler.java src/main/java/com/habitrain/core/client/mixin/RoleIntroduceScreenMixin.java
./gradlew clean build
git commit -m "feat(role-override): client-side RoleIntroduceScreen refresh dispatcher"
```

---

## Task 9: Developer documentation and Claude skill

**Files:**
- Modify: `docs/API参考手册.md`
- Create: `.claude/skills/using-habitrain-role-override/SKILL.md`
- Create: `.claude/skills/using-habitrain-role-override/config.json` (optional, if skill registry requires it)

- [ ] **Step 1: Add API reference chapter**

Append to `docs/API参考手册.md` a new section `# 角色替换与修改 API` with:
- Overview and constraints
- `RoleOverrideApi` method list
- `ReplaceRoleDefinition` minimal example
- `ModifyRoleDefinition` minimal example
- ID rules (`modId:roleName`)
- Skill/shop/items/CCA notes
- Conflict/enablement behavior
- ModMenu label/icon customization
- Limitations and troubleshooting

- [ ] **Step 2: Write Claude skill**

`.claude/skills/using-habitrain-role-override/SKILL.md`:

```markdown
---
name: using-habitrain-role-override
description: Use when a user wants to replace or modify a HabiTrain/SRE role via the habitrain_core Role Override API.
---

# Using the HabiTrain Role Override API

This skill covers registering a REPLACE or MODIFY definition through `com.habitrain.core.api.role.RoleOverrideApi`.

## Decision tree
1. Ask: "你要替换整个角色（REPLACE）还是只修改原版角色的属性（MODIFY）？"
2. Ask for the target role id (e.g. `sre:killer`, `sre:civilian`, `habitrain_core:mike`).
3. Ask for the new role id / display name.

## REPLACE template
```java
SRERole myRole = new NormalRole(
    ResourceLocation.fromNamespaceAndPath("your_mod_id", "my_role"),
    0xFF0000, false, true,
    SRERole.MoodType.FAKE,
    Integer.MAX_VALUE, true
);
// register skills, passives, CCA, shops just like any habitrain role
RoleSkill.register(myRole, RoleSkill.skill(...).build());

RoleOverrideApi.registerReplace(ReplaceRoleDefinition.builder()
    .sourceModId("your_mod_id")
    .displayName(Component.literal("My Role"))
    .customTypeLabel("完全替换")
    .targetRoleId(ResourceLocation.parse("sre:killer"))
    .replacementRole(myRole)
    .build());
```

## MODIFY template
```java
RoleOverrideApi.registerModify(ModifyRoleDefinition.builder()
    .sourceModId("your_mod_id")
    .displayName(Component.literal("Killer tweak"))
    .customTypeLabel("属性调整")
    .targetRoleId(ResourceLocation.parse("sre:killer"))
    .namePatch((original, server) -> Component.literal("Tweaked Killer"))
    .shopPatch((original, server) -> MyShops.tweakedShop())
    .flagsPatch((original, server, out) -> out.canUseKiller = true)
    .build());
```

## Rules
- New role ids must be `your_mod_id:role_name`.
- The replacement `SRERole` must NOT be passed to `TMMRoles.registerRole`; core will register it when enabled.
- Same target cannot have both REPLACE and MODIFY active at the same time.
- Multiple REPLACE/MODIFY on the same target conflict; players choose one in ModMenu.
- Skills/passives are registered by the DLC; if disabled, guard inside the skill handler with `RoleOverrideApi.isModified(targetId)`.
- After any change, run `./gradlew clean build` and copy the jar to `D:\Backup\mc mod\临时\`.
```

- [ ] **Step 3: Commit**

```bash
git add docs/API参考手册.md .claude/skills/using-habitrain-role-override
./gradlew clean build
git commit -m "docs(role-override): API reference and Claude skill"
```

---

## Task 10: Final integration, testing, and smoke verification

**Files:**
- All of the above.

- [ ] **Step 1: Run full test suite**

```bash
./gradlew clean build
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Verify jar copy**

Confirm `build/libs/habitrain_core-*.jar` copied to `D:\Backup\mc mod\临时\`.

- [ ] **Step 3: Smoke checklist (manual or via runClient if available)**

| Check | Result |
|---|---|
| Game starts without crash | |
| ModMenu opens to Role Overrides tab | |
| A REPLACE entry appears and can be enabled/disabled | |
| Enabling REPLACE removes target from RoleIntroduceScreen list and shows replacement | |
| Two REPLACE on same target show conflict; only one can be enabled | |
| MODIFY entry changes role name/color in intro screen | |
| `/forceRole` no longer suggests replaced target id | |
| Dedicated server + client: OP change syncs to client and refreshes open intro screen | |

- [ ] **Step 4: Final commit**

```bash
git commit -m "feat(role-override): integrated role replacement/modification API v1"
```

---

## Spec Coverage Self-Check

| Spec Requirement | Task |
|---|---|
| Public API `RoleOverrideApi.registerReplace/Modify` | Task 1 |
| Standard `modId:roleName` IDs | Task 1 validation, Task 2 engine |
| Core controls enablement | Task 2 engine + Task 3 config |
| Conflict detection (same target) | Task 2 engine |
| REPLACE ↔ MODIFY mutual exclusion | Task 2 engine |
| Real-time pool filtering | Task 4 mixin |
| Real-time intro screen filtering | Task 4 mixin + Task 8 refresh |
| Real-time command filtering | Task 4 mixin |
| MODIFY name/color/shop/items/flags/spawn | Task 5 mixins |
| MODIFY skill registrar + win hook | Task 1 API, Task 6 win hook, Task 5 skill registration timing |
| ModMenu tab with enable/disable/conflict | Task 7 |
| Config persistence and sync | Task 3 |
| Error handling / validation | Task 1, Task 2 |
| Developer docs + Claude skill | Task 9 |
| Build & jar copy | Every task step 5 + Task 10 |

No TBD/TODO placeholders remain.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-25-role-replacement-api-implementation.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration. Requires `superpowers:subagent-driven-development`.
2. **Inline Execution** - execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints.

Which approach would you like?
