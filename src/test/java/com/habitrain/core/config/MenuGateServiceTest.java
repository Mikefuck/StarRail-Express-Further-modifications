package com.habitrain.core.config;

import com.habitrain.core.api.MenuGateApi;
import com.habitrain.core.persist.AtomicJsonFiles;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuGateServiceTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path tmp;

    private Path file;

    @BeforeEach
    void setUp() {
        file = tmp.resolve("habitrain_menu_gate.json");
        MenuGateService.setFileForTests(file);
    }

    @AfterEach
    void tearDown() {
        MenuGateService.setFileForTests(null);
    }

    @Test
    void offlineAddWithoutUuidIsRejected() {
        assertFalse(MenuGateService.add("Steve", ""));
        assertFalse(MenuGateService.add("Steve", null));
        assertFalse(MenuGateService.add("Steve", "not-a-uuid"));
        assertTrue(MenuGateService.getAllowed().isEmpty());
        assertFalse(Files.isRegularFile(file));
    }

    @Test
    void addRequiresUuidAndWritesAtomically() throws Exception {
        assertTrue(MenuGateService.add("Steve", STEVE.toString()));
        assertEquals(1, MenuGateService.getAllowed().size());
        assertEquals(STEVE.toString(), MenuGateService.getAllowed().get(0).getUuid());
        assertTrue(Files.isRegularFile(file));
        String body = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(body.contains(STEVE.toString()));
        assertFalse(Files.exists(file.resolveSibling("habitrain_menu_gate.json.tmp")));
    }

    @Test
    void firstLoadOfCorruptFileDoesNotWriteEmptyAllowList() throws Exception {
        Files.writeString(file, "{not-json", StandardCharsets.UTF_8);
        MenuGateService.load();

        assertTrue(MenuGateService.isEnabled());
        assertTrue(MenuGateService.getAllowed().isEmpty());
        assertFalse(Files.isRegularFile(file), "坏档不得被空允许列表覆盖");
    }

    @Test
    void loadCorruptKeepsLastGoodMemoryAndDoesNotWriteEmptyList() throws Exception {
        assertTrue(MenuGateService.add("Steve", STEVE.toString()));
        Files.deleteIfExists(AtomicJsonFiles.bakPath(file));
        Files.writeString(file, "{", StandardCharsets.UTF_8);

        MenuGateService.load();

        assertEquals(1, MenuGateService.getAllowed().size());
        assertEquals("Steve", MenuGateService.getAllowed().get(0).getName());
        assertFalse(Files.isRegularFile(file), "坏档隔离后不得写回空名单");
    }

    @Test
    void saveFailureRollsBackInMemoryChange() throws Exception {
        assertTrue(MenuGateService.add("Steve", STEVE.toString()));
        Files.createDirectory(AtomicJsonFiles.bakPath(file));

        assertFalse(MenuGateService.setEnabled(false));
        assertTrue(MenuGateService.isEnabled());
        assertFalse(MenuGateService.add("Alex", "00000000-0000-0000-0000-000000000002"));
        assertEquals(1, MenuGateService.getAllowed().size());
    }

    @Test
    void isBlockedMatchesLotteryBridgeSemantics() {
        assertFalse(MenuGateService.isBlocked((ServerPlayer) null));
        assertFalse(MenuGateApi.isBlocked(null));
        assertFalse(MenuGateApi.isAllowed(null));
        assertTrue(MenuGateApi.isEnabled());

        assertFalse(MenuGateService.isBlockedOnDedicated(false, false));
        assertFalse(MenuGateService.isBlockedOnDedicated(false, true));
        assertTrue(MenuGateService.isBlockedOnDedicated(true, false));
        assertFalse(MenuGateService.isBlockedOnDedicated(true, true));

        assertTrue(MenuGateService.setEnabled(false));
        assertFalse(MenuGateApi.isEnabled());
        assertFalse(MenuGateService.isBlockedOnDedicated(true, false));
    }
}
