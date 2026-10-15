package com.habitrain.core.client.role;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.state.*;
import com.habitrain.core.network.RoleStateSyncPayload;
import com.habitrain.core.role.config.RoleExtensionConfigService;
import com.habitrain.core.role.state.RoleStateServiceImpl;
import com.habitrain.core.role.state.RoleStateSyncService;
import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.habitrain.core.api.role.v2.state.RoleStateClientValue.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class RoleStateClientApiTest {
    private static final RoleKey ROLE = RoleKey.of("audit", "role");
    private static final UUID OWNER = UUID.randomUUID();
    private static final ResourceLocation WORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation OTHER_WORLD = ResourceLocation.parse("minecraft:the_nether");
    private RoleStateServiceImpl server;
    private RoleStateClientApi client;
    private RoleStateKey<Integer> key;

    @BeforeEach void setup() {
        server = (RoleStateServiceImpl) RoleStateApi.instance();
        server.clear(true);
        server.setStore(null);
        RoleExtensionConfigService.INSTANCE.resetForTests();
        RoleStateClientCache.clear();
        client = RoleStateClientApi.instance();
        key = server.register(RoleStateSpec.of("audit", "charge", Integer.class).role(ROLE)
                .sync(SyncPolicy.OWNER).resetOn(ResetCause.MANUAL).codec(Codec.INT).defaultValue(() -> 0).build());
        server.syncService().setRecipients(new RoleStateSyncService.RecipientProvider() {
            public Collection<UUID> allOnline() { return List.of(OWNER); }
            public Collection<UUID> inWorld(String world) { return List.of(OWNER); }
        });
        server.syncService().setSender((recipient, payload) -> {
            if (!OWNER.equals(recipient)) return;
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                RoleStateSyncPayload.CODEC.encode(buffer, payload);
                RoleStateClientCache.accept(RoleStateSyncPayload.CODEC.decode(buffer));
            } finally { buffer.release(); }
        });
    }
    @AfterEach void cleanup() {
        server.syncService().setSender(null);
        server.syncService().setRecipients(null);
        server.clear(true);
        RoleStateClientCache.clear();
        RoleExtensionConfigService.INSTANCE.resetForTests();
    }
    private RoleStateClientValue<Integer> read() { return client.read(key, StateScope.PLAYER, WORLD, OWNER); }

    @Test void serverWriteWireCodecAndPublicClientReadWorkWithoutServerPlayer() {
        assertEquals(MISSING, read().status());
        RoleStateApi.instance().set(key, OWNER, WORLD, 7);
        assertEquals(7, read().value());
        assertTrue(read().revision() > 0);
        assertEquals(7, client.read(key, StateScope.PLAYER, OTHER_WORLD, OWNER).value());
        assertEquals(MISSING, client.read(key, StateScope.PLAYER, WORLD, UUID.randomUUID()).status());
        // Server memory is not a fallback source, including in an integrated JVM.
        RoleStateClientCache.clear();
        assertEquals(MISSING, read().status());
        assertEquals(7, RoleStateApi.instance().get(key, OWNER, WORLD));
    }
    @Test void nullRemovalDisconnectAndFullSnapshotHaveExplicitSemantics() {
        server.set(key, OWNER, WORLD, null);
        assertTrue(read().present());
        assertNull(read().orElse(123));
        server.reset(OWNER, ROLE, ResetCause.MANUAL);
        assertEquals(MISSING, read().status());
        server.set(key, OWNER, WORLD, 3);
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotBegin(123, 100));
        assertEquals(3, read().value());
        RoleStateClientCache.accept(RoleStateSyncPayload.snapshotEnd(123, 101));
        assertEquals(MISSING, read().status());
        server.set(key, OWNER, WORLD, 4);
        RoleStateClientCache.clear();
        assertEquals(MISSING, read().status());
        server.sendCurrentStateTo(OWNER);
        assertEquals(4, read().value());
    }
    @Test void worldsAreIsolatedAndScopeMismatchIsUnavailable() {
        var worldKey = server.register(RoleStateSpec.of("audit", "world", Integer.class).role(ROLE)
                .scope(StateScope.WORLD).sync(SyncPolicy.ALL).codec(Codec.INT).build());
        server.set(worldKey, null, WORLD, 3);
        server.set(worldKey, null, OTHER_WORLD, 8);
        assertEquals(3, client.read(worldKey, StateScope.WORLD, WORLD, OWNER).value());
        assertEquals(8, client.read(worldKey, StateScope.WORLD, OTHER_WORLD, null).value());
        assertEquals(SCHEMA_MISMATCH, client.read(worldKey, StateScope.ROUND, WORLD, null).status());
    }
    @Test void unknownSchemaTypeVersionAndInvalidBytesAreUnavailable() {
        assertThrows(IllegalArgumentException.class, () -> client.read(key, StateScope.PLAYER, WORLD, null));
        var unknown = new RoleStateKey<>(ResourceLocation.parse("audit:unknown"), ROLE, Integer.class);
        assertEquals(UNKNOWN_SCHEMA, client.read(unknown, StateScope.PLAYER, WORLD, OWNER).status());
        var wrongType = new RoleStateKey<>(key.id(), ROLE, String.class);
        assertEquals(SCHEMA_MISMATCH, client.read(wrongType, StateScope.PLAYER, WORLD, OWNER).status());
        RoleStateClientCache.accept(RoleStateSyncPayload.value(key.id(), ROLE, StateScope.PLAYER, null,
                2, "7".getBytes(StandardCharsets.UTF_8), OWNER, 1));
        assertEquals(SCHEMA_MISMATCH, read().status());
        RoleStateClientCache.accept(RoleStateSyncPayload.value(key.id(), ROLE, StateScope.PLAYER, null,
                1, "invalid".getBytes(StandardCharsets.UTF_8), OWNER, 2));
        assertEquals(DECODE_FAILED, read().status());
        assertEquals(0, read().orElse(0));
    }
    @Test void decodedMutableValuesCannotMutateClientMirror() {
        var text = server.register(RoleStateSpec.of("audit", "text", StringBuilder.class).role(ROLE)
                .sync(SyncPolicy.OWNER).codec(Codec.STRING.xmap(StringBuilder::new, StringBuilder::toString)).build());
        server.set(text, OWNER, WORLD, new StringBuilder("value"));
        client.read(text, StateScope.PLAYER, WORLD, OWNER).value().append(" changed");
        assertEquals("value", client.read(text, StateScope.PLAYER, WORLD, OWNER).value().toString());
    }
}
