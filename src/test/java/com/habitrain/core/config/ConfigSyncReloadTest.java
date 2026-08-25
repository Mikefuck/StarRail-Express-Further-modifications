package com.habitrain.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSyncReloadTest {

    @Test
    void applyingServerSyncThenReloadingLocalJsonRestoresDiskValues() {
        ConfigRepository repo = new ConfigRepository();
        ConfigSync sync = new ConfigSync(null);

        sync.loadFromJsonString(repo, """
                {
                  "global": {"tempPowerPrice": 100, "knifeDurabilityEnabled": false},
                  "tasks": {
                    "habitrain_core:pet_cat": {
                      "enabled": true,
                      "instinctColor": -926365441,
                      "hasInstinctColor": false
                    }
                  }
                }
                """);
        String diskSnapshot = """
                {
                  "global": {"tempPowerPrice": 100, "knifeDurabilityEnabled": false},
                  "tasks": {
                    "habitrain_core:pet_cat": {
                      "enabled": true,
                      "instinctColor": -926365441,
                      "hasInstinctColor": false
                    }
                  }
                }
                """;

        sync.applySyncFromJson(repo, """
                {
                  "global": {"tempPowerPrice": 250, "knifeDurabilityEnabled": true},
                  "tasks": {
                    "habitrain_core:pet_cat": {
                      "enabled": false,
                      "instinctColor": -939524096,
                      "hasInstinctColor": true
                    }
                  }
                }
                """);
        assertEquals(250, repo.getTempPowerPrice());
        assertTrue(repo.isKnifeDurabilityEnabled());
        assertFalse(repo.getTaskConfig("habitrain_core:pet_cat").enabled);

        sync.loadFromJsonString(repo, diskSnapshot);
        assertEquals(100, repo.getTempPowerPrice());
        assertFalse(repo.isKnifeDurabilityEnabled());
        assertTrue(repo.getTaskConfig("habitrain_core:pet_cat").enabled);
        assertFalse(repo.getTaskConfig("habitrain_core:pet_cat").hasInstinctColor);
    }
}
