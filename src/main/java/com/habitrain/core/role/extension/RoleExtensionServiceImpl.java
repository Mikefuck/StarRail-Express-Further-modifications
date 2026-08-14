package com.habitrain.core.role.extension;

import com.habitrain.core.api.role.v2.RoleExtensionApi;
import com.habitrain.core.api.role.v2.RoleExtensionEntrypoint;
import com.habitrain.core.api.role.v2.RoleExtensionRegistrar;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.action.RoleActionApi;
import com.habitrain.core.api.role.v2.action.RoleActionSpec;
import com.habitrain.core.api.role.v2.behavior.RoleHooks;
import com.habitrain.core.api.role.v2.behavior.RoleScope;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleChatPolicy;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.state.RoleStateApi;
import com.habitrain.core.api.role.v2.state.RoleStateKey;
import com.habitrain.core.api.role.v2.state.RoleStateSpec;
import com.habitrain.core.api.role.v2.definition.PatchPriority;
import com.habitrain.core.api.role.v2.definition.RoleAlias;
import com.habitrain.core.api.role.v2.definition.RoleDefinition;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.api.role.v2.definition.RoleReplacement;
import com.habitrain.core.role.behavior.RoleHookRegistry;
import io.wifi.starrailexpress.api.SRERole;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link RoleExtensionApi} implementation and the process-wide registrar.
 *
 * <p>{@link #loadProviders()} drives every mod registered under the
 * {@code habitrain:role_extensions} entrypoint, handing each provider a
 * provider-scoped {@link ScopedRoleExtensionRegistrar} backed by a
 * {@link ProviderRegistrationTransaction}. A provider that throws is rolled back
 * (zero entries, zero {@code TMMRoles} leakage). The process-wide registrar keeps
 * the {@link RoleExtensionRegistrar} shape for source/binary compatibility
 * ({@code registrar()} returns {@code this}); only the four registry operations
 * require a live provider transaction.
 */
public final class RoleExtensionServiceImpl implements RoleExtensionApi, RoleExtensionRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger("RoleExtensionApi");
    private static final String ENTRYPOINT_KEY = "habitrain:role_extensions";
    private static final String API_VERSION = "2.0";

    private volatile boolean loaded;

    public RoleExtensionServiceImpl() {}

    @Override
    public RoleExtensionRegistrar registrar() {
        return this;
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
                container.getEntrypoint().register(registrar);
                tx.commit();
            } catch (RuntimeException e) {
                tx.rollback();
                LOGGER.error("Role extension entrypoint {} failed and was rolled back",
                        container.getEntrypoint().getClass().getName(), e);
            }
        }
        LOGGER.info("Loaded {} role extension provider(s)", count());
    }

    @Override
    public SRERole add(RoleDefinition definition) {
        throw new IllegalStateException("add must be called from a role_extensions entrypoint");
    }

    @Override
    public void modify(RolePatch patch) {
        throw new IllegalStateException("modify must be called from a role_extensions entrypoint");
    }

    @Override
    public void replace(RoleReplacement replacement) {
        throw new IllegalStateException("replace must be called from a role_extensions entrypoint");
    }

    @Override
    public void alias(RoleAlias alias) {
        throw new IllegalStateException("alias must be called from a role_extensions entrypoint");
    }

    @Override
    public void hooks(RoleKey role, RoleHooks hooks) {
        RoleHookRegistry.INSTANCE.register(role, hooks);
    }

    @Override
    public void hooks(RoleKey role, RoleScope scope, RoleHooks hooks) {
        RoleHookRegistry.INSTANCE.register(role, scope, RoleHookRegistry.DEFAULT_PROVIDER,
                "hooks@" + role, PatchPriority.NORMAL, hooks);
    }

    @Override
    public <T> RoleStateKey<T> state(RoleStateSpec<T> spec) {
        return RoleStateApi.instance().register(spec);
    }

    @Override
    public RoleActionSpec action(RoleActionSpec spec) {
        return RoleActionApi.instance().register(spec);
    }

    @Override
    public RoleVoicePolicy voice(RoleVoicePolicy policy) {
        return RoleCapabilityApi.instance().voice(policy);
    }

    @Override
    public RoleChatPolicy chat(RoleChatPolicy policy) {
        return RoleCapabilityApi.instance().chat(policy);
    }

    private int count() {
        return RoleExtensionRegistry.INSTANCE.getManagedRoles().size();
    }
}
