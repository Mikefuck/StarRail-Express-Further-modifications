# Seven Sins Roles + Seven Virtues Modifiers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship seven mutually exclusive deadly-sin roles and seven-virtue modifiers in `habitrain_core`, playable in SRE murder and Blackout with correct faction counting and custom wins.

**Architecture:** Extend existing hubs (`HabiRoles` / new `HabiModifiers`) with `sins/` and `modifier/` packages. Mutual exclusion = `setDefaultMax(1)` + full `addTwoWayOpposingRole` clique + `OnGamePlayerRolesConfirm` hard cap. SRE wins via `CustomWinnerRole` + `AllowGameEnd`; Blackout wins via extended `BlackoutRoleManager` faction tags + `BlackoutVictoryChecker` + shared `SinVictoryHooks`. Greed trade is last phase.

**Tech Stack:** Fabric 1.21.1, Java 21, Cardinal Components, StarRailExpress 4.3.0 (`libs/star_rail_express-4.3.0.jar`), existing habitrain role/CCA/event patterns.

**Spec:** `docs/superpowers/specs/2026-07-15-seven-sins-virtues-design.md`

## Global Constraints

- Java 21; Minecraft 1.21.1 + Fabric; hard dep `starrailexpress` via local JAR
- No unit-test harness — verify with `./gradlew clean build` + manual runtime checklist per task
- After any code change: `./gradlew clean build` and ensure JAR lands in `D:\Backup\mc mod\临时\`
- Role/modifier lang keys use **path only** (not full id)
- Shops for habitrain roles: **always** override `getShopEntries()`; never rely only on `ShopContent.customEntries`
- `setComponentKey` **before** `TMMRoles.registerRole` inserts into map
- Do **not** re-register `stupid_express:lovers` or `noellesroles:generous`
- Do **not** touch `D:\Backup\mc mod\backup\`
- Address user as Mike only in chat, not in commit messages
- Blackout `SREBlackoutGameMode.allowGameEnd` always returns `NOT_MODIFY` — Blackout custom wins **must** go through `BlackoutVictoryChecker`, not SRE `AllowGameEnd`

## File Structure Map

### Create

```
src/main/java/com/habitrain/core/game/sre/role/sins/
  SevenSins.java
  SevenSinsMutex.java
  SevenSinEvents.java
  SinDeathReasons.java
  shop/SevenSinShops.java
  win/SinVictoryHooks.java
  win/PrideRole.java
  win/GreedRole.java
  win/LustRole.java
  win/SlothRole.java
  component/PrideComponent.java
  component/EnvyComponent.java
  component/WrathComponent.java
  component/GreedComponent.java
  component/GluttonyComponent.java
  component/LustComponent.java
  component/SlothComponent.java
  item/GreedPouchItem.java                    # P3
  trade/GreedTradeManager.java                # P3
  trade/GreedTradeState.java                  # P3
  trade/GreedDealTracker.java                 # P3 world-scoped deal counts

src/main/java/com/habitrain/core/game/sre/modifier/
  HabiModifiers.java
  VirtueGroup.java
  virtue/HumilityVirtue.java
  virtue/MercyVirtue.java
  virtue/TaskTimeVirtues.java
  virtue/TemperanceVirtue.java
  virtue/ChastityVirtue.java
  virtue/TemperancePurchaseState.java

src/main/java/com/habitrain/core/network/          # as needed P2/P3
  SinStageSyncPayload.java                    # wrath stage / sloth attackers (optional)
  GreedTradePayloads.java                     # P3

src/main/java/com/habitrain/core/game/sre/mixin/
  GluttonyEatMixin.java                       # P1
  TemperanceShopMixin.java                    # P4
  TaskInteractTimeMixin.java                  # P4 (or extend SREPlayerTaskComponentMixin)
  GreedPouchDropMixin.java                    # P3
```

### Modify

```
HabiTrainCore.java              — HabiModifiers.init() after HabiRoles
HabiRoles.java                  — call SevenSins.init() + SevenSinsMutex + SevenSinEvents
HabiComponents.java             — register 7 CCA keys + clearAll
fabric.mod.json                 — cardinal-components list
BlackoutRoleManager.java        — Faction tags + sync mapping
BlackoutVictoryChecker.java     — sin win order + hijacks + populateRoundEndData
HabiRoleEvents.java             — optional thin forward only if needed
habitrain_core.mixins.json      — new mixins
assets/.../lang/zh_cn.json
assets/.../lang/en_us.json
NetworkRegistrar.java           — P2/P3 payloads if any
```

---

### Task 1: P0 — Sin catalog helpers + death reason tables

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/role/sins/SinDeathReasons.java`
- Create: `src/main/java/com/habitrain/core/game/sre/role/sins/SevenSins.java` (IDs + empty static role fields only first; full register in Task 2)

**Interfaces:**
- Produces: `SinDeathReasons.isConventionalWeapon(ResourceLocation)`, `isPoisonDeath(ResourceLocation)`, path constants for knife/bat/etc.
- Produces: `SevenSins.PRIDE_ID` … `SLOTH_ID` ResourceLocations via `HabiTrainCore.id("sin_pride")` etc.

- [ ] **Step 1: Add `SinDeathReasons`**

```java
package com.habitrain.core.game.sre.role.sins;

import net.minecraft.resources.ResourceLocation;
import java.util.Locale;
import java.util.Set;

public final class SinDeathReasons {
    private SinDeathReasons() {}

    /** Conventional weapons pride/wrath/envy may cancel when allowed. */
    private static final Set<String> CONVENTIONAL = Set.of(
            "knife", "bat", "nunchuck", "fist", "revolver", "gun",
            "throwing_knife", "once_revolver", "sheriff_gun"
    );

    private static final Set<String> POISON = Set.of(
            "poison", "wither", "toxic", "venom"
    );

    private static final Set<String> FORCE_ALWAYS = Set.of(
            "fell_out_of_train", "disconnected", "sanity_collapse",
            "exile", "void", "command"
    );

    public static boolean isForcePath(ResourceLocation reason) {
        if (reason == null) return false;
        return FORCE_ALWAYS.contains(reason.getPath().toLowerCase(Locale.ROOT));
    }

    public static boolean isConventionalWeapon(ResourceLocation reason) {
        if (reason == null) return false;
        String p = reason.getPath().toLowerCase(Locale.ROOT);
        if (FORCE_ALWAYS.contains(p)) return false;
        for (String key : CONVENTIONAL) {
            if (p.contains(key)) return true;
        }
        return false;
    }

    public static boolean isPoisonDeath(ResourceLocation reason) {
        if (reason == null) return false;
        String p = reason.getPath().toLowerCase(Locale.ROOT);
        for (String key : POISON) {
            if (p.contains(key)) return true;
        }
        return false;
    }
}
```

- [ ] **Step 2: Add ID constants skeleton in `SevenSins`**

```java
package com.habitrain.core.game.sre.role.sins;

import com.habitrain.core.HabiTrainCore;
import io.wifi.starrailexpress.api.SRERole;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.Set;

public final class SevenSins {
    private SevenSins() {}

    public static final ResourceLocation PRIDE_ID = HabiTrainCore.id("sin_pride");
    public static final ResourceLocation ENVY_ID = HabiTrainCore.id("sin_envy");
    public static final ResourceLocation WRATH_ID = HabiTrainCore.id("sin_wrath");
    public static final ResourceLocation GREED_ID = HabiTrainCore.id("sin_greed");
    public static final ResourceLocation GLUTTONY_ID = HabiTrainCore.id("sin_gluttony");
    public static final ResourceLocation LUST_ID = HabiTrainCore.id("sin_lust");
    public static final ResourceLocation SLOTH_ID = HabiTrainCore.id("sin_sloth");

    public static SRERole PRIDE, ENVY, WRATH, GREED, GLUTTONY, LUST, SLOTH;

    public static Set<ResourceLocation> allIds() {
        return Set.of(PRIDE_ID, ENVY_ID, WRATH_ID, GREED_ID, GLUTTONY_ID, LUST_ID, SLOTH_ID);
    }

    public static boolean isSin(SRERole role) {
        return role != null && allIds().contains(role.getIdentifier());
    }

    public static boolean isIndependentSin(SRERole role) {
        if (role == null) return false;
        ResourceLocation id = role.getIdentifier();
        return PRIDE_ID.equals(id) || GREED_ID.equals(id)
                || LUST_ID.equals(id) || SLOTH_ID.equals(id);
    }

    public static boolean isKillerShareSin(SRERole role) {
        return role != null && WRATH_ID.equals(role.getIdentifier());
    }

    public static void init() {
        // filled in Task 2
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/role/sins/SinDeathReasons.java \
        src/main/java/com/habitrain/core/game/sre/role/sins/SevenSins.java
git commit -m "feat(sins): add sin id catalog and death reason tables"
```

---

### Task 2: P0 — Register seven roles (flags, shops stubs, opposing clique)

**Files:**
- Create: `sins/shop/SevenSinShops.java`
- Create: `sins/win/PrideRole.java`, `GreedRole.java`, `LustRole.java`, `SlothRole.java` (minimal `CustomWinnerRole` subclasses)
- Modify: `SevenSins.java` — full `registerRoles` + clique
- Modify: `HabiRoles.java` — call `SevenSins.init()` from `init()`

**Interfaces:**
- Consumes: ID constants from Task 1
- Produces: non-null `SevenSins.PRIDE`…`SLOTH` after `init()`; each `setDefaultMax(1)`; full opposing clique

- [ ] **Step 1: Shop factories (stubs OK for P0)**

```java
package com.habitrain.core.game.sre.role.sins.shop;

import io.wifi.starrailexpress.util.ShopEntry;
import java.util.ArrayList;
import java.util.List;

public final class SevenSinShops {
    private SevenSinShops() {}

    public static List<ShopEntry> empty() { return new ArrayList<>(); }

    /** Envy full shop filled in P1; P0 return empty or knife-only if items resolve. */
    public static List<ShopEntry> envyShop() { return empty(); }

    public static List<ShopEntry> greedShop() { return empty(); } // lockpick in P1/P3

    public static List<ShopEntry> gluttonyShop() { return empty(); } // dynamic in P1

    public static List<ShopEntry> lustShop() { return empty(); }
}
```

- [ ] **Step 2: Minimal CustomWinnerRole subclasses**

Each file same shape (example pride):

```java
package com.habitrain.core.game.sre.role.sins.win;

import io.wifi.starrailexpress.api.CustomWinnerRole;
import io.wifi.starrailexpress.game.GameUtils.WinStatus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class PrideRole extends CustomWinnerRole {
    public PrideRole(ResourceLocation id, int color, boolean isInnocent, boolean canUseKiller,
                     MoodType moodType, int maxSprintTime, boolean canSeeTime) {
        super(id, color, isInnocent, canUseKiller, moodType, maxSprintTime, canSeeTime);
    }

    @Override
    public WinStatus checkWin(ServerPlayer player, WinStatus winStatus) {
        // P0: no-op; SinVictoryHooks in Task 4
        return WinStatus.NOT_MODIFY;
    }

    @Override
    public boolean didPlayerWin(ServerPlayer player, boolean original, WinStatus winStatus) {
        return original;
    }
}
```

Mirror for `GreedRole`, `LustRole`, `SlothRole`.

- [ ] **Step 3: Implement `SevenSins.registerRoles()`**

Pattern (envy = killer NormalRole; pride = CustomWinnerRole neutral):

```java
// Pride: independent neutral, instinct, no time
PRIDE = TMMRoles.registerRole(new PrideRole(
        PRIDE_ID, new Color(180, 40, 40).getRGB(),
        false, false, SRERole.MoodType.REAL,
        TMMRoles.CIVILIAN.getMaxSprintTime(), false
) {
    @Override public List<ShopEntry> getShopEntries() { return SevenSinShops.empty(); }
}.setNeutrals(true).setCanSeeCoin(true).setDefaultMax(1)
 // setCanUseInstinct if API available on role — match design "可以透视"
);

// Envy: killer
ENVY = TMMRoles.registerRole(new NormalRole(
        ENVY_ID, new Color(40, 160, 60).getRGB(),
        false, true, SRERole.MoodType.FAKE, Integer.MAX_VALUE, false
) {
    @Override public List<ShopEntry> getShopEntries() { return SevenSinShops.envyShop(); }
}.setCanSeeCoin(true).setDefaultMax(1));

// Wrath: neutral for killer, see time, no instinct
WRATH = TMMRoles.registerRole(new NormalRole(
        WRATH_ID, new Color(200, 30, 30).getRGB(),
        false, false, SRERole.MoodType.FAKE,
        Integer.MAX_VALUE, true
).setNeutrals(true).setNeutralForKiller(true).setCanSeeCoin(true).setDefaultMax(1));

// Greed / Lust / Sloth: CustomWinnerRole + setNeutrals(true) + shops
// Gluttony: innocent civilian NormalRole
```

After all seven registered:

```java
private static void wireOpposingClique() {
    SRERole[] sins = {PRIDE, ENVY, WRATH, GREED, GLUTTONY, LUST, SLOTH};
    for (int i = 0; i < sins.length; i++) {
        for (int j = i + 1; j < sins.length; j++) {
            sins[i].addTwoWayOpposingRole(sins[j]);
        }
    }
}
```

`init()`: `registerRoles(); wireOpposingClique();` (skills later tasks).

- [ ] **Step 4: Wire from `HabiRoles.init()`**

After existing `registerSkills()` / shops / events:

```java
com.habitrain.core.game.sre.role.sins.SevenSins.init();
```

Log: registered 7 sins.

- [ ] **Step 5: Lang stubs (zh + en)**

For each path `sin_pride` … `sin_sloth`:

- `announcement.star.role.<path>`
- `announcement.star.goals.<path>`
- `info.screen.roleid.<path>`
- `info.screen.roleid.<path>.simple`

Use Mike’s intro text from the design message (Chinese in zh_cn; short English in en_us).

- [ ] **Step 6: Build**

Run: `./gradlew clean build`  
Expected: BUILD SUCCESSFUL  
Copy jar to `D:\Backup\mc mod\临时\` if not auto-copied.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/habitrain/core/game/sre/role/sins \
        src/main/java/com/habitrain/core/game/sre/role/HabiRoles.java \
        src/main/resources/assets/habitrain_core/lang
git commit -m "feat(sins): register seven deadly sin roles with opposing clique"
```

---

### Task 3: P0 — Mutex confirm hook + lust eligibility gate

**Files:**
- Create: `sins/SevenSinsMutex.java`
- Modify: `SevenSins.init()` to call `SevenSinsMutex.init()`

**Interfaces:**
- Consumes: `SevenSins.allIds()`, role fields
- Produces: `OnGamePlayerRolesConfirm` listener that mutates map to ≤1 sin; demotes lust if lovers unavailable

- [ ] **Step 1: Implement mutex**

```java
package com.habitrain.core.game.sre.role.sins;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.api.TMMRoles;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.agmas.harpymodloader.events.OnGamePlayerRolesConfirm;
import org.agmas.harpymodloader.SREDisableManager;
// lovers enable check via SEModifiers.LOVERS + WorldModifierComponent enable flags if available

import java.util.*;

public final class SevenSinsMutex {
    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;
        OnGamePlayerRolesConfirm.EVENT.register(SevenSinsMutex::beforeAssign);
    }

    static void beforeAssign(ServerLevel level, Map<Player, SRERole> map) {
        if (map == null || map.isEmpty()) return;

        List<Map.Entry<Player, SRERole>> sins = new ArrayList<>();
        for (var e : map.entrySet()) {
            if (SevenSins.isSin(e.getValue())) sins.add(e);
        }
        if (sins.size() > 1) {
            sins.sort(Comparator.comparing(a -> a.getKey().getUUID()));
            for (int i = 1; i < sins.size(); i++) {
                Player p = sins.get(i).getKey();
                SRERole removed = sins.get(i).getValue();
                map.put(p, fallbackNonSin(removed));
            }
        }

        // lust eligibility
        for (var e : new ArrayList<>(map.entrySet())) {
            if (e.getValue() != null && SevenSins.LUST_ID.equals(e.getValue().getIdentifier())) {
                if (!lustEligible(level, map)) {
                    map.put(e.getKey(), fallbackNonSin(e.getValue()));
                }
            }
        }
    }

    static boolean lustEligible(ServerLevel level, Map<Player, SRERole> map) {
        if (map.size() < 2) return false;
        try {
            // Prefer: lovers modifier not disabled globally
            var lovers = pro.fazeclan.river.stupid_express.constants.SEModifiers.LOVERS;
            if (lovers == null) return false;
            return !SREDisableManager.isModifierDisabled(lovers); // if API name differs, use isRoleDisabled-style check available on modifiers
        } catch (Throwable t) {
            return map.size() >= 2; // fail-open only on API mismatch; log warning
        }
    }

    static SRERole fallbackNonSin(SRERole removed) {
        int type = removed.getRoleType(); // 1 civilian, 2 neutrals, 3 n-for-killer, 4 killer, 5 vigilante
        if (type == 4) return TMMRoles.MURDERER != null ? TMMRoles.MURDERER : TMMRoles.CIVILIAN;
        if (type == 2 || type == 3) {
            // neutral fallback: civilian is safer than leaving empty
            return TMMRoles.CIVILIAN;
        }
        return TMMRoles.CIVILIAN;
    }
}
```

**Note for implementer:** Resolve exact `SEModifiers.LOVERS` disable API and `TMMRoles.MURDERER` field names against the JAR/source at coding time (`javap` or DLC tree). If murderer field differs, use any default killer role from `TMMRoles`.

- [ ] **Step 2: Build + commit**

```bash
./gradlew clean build
git add src/main/java/com/habitrain/core/game/sre/role/sins/SevenSinsMutex.java \
        src/main/java/com/habitrain/core/game/sre/role/sins/SevenSins.java
git commit -m "feat(sins): enforce one-of-seven mutex at role confirm"
```

---

### Task 4: P0 — Blackout faction tags + SinVictoryHooks shell

**Files:**
- Modify: `BlackoutRoleManager.java` — extend `Faction`, change `syncFactionsFromSreRoles`
- Create: `sins/win/SinVictoryHooks.java`
- Modify: `BlackoutVictoryChecker.java` — call hooks (shell: no win yet, but skip counting)
- Modify: `HabiTrainCore` or `SevenSins.init` to `SinVictoryHooks.init()`

**Interfaces:**
- Produces: `Faction.SIN_INDEPENDENT`, `Faction.SIN_KILLER_SHARE`
- Produces: `SinVictoryHooks.init()`, `countAliveExcludingIndependent(level)`, `isPrideBlockingFactionEnd(level)`
- `getRemainingGood/Bad` must **not** count `SIN_*`

- [ ] **Step 1: Extend Faction**

```java
public enum Faction {
    GOOD,
    BAD,
    /** Independent sins: pride/greed/lust/sloth — alive but not in good/bad counts. */
    SIN_INDEPENDENT,
    /** Wrath — not in counts; shares personal win with killers. */
    SIN_KILLER_SHARE
}
```

Audit call sites that switch on `Faction` or compare `== GOOD/BAD` (sanity death, restore power, populateRoundEndData). **Default:** treat only GOOD/BAD as faction-aligned; `SIN_*` are neither.

Critical fix in `getFaction` default: do **not** default missing to GOOD for unknown — keep current default only for legacy; after sync every assigned player has explicit faction.

- [ ] **Step 2: Rewrite sync mapping**

```java
Faction faction;
if (SevenSins.isIndependentSin(sreRole)) {
    faction = Faction.SIN_INDEPENDENT;
} else if (SevenSins.isKillerShareSin(sreRole)) {
    faction = Faction.SIN_KILLER_SHARE;
} else {
    faction = sreRole.canUseKiller() ? Faction.BAD : Faction.GOOD;
}
```

Also update `reassignRole` / `setSheriff` paths if they assume binary factions.

- [ ] **Step 3: `SinVictoryHooks` shell**

```java
public final class SinVictoryHooks {
    private static boolean registered;

    public static void init() {
        if (registered) return;
        registered = true;
        // SRE murder only:
        io.wifi.starrailexpress.event.AllowGameEnd.EVENT.register(
                (world, winStatus, isLooseEnds) -> onAllowGameEnd(world, winStatus, isLooseEnds));
    }

    static GameUtils.WinStatus onAllowGameEnd(ServerLevel world, GameUtils.WinStatus proposed, boolean loose) {
        // P0: if pride alive and other non-pride alive and proposed is KILLERS/PASSENGERS → NONE
        // Full logic Task 7; return NOT_MODIFY for now after logging
        return GameUtils.WinStatus.NOT_MODIFY;
    }

    public static boolean isPrideBlocking(ServerLevel level) { return false; /* Task 7 */ }

    public static void triggerCustomSinWin(ServerLevel level, SRERole role, ServerPlayer winner) {
        // RoleUtils.customWinnerWin(level, role.identifier().getPath(), role.color());
    }
}
```

- [ ] **Step 4: Adjust Blackout victory count usage**

In `checkVictory`, before good/bad wipe checks:

```java
if (SinVictoryHooks.isPrideBlocking(level)) {
    // skip good==0 / bad==0 early end; still allow timer later if desired
}
```

P0: method returns false; structure only.

- [ ] **Step 5: `populateRoundEndData` personal wins**

When `winner == BAD`, set `didWin=true` also for `Faction.SIN_KILLER_SHARE` history players who were wrath.

When custom sin win (later tasks), write `WinStatus.CUSTOM` + CustomWinnerID if roundEnd API allows; else use NO_PLAYER + message (document chosen approach in code comment after probing `SREGameRoundEndComponent`).

- [ ] **Step 6: Build + commit**

```bash
./gradlew clean build
git commit -m "feat(sins): blackout sin faction tags and victory hook shell"
```

**Manual check:** force blackout with envy → BAD; force pride → not in good count; good wipe with pride still alive should not immediately end once Task 7 fills `isPrideBlocking` — for P0 only verify counts via log.

---

### Task 5: P0 — CCA keys for all seven components (empty state machines)

**Files:**
- Create seven `component/*Component.java` under `sins/component/` implementing `RoleComponent` (+ `ServerTickingComponent` where needed)
- Modify: `HabiComponents.java`
- Modify: `fabric.mod.json` cardinal-components list
- Modify: role registration to `.setComponentKey(...)` **before** register (Task 2 may need amend)

**Interfaces:**
- Each KEY: `ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_pride"), PrideComponent.class)` etc.
- Methods: `init()`, `clear()`, NBT read/write empty

- [ ] **Step 1: Component template** (pride example)

```java
public final class PrideComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<PrideComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_pride"), PrideComponent.class);

    private final Player player;
    private long breakImmuneUntilGameTime;
    private ResourceLocation copiedShopRoleId;
    // shop snapshot fields filled P1

    public PrideComponent(Player player) { this.player = player; }

    @Override public Player getPlayer() { return player; }
    public void init() { clear(); }
    public void clear() {
        breakImmuneUntilGameTime = 0;
        copiedShopRoleId = null;
    }
    @Override public void serverTick() { /* P1 */ }
    // writeToNbt / readFromNbt / writeSync / readSync as required by RoleComponent
}
```

Minimal empty versions for Envy/Wrath/Greed/Gluttony/Lust/Sloth.

- [ ] **Step 2: Register in HabiComponents**

```java
registry.registerForPlayers(PrideComponent.KEY, PrideComponent::new, RespawnCopyStrategy.NEVER_COPY);
// ... ×7
// clearAll: try clear each
```

- [ ] **Step 3: fabric.mod.json**

Add:

```
"habitrain_core:sin_pride",
"habitrain_core:sin_envy",
"habitrain_core:sin_wrath",
"habitrain_core:sin_greed",
"habitrain_core:sin_gluttony",
"habitrain_core:sin_lust",
"habitrain_core:sin_sloth"
```

- [ ] **Step 4: Attach keys on roles** in `SevenSins.registerRoles` using `.setComponentKey(X.KEY)` on the role instance **before** `registerRole` returns into map (same pattern as `MimeKiller`).

- [ ] **Step 5: `SevenSinEvents.init`** — `ModdedRoleAssigned` → `comp.init()` for each sin id.

- [ ] **Step 6: Build + commit**

```bash
./gradlew clean build
git commit -m "feat(sins): register seven sin CCA components"
```

---

### Task 6: P0 — Virtue modifier scaffold + generous link

**Files:**
- Create: `modifier/HabiModifiers.java`, `modifier/VirtueGroup.java`
- Modify: `HabiTrainCore.onInitialize` after `HabiRoles.init()`:
  ```java
  com.habitrain.core.game.sre.modifier.HabiModifiers.init();
  ```
- Lang for six virtues

**Interfaces:**
- Produces: `HabiModifiers.HUMILITY`, `MERCY`, `PATIENCE`, `DILIGENCE`, `TEMPERANCE`, `CHASTITY`
- Produces: `HabiModifiers.GENEROUS` = reference to `TraitorAndModifiers.GENEROUS` (not re-registered)
- Produces: `VirtueGroup.contains(SREModifier)`, `VirtueGroup.areExclusive(a,b)`

- [ ] **Step 1: Register six modifiers**

```java
HUMILITY = HMLModifiers.registerModifier(new SREModifier(
        HabiTrainCore.id("virtue_humility"), 0xC0C0C0,
        new HashSet<>(), new HashSet<>(), false, false)).setDefaultMax(3);
// mercy: civilianOnly=true
MERCY = HMLModifiers.registerModifier(new SREModifier(
        HabiTrainCore.id("virtue_mercy"), 0x88CCFF,
        new HashSet<>(), new HashSet<>(), false, true)).setDefaultMax(2);
// patience / diligence / temperance / chastity similarly
```

```java
try {
    GENEROUS = org.agmas.noellesroles.role.TraitorAndModifiers.GENEROUS;
} catch (Throwable t) {
    GENEROUS = null;
    HabiTrainCore.LOGGER.warn("upstream generous missing");
}
```

- [ ] **Step 2: VirtueGroup**

```java
public final class VirtueGroup {
    public static Set<SREModifier> all() {
        Set<SREModifier> s = new LinkedHashSet<>();
        s.add(HabiModifiers.HUMILITY);
        // ... all six + GENEROUS if non-null
        return s;
    }

    public static boolean isVirtue(SREModifier m) {
        return m != null && all().contains(m);
    }

    public static boolean isHardExclusivePair(SREModifier a, SREModifier b) {
        return (a == HabiModifiers.PATIENCE && b == HabiModifiers.DILIGENCE)
                || (a == HabiModifiers.DILIGENCE && b == HabiModifiers.PATIENCE);
    }
}
```

- [ ] **Step 3: Assign mutex (best-effort)**

On `ModifierAssigned`:

```java
ModifierAssigned.EVENT.register((player, mod) -> {
    if (!VirtueGroup.isVirtue(mod)) return;
    WorldModifierComponent wmc = WorldModifierComponent.getInstance(player);
    for (SREModifier other : new HashSet<>(wmc.getModifiers(player))) {
        if (other == mod || !VirtueGroup.isVirtue(other)) continue;
        if (VirtueGroup.isHardExclusivePair(mod, other) || true /* one virtue rule */) {
            wmc.removeModifier(player, other);
        }
    }
});
```

One-virtue rule: remove other virtues when a new virtue is assigned (including generous).

- [ ] **Step 4: Lang + build + commit**

```bash
./gradlew clean build
git commit -m "feat(virtues): scaffold seven virtues modifiers and group mutex"
```

---

### Task 7: P1 — Pride full mechanics + dual-mode win/block

**Files:**
- Modify: `PrideComponent`, `PrideRole`, `SevenSinEvents`, `SinVictoryHooks`, `BlackoutVictoryChecker`
- Skill register in `SevenSins.registerSkills`

**Behavior checklist (from spec):**
1. G skill 60s: raytrace player → snapshot `targetRole.getShopEntries()` base prices into component; pride shop reads snapshot
2. Tick: count other alive players within 8 blocks ≥3 → glowing + `weaponImmune`
3. On pride kill: set break 5s
4. `AllowPlayerDeathWithKiller`: if immune && conventional weapon && !force → false + heal
5. SRE `AllowGameEnd`: if pride alive and any other alive and proposed KILLERS/PASSENGERS → `NONE`
6. If only pride alive → `CUSTOM` + `customWinnerWin`
7. Blackout: same via checker order before faction wipe

- [ ] **Step 1: Implement component fields + `useCopyShop(RoleSkillContext)`**
- [ ] **Step 2: Override pride `getShopEntries` to return snapshot or empty**
- [ ] **Step 3: Death + kill hooks in `SevenSinEvents`**
- [ ] **Step 4: Fill `SinVictoryHooks` pride block/win for SRE**
- [ ] **Step 5: Blackout `checkVictory` pride last-survivor + block**
- [ ] **Step 6: Build + manual checklist + commit**

```bash
./gradlew clean build
git commit -m "feat(sins): implement pride copy-shop, aura immune, and solo win"
```

**Manual:** force pride; 3 players nearby immune to knife; kill someone → 5s vulnerable; eliminate all others → pride win message; with others alive, killing all good should not end if pride still alive.

---

### Task 8: P1 — Envy mark + balance-gated kill + loot

**Files:**
- `EnvyComponent`, `SevenSinShops.envyShop()`, skills, death hooks

**Shop (resolve item refs from TMMItems / noelles at implement time):**
- knife 200 (`KillerKnifeShopEntry` if required)
- gun 300
- psycho 500
- lockpick 150
- blackout 150

**Logic:**
- G 90s mark UUID
- `AllowPlayerDeathWithKiller`: if victim is mark && envyBalance > targetBalance → cancel (return false)
- `OnPlayerDeathWithKiller`: if envy killed mark → steal random transferable item else up to 100 coins

Item exclusion helper:

```java
static boolean isTransferable(ItemStack stack) {
    if (stack == null || stack.isEmpty()) return false;
    // reject keys, task-tagged, soulbound OWNER mismatch, empty
    return true; // implement real filters using existing tags/components
}
```

- [ ] Implement → build → commit `feat(sins): implement envy mark kill gate and loot`

---

### Task 9: P1 — Gluttony eat buffs + edible shop + debuff scrub

**Files:**
- `GluttonyComponent`, `SevenSinShops.gluttonyShop()`, `GluttonyEatMixin`, events

**Shop:** scan `BuiltInRegistries.ITEM` for food; price 5; milk 300; honey 100.

**Eat mixin:** inject after successful food consume; only if role is gluttony; roll effect from whitelist; stack amplifier; max → permanent flag in component; reapply permanent each tick.

**Debuff:** serverTick remove “ordinary” negative effects via allowlist of MobEffects to clear; never clear betel/mod custom effects (maintain deny list of effect ids).

- [ ] Implement → build → commit `feat(sins): implement gluttony food buffs and shop`

---

### Task 10: P4 — Virtue effects (humility, mercy, patience, diligence, temperance, chastity)

**Files:**
- virtue/*.java, mixins, task complete hook in `TaskManager` or existing complete callback

| Virtue | Implementation note |
|--------|---------------------|
| Humility | On task complete → nearby players actionbar `谢谢` within 12 blocks |
| Mercy | `AllowPlayerDeathWithKiller`: victim has mercy, killer is GOOD/innocent → cancel once, `removeModifier` |
| Patience | Multiply interact progress need ×1.5 or rate ×2/3 for interactive tasks only |
| Diligence | Interact time ×0.7 |
| Temperance | Per-player map itemId→lastPrice; on buy quote max(base*0.5, last*0.9) |
| Chastity | Clear vanilla poison; cancel poison death reasons; deny list for betel etc. |

**Patience/diligence:** Prefer extending existing `SREPlayerTaskComponentMixin` with virtue gates rather than a second mixin on the same method if ordinal conflicts appear.

- [ ] Implement each virtue → build after batch → commit  
  `feat(virtues): implement humility mercy task-time temperance chastity effects`

---

### Task 11: P2 — Wrath stage machine + filters

**Files:**
- `WrathComponent`, events, optional client stage payload, fake weapons in `getDefaultItems`

**State:** `stage` 0–5+, `speedStacks`, `enteredFrenzy`, `killsAfterFrenzy`.

**On lethal hit from GOOD attacker + conventional weapon:**
- if stage < 5: cancel death, apply stage effect, stage++
- if stage >= 5 and speedStacks < 5: cancel, speedStacks++
- else: allow death

**Stage effects:** root 3s; give bat + flag red filter; B&W flag; darkness+blind; nausea+frenzy flag. Reapply from component each tick so milk fails.

**On wrath kill:** stage = max(threshold, stage-1); if frenzy, killsAfterFrenzy++; at 5 → `GameUtils.killPlayer(wrath, true, null, wrath_exhaustion)`.

**Client filter:** reuse ImmersiveFilterShader / potion visuals; document approximation if B&W shader missing.

- [ ] Implement → build → commit `feat(sins): implement wrath rage stages and frenzy`

---

### Task 12: P2 — Sloth sleep, shield, berserk, input/voice lock, win hijack

**Files:**
- `SlothComponent`, movement/interact cancel events, `MicrophonePacketEvent` listener, Blackout+SRE win hijack

**Safe time end:** detect via `SREGameWorldComponent` safe-time flag / game tick; enter sleep; shields = `ceil(alive/2)` using `SREArmorPlayerComponent.addArmor` or internal counter.

**While sleeping:** cancel movement (server set delta 0), block use/attack/container/chat; cancel mic packets for uuid.

**Shield damage:** on conventional hit, decrement shield, record attacker; at 0 → wake 10s berserk, can only damage attackers set.

**Once-per-game skill:** if sleeping && shield>=1 → consume shields, explode (kill others in radius like SuperLooseEnd, self safe), 30s open berserk; each 2 kills +1 shield; on end re-sleep with max(1, shields), clear attackers.

**Win:** if faction win would fire and sloth alive → sloth custom win (SRE AllowGameEnd CUSTOM; Blackout endGame hijack).

- [ ] Implement → build → commit `feat(sins): implement sloth sleep shield berserk and win hijack`

---

### Task 13: P2 — Lust charge, desire mark, lovers-win hijack

**Files:**
- `LustComponent`, client highlight (OnGetInstinctHighlight or payload), `SinVictoryHooks` lovers branch

**Phase 1 skill (hold/tick while active):** if both true lovers (read `LoversComponent` / LOVERS modifier holders) within 8 blocks of lust and line-of-sight, add charge; pause when split without reset; 30s → phase 2.

**Phase 2 skill once:** mark all other alive with desire flag (component set on each player or world map); visual only + win id; **do not** call bindLovers / add LOVERS modifier.

**Win hijack:** on `AllowGameEnd` when proposed LOVERS (or custom lovers path) and lust alive → CUSTOM lust win / replace CustomWinnerPlayers. Blackout: call same helper if lovers win ever surfaces.

- [ ] Implement → build → commit `feat(sins): implement lust observe charge and lovers win steal`

---

### Task 14: P3 — Greed pouch bind, collection win, drop/steal death

**Files:**
- `GreedPouchItem`, data component owner UUID, `GreedComponent` targetCount = ceil(startPlayers * 2.5)
- Mixins: drop, inventory click transfer, death drops
- Win when unique item ids in pouch ≥ target

**Rules:**
- Cannot intentional drop/trade away; if owner loses pouch → forceDeath greed
- Pouch only counts item **type** ids

- [ ] Implement → build → commit `feat(sins): implement greed bound pouch and collection win`

---

### Task 15: P3 — Greed anonymous trade UI + deal tracker

**Files:**
- `GreedDealTracker` (per-level map itemId → count 0..3)
- `GreedTradeManager` dual confirm sessions
- Network C2S/S2C open/offer/confirm/cancel
- Client screen minimal list + confirm

**Pricing:**
- sell price = 30 + 30 * n
- buy price = max(30, 300 - 30 * n)
- n = global deals for that itemId this round, cap 3

**On commit:** revalidate stacks, coins, pouch still owned by greed; then transfer atomically; increment n.

- [ ] Implement → build → commit `feat(sins): implement greed anonymous trade and global price tiers`

---

### Task 16: Integration pass + lang polish + dual-mode checklist

**Files:** lang, log messages, any missed `clearAll` on game end

- [ ] **Step 1: Full build**

```bash
./gradlew clean build
```

Expected: SUCCESS; jar in `D:\Backup\mc mod\临时\`

- [ ] **Step 2: Manual matrix** (from spec §9)

- Mutex ≤1 sin; lust without lovers demoted  
- Pride / Envy / Gluttony / Wrath / Sloth / Lust / Greed happy paths  
- Virtues via `/forcemodifier`  
- Blackout: independent not in good/bad; wrath shares killer personal win; pride blocks wipe  

- [ ] **Step 3: Final commit if polish**

```bash
git commit -m "chore(sins): polish lang and dual-mode victory integration"
```

---

## Spec coverage (self-review)

| Spec section | Tasks |
|--------------|-------|
| Package + registration | 1–2, 5 |
| Mutex + lust gate | 3 |
| Blackout counts + dual win shell | 4, 7, 12–13 |
| Pride / Envy / Gluttony | 7–9 |
| Wrath / Sloth / Lust | 11–13 |
| Greed pouch + trade | 14–15 |
| Virtues scaffold + effects | 6, 10 |
| Phased delivery order | Task numbers follow P0→P1→P4→P2→P3 |
| Build/copy rule | every task |

## Placeholder / consistency notes for implementers

- Resolve exact field names for default killer role, modifier-disable API, instinct setter, and `SREGameRoundEndComponent` custom winner fields against JAR at coding time — do not invent alternate win pipelines.
- `KillerKnifeShopEntry` vs `ShopEntry` for knives: follow `HabiRoleShops` existing pattern.
- Blackout never uses SRE `AllowGameEnd` for end decisions; always dual-write logic in `SinVictoryHooks` helpers callable from both.
- Temperance price order: base → temperance → DynamicShop flat/mult.
- One virtue per player enforced on assign; patience/diligence also hard exclusive pair.
