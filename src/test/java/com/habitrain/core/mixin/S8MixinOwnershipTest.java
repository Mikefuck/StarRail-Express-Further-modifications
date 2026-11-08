package com.habitrain.core.mixin;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.extensibility.*;
import static org.junit.jupiter.api.Assertions.*;

class S8MixinOwnershipTest {
    private static IMixinInfo mixin(String name) {
        return (IMixinInfo)Proxy.newProxyInstance(IMixinInfo.class.getClassLoader(),new Class<?>[]{IMixinInfo.class},
                (proxy,method,args)->switch(method.getName()) {
                    case "getClassName"->name;
                    case "getTargetClasses"->java.util.List.of("example.Target");
                    default->null;
                });
    }
    @Test void ownershipUsesTheActualMixinClassRatherThanATargetSharedWithOtherMods() throws Exception {
        var method=HabiMixinPlugin.class.getDeclaredMethod("isCoreMixin",IMixinInfo.class,IMixinConfig.class);
        method.setAccessible(true);
        assertEquals(false,method.invoke(null,mixin("another.mod.OptionalMixin"),null));
        assertEquals(true,method.invoke(null,mixin("com.habitrain.core.client.mixin.RequiredMixin"),null));
    }
    @Test void errorsKeepTheOriginalMixinFrameworkDecision() {
        var plugin=new HabiMixinPlugin();
        for(var action:IMixinErrorHandler.ErrorAction.values()) {
            assertSame(action,plugin.onApplyError("example.Target",new IllegalStateException("other mod"),mixin("another.mod.OptionalMixin"),action));
            assertSame(action,plugin.onPrepareError(null,new IllegalStateException("other mod"),mixin("another.mod.OptionalMixin"),action));
        }
    }
}
