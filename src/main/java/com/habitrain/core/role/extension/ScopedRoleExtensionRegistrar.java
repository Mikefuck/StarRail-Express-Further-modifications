package com.habitrain.core.role.extension;

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

/**
 * The {@link RoleExtensionRegistrar} handed to one provider entrypoint. The four
 * registry operations ({@code ADD}/{@code MODIFY}/{@code REPLACE}/{@code ALIAS})
 * are scoped to that provider's {@link ProviderRegistrationTransaction},
 * including hooks, state, actions and capability policies.  The provider sees
 * normal typed return values while the transaction owns the actual commit.
 */
public final class ScopedRoleExtensionRegistrar implements RoleExtensionRegistrar {

    private final ProviderRegistrationTransaction transaction;

    ScopedRoleExtensionRegistrar(ProviderRegistrationTransaction transaction) {
        this.transaction = transaction;
    }

    @Override
    public SRERole add(RoleDefinition definition) {
        return transaction.add(definition);
    }

    @Override
    public void modify(RolePatch patch) {
        transaction.modify(patch);
    }

    @Override
    public void replace(RoleReplacement replacement) {
        transaction.replace(replacement);
    }

    @Override
    public void alias(RoleAlias alias) {
        transaction.alias(alias);
    }

    @Override
    public void hooks(RoleKey role, RoleHooks hooks) {
        transaction.hooks(role, RoleScope.HOLDER, hooks);
    }

    @Override
    public void hooks(RoleKey role, RoleScope scope, RoleHooks hooks) {
        transaction.hooks(role, scope, hooks);
    }

    @Override
    public <T> RoleStateKey<T> state(RoleStateSpec<T> spec) {
        return transaction.state(spec);
    }

    @Override
    public RoleActionSpec action(RoleActionSpec spec) {
        return transaction.action(spec);
    }

    @Override
    public RoleVoicePolicy voice(RoleVoicePolicy policy) {
        return transaction.voice(policy);
    }

    @Override
    public RoleChatPolicy chat(RoleChatPolicy policy) {
        return transaction.chat(policy);
    }
}
