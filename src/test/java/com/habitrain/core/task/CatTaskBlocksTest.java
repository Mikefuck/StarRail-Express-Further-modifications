package com.habitrain.core.task;

import com.habitrain.core.BuiltinTaskRegistrar;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatTaskBlocksTest {

    @Test
    void scanIdsMatchYuushya230NamedDecoCats() {
        Set<String> ids = Set.copyOf(Arrays.asList(BuiltinTaskRegistrar.CAT_BLOCK_IDS));
        assertEquals(Set.of(
                "yuushya:british_shorthair",
                "yuushya:orange_cat",
                "yuushya:white_cat",
                "yuushya:black_cat",
                "yuushya:ragdoll",
                "yuushya:calico",
                "yuushya:jellie",
                "yuushya:siamese",
                "yuushya:tabby"
        ), ids);
    }

    @Test
    void scanIdsDoNotUseLegacyNumericIds() {
        Set<String> ids = Set.copyOf(Arrays.asList(BuiltinTaskRegistrar.CAT_BLOCK_IDS));
        assertFalse(ids.contains("yuushya:440"));
        assertFalse(ids.contains("yuushya:442"));
        assertFalse(ids.contains("yuushya:tuxedo"));
        assertFalse(ids.contains("yuushya:fat_red"));
    }
}
