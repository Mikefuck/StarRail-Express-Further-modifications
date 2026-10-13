package com.habitrain.core.role;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemovedRoleV1PackagingTest {
    @Test
    void retiredApiEngineBridgeAndConfigScreenAreAbsent() {
        String root = "/com/habitrain/core/";
        for (String retired : new String[]{
                "api/role/RoleOverrideApi", "api/role/ModifyRoleDefinition", "api/role/ReplaceRoleDefinition",
                "api/role/RoleOverrideEntry", "api/role/RoleOverrideKind", "api/role/OverrideStatus",
                "api/role/BlackoutWinCheckContext", "api/role/patch/SkillRegistrar", "api/role/patch/ManagedSkillPatch",
                "api/role/book/RoleBookContent", "api/role/book/RoleBookPage",
                "role/override/RoleOverrideRegistry", "role/override/RoleOverrideEngine",
                "role/override/RoleOverrideBridgeImpl", "role/override/EffectiveSnapshot",
                "role/override/RoleOverrideTickApplier", "role/override/RoleOverrideSkillManager",
                "role/override/RoleOverrideWinHook", "role/legacy/LegacyRoleOverrideTranslator",
                "role/extension/RolePatchBundle", "config/RoleOverrideConfigSection",
                "client/gui/menu/page/ModeRolesPage", "client/role/RoleOverrideRefreshDispatcher",
                "api/spi/RoleSpi$RoleOverrideBridge"}) {
            assertNull(getClass().getResource(root + retired + ".class"), retired);
        }
        for (String callback : new String[]{"ColorPatch", "DefaultItemsPatch", "FlagsPatch", "NamePatch",
                "RoleTextPatch", "ShopPatch", "ShopTransform", "SpawnInfoPatch", "WinConditionHook"}) {
            assertNull(getClass().getResource(root + "api/role/patch/" + callback + ".class"), callback);
            assertNotNull(getClass().getResource(root + "api/role/v2/patch/" + callback + ".class"), callback);
        }
        for (String retained : new String[]{"api/role/v2/RoleExtensionApi", "api/role/v2/book/RoleBookPage",
                "api/role/v2/book/RoleBookContent", "api/role/v2/behavior/RoleWinCheckContext",
                "role/extension/RoleExtensionLifecycleHandler", "role/extension/RoleV2WinHookSupport",
                "client/gui/menu/page/RoleExtensionsPage", "game/sre/role/SreRoleResolver"}) {
            assertNotNull(getClass().getResource(root + retained + ".class"), retained);
        }
    }
}
