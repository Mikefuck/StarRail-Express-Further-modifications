# Lottery + Vote Order Implementation Plan

> **For agentic workers:** Implement task-by-task. Steps use checkbox syntax.

**Goal:** Ship map shuffle + mode order in habitrain_core, and coin/dupe/login calendar in habitrain_lottery.

**Architecture:** API minimal orchestrator/GUI changes; lottery extends world store + mixins + new block/BER without editing SRE jar.

**Tech Stack:** Fabric 1.21.1, Java 21, Mixin, existing OptionVote / PlayerLotteryStore patterns.

## Global Constraints

- File access only under `D:\Backup\mc mod\` except forbidden `backup\`.
- After each mod change: `./gradlew clean build` and copy JAR to `D:\Backup\mc mod\临时\`.
- Address user as Mike.

---

### Task 1: API map shuffle + mode order

**Files:**
- Modify: `哈比列车api/src/main/java/com/habitrain/core/vote/ModeMapVoteOrchestrator.java`
- Modify: `哈比列车api/src/main/java/com/habitrain/core/config/ModeMapVoteSettings.java`
- Modify: `哈比列车api/src/main/java/com/habitrain/core/client/gui/config/VoteTabScreen.java`

- [ ] Mode IDs ordered by settings.modes then registry
- [ ] Shuffle mapOptions before start
- [ ] VoteTabScreen mode ↑↓ rebuild LinkedHashMap
- [ ] Build + copy API jar

### Task 2: Lottery economy (dupe + coin2lottery)

**Files:**
- Modify RatesConfig, rates.json, LotteryGrantService, LotteryCommands, mixins, LotteryNetwork as needed

- [ ] coinPerDraw config
- [ ] rollOnce / addCoinNum multiplier mixin
- [ ] sre:loot coin2lottery command
- [ ] Build lottery jar (partial OK before calendar)

### Task 3: Login rewards + calendar block

- [ ] PlayerLotteryData fields + LoginRewardService
- [ ] LoginState S2C
- [ ] login_calendar block + BER multi-tile
- [ ] Build + copy lottery jar

### Task 4: Final verification

- [ ] Both jars in 临时\
