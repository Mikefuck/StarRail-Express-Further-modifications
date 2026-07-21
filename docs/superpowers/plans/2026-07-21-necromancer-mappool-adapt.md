# Necromancer Mike Credit + Per-Round Map Pool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When Mike converts a killer to non-killer while a living necromancer is present, grant +1 necromancer revive; replace daily map-pool calendar rotation with per-round (use current pool, then advance), default 6 pools, cross-pool map membership, and balanced repartition (4 maps/pool).

**Architecture:** (1) Small pure helper `NecromancerReviveSupport` called only from `MikeCodeEditSkill` after successful role change — reflects into `NecromancerComponent` already present in `star_rail_express` jar. (2) `MapPoolRotationService.repartition` rewritten for balanced multi-membership; calendar tick becomes no-op; `ModeMapVoteOrchestrator` advances after `resolveEffectiveMaps` (B1). Defaults: 6 pools seed, 4 maps per pool.

**Tech Stack:** Java 21, Fabric 1.21.1, existing `habitrain_core` config/vote stack, `star_rail_express` jar (contains `NecromancerComponent`, `SERoles`, `BounsRoles`).

## Global Constraints

- Address user as Mike; only touch files under `D:\Backup\mc mod\` excluding `backup\`.
- After any code modification: `./gradlew clean build` and copy jar to `D:\Backup\mc mod\临时\`.
- Do not modify DLC sources; soft-fail if necromancer component missing.
- Do not force-migrate existing 5-pool configs to 6.
- Spec: `docs/superpowers/specs/2026-07-21-necromancer-mappool-adapt-design.md`.

## File map

| File | Responsibility |
|---|---|
| `src/main/java/com/habitrain/core/game/sre/role/NecromancerReviveSupport.java` | **Create** — detect living necromancer; +1 revive via component |
| `src/main/java/com/habitrain/core/game/sre/role/skill/MikeCodeEditSkill.java` | Call support after successful convert |
| `src/main/java/com/habitrain/core/config/MapPoolRotationSettings.java` | `DEFAULT_POOL_COUNT = 6` |
| `src/main/java/com/habitrain/core/vote/MapPoolRotationService.java` | Balanced repartition; calendar no-op; `MAPS_PER_POOL` |
| `src/main/java/com/habitrain/core/vote/ModeMapVoteOrchestrator.java` | After resolve, advance + save |
| `src/main/java/com/habitrain/core/client/gui/config/VoteTabScreen.java` | Per-round summary copy |
| `src/main/java/com/habitrain/core/client/gui/config/MapPoolEditorScreen.java` | Repartition button label/toast |

**Note:** No unit-test module in this project. Verify with `./gradlew clean build` + manual playtest checklist from spec.

---

### Task 1: NecromancerReviveSupport + Mike hook

**Files:**
- Create: `src/main/java/com/habitrain/core/game/sre/role/NecromancerReviveSupport.java`
- Modify: `src/main/java/com/habitrain/core/game/sre/role/skill/MikeCodeEditSkill.java` (after successful `changeRole` / `reassignRole`, before success messages)

**Interfaces:**
- Produces: `public static boolean onKillerConvertedAway(ServerLevel level, SRERole oldRole, SRERole nextRole)` — returns true if +1 applied
- Consumes: `SREGameWorldComponent`, player list, optional jar types via direct import if compileable else reflection

- [ ] **Step 1: Create `NecromancerReviveSupport`**

```java
package com.habitrain.core.game.sre.role;

import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.game.GameUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Mike 将杀手转为非杀手时，若场上有存活死灵，给世界 NecromancerComponent +1 可用复活。
 */
public final class NecromancerReviveSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("NecromancerReviveSupport");

    /** stupid_express 死灵 */
    private static final ResourceLocation NECROMANCER_ID =
            ResourceLocation.fromNamespaceAndPath("stupid_express", "necromancer");
    /** noelles 猫死灵（SRE.wifiId 命名空间以运行时 id 为准；常见 wifi / starrailexpress） */
    private static final ResourceLocation CAT_NECROMANCER_WIFI =
            ResourceLocation.fromNamespaceAndPath("wifi", "cat_necromancer");
    private static final ResourceLocation CAT_NECROMANCER_SRE =
            ResourceLocation.fromNamespaceAndPath("starrailexpress", "cat_necromancer");

    private static final Set<ResourceLocation> NECRO_IDS = Set.of(
            NECROMANCER_ID, CAT_NECROMANCER_WIFI, CAT_NECROMANCER_SRE
    );

    private static volatile boolean componentUnavailableLogged;

    private NecromancerReviveSupport() {}

    /**
     * @return true if availableRevives was increased
     */
    public static boolean onKillerConvertedAway(ServerLevel level, SRERole oldRole, SRERole nextRole) {
        if (level == null || oldRole == null || nextRole == null) return false;
        if (!oldRole.canUseKiller()) return false;
        if (nextRole.canUseKiller()) return false;
        if (!hasLivingNecromancer(level)) return false;
        return increaseRevives(level);
    }

    static boolean hasLivingNecromancer(ServerLevel level) {
        SREGameWorldComponent game;
        try {
            game = SREGameWorldComponent.KEY.get(level);
        } catch (Throwable t) {
            return false;
        }
        if (game == null) return false;

        Set<ResourceLocation> extra = resolveNecroIdsFromClasspath();
        for (Player p : level.players()) {
            if (!(p instanceof ServerPlayer sp)) continue;
            if (!GameUtils.isPlayerAliveAndSurvival(sp)) continue;
            SRERole role;
            try {
                role = game.getRole(sp);
            } catch (Throwable t) {
                continue;
            }
            if (role == null || role.identifier() == null) continue;
            ResourceLocation id = role.identifier();
            if (NECRO_IDS.contains(id) || extra.contains(id)) {
                return true;
            }
            // path fallback if namespace drifts
            String path = id.getPath();
            if ("necromancer".equals(path) || "cat_necromancer".equals(path)) {
                return true;
            }
        }
        return false;
    }

    private static Set<ResourceLocation> resolveNecroIdsFromClasspath() {
        Set<ResourceLocation> out = new HashSet<>();
        addRoleIdField(out, "pro.fazeclan.river.stupid_express.constants.SERoles", "NECROMANCER");
        addRoleIdField(out, "org.agmas.noellesroles.role.BounsRoles", "CAT_NECROMANCER");
        return out;
    }

    private static void addRoleIdField(Set<ResourceLocation> out, String className, String field) {
        try {
            Class<?> c = Class.forName(className);
            Object role = c.getField(field).get(null);
            if (role instanceof SRERole sre && sre.identifier() != null) {
                out.add(sre.identifier());
            }
        } catch (Throwable ignored) {
            // optional DLC classes
        }
    }

    private static boolean increaseRevives(ServerLevel level) {
        try {
            Class<?> compClass = Class.forName(
                    "pro.fazeclan.river.stupid_express.role.necromancer.cca.NecromancerComponent");
            Object key = compClass.getField("KEY").get(null);
            Method get = key.getClass().getMethod("get", Object.class);
            Object component = get.invoke(key, level);
            if (component == null) return false;
            Method increase = compClass.getMethod("increaseAvailableRevives");
            increase.invoke(component);
            try {
                Method sync = compClass.getMethod("sync");
                sync.invoke(component);
            } catch (NoSuchMethodException ignored) {
                // older shape
            }
            int available = -1;
            try {
                Method getter = compClass.getMethod("getAvailableRevives");
                Object v = getter.invoke(component);
                if (v instanceof Integer i) available = i;
            } catch (Throwable ignored) {}
            LOGGER.info("[Necro] +1 revive after killer->non-killer; availableRevives={}", available);
            return true;
        } catch (Throwable t) {
            if (!componentUnavailableLogged) {
                componentUnavailableLogged = true;
                LOGGER.warn("[Necro] NecromancerComponent unavailable; skip revive credit", t);
            }
            return false;
        }
    }
}
```

- [ ] **Step 2: Hook Mike skill after reassign**

In `MikeCodeEditSkill.use`, immediately after the `if (target.level() instanceof ServerLevel level) { ... reassignRole ... }` block (after line ~111), add:

```java
        if (target.level() instanceof ServerLevel levelForNecro) {
            try {
                NecromancerReviveSupport.onKillerConvertedAway(levelForNecro, current, next);
            } catch (Throwable t) {
                HabiTrainCore.LOGGER.warn("[Mike] necromancer revive credit failed", t);
            }
        }
```

Add import:

```java
import com.habitrain.core.game.sre.role.NecromancerReviveSupport;
```

- [ ] **Step 3: Compile check (partial)**

Run from project root:

```powershell
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL (or only unrelated pre-existing errors).

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/habitrain/core/game/sre/role/NecromancerReviveSupport.java src/main/java/com/habitrain/core/game/sre/role/skill/MikeCodeEditSkill.java
git commit -m "feat(necro): grant revive when Mike converts killer away"
```

---

### Task 2: Map pool defaults + balanced repartition + calendar no-op

**Files:**
- Modify: `src/main/java/com/habitrain/core/config/MapPoolRotationSettings.java` (`DEFAULT_POOL_COUNT`)
- Modify: `src/main/java/com/habitrain/core/vote/MapPoolRotationService.java` (class javadoc, `MAPS_PER_POOL`, `repartition`, `onCalendarTick`)

**Interfaces:**
- Consumes: existing `globalEnabledMapIds`, `MapPoolEntry`, `poolCount()`
- Produces: same public methods; new behavior for `repartition` and `onCalendarTick`

- [ ] **Step 1: Change default pool count**

In `MapPoolRotationSettings.java`:

```java
public static final int DEFAULT_POOL_COUNT = 6;
```

Update class javadoc if it says "daily" / "5".

- [ ] **Step 2: Rewrite `MapPoolRotationService` core**

1. Update class javadoc to "per-round rotation; balanced multi-membership repartition".
2. Add constant:

```java
public static final int MAPS_PER_POOL = 4;
```

3. Replace `repartition` body with balanced algorithm:

```java
public static void repartition(ModeMapVoteSettings settings, Random random) {
    if (settings == null) return;
    MapPoolRotationSettings rot = settings.rotationOrDefault();
    int poolN = rot.poolCount();
    if (poolN <= 0) return;
    List<String> all = new ArrayList<>(globalEnabledMapIds(settings));
    Random rng = random != null ? random : new Random();

    if (all.isEmpty()) {
        for (int i = 0; i < poolN; i++) {
            MapPoolEntry pool = rot.poolAt(i);
            pool.mapIds = new ArrayList<>();
        }
        rot.poolsAdvancedSinceRepartition = 0;
        LOGGER.info("[MapPool] repartitioned empty maps into {} pools", poolN);
        return;
    }

    // Global occurrence counts for balance across pools.
    java.util.HashMap<String, Integer> count = new java.util.HashMap<>();
    for (String id : all) count.put(id, 0);

    int k = Math.min(MAPS_PER_POOL, all.size());
    for (int i = 0; i < poolN; i++) {
        MapPoolEntry pool = rot.poolAt(i);
        pool.mapIds = new ArrayList<>();
        Set<String> chosen = new LinkedHashSet<>();
        while (chosen.size() < k) {
            int best = Integer.MAX_VALUE;
            List<String> candidates = new ArrayList<>();
            for (String id : all) {
                if (chosen.contains(id)) continue;
                int c = count.getOrDefault(id, 0);
                if (c < best) {
                    best = c;
                    candidates.clear();
                    candidates.add(id);
                } else if (c == best) {
                    candidates.add(id);
                }
            }
            if (candidates.isEmpty()) break;
            String pick = candidates.get(rng.nextInt(candidates.size()));
            chosen.add(pick);
            pool.mapIds.add(pick);
            count.put(pick, count.getOrDefault(pick, 0) + 1);
        }
    }
    rot.poolsAdvancedSinceRepartition = 0;
    LOGGER.info("[MapPool] balanced repartition n={} pools={} mapsPerPool={}", all.size(), poolN, k);
}
```

Ensure imports include `LinkedHashSet`, `Set`, `HashMap` as needed (file already has several).

4. Replace `onCalendarTick` to **not** advance by date:

```java
public static void onCalendarTick(MinecraftServer server) {
    // Per-round advance only (ModeMapVoteOrchestrator). Calendar daily rotation removed 2026-07-21.
    // Intentionally empty — kept as hook for ModTickHandler compatibility.
}
```

Remove unused LocalDate usage from this method path if no longer referenced elsewhere in the class. If `todayString` / `DAY` only served calendar, leave `todayString` if used by UI/status, or keep method for compatibility.

5. Optionally update `statusLine` to drop heavy date emphasis (keep field dump OK for debug).

- [ ] **Step 3: Compile**

```powershell
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/habitrain/core/config/MapPoolRotationSettings.java src/main/java/com/habitrain/core/vote/MapPoolRotationService.java
git commit -m "feat(mappool): balanced 6-pool repartition; disable calendar advance"
```

---

### Task 3: Per-round advance in ModeMapVoteOrchestrator (B1)

**Files:**
- Modify: `src/main/java/com/habitrain/core/vote/ModeMapVoteOrchestrator.java` (`onModeResolved` map-pool block ~204–216)

**Interfaces:**
- Consumes: `MapPoolRotationService.resolveEffectiveMaps`, `advance`
- Produces: settings saved after advance for next round

- [ ] **Step 1: After resolve, advance + save**

Replace the block that currently does resolve + setSettings with:

```java
        Random rng = new Random(level.getRandom().nextLong());
        List<String> effectiveIds = candidateIds;
        MapPoolRotationSettings rot = settings.rotationOrDefault();
        if (MapPoolRotationService.shouldApply(settings, candidateIds.size())) {
            effectiveIds = MapPoolRotationService.resolveEffectiveMaps(settings, candidateIds, rng);
            if (effectiveIds.isEmpty()) {
                effectiveIds = candidateIds;
            }
            int usedPool = rot.activePoolIndex;
            LOGGER.info("[ModeMapVote] map pool applied mode={} poolIndex={} candidates={} effective={} applyMode={}",
                    winnerId, usedPool, candidateIds.size(), effectiveIds.size(), rot.applyMode);

            // B1: this round uses current pool; advance for the next round.
            boolean advanced = MapPoolRotationService.advance(settings, rng);
            ConfigManager.getInstance().setModeMapVoteSettings(settings);
            ConfigManager.getInstance().save();
            try {
                if (level.getServer() != null && !level.getServer().isSingleplayer()) {
                    com.habitrain.core.network.FullConfigSyncPayload.broadcastToAll(level.getServer());
                }
            } catch (Throwable t) {
                LOGGER.warn("[ModeMapVote] map pool config sync failed", t);
            }
            LOGGER.info("[ModeMapVote] map pool post-resolve advance={} nextIndex={}",
                    advanced, settings.rotationOrDefault().activePoolIndex);
        }
```

Prefer existing project import style: if `FullConfigSyncPayload` is already imported in this file, use short name; otherwise add:

```java
import com.habitrain.core.network.FullConfigSyncPayload;
```

**Important:** Capture `usedPool` before advance for the log line (resolve may also clamp `activePoolIndex`).

- [ ] **Step 2: Compile**

```powershell
./gradlew compileJava
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/habitrain/core/vote/ModeMapVoteOrchestrator.java
git commit -m "feat(mappool): advance pool after each mode-resolve (per-round)"
```

---

### Task 4: UI copy (Vote tab + pool editor)

**Files:**
- Modify: `src/main/java/com/habitrain/core/client/gui/config/VoteTabScreen.java` (~248–251)
- Modify: `src/main/java/com/habitrain/core/client/gui/config/MapPoolEditorScreen.java` (repartition button + toast)

- [ ] **Step 1: VoteTabScreen summary**

Replace summary construction:

```java
        String summary = "每局轮换 · 共" + rot.poolCount() + "池 · 当前池" + (rot.activePoolIndex + 1) + " · "
                + (curPool.displayName != null ? curPool.displayName : "")
                + " · " + poolCount + "图";
```

- [ ] **Step 2: MapPoolEditorScreen labels**

Button:

```java
repartitionBtn = Button.builder(Component.literal("§e重新均摊分池"), b -> {
```

Toast after repartition:

```java
toast("§a已均摊分池（每池最多4图，可跨池重复；请保存）");
```

- [ ] **Step 3: Compile**

```powershell
./gradlew compileJava
```

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/habitrain/core/client/gui/config/VoteTabScreen.java src/main/java/com/habitrain/core/client/gui/config/MapPoolEditorScreen.java
git commit -m "ui(mappool): per-round rotation labels and balanced repartition copy"
```

---

### Task 5: Full build + jar copy + verification checklist

**Files:** none new (verification only)

- [ ] **Step 1: Full build and copy**

From `D:\Backup\mc mod\哈比列车api`:

```powershell
./gradlew clean build
```

Expected: BUILD SUCCESSFUL. `assemble` / `copyReleaseJar` should place jar under `D:\Backup\mc mod\临时\`.

If copy task fails, manually:

```powershell
Copy-Item -Force build/libs/habitrain_core-*.jar "D:\Backup\mc mod\临时\"
```

(use actual archive name from `build/libs`).

- [ ] **Step 2: Manual checklist (document results in commit message or chat)**

1. 死灵+狼 → Mike 狼改非杀手 → revive +1 → 可点尸体复活  
2. 无死灵 → 次数不变  
3. 狼→狼 → 次数不变  
4. 连续两局模式投票 → 第二局池 index+1，不依赖跨日  
5. 编辑器「重新均摊分池」→ 每池 ≤4、可跨池重复  
6. 过 0 点不自动跳池  

- [ ] **Step 3: Final commit if any fixups**

Only if build/fixups needed; otherwise note verification in session.

---

## Spec coverage self-check

| Spec requirement | Task |
|---|---|
| Mike killer→non-killer +1 when living necro | Task 1 |
| Field-only Mike path; no DLC death change | Task 1 |
| Soft-fail missing component | Task 1 reflection |
| B1 resolve then advance | Task 3 |
| Calendar advance removed | Task 2 |
| DEFAULT_POOL_COUNT 6 empty seed only | Task 2 (`ensurePools` unchanged) |
| MAPS_PER_POOL 4 + cross-pool + balance | Task 2 repartition |
| autoRepartition still via advance | Task 2 (unchanged advance) + Task 3 |
| UI 每局轮换 | Task 4 |
| Build + 临时 jar | Task 5 |
| No force 5→6 migrate | Task 2 (no migration code) |

## Placeholder / consistency check

- Method name stable: `onKillerConvertedAway(ServerLevel, SRERole, SRERole)`
- `MAPS_PER_POOL = 4` matches `PAD_TARGET`
- Advance order B1 only in Task 3
- No TBD/TODO left in steps
