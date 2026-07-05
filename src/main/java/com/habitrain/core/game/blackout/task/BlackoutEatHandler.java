package com.habitrain.core.game.blackout.task;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BlackoutEatHandler {

    private static final Map<UUID, Boolean> eatingTracked = new HashMap<>();

    public static void register() {
    }

    public static void clearState(UUID uuid) {
        eatingTracked.remove(uuid);
    }

    public static void clearAll() {
        eatingTracked.clear();
    }
}