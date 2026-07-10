package com.habitrain.core.client.gui;

import com.habitrain.core.network.BlackoutTaskShopOpenPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端缓存当前打开的停电任务商店状态（由 S2C BlackoutTaskShopOpenPayload 更新）。
 */
public final class BlackoutTaskShopState {
    private static int balance = 0;
    private static boolean generatorDestroyed = false;
    private static boolean restoreUsed = false;
    private static List<BlackoutTaskShopOpenPayload.Entry> entries = new ArrayList<>();
    private static long lastUpdate = 0;

    private BlackoutTaskShopState() {}

    public static void update(BlackoutTaskShopOpenPayload payload) {
        balance = payload.balance();
        generatorDestroyed = payload.generatorDestroyed();
        restoreUsed = payload.restoreUsed();
        entries = payload.entries();
        lastUpdate = System.currentTimeMillis();
    }

    public static int getBalance() { return balance; }
    public static boolean isGeneratorDestroyed() { return generatorDestroyed; }
    public static boolean isRestoreUsed() { return restoreUsed; }
    public static List<BlackoutTaskShopOpenPayload.Entry> getEntries() { return entries; }
    public static long getLastUpdate() { return lastUpdate; }

    /** 客户端换世界/disconnect 时重置，避免旧余额/条目跨世界残留（P1-23）。 */
    public static void clear() {
        balance = 0;
        generatorDestroyed = false;
        restoreUsed = false;
        entries = new ArrayList<>();
        lastUpdate = 0;
    }
}