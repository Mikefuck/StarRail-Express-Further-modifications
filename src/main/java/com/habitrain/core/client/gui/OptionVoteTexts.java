package com.habitrain.core.client.gui;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * Client-side localization for option-vote UI (mode/map lobby votes).
 * Server may send placeholders / raw ids; real text is resolved here from lang keys.
 */
public final class OptionVoteTexts {
    private OptionVoteTexts() {}

    public static Component closeButton() {
        return Component.translatable("vote.habitrain_core.ui.close");
    }

    public static Component votesLabel(int votes) {
        return Component.translatable("vote.habitrain_core.ui.votes", votes);
    }

    public static Component compactVotes(int votes) {
        return Component.translatable("vote.habitrain_core.ui.votes_compact", votes);
    }

    public static Component voteShare(int votes, int percent) {
        return Component.translatable("vote.habitrain_core.ui.vote_share", votes, percent);
    }

    public static Component timeLeft(int seconds) {
        return Component.translatable("vote.habitrain_core.ui.time_left", seconds);
    }

    public static Component ended() {
        return Component.translatable("vote.habitrain_core.ui.ended");
    }

    public static Component phaseFor(String voteId) {
        if ("mode".equals(voteId)) {
            return Component.translatable("vote.habitrain_core.ui.phase_mode");
        }
        if ("map".equals(voteId)) {
            return Component.translatable("vote.habitrain_core.ui.phase_map");
        }
        return Component.translatable("vote.habitrain_core.ui.phase_generic");
    }

    public static Component candidateProgress(int current, int total) {
        return Component.translatable("vote.habitrain_core.ui.candidate_progress", current, total);
    }

    public static Component optionId(String id) {
        return Component.translatable("vote.habitrain_core.ui.option_id", id == null ? "" : id);
    }

    public static Component selectedStatus() {
        return Component.translatable("vote.habitrain_core.ui.selected");
    }

    public static Component availableStatus() {
        return Component.translatable("vote.habitrain_core.ui.available");
    }

    public static Component controlHint() {
        return Component.translatable("vote.habitrain_core.ui.hint");
    }

    public static Component mapControlHint() {
        return Component.translatable("vote.habitrain_core.ui.map_hint");
    }

    /** 加载页等仅支持 ESC 隐藏时的提示。 */
    public static Component hideHint() {
        return Component.translatable("vote.habitrain_core.ui.hide_hint");
    }

    /**
     * 投票倒计时页提示：ESC + 已注册的 open_vote 键均可隐藏。
     * 键名来自 {@link com.habitrain.core.client.BlackoutKeyHandler}，随玩家改键变化。
     */
    public static Component hideHintWithBoundKey() {
        return Component.translatable(
                "vote.habitrain_core.ui.hide_hint_key",
                com.habitrain.core.client.BlackoutKeyHandler.getBoundKeyDisplay());
    }

    public static Component noCandidates() {
        return Component.translatable("vote.habitrain_core.ui.no_candidates");
    }

    public static Component recommendedPlayersLabel() {
        return Component.translatable("vote.habitrain_core.ui.recommended_players");
    }

    public static Component mapNumber(String id) {
        return Component.translatable("vote.habitrain_core.ui.map_number", id == null ? "" : id);
    }

    public static Component unlimited() {
        return Component.translatable("vote.habitrain_core.ui.unlimited");
    }

    public static Component votedTo(Component mapName) {
        return Component.translatable("vote.habitrain_core.ui.voted_to", mapName);
    }

    public static Component winnerStatus() {
        return Component.translatable("vote.habitrain_core.ui.winner");
    }

    public static Component transitionPreparing() {
        return Component.translatable("vote.habitrain_core.transition.preparing");
    }

    public static Component transitionTitle() {
        return Component.translatable("vote.habitrain_core.transition.title");
    }

    public static Component transitionDestination(Component destination) {
        return Component.translatable("vote.habitrain_core.transition.destination", destination);
    }

    public static Component titleFor(String voteId) {
        if ("mode".equals(voteId)) {
            return Component.translatable("vote.habitrain_core.title.mode");
        }
        if ("map".equals(voteId)) {
            return Component.translatable("vote.habitrain_core.title.map");
        }
        // fallback: payload title string if present, else voteId
        String payloadTitle = OptionVoteState.getTitle();
        if (payloadTitle != null && !payloadTitle.isBlank()
                && !"mode".equals(payloadTitle) && !"map".equals(payloadTitle)) {
            return Component.literal(payloadTitle);
        }
        return Component.literal(voteId == null ? "" : voteId);
    }

    public static Component descriptionFor(String voteId) {
        if ("mode".equals(voteId)) {
            return Component.translatable("vote.habitrain_core.desc.mode");
        }
        if ("map".equals(voteId)) {
            return Component.translatable("vote.habitrain_core.desc.map");
        }
        String payloadDesc = OptionVoteState.getDescription();
        if (payloadDesc != null && !payloadDesc.isBlank()) {
            return Component.literal(payloadDesc);
        }
        return Component.empty();
    }

    /**
     * Candidate label: operator override (displayName != optionId) stays literal;
     * otherwise try lang key, else raw id.
     */
    public static Component candidateLabel(String optionId, String displayName) {
        String id = optionId == null ? "" : optionId;
        String dn = displayName == null ? "" : displayName;
        if (!dn.isBlank() && !dn.equals(id)) {
            return Component.literal(dn);
        }
        String key = optionLangKey(id);
        if (Language.getInstance().has(key)) {
            return Component.translatable(key);
        }
        // short path after last ':' for nicer raw fallback
        int idx = id.lastIndexOf(':');
        String shortId = idx >= 0 ? id.substring(idx + 1) : id;
        return Component.literal(shortId.isBlank() ? id : shortId);
    }

    /** Sanitize option id into a lang key segment: {@code sre:murder} → {@code sre.murder}. */
    public static String optionLangKey(String optionId) {
        if (optionId == null || optionId.isBlank()) {
            return "vote.habitrain_core.option.unknown";
        }
        String sanitized = optionId.replace(':', '.').replace('/', '.').replace(' ', '_');
        return "vote.habitrain_core.option." + sanitized;
    }

    /** HUD / keybind friendly action label for current option vote. */
    public static String openActionLabel() {
        String voteId = OptionVoteState.getVoteId();
        if ("mode".equals(voteId)) {
            return I18n.get("vote.habitrain_core.hud.open_mode");
        }
        if ("map".equals(voteId)) {
            return I18n.get("vote.habitrain_core.hud.open_map");
        }
        return I18n.get("vote.habitrain_core.hud.open_vote");
    }
}
