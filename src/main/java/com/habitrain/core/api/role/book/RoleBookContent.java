package com.habitrain.core.api.role.book;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Complete provider-owned page set for a replacement role.
 *
 * <p>When the owning REPLACE definition is active, core removes every upstream
 * right-side role-book page and displays exactly these pages in this order.</p>
 */
public record RoleBookContent(List<RoleBookPage> pages) {
    public RoleBookContent {
        Objects.requireNonNull(pages, "pages");
        pages = List.copyOf(pages);
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("Replacement role book requires at least one page");
        }
        pages.forEach(page -> Objects.requireNonNull(page, "page"));
    }

    public static RoleBookContent of(RoleBookPage... pages) {
        Objects.requireNonNull(pages, "pages");
        return new RoleBookContent(Arrays.asList(pages));
    }
}
