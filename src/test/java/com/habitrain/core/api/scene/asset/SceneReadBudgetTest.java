package com.habitrain.core.api.scene.asset;

import com.habitrain.core.api.scene.SceneLimits;
import com.habitrain.core.api.scene.model.SceneBounds;
import java.util.List;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneReadBudgetTest {
    @Test void fullSectionAllowanceRetainsRoomForPalettesAndPayloads() {
        assertDoesNotThrow(() -> {
            var budget = new SceneReadBudget();
            budget.sections(SceneLimits.MAX_DECODE_SECTIONS);
            budget.palette(SceneLimits.MAX_DECODE_SECTIONS * 16);
            budget.payload((int) SceneLimits.MAX_TOTAL_PAYLOAD_BYTES);
        });
    }
    @Test void cumulativeBudgetRejectsBeforeNextAllocationAndFailedChargeDoesNotConsume() throws Exception {
        var budget = new SceneReadBudget(100, 10);
        budget.charge(60, 6);
        assertThrows(IOException.class, () -> budget.charge(41, 1));
        budget.charge(40, 4);
        assertThrows(IOException.class, () -> budget.charge(0, 1));
        assertThrows(IOException.class, () -> budget.charge(Long.MAX_VALUE, 0));
    }
    @Test void bothWireVersionsUseTheBudgetBeforeAllocatingSectionArrays() throws Exception {
        var section = new SceneAssetCodec.SectionData(0, 0, 0, List.of("minecraft:air"), new short[4096], new byte[2048], new byte[2048]);
        var data = new SceneAssetCodec.AssetData(1, "minecraft:overworld", new SceneBounds(0,0,0,16,16,16), "test", List.of(section));
        for (int version : new int[]{2,3}) {
            byte[] bytes = SceneAssetCodec.encode(data, version, SceneAssetCodec.DEFAULT_GZIP_LEVEL);
            assertEquals(1, SceneAssetCodec.decode(bytes).sections.size());
            assertThrows(IOException.class, () -> SceneAssetCodec.decode(bytes, new SceneReadBudget(100, 100)));
        }
    }
}
