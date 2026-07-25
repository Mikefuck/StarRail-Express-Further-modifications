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

    public static Component timeLeft(int seconds) {
        return Component.translatable("vote.habitrain_core.ui.time_left", seconds);
    }

    public static Component ended() {
        return Component.translatable("vote.habitrain_core.ui.ended");
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
