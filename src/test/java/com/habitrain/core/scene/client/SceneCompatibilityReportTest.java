package com.habitrain.core.scene.client;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SceneCompatibilityReportTest {

    @Test
    public void testEmptyReportIsCompatible() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();
        assertTrue(report.isCompatible());
        assertFalse(report.hasBlockingIssues());
        assertEquals(0, report.totalBlocks());
        assertEquals(0, report.adaptedBlocks());
        assertTrue(report.getIssues().isEmpty());
    }

    @Test
    public void testReportSuccessfulAdaptation() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();
        ResourceLocation blockId = ResourceLocation.parse("mymod:custom_block");
        ResourceLocation adapterId = ResourceLocation.parse("mymod:custom_adapter");

        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(1, 2, 3),
                blockId,
                "CustomBlockModel",
                adapterId,
                1,
                true,
                true,
                SceneCompatibilityReport.IssueType.NONE,
                "Success",
                24,
                SceneMaterialKey.SOLID,
                0,
                15
        ));

        assertTrue(report.isCompatible());
        assertFalse(report.hasBlockingIssues());
        assertEquals(1, report.totalBlocks());
        assertEquals(1, report.adaptedBlocks());
        assertEquals(0, report.getIssues().size());
    }

    @Test
    public void testReportBlockingIssues() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();

        // Add non-blocking skipped entry
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(0, 0, 0),
                ResourceLocation.parse("test:skipped_block"),
                "SkippedModel",
                null, 0, false, false,
                SceneCompatibilityReport.IssueType.SKIPPED,
                "Skipped intentionally", 0, null, 0, 15
        ));
        assertFalse(report.hasBlockingIssues());
        assertTrue(report.isCompatible(), "SKIPPED is not considered an error issue");

        // Add missing texture blocking issue
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(1, 1, 1),
                ResourceLocation.parse("test:missing_texture_block"),
                "MissingTexModel",
                null, 0, false, false,
                SceneCompatibilityReport.IssueType.MISSING_TEXTURE,
                "Missing texture", 0, null, 0, 15
        ));
        assertFalse(report.isCompatible());
        assertTrue(report.hasBlockingIssues());
        assertEquals(1, report.getIssues().size());
        assertEquals(1, report.getIssuesByType(SceneCompatibilityReport.IssueType.MISSING_TEXTURE).size());

        // Add missing adapter blocking issue
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(2, 2, 2),
                ResourceLocation.parse("test:tile_entity_block"),
                "TileEntityModel",
                null, 0, true, false,
                SceneCompatibilityReport.IssueType.MISSING_ADAPTER,
                "Missing adapter for block entity", 0, null, 0, 15
        ));
        assertEquals(2, report.getIssues().size());
        assertEquals(2, report.getBlockingIssues().size());
        assertEquals(1, report.getIssuesByType(SceneCompatibilityReport.IssueType.MISSING_ADAPTER).size());

        // Check summary string format
        String summary = report.toSummaryString();
        assertTrue(summary.contains("issues=2"));
        assertTrue(summary.contains("blocking=2"));
        assertTrue(summary.contains("compatible=false"));
    }
}
