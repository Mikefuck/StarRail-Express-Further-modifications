package com.habitrain.core.api.role.v2.book;

import com.habitrain.core.api.role.v2.definition.ListOp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reversible role-book page patch: append, drop matching titles, or replace
 * the whole page set. Merge semantics match {@link ListOp}.
 */
public record RoleBookPatch(ListOp op, List<RoleBookPage> pages) {

    public RoleBookPatch {
        Objects.requireNonNull(op, "op");
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        if (pages.isEmpty() && op != ListOp.REPLACE_ALL) {
            throw new IllegalArgumentException("RoleBookPatch requires at least one page");
        }
    }

    public static RoleBookPatch append(RoleBookPage... pages) {
        return new RoleBookPatch(ListOp.APPEND, List.of(pages));
    }

    public static RoleBookPatch removeMatchingTitles(RoleBookPage... pages) {
        return new RoleBookPatch(ListOp.REMOVE, List.of(pages));
    }

    public static RoleBookPatch replaceAll(RoleBookPage... pages) {
        return new RoleBookPatch(ListOp.REPLACE_ALL, List.of(pages));
    }

    public static RoleBookPatch replaceMatchingTitles(RoleBookPage... pages) {
        return new RoleBookPatch(ListOp.REPLACE_MATCHING_IDS, List.of(pages));
    }

    /**
     * Folds this patch onto {@code current}. {@link ListOp#REMOVE} drops current
     * pages whose title string matches a patch page; {@link ListOp#APPEND}
     * concatenates; {@link ListOp#REPLACE_ALL} discards current;
     * {@link ListOp#REPLACE_MATCHING_IDS} replaces only the baseline pages whose
     * title matches a patch page (pages carry no ids, so titles are the match
     * key) and keeps the unmatched ones.
     */
    public List<RoleBookPage> apply(List<RoleBookPage> current) {
        List<RoleBookPage> baseline = current == null ? List.of() : current;
        return switch (op) {
            case APPEND -> {
                List<RoleBookPage> next = new ArrayList<>(baseline);
                next.addAll(pages);
                yield List.copyOf(next);
            }
            case REMOVE -> {
                Set<String> drop = titles(pages);
                yield baseline.stream()
                        .filter(page -> !drop.contains(titleOf(page)))
                        .toList();
            }
            case REPLACE_ALL -> List.copyOf(pages);
            case REPLACE_MATCHING_IDS -> {
                var replacements = new java.util.LinkedHashMap<String, RoleBookPage>();
                for (RoleBookPage page : pages) {
                    if (replacements.put(titleOf(page), page) != null) {
                        throw new IllegalArgumentException("Duplicate replacement page title: " + titleOf(page));
                    }
                }
                List<RoleBookPage> next = new ArrayList<>();
                for (RoleBookPage page : baseline) {
                    RoleBookPage replacement = replacements.remove(titleOf(page));
                    next.add(replacement == null ? page : replacement);
                }
                next.addAll(replacements.values());
                yield List.copyOf(next);
            }
        };
    }

    private static Set<String> titles(List<RoleBookPage> pages) {
        Set<String> titles = new HashSet<>();
        for (RoleBookPage page : pages) {
            titles.add(titleOf(page));
        }
        return titles;
    }

    private static String titleOf(RoleBookPage page) {
        // Component contents retain translation keys; getString() depends on
        // the machine's current language and can collapse distinct keys.
        return page.title().getContents().toString();
    }
}
