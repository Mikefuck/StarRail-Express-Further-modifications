package com.habitrain.core.api.role.v2.book;

import com.habitrain.core.api.role.book.RoleBookPage;
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
        if (op != ListOp.REMOVE && pages.isEmpty()) {
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
            // 与 RoleSkillPatch 同语义：剔除命中项后追加全部 patch 页（review M14——
            // 此前实现与 REPLACE_ALL 完全相同，会丢弃全部基线页）。
            case REPLACE_MATCHING_IDS -> {
                Set<String> replace = titles(pages);
                List<RoleBookPage> kept = baseline.stream()
                        .filter(page -> !replace.contains(titleOf(page)))
                        .toList();
                List<RoleBookPage> next = new ArrayList<>(kept);
                next.addAll(pages);
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
        return page.title() == null ? "" : page.title().getString();
    }
}
