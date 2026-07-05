package com.habitrain.core.game.blackout.task;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BlackoutDrinkHandler {

    private static final Map<UUID, Boolean> drinkingTracked = new HashMap<>();

    public static void register() {
    }

    public static void clearState(UUID uuid) {
        drinkingTracked.remove(uuid);
    }

    public static void clearAll() {
        drinkingTracked.clear();
    }
}