package com.habitrain.core.scene.client;

import com.habitrain.core.scene.model.ScenePublishPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SceneCompatibilityReportPolicyTest {

    @Test
    void strictOnlyFailureCanBeRetriedWithoutRejectingTheUsableMesh() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(4, 2, 1), ResourceLocation.parse("custommod:block_entity"), "CustomModel",
                null, 0, true, false,
                SceneCompatibilityReport.IssueType.MISSING_ADAPTER,
                "dynamic layer unavailable", 0, null, 15, 0));

        SceneRenderRuntime.StagingInspectionResult result =
                new SceneRenderRuntime.StagingInspectionResult(
                        "hash", true, false, ScenePublishPolicy.STRICT, report);

        assertTrue(result.isPolicyOnlyFailure());
        assertFalse(report.isCompatible(ScenePublishPolicy.STRICT));
        assertTrue(report.isCompatible(ScenePublishPolicy.SKIP_AND_WARN));
    }

    @Test
    void severeMaterialFailureCannotBeRetriedUnderSkipAndWarn() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(0, 0, 0), ResourceLocation.parse("custommod:missing"), "MissingModel",
                null, 0, false, false,
                SceneCompatibilityReport.IssueType.MISSING_TEXTURE,
                "missing sprite", 0, null, 15, 0));

        SceneRenderRuntime.StagingInspectionResult result =
                new SceneRenderRuntime.StagingInspectionResult(
                        "hash", true, false, ScenePublishPolicy.STRICT, report);

        assertFalse(result.isPolicyOnlyFailure());
        assertFalse(report.isCompatible(ScenePublishPolicy.SKIP_AND_WARN));
    }

    @Test
    public void testStrictVsSkipAndWarnBlockingIssues() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();

        // 1. Normal block
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(0, 0, 0), ResourceLocation.parse("minecraft:stone"), "StoneModel",
                null, 0, false, false,
                SceneCompatibilityReport.IssueType.NONE, "Normal stone", 24, null, 15, 15
        ));

        // 2. Missing adapter block (e.g. third-party polygon block)
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(1, 0, 0), ResourceLocation.parse("custommod:gear"), "GearModel",
                null, 0, true, false,
                SceneCompatibilityReport.IssueType.MISSING_ADAPTER, "Missing adapter", 0, null, 15, 15
        ));

        // Under STRICT policy: MISSING_ADAPTER is blocking
        assertTrue(report.hasBlockingIssues(ScenePublishPolicy.STRICT));
        assertEquals(1, report.getBlockingIssues(ScenePublishPolicy.STRICT).size());
        assertFalse(report.isCompatible(ScenePublishPolicy.STRICT));

        // Under SKIP_AND_WARN policy: MISSING_ADAPTER is NOT blocking
        assertFalse(report.hasBlockingIssues(ScenePublishPolicy.SKIP_AND_WARN));
        assertEquals(0, report.getBlockingIssues(ScenePublishPolicy.SKIP_AND_WARN).size());
        assertTrue(report.isCompatible(ScenePublishPolicy.SKIP_AND_WARN));

        // 3. Now add a severe missing texture issue
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(2, 0, 0), ResourceLocation.parse("custommod:broken"), "BrokenModel",
                null, 0, false, false,
                SceneCompatibilityReport.IssueType.MISSING_TEXTURE, "Missing texture", 0, null, 15, 15
        ));

        // Under BOTH policies: MISSING_TEXTURE is always blocking! Never allow missing purple/black textures!
        assertTrue(report.hasBlockingIssues(ScenePublishPolicy.STRICT));
        assertEquals(2, report.getBlockingIssues(ScenePublishPolicy.STRICT).size());

        assertTrue(report.hasBlockingIssues(ScenePublishPolicy.SKIP_AND_WARN));
        assertEquals(1, report.getBlockingIssues(ScenePublishPolicy.SKIP_AND_WARN).size());
        assertFalse(report.isCompatible(ScenePublishPolicy.SKIP_AND_WARN));
    }

    @Test
    public void testCompatibilityPercentagesAndCounts() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();
        assertEquals(100.0, report.compatibilityPercentage(ScenePublishPolicy.STRICT), 0.001);

        report.addEntry(new SceneCompatibilityReport.Entry(
                BlockPos.ZERO, ResourceLocation.parse("minecraft:stone"), null, null, 0,
                false, false, SceneCompatibilityReport.IssueType.NONE, "", 0, null, 0, 0
        ));
        report.addEntry(new SceneCompatibilityReport.Entry(
                new BlockPos(1, 0, 0), ResourceLocation.parse("custommod:machine"), null, null, 0,
                true, false, SceneCompatibilityReport.IssueType.MISSING_ADAPTER, "", 0, null, 0, 0
        ));

        assertEquals(2, report.totalBlocks());
        assertEquals(1, report.normalBlocks());
        assertEquals(1, report.warningBlocks());
        assertEquals(0, report.severeBlocks());

        // In STRICT: 1 blocking out of 2 -> 50%
        assertEquals(50.0, report.compatibilityPercentage(ScenePublishPolicy.STRICT), 0.001);

        // In SKIP_AND_WARN: 0 blocking out of 2 -> 100%
        assertEquals(100.0, report.compatibilityPercentage(ScenePublishPolicy.SKIP_AND_WARN), 0.001);

        String summary = report.toSummaryString(ScenePublishPolicy.SKIP_AND_WARN);
        assertTrue(summary.contains("rate=100.0%"));
    }

    @Test
    public void duplicateDiagnosticsDoNotInflateBlockCounts() {
        SceneCompatibilityReport report = new SceneCompatibilityReport();
        BlockPos position = new BlockPos(4, 5, 6);
        report.addEntry(new SceneCompatibilityReport.Entry(
                position, ResourceLocation.parse("custommod:machine"), null, null, 0,
                true, false, SceneCompatibilityReport.IssueType.MISSING_ADAPTER,
                "Missing adapter", 0, null, 0, 0));
        report.addEntry(new SceneCompatibilityReport.Entry(
                position, ResourceLocation.parse("custommod:machine"), null, null, 0,
                true, false, SceneCompatibilityReport.IssueType.NONE,
                "Static fallback", 0, null, 0, 0));

        assertEquals(1, report.totalBlocks());
        assertEquals(0, report.normalBlocks(), "a warned block must not also be counted as normal");
        assertEquals(1, report.warningBlocks());
        assertEquals(0.0, report.compatibilityPercentage(ScenePublishPolicy.STRICT), 0.001);
        assertEquals(100.0, report.compatibilityPercentage(ScenePublishPolicy.SKIP_AND_WARN), 0.001);
    }
}
