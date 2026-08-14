package com.habitrain.core.api.role.v2.behavior;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A set-patch over the proposed winners, matching design §14.2.
 *
 * <p>Hooks return this instead of a raw boolean so the dispatcher can fold
 * multiple roles: add/remove mutate the accumulator, replace/declare
 * overwrite it. Persistence of custom winners is the dispatcher's job.
 */
public record WinPatch(
        WinPatchOp op,
        List<UUID> winners,
        @Nullable String faction,
        @Nullable String customId,
        @Nullable String reason
) {

    public WinPatch {
        op = op == null ? WinPatchOp.NO_CHANGE : op;
        winners = winners == null ? List.of() : List.copyOf(winners);
    }

    public static WinPatch noChange() {
        return new WinPatch(WinPatchOp.NO_CHANGE, List.of(), null, null, null);
    }

    public static WinPatch addWinners(UUID... ids) {
        return new WinPatch(WinPatchOp.ADD_WINNER, List.of(ids), null, null, null);
    }

    public static WinPatch removeWinners(UUID... ids) {
        return new WinPatch(WinPatchOp.REMOVE_WINNER, List.of(ids), null, null, null);
    }

    public static WinPatch replaceWinners(List<UUID> ids) {
        return new WinPatch(WinPatchOp.REPLACE_WINNERS, ids, null, null, null);
    }

    public static WinPatch declareFaction(String faction) {
        return new WinPatch(WinPatchOp.DECLARE_FACTION_WIN, List.of(), faction, null, null);
    }

    public static WinPatch declareCustom(String customId, List<UUID> winners, @Nullable String reason) {
        return new WinPatch(WinPatchOp.DECLARE_CUSTOM, winners, null, customId, reason);
    }

    /**
     * Folds {@code next} onto {@code acc}. {@code null} / {@link WinPatchOp#NO_CHANGE}
     * leave the accumulator alone.
     */
    public static WinPatch merge(@Nullable WinPatch acc, @Nullable WinPatch next) {
        if (acc == null) {
            acc = noChange();
        }
        if (next == null || next.op() == WinPatchOp.NO_CHANGE) {
            return acc;
        }
        return switch (next.op()) {
            case REPLACE_WINNERS, DECLARE_FACTION_WIN, DECLARE_CUSTOM -> next;
            case ADD_WINNER -> acc.withWinners(union(acc.winners(), next.winners()),
                    acc.op() == WinPatchOp.NO_CHANGE ? WinPatchOp.ADD_WINNER : acc.op());
            case REMOVE_WINNER -> {
                if (acc.op() == WinPatchOp.NO_CHANGE) {
                    // Nothing accumulated to remove from. A REMOVE_WINNER patch
                    // here would be misread by the dispatcher as "declare these
                    // players winners" (see RoleEventDispatcher.applyWinPatch).
                    yield acc;
                }
                List<UUID> remaining = acc.winners().stream()
                        .filter(id -> !next.winners().contains(id))
                        .toList();
                yield acc.withWinners(remaining, acc.op());
            }
            case NO_CHANGE -> acc;
        };
    }

    private WinPatch withWinners(List<UUID> nextWinners, WinPatchOp nextOp) {
        return new WinPatch(nextOp, nextWinners, faction, customId, reason);
    }

    private static List<UUID> union(List<UUID> left, List<UUID> right) {
        List<UUID> out = new ArrayList<>(left);
        for (UUID id : right) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }
}
