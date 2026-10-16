package com.habitrain.core.game.sre.role.sins.component;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.game.sre.modifier.HabiModifiers;
import com.habitrain.core.game.sre.role.HabiRoles;
import com.habitrain.core.game.sre.role.sins.SevenSins;
import io.wifi.starrailexpress.api.RoleComponent;
import io.wifi.starrailexpress.api.RoleSkill;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.cca.SREPlayerShopComponent;
import io.wifi.starrailexpress.game.GameConstants;
import io.wifi.starrailexpress.game.GameUtils;
import io.wifi.starrailexpress.index.TMMEntities;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.agmas.harpymodloader.component.WorldModifierComponent;
import org.agmas.noellesroles.init.ModEffects;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.util.*;

/** Owner-only role state and the individually authorized highlights of every player. */
public final class LustComponent implements RoleComponent, ServerTickingComponent {
    public static final ComponentKey<LustComponent> KEY =
            ComponentRegistry.getOrCreate(HabiTrainCore.id("sin_lust"), LustComponent.class);
    public record CharmEntry(UUID id, String name, boolean alive, boolean online) {}

    private final Player player;
    private boolean initialized;
    private int initialPlayerCount;
    private UUID selected;
    private long selectionReadyAt;
    private UUID revivalToken;
    private long selectedDiedAt;
    private Vec3 deathPosition;
    private long nauseaAt;
    private List<CharmEntry> roster = List.of();
    private final Set<UUID> highlights = new HashSet<>();

    public LustComponent(Player player) { this.player = player; }
    @Override public Player getPlayer() { return player; }
    public boolean isInitialized() { return initialized; }
    public UUID selected() { return selected; }
    public UUID revivalToken() { return revivalToken; }
    public List<CharmEntry> roster() { return roster; }
    public boolean highlights(UUID id) { return highlights.contains(id); }
    public long selectionTicksLeft() { return Math.max(0, selectionReadyAt - player.level().getGameTime()); }
    public long revivalTicksLeft() {
        return revivalToken == null ? 0 : Math.max(0,
                selectedDiedAt + LustPolicy.REVIVAL_WINDOW_TICKS - player.level().getGameTime());
    }

    @Override public void init() { clear(); }
    @Override public void clear() {
        initialized = false;
        initialPlayerCount = 0;
        selected = null;
        selectionReadyAt = 0;
        clearRevival();
        nauseaAt = 0;
        roster = List.of();
        highlights.clear();
        if (!player.level().isClientSide()) KEY.sync(player);
    }

    /** The upstream room map is fixed at opening, before anybody can move rooms. */
    public void startRound() {
        if (initialized || !(player instanceof ServerPlayer self)
                || !HabiRoles.isHabiRole(self, SevenSins.LUST)) return;
        var game = SREGameWorldComponent.KEY.get(self.level());
        initialPlayerCount = game.getStartingPlayerCount();
        if (initialPlayerCount <= 0) initialPlayerCount = game.getRoles().size();
        initialized = true;
        Integer room = GameUtils.roomToPlayer.get(self.getUUID());
        if (room != null) {
            var modifiers = WorldModifierComponent.getInstance(self);
            for (ServerPlayer other : self.serverLevel().players()) {
                if (other != self && isAliveParticipant(other)
                        && room.equals(GameUtils.roomToPlayer.get(other.getUUID()))) {
                    modifiers.addModifier(other.getUUID(), HabiModifiers.CHARMED, false);
                }
            }
            modifiers.sync();
        }
        refreshViews(self.serverLevel());
        KEY.sync(self);
    }

    public static boolean isAliveParticipant(Player player) {
        return player != null && !player.isRemoved()
                && SREGameWorldComponent.KEY.get(player.level()).getRole(player) != null
                && GameUtils.isPlayerAliveAndSurvival(player);
    }

    public static boolean isCharmed(Player player) {
        return player != null && HabiModifiers.CHARMED != null
                && WorldModifierComponent.getInstance(player).isModifier(player, HabiModifiers.CHARMED);
    }

    private static boolean canAct(ServerPlayer self) {
        return self != null && SREGameWorldComponent.KEY.get(self.level()).getGameStatus()
                == SREGameWorldComponent.GameStatus.ACTIVE
                && HabiRoles.isHabiRole(self, SevenSins.LUST) && isAliveParticipant(self)
                && !self.hasEffect(ModEffects.SKILL_BANED) && !SlothComponent.isSleepingSloth(self);
    }

    public static boolean useCoinGift(RoleSkill.RoleSkillContext ctx) {
        ServerPlayer self = ctx.player();
        if (!canAct(self)) return false;
        LustComponent state = KEY.get(self);
        state.startRound();
        List<UUID> recipients = self.serverLevel().players().stream()
                .filter(p -> p != self && isAliveParticipant(p) && isCharmed(p))
                .map(Player::getUUID).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.shuffle(recipients, new Random(self.getRandom().nextLong()));
        var grants = LustPolicy.distribute(state.initialPlayerCount, recipients, state.selected);
        if (grants.isEmpty()) {
            self.displayClientMessage(Component.translatable("message.habitrain_core.sin_lust.no_targets"), true);
            return false;
        }
        grants.forEach((id, amount) -> {
            Player target = self.serverLevel().getPlayerByUUID(id);
            if (target != null && amount > 0) {
                SREPlayerShopComponent.KEY.get(target).addToBalance(amount);
                target.displayClientMessage(Component.translatable(
                        "message.habitrain_core.sin_lust.received", amount), true);
            }
        });
        return true;
    }

    public static void select(ServerPlayer self, UUID targetId) {
        if (!canAct(self)) return;
        LustComponent state = KEY.get(self);
        Player target = self.serverLevel().getPlayerByUUID(targetId);
        if (target == null || target == self || !isAliveParticipant(target) || !isCharmed(target)) return;
        if (targetId.equals(state.selected)) return;
        if (state.selectionTicksLeft() > 0) {
            self.displayClientMessage(Component.translatable("message.habitrain_core.sin_lust.selection_cooldown",
                    (state.selectionTicksLeft() + 19) / 20), true);
            return;
        }
        state.selected = targetId;
        state.selectionReadyAt = self.level().getGameTime() + LustPolicy.SELECTION_COOLDOWN_TICKS;
        state.clearRevival();
        refreshViews(self.serverLevel());
        KEY.sync(self);
        self.displayClientMessage(Component.translatable("message.habitrain_core.sin_lust.selected",
                target.getDisplayName()), true);
    }

    /** Called after confirmed death; the prompt is tied to this particular death. */
    public static void onPlayerDeath(Player victim) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        for (ServerPlayer self : level.players()) {
            if (!HabiRoles.isHabiRole(self, SevenSins.LUST) || !isAliveParticipant(self)) continue;
            LustComponent state = KEY.get(self);
            if (!victim.getUUID().equals(state.selected) || state.revivalToken != null) continue;
            state.selectedDiedAt = level.getGameTime();
            state.deathPosition = victim.position();
            state.revivalToken = UUID.randomUUID();
            KEY.sync(self);
        }
        refreshViews(level);
    }

    public static void answerRevival(ServerPlayer self, UUID token, boolean accept) {
        if (!canAct(self)) return;
        LustComponent state = KEY.get(self);
        if (token == null || !token.equals(state.revivalToken)) return;
        Player target = state.selected == null ? null : self.getServer().getPlayerList().getPlayer(state.selected);
        boolean allowed = LustPolicy.canRevive(state.selectedDiedAt, self.level().getGameTime())
                && target instanceof ServerPlayer && !isAliveParticipant(target)
                && WorldModifierComponent.getInstance(self).isModifier(state.selected, HabiModifiers.CHARMED)
                && state.deathPosition != null;
        Vec3 pos = state.deathPosition;
        state.clearRevival();
        KEY.sync(self);
        if (!accept || !allowed) return;
        // Revive first so sacrificing the last ally cannot finish the round in between.
        ServerPlayer revived = (ServerPlayer) target;
        if (revived.serverLevel() != self.serverLevel()) {
            revived.teleportTo(self.serverLevel(), pos.x, pos.y, pos.z, revived.getYRot(), revived.getXRot());
        }
        GameUtils.revivePlayer(revived, pos.x, pos.y, pos.z);
        self.serverLevel().getEntities(TMMEntities.PLAYER_BODY,
                body -> revived.getUUID().equals(body.getPlayerUuid())).forEach(net.minecraft.world.entity.Entity::discard);
        GameUtils.forceKillPlayer(self, true, null, GameConstants.DeathReasons.GENERIC);
        refreshViews(self.serverLevel());
    }

    private void clearRevival() {
        revivalToken = null;
        selectedDiedAt = 0;
        deathPosition = null;
    }

    public static boolean canUseIncense(Player self) {
        return self instanceof ServerPlayer sp && canAct(sp) && !incenseTargets(sp).isEmpty();
    }

    private static List<ServerPlayer> incenseTargets(ServerPlayer self) {
        return self.serverLevel().players().stream().filter(p -> p != self && isAliveParticipant(p)
                && !isCharmed(p) && self.distanceToSqr(p) <= 25.0).toList();
    }

    public static boolean useIncense(Player player) {
        if (!(player instanceof ServerPlayer self) || !canAct(self)) return false;
        var candidates = incenseTargets(self);
        if (candidates.isEmpty()) return false;
        ServerPlayer target = candidates.get(self.getRandom().nextInt(candidates.size()));
        WorldModifierComponent.getInstance(self).addModifier(target.getUUID(), HabiModifiers.CHARMED);
        refreshViews(self.serverLevel());
        return true;
    }

    public static boolean useStimulant(Player player) {
        if (!(player instanceof ServerPlayer self) || !canAct(self)) return false;
        for (ServerPlayer target : self.serverLevel().players()) {
            if (!isAliveParticipant(target) || (target != self && !isCharmed(target))) continue;
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600, 2));
            target.addEffect(new MobEffectInstance(MobEffects.JUMP, 600, 0));
            KEY.get(target).nauseaAt = self.level().getGameTime() + 600;
        }
        return true;
    }

    /** Each viewer only receives the UUIDs their relationship permits them to see. */
    public static void refreshViews(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        List<ServerPlayer> owners = players.stream().filter(p ->
                HabiRoles.isHabiRole(p, SevenSins.LUST) && isAliveParticipant(p)).toList();
        Map<UUID, String> knownNames = new HashMap<>();
        for (ServerPlayer owner : owners) {
            for (CharmEntry entry : KEY.get(owner).roster) knownNames.put(entry.id(), entry.name());
        }
        List<CharmEntry> marked = HabiModifiers.CHARMED == null ? List.of()
                : WorldModifierComponent.getInstance(level).getAllWithModifier(HabiModifiers.CHARMED).stream()
                .map(id -> {
                    Player target = level.getServer().getPlayerList().getPlayer(id);
                    return target == null
                            ? new CharmEntry(id, knownNames.getOrDefault(id, id.toString().substring(0, 8)), false, false)
                            : new CharmEntry(id, target.getGameProfile().getName(),
                                    target.level() == level && isAliveParticipant(target), true);
                }).sorted(Comparator.comparing(CharmEntry::name)).toList();
        for (ServerPlayer viewer : players) {
            LustComponent state = KEY.get(viewer);
            Set<UUID> next = new HashSet<>();
            if (isAliveParticipant(viewer)) {
                for (ServerPlayer owner : owners) {
                    UUID chosen = KEY.get(owner).selected;
                    if (owner == viewer) {
                        marked.stream().filter(CharmEntry::alive).map(CharmEntry::id).forEach(next::add);
                    } else if (isCharmed(viewer)) {
                        if (chosen != null && marked.stream().anyMatch(e -> e.alive() && chosen.equals(e.id()))) {
                            next.add(chosen);
                        }
                        if (viewer.getUUID().equals(chosen)) next.add(owner.getUUID());
                    }
                }
            }
            next.remove(viewer.getUUID());
            List<CharmEntry> nextRoster = owners.contains(viewer) ? marked : List.of();
            if (!state.highlights.equals(next) || !state.roster.equals(nextRoster)) {
                state.highlights.clear();
                state.highlights.addAll(next);
                state.roster = nextRoster;
                KEY.sync(viewer);
            }
        }
        if (!owners.isEmpty()) applyDeathRestrictions(level, players);
    }

    /** Also handles existing spectators when Lust is revived or assigned during a round. */
    public static void applyDeathRestrictions(Level level, List<? extends Player> victims) {
        if (level.isClientSide() || !SREGameWorldComponent.KEY.get(level).isRunning()
                || level.players().stream().noneMatch(p -> HabiRoles.isHabiRole(p, SevenSins.LUST)
                && isAliveParticipant(p))) return;
        for (Player victim : victims) {
            if (isAliveParticipant(victim) || !victim.isSpectator()) continue;
            var penalty = org.agmas.noellesroles.component.DeathPenaltyComponent.KEY.get(victim);
            if (penalty.limitCameraUUID != null || penalty.limitPos != null || penalty.penaltyExpiry == -1) continue;
            penalty.setPenalty(-1, true);
            victim.sendSystemMessage(Component.translatable("message.habitrain_core.sin_lust.death_hidden"));
        }
    }

    @Override public void serverTick() {
        if (!(player instanceof ServerPlayer self)) return;
        var game = SREGameWorldComponent.KEY.get(self.level());
        if (!game.isRunning()) return;
        long now = self.level().getGameTime();
        if (nauseaAt > 0 && now >= nauseaAt) {
            nauseaAt = 0;
            if (isAliveParticipant(self)) self.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 400, 0));
        }
        if (!HabiRoles.isHabiRole(self, SevenSins.LUST)) return;
        if (game.getGameStatus() != SREGameWorldComponent.GameStatus.ACTIVE) return;
        if (revivalToken != null) {
            Player target = selected == null ? null : self.getServer().getPlayerList().getPlayer(selected);
            if (!isAliveParticipant(self) || !LustPolicy.canRevive(selectedDiedAt, now)
                    || target == null || isAliveParticipant(target)
                    || !WorldModifierComponent.getInstance(self).isModifier(selected, HabiModifiers.CHARMED)) {
                clearRevival();
                KEY.sync(self);
            }
        }
    }

    @Override public void writeToSyncNbt(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("Initialized", initialized);
        tag.putInt("InitialPlayers", initialPlayerCount);
        if (selected != null) tag.putUUID("Selected", selected);
        tag.putLong("SelectionReadyAt", selectionReadyAt);
        if (revivalToken != null) tag.putUUID("RevivalToken", revivalToken);
        tag.putLong("SelectedDiedAt", selectedDiedAt);
        ListTag entries = new ListTag();
        for (CharmEntry entry : roster) {
            CompoundTag row = new CompoundTag();
            row.putUUID("Id", entry.id()); row.putString("Name", entry.name());
            row.putBoolean("Alive", entry.alive()); row.putBoolean("Online", entry.online());
            entries.add(row);
        }
        tag.put("Roster", entries);
        ListTag vision = new ListTag();
        for (UUID id : highlights) {
            CompoundTag row = new CompoundTag(); row.putUUID("Id", id); vision.add(row);
        }
        tag.put("Highlights", vision);
    }

    @Override public void readFromSyncNbt(CompoundTag tag, HolderLookup.Provider registries) {
        initialized = tag.getBoolean("Initialized");
        initialPlayerCount = tag.getInt("InitialPlayers");
        selected = tag.hasUUID("Selected") ? tag.getUUID("Selected") : null;
        selectionReadyAt = tag.getLong("SelectionReadyAt");
        revivalToken = tag.hasUUID("RevivalToken") ? tag.getUUID("RevivalToken") : null;
        selectedDiedAt = tag.getLong("SelectedDiedAt");
        List<CharmEntry> entries = new ArrayList<>();
        for (Tag value : tag.getList("Roster", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            if (row.hasUUID("Id")) entries.add(new CharmEntry(row.getUUID("Id"), row.getString("Name"),
                    row.getBoolean("Alive"), row.getBoolean("Online")));
        }
        roster = List.copyOf(entries);
        highlights.clear();
        for (Tag value : tag.getList("Highlights", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) value;
            if (row.hasUUID("Id")) highlights.add(row.getUUID("Id"));
        }
    }

    @Override public void writeToNbt(CompoundTag tag, HolderLookup.Provider registries) {
        writeToSyncNbt(tag, registries);
        tag.putLong("NauseaAt", nauseaAt);
        if (deathPosition != null) {
            tag.putDouble("DeathX", deathPosition.x); tag.putDouble("DeathY", deathPosition.y); tag.putDouble("DeathZ", deathPosition.z);
        }
    }
    @Override public void readFromNbt(CompoundTag tag, HolderLookup.Provider registries) {
        readFromSyncNbt(tag, registries);
        nauseaAt = tag.getLong("NauseaAt");
        deathPosition = tag.contains("DeathX") ? new Vec3(tag.getDouble("DeathX"), tag.getDouble("DeathY"), tag.getDouble("DeathZ")) : null;
    }
}
