package com.habitrain.core.client.gui;

import net.minecraft.locale.Language;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/** Screen-owned labels; a language/resource reload invalidates their derived layout. */
final class TicketTextCache {
    static final Component[] HOTBAR_NUMBERS = new Component[9];
    static {
        for (int i = 0; i < HOTBAR_NUMBERS.length; i++) HOTBAR_NUMBERS[i] = Component.literal(Integer.toString(i + 1));
    }

    private final Map<String, Component> labels = new HashMap<>();
    private final Map<String, String> subtitles = new HashMap<>();
    private Language language;

    boolean refreshLanguage() {
        Language next = Language.getInstance();
        if (language == next) return false;
        language = next;
        labels.clear();
        subtitles.clear();
        return true;
    }

    Component label(String key) {
        return labels.computeIfAbsent(key, Component::translatable);
    }

    String subtitle(String key) {
        return subtitles.computeIfAbsent(key, OptionVoteTexts::subtitle);
    }

    Component lockedHint() {
        return labels.computeIfAbsent("inventory.habitrain_core.ticket.locked",
                key -> Component.translatable(key).withStyle(ChatFormatting.RED));
    }
}
