package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleExtensionApi;
import com.habitrain.core.api.role.v2.RoleExtensionEntrypoint;
import com.habitrain.core.api.role.v2.RoleExtensionRegistrar;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.action.RoleActionSpec;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleScope;
import com.habitrain.core.api.role.v2.capability.RoleChatPolicy;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.definition.RoleAlias;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.api.role.v2.state.RoleStateKey;
import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import io.wifi.starrailexpress.api.SRERole;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link RoleExtensionApi} implementation and the process-wide registrar
 * facade.
 *
 * <p>{@link #loadProviders()} drives every mod registered under the
 * {@code habitrain:role_extensions} entrypoint, handing each provider a
 * provider-scoped {@link ScopedRoleExtensionRegistrar} backed by a
 * {@link ProviderRegistrationTransaction}. A provider that throws is rolled back
 * (zero entries, zero {@code TMMRoles} leakage).
 *
 * <p>{@link #registrar()} is READ-ONLY (audit P1-1): it no longer exposes the
 * process-global write path that let external code bypass the provider identity
 * capture, the unified rollback and the per-provider namespace checks of the
 * transaction. Every registration type — ADD/MODIFY/REPLACE/ALIAS plus
 * hooks/state/action/voice/chat — must go through the entrypoint-scoped
 * registrar; calling any method on the process-wide facade throws.
 */
public final class RoleExtensionServiceImpl implements RoleExtensionApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleExtensionApi");
    private static final String ENTRYPOINT_KEY = "habitrain:role_extensions";
    private static final String API_VERSION = "2.0";

    private volatile boolean loaded;

    public RoleExtensionServiceImpl() {}

    @Override
    public RoleExtensionRegistrar registrar() {
        return READ_ONLY_REGISTRAR;
    }

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    @Override
    public synchronized void loadProviders() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (EntrypointContainer<RoleExtensionEntrypoint> container : FabricLoader.getInstance()
                .getEntrypointContainers(ENTRYPOINT_KEY, RoleExtensionEntrypoint.class)) {
            String providerId = container.getProvider().getMetadata().getId();
            ProviderRegistrationTransaction tx = RoleExtensionRegistry.INSTANCE.begin(providerId);
            RoleExtensionRegistrar registrar = new ScopedRoleExtensionRegistrar(tx);
            try {
                RoleExtensionEntrypoint entrypoint = container.getEntrypoint();
                entrypoint.register(registrar);
                tx.setRequiresClient(entrypoint.requiresClient());
                tx.commit();
            } catch (RuntimeException e) {
                tx.rollback();
                LOGGER.error("Role extension entrypoint {} failed and was rolled back",
                        container.getEntrypoint().getClass().getName(), e);
            }
        }
        LOGGER.info("Loaded {} role extension provider(s)", count());
    }

    private int count() {
        return RoleExtensionRegistry.INSTANCE.getManagedRoles().size();
    }

    /**
     * The read-only process-wide facade (audit P1-1). All registration methods
     * throw: declarations must happen inside a {@code role_extensions}
     * entrypoint, where the provider identity and transaction are known.
     */
    private static final RoleExtensionRegistrar READ_ONLY_REGISTRAR = new RoleExtensionRegistrar() {
        private IllegalStateException blocked() {
            return new IllegalStateException(
                    "Role extension registrations must go through the habitrain:role_extensions "
                            + "entrypoint registrar (provider-scoped transaction); the process-wide "
                            + "registrar() is read-only since audit P1-1");
        }

        @Override
        public SRERole add(RoleDefinition definition) {
            throw blocked();
        }

        @Override
        public void modify(RolePatch patch) {
            throw blocked();
        }

        @Override
        public void replace(RoleReplacement replacement) {
            throw blocked();
        }

        @Override
        public void alias(RoleAlias alias) {
            throw blocked();
        }

        @Override
        public void hooks(RoleKey role, RoleHooks hooks) {
            throw blocked();
        }

        @Override
        public void hooks(RoleKey role, RoleScope scope, RoleHooks hooks) {
            throw blocked();
        }

        @Override
        public <T> RoleStateKey<T> state(RoleStateSpec<T> spec) {
            throw blocked();
        }

        @Override
        public RoleActionSpec action(RoleActionSpec spec) {
            throw blocked();
        }

        @Override
        public RoleVoicePolicy voice(RoleVoicePolicy policy) {
            throw blocked();
        }

        @Override
        public RoleChatPolicy chat(RoleChatPolicy policy) {
            throw blocked();
        }
    };
}
