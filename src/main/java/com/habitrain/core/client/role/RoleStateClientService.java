package com.habitrain.core.client.role;

import com.google.gson.JsonParser;
import com.habitrain.core.api.role.v2.state.*;
import com.mojang.serialization.JsonOps;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import static com.habitrain.core.api.role.v2.state.RoleStateClientValue.Status.*;

@Environment(EnvType.CLIENT)
public final class RoleStateClientService implements RoleStateClientApi {
    public static final RoleStateClientService INSTANCE = new RoleStateClientService();

    @Override
    public <T> RoleStateClientValue<T> read(RoleStateKey<T> key, StateScope scope,
                                           @Nullable ResourceLocation worldKey, @Nullable UUID owner) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(scope, "scope");
        if (scope == StateScope.PLAYER && owner == null) {
            throw new IllegalArgumentException("PLAYER state requires an owner UUID");
        }
        RoleStateSpec<T> spec;
        try {
            spec = RoleStateApi.instance().spec(key);
        } catch (IllegalArgumentException mismatch) {
            return new RoleStateClientValue<>(SCHEMA_MISMATCH, null, 0, 0);
        }
        if (spec == null) return new RoleStateClientValue<>(UNKNOWN_SCHEMA, null, 0, 0);
        if (spec.scope() != scope || spec.codec() == null) {
            return new RoleStateClientValue<>(SCHEMA_MISMATCH, null, 0, 0);
        }
        String world = scope == StateScope.PLAYER || worldKey == null ? null : worldKey.toString();
        UUID player = scope == StateScope.PLAYER ? owner : null;
        var payload = RoleStateClientCache.forSlot(key.id(), key.role(), scope, world, player);
        if (payload == null) return new RoleStateClientValue<>(MISSING, null, 0, 0);
        if (payload.dataVersion() != spec.dataVersion()) {
            return new RoleStateClientValue<>(SCHEMA_MISMATCH, null, payload.dataVersion(), payload.revision());
        }
        byte[] bytes = payload.encoded();
        if (bytes == null) return new RoleStateClientValue<>(PRESENT, null, payload.dataVersion(), payload.revision());
        try {
            if (bytes.length > spec.maxSerializedBytes()) throw new IllegalArgumentException("State exceeds schema cap");
            T value = spec.codec().parse(JsonOps.INSTANCE,
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)))
                    .getOrThrow(message -> new IllegalArgumentException(message));
            return new RoleStateClientValue<>(PRESENT, key.type().cast(value), payload.dataVersion(), payload.revision());
        } catch (RuntimeException failure) {
            return new RoleStateClientValue<>(DECODE_FAILED, null, payload.dataVersion(), payload.revision());
        }
    }
}
