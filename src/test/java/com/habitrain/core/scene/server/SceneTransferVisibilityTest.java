package com.habitrain.core.scene.server;

import com.habitrain.core.api.scene.SceneInstanceSpec;
import com.habitrain.core.api.scene.model.SceneInstance;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneTransferVisibilityTest {
    @Test void permissionMustStillExistForEveryPrivateAssetRequest() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(); String hash = "a".repeat(64);
        var spec = SceneInstanceSpec.builder("test:private").assetKey("asset").dimension("minecraft:overworld").visibleTo(Set.of(owner)).build();
        var instance = new SceneInstance(spec, hash, 1, -1, -1, 0);
        assertTrue(SceneTransferService.instanceAllows(instance, owner, "minecraft:overworld", hash));
        assertFalse(SceneTransferService.instanceAllows(instance, other, "minecraft:overworld", hash));
        assertFalse(SceneTransferService.instanceAllows(instance, owner, "minecraft:the_nether", hash));
        assertFalse(SceneTransferService.instanceAllows(instance, owner, "minecraft:overworld", "b".repeat(64)));
        var changed = new SceneInstance(spec.toBuilder().visibleTo(Set.of(other)).build(), hash, 2, -1, -1, 0);
        assertFalse(SceneTransferService.instanceAllows(changed, owner, "minecraft:overworld", hash));
        assertFalse(SceneTransferService.instanceAllows(null, owner, "minecraft:overworld", hash));
    }
}
