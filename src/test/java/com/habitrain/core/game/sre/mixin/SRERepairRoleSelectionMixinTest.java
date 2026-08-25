package com.habitrain.core.game.sre.mixin;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;

import java.lang.reflect.Method;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SRERepairRoleSelectionMixinTest {

    private static final String NEW_CTOR_TARGET = "(Ljava/util/Collection;)Ljava/util/ArrayList;";

    @Test
    void beginNewRedirectUsesCtorReturnTypeNotInitVoid() throws Exception {
        Method handler = SRERepairRoleSelectionMixin.class.getDeclaredMethod(
                "habitrain$filterRepairersFromShuffle", Collection.class);
        Redirect redirect = handler.getAnnotation(Redirect.class);
        assertNotNull(redirect);
        At at = redirect.at();
        assertEquals("NEW", at.value());
        assertEquals(NEW_CTOR_TARGET, at.target());
    }

    @Test
    void mixinNewCtorTargetResolvesToArrayListNotVoid() {
        MemberInfo wrong = MemberInfo.parse(
                "Ljava/util/ArrayList;<init>(Ljava/util/Collection;)V", null);
        assertEquals("V", wrong.toCtorType());

        MemberInfo correct = MemberInfo.parse(NEW_CTOR_TARGET, null);
        assertEquals("java/util/ArrayList", correct.toCtorType());
        assertEquals("(Ljava/util/Collection;)V", correct.toCtorDesc());
    }
}
