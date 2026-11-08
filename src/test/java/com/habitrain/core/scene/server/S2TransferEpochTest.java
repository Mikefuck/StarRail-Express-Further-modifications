package com.habitrain.core.scene.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;

class S2TransferEpochTest {
    private final SceneTransferService service = SceneTransferService.getInstance();
    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        var method = SceneTransferService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(service, args);
    }
    private long acquire(UUID player) throws Exception {
        return (long) invoke("tryAcquirePlayerSlot", new Class<?>[]{UUID.class}, player);
    }
    private void release(UUID player, long epoch) throws Exception {
        invoke("releasePlayerSlot", new Class<?>[]{UUID.class, long.class}, player, epoch);
    }
    @AfterEach void reset() {
        service.shutdown(); service.awaitStopped(); service.start();
    }
    @Test void oldCompletionCannotDecrementTheReconnectedPlayersWindow() throws Exception {
        service.start();
        UUID player = UUID.randomUUID();
        long old = acquire(player);
        service.onPlayerDisconnect(player);
        long current = acquire(player);
        assertNotEquals(old, current);
        release(player, old);
        assertEquals(1, service.inFlightChunks(player));
        release(player, current);
        assertEquals(0, service.inFlightChunks(player));
    }
    @Test void shutdownDoesNotResurrectExecutorsAndRestartCannotReuseAnOldEpoch() throws Exception {
        service.start();
        UUID player = UUID.randomUUID();
        long old = acquire(player);
        service.shutdown();
        assertEquals(-1, acquire(player));
        var failure = assertThrows(InvocationTargetException.class,
                () -> invoke("ensureExecutor", new Class<?>[0]));
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        release(player, old);
        assertEquals(0, service.inFlightChunks(player));
        service.awaitStopped();
        service.start();
        long current = acquire(player);
        assertNotEquals(old, current);
        release(player, old);
        assertEquals(1, service.inFlightChunks(player));
    }
}
