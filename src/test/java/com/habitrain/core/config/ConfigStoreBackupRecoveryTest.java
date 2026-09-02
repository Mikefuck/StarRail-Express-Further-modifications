package com.habitrain.core.config;

import com.habitrain.core.persist.AtomicJsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreBackupRecoveryTest {

    @TempDir
    Path tmp;

    @Test
    void corruptPrimaryRestoresLastGoodBackupInsteadOfDefaults() throws Exception {
        Path file = tmp.resolve("habitrain_core.json");
        Files.writeString(file, "{", StandardCharsets.UTF_8);
        Files.writeString(AtomicJsonFiles.bakPath(file), """
                {
                  "global": {
                    "knifeDurabilityEnabled": true,
                    "sheriffCountDivisor": 9,
                    "blackoutGlobalCooldownSeconds": 73
                  }
                }
                """, StandardCharsets.UTF_8);

        ConfigRepository repository = new ConfigRepository();
        new ConfigStore(file.toFile()).load(repository);

        assertTrue(repository.isKnifeDurabilityEnabled());
        assertTrue(repository.getSheriffCountDivisor() == 9);
        assertEquals(73, repository.getBlackoutGlobalCooldownSeconds());
        assertTrue(Files.isRegularFile(file), "backup should be restored as the live primary");
    }
}
