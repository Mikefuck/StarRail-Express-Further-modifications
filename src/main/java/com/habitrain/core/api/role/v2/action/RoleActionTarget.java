package com.habitrain.core.api.role.v2.action;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Structured, platform-verified action target (fix-doc §12.3 expansion).
 *
 * <p>The server decodes the target from the payload before the handler runs, so
 * provider handlers receive a typed value instead of re-parsing raw bytes.
 * Preconditions are applied per target kind (审核 R-02，旧文档写反了)：
 * {@code maxDistance} is honoured for both {@code Player} and {@code Block}
 * targets; {@code requireLineOfSight} / {@code requireTargetAlive} only apply
 * to {@code Player}. {@code Entity} targets are decoded and existence-checked
 * but do not apply any of those preconditions.
 */
public sealed interface RoleActionTarget {

    /** No structured target; payload is opaque. */
    record None() implements RoleActionTarget {
        public static final None INSTANCE = new None();
    }

    /** A verified online player in the acting player's world. */
    record Player(UUID playerId) implements RoleActionTarget {
        public Player {
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    /** A block position in the acting player's world. */
    record Block(BlockPos pos) implements RoleActionTarget {
        public Block {
            Objects.requireNonNull(pos, "pos");
        }
    }

    /** A live entity id in the acting player's world. */
    record Entity(int entityId) implements RoleActionTarget {
        public Entity {
            if (entityId <= 0) {
                throw new IllegalArgumentException("entityId must be positive");
            }
        }
    }

    /** Converts the legacy {@code targetId} accessor into a {@code Player} target. */
    static @Nullable RoleActionTarget ofPlayer(@Nullable UUID playerId) {
        return playerId == null ? null : new Player(playerId);
    }
}
