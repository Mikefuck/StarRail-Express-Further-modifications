package com.habitrain.core.api.role.v2.definition;

import java.util.Objects;

/**
 * Immutable faction/alignment profile of a role.
 *
 * <p>{@link com.habitrain.core.role.extension.ManagedSRERole} constructs the
 * upstream object through {@code NormalRole}'s constructor, which already
 * derives {@code passiveIncome = canUseKiller} and
 * {@code neutral = !innocent && !canUseKiller}. The profile only
 * <em>overrides</em> those derivations when the provider explicitly opts in:
 * {@code *Explicit} flags tell the compiler whether the corresponding boolean
 * is a deliberate value or merely the constructor-derived default.
 *
 * <p>{@code setNeutralForKiller}/{@code setNeutralForInnocent} also force
 * {@code isNeutrals=true} upstream — that is SRE's contract, not ours.
 */
public record RoleFactionProfile(boolean innocent, boolean canUseKiller,
                                 boolean neutral, boolean vigilanteTeam,
                                 boolean neutralExplicit, boolean vigilanteTeamExplicit,
                                 boolean neutralForKiller, boolean neutralForInnocent,
                                 boolean mafiaTeam,
                                 boolean neutralForKillerExplicit,
                                 boolean neutralForInnocentExplicit,
                                 boolean mafiaTeamExplicit) {

    public RoleFactionProfile {
        Objects.requireNonNull(neutralExplicit, "neutralExplicit");
        Objects.requireNonNull(vigilanteTeamExplicit, "vigilanteTeamExplicit");
        Objects.requireNonNull(neutralForKillerExplicit, "neutralForKillerExplicit");
        Objects.requireNonNull(neutralForInnocentExplicit, "neutralForInnocentExplicit");
        Objects.requireNonNull(mafiaTeamExplicit, "mafiaTeamExplicit");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean innocent;
        private boolean canUseKiller;
        private boolean neutral;
        private boolean vigilanteTeam;
        private boolean neutralExplicit;
        private boolean vigilanteTeamExplicit;
        private boolean neutralForKiller;
        private boolean neutralForInnocent;
        private boolean mafiaTeam;
        private boolean neutralForKillerExplicit;
        private boolean neutralForInnocentExplicit;
        private boolean mafiaTeamExplicit;

        public Builder innocent() { this.innocent = true; return this; }
        public Builder killer() { this.canUseKiller = true; return this; }

        public Builder neutral() { this.neutral = true; this.neutralExplicit = true; return this; }
        public Builder notNeutral() { this.neutral = false; this.neutralExplicit = true; return this; }

        public Builder vigilanteTeam() { this.vigilanteTeam = true; this.vigilanteTeamExplicit = true; return this; }
        public Builder notVigilanteTeam() { this.vigilanteTeam = false; this.vigilanteTeamExplicit = true; return this; }

        public Builder neutralForKiller() {
            this.neutralForKiller = true;
            this.neutralForKillerExplicit = true;
            return this;
        }

        public Builder notNeutralForKiller() {
            this.neutralForKiller = false;
            this.neutralForKillerExplicit = true;
            return this;
        }

        public Builder neutralForInnocent() {
            this.neutralForInnocent = true;
            this.neutralForInnocentExplicit = true;
            return this;
        }

        public Builder notNeutralForInnocent() {
            this.neutralForInnocent = false;
            this.neutralForInnocentExplicit = true;
            return this;
        }

        public Builder mafiaTeam() {
            this.mafiaTeam = true;
            this.mafiaTeamExplicit = true;
            return this;
        }

        public Builder notMafiaTeam() {
            this.mafiaTeam = false;
            this.mafiaTeamExplicit = true;
            return this;
        }

        public RoleFactionProfile build() {
            return new RoleFactionProfile(innocent, canUseKiller, neutral,
                    vigilanteTeam, neutralExplicit, vigilanteTeamExplicit,
                    neutralForKiller, neutralForInnocent, mafiaTeam,
                    neutralForKillerExplicit, neutralForInnocentExplicit, mafiaTeamExplicit);
        }
    }
}
