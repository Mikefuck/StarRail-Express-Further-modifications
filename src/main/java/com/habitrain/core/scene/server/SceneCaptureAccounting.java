package com.habitrain.core.scene.server;

/** Tracks definitive section outcomes separately from temporary missing chunks. */
final class SceneCaptureAccounting {
    enum SectionOutcome {
        CAPTURED,
        EMPTY,
        OUTSIDE_BUILD_HEIGHT,
        MISSING
    }

    private int captured;
    private int empty;
    private int outsideBuildHeight;
    private int consecutiveMissingTicks;

    void record(SectionOutcome outcome) {
        switch (outcome) {
            case CAPTURED -> {
                captured++;
                consecutiveMissingTicks = 0;
            }
            case EMPTY -> {
                empty++;
                consecutiveMissingTicks = 0;
            }
            case OUTSIDE_BUILD_HEIGHT -> {
                outsideBuildHeight++;
                consecutiveMissingTicks = 0;
            }
            case MISSING -> consecutiveMissingTicks++;
        }
    }

    int processedSections() {
        return captured + empty + outsideBuildHeight;
    }

    int capturedSections() {
        return captured;
    }

    int emptySections() {
        return empty;
    }

    int outsideBuildHeightSections() {
        return outsideBuildHeight;
    }

    int consecutiveMissingTicks() {
        return consecutiveMissingTicks;
    }

    boolean isComplete(int expectedSections) {
        return expectedSections >= 0 && processedSections() == expectedSections;
    }
}
