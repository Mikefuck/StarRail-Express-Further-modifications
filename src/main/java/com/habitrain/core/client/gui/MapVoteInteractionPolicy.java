package com.habitrain.core.client.gui;

/** Pure input routing for the map-vote browse/detail state machine. */
final class MapVoteInteractionPolicy {
    enum Event {
        LEFT_CLICK_CONFIRM,
        BROWSE,
        READ_DETAIL,
        AUTO_PICK
    }

    enum ScrollIntent {
        BROWSE_PREVIOUS,
        BROWSE_NEXT,
        READ_PREVIOUS,
        READ_NEXT
    }

    private MapVoteInteractionPolicy() {}

    static boolean detailOpenAfter(boolean currentlyOpen, Event event) {
        return switch (event) {
            case LEFT_CLICK_CONFIRM -> true;
            case READ_DETAIL -> currentlyOpen;
            case BROWSE, AUTO_PICK -> false;
        };
    }

    static ScrollIntent scrollIntent(boolean detailOpen, boolean overDetailPane, double scrollY) {
        boolean previous = scrollY > 0.0;
        if (detailOpen && overDetailPane) {
            return previous ? ScrollIntent.READ_PREVIOUS : ScrollIntent.READ_NEXT;
        }
        return previous ? ScrollIntent.BROWSE_PREVIOUS : ScrollIntent.BROWSE_NEXT;
    }
}
