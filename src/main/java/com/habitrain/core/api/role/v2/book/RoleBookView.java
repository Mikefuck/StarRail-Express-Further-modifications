package com.habitrain.core.api.role.v2.book;

import com.habitrain.core.api.role.book.RoleBookPage;

import java.util.List;
import java.util.Objects;

/**
 * Effective role-book pages after ADD content and {@link RoleBookPatch} folds.
 *
 * <p>{@link #replaceAll()} means the provider owns the complete right-side
 * tab set (clear upstream tabs). Otherwise the pages are appendices.
 */
public record RoleBookView(boolean replaceAll, List<RoleBookPage> pages) {

    public RoleBookView {
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
    }

    public static RoleBookView none() {
        return new RoleBookView(false, List.of());
    }

    public static RoleBookView append(List<RoleBookPage> pages) {
        return new RoleBookView(false, pages == null ? List.of() : pages);
    }

    public static RoleBookView replaceAll(List<RoleBookPage> pages) {
        return new RoleBookView(true, pages == null ? List.of() : pages);
    }

    public boolean isEmpty() {
        return pages.isEmpty();
    }
}
