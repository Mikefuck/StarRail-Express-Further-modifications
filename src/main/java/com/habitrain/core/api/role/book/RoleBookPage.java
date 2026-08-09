package com.habitrain.core.api.role.book;

import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One text page in the SRE role introduction book.
 *
 * <p>The title is used as the page tab label. Paragraphs are wrapped by core
 * at render time and retain the styling and translations of their components.</p>
 */
public record RoleBookPage(Component title, List<Component> paragraphs) {
    public RoleBookPage {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(paragraphs, "paragraphs");
        paragraphs = List.copyOf(paragraphs);
        if (paragraphs.isEmpty()) {
            throw new IllegalArgumentException("Role book page requires at least one paragraph");
        }
        paragraphs.forEach(paragraph -> Objects.requireNonNull(paragraph, "paragraph"));
    }

    public static RoleBookPage of(Component title, Component... paragraphs) {
        Objects.requireNonNull(paragraphs, "paragraphs");
        return new RoleBookPage(title, Arrays.asList(paragraphs));
    }
}
