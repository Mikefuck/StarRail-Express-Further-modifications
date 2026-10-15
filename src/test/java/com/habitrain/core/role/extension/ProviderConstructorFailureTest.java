package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleExtensionEntrypoint;
import com.habitrain.core.internal.CoreLifecycleTestSupport;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class ProviderConstructorFailureTest {
    @Test @SuppressWarnings("unchecked")
    void constructorFailureIsIsolatedAndRemainingProviderLoads() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var metadata = (LoaderModMetadata) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{LoaderModMetadata.class}, (p, m, a) -> switch (m.getName()) {
                    case "getId", "getName" -> "audit_broken_provider";
                    case "getType" -> "fabric";
                    default -> null;
                });
        var mod = (ModContainerImpl) unsafe.allocateInstance(ModContainerImpl.class);
        var info = ModContainerImpl.class.getDeclaredField("info");
        info.setAccessible(true);
        info.set(mod, metadata);
        var attempts = new AtomicInteger();
        var nextProviderCalls = new AtomicInteger();
        Class<?> entryType = Class.forName("net.fabricmc.loader.impl.entrypoint.EntrypointStorage$Entry");
        Object broken = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{entryType}, (p, m, a) -> switch (m.getName()) {
            case "getOrCreate" -> { attempts.incrementAndGet(); throw new IllegalStateException("constructor fault"); }
            case "isOptional" -> false;
            case "getModContainer" -> mod;
            case "getDefinition" -> "audit.BrokenProvider";
            default -> null;
        });
        Object healthy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{entryType}, (p, m, a) -> switch (m.getName()) {
            case "getOrCreate" -> (RoleExtensionEntrypoint) registrar -> nextProviderCalls.incrementAndGet();
            case "isOptional" -> false;
            case "getModContainer" -> mod;
            case "getDefinition" -> "audit.HealthyProvider";
            default -> null;
        });
        Object loader = FabricLoader.getInstance();
        var storageField = loader.getClass().getDeclaredField("entrypointStorage");
        storageField.setAccessible(true);
        Object storage = storageField.get(loader);
        var mapField = EntrypointStorage.class.getDeclaredField("entryMap");
        mapField.setAccessible(true);
        var entries = (Map<String, List<Object>>) mapField.get(storage);
        String key = "habitrain:role_extensions";
        var previous = entries.put(key, List.of(broken, healthy));
        var registrySnapshot = RoleExtensionRegistry.INSTANCE.snapshotForTransaction(false);
        try {
            var service = new RoleExtensionServiceImpl();
            assertDoesNotThrow(() -> CoreLifecycleTestSupport.run(service::loadProviders));
            assertEquals(1, attempts.get(), "failed entrypoint must never be reconstructed for logging");
            assertEquals(1, nextProviderCalls.get());
            CoreLifecycleTestSupport.run(service::loadProviders);
            assertEquals(1, nextProviderCalls.get(), "loading remains idempotent");
        } finally {
            if (previous == null) entries.remove(key); else entries.put(key, previous);
            RoleExtensionRegistry.INSTANCE.restoreTransactionSnapshot(registrySnapshot);
        }
    }
}

