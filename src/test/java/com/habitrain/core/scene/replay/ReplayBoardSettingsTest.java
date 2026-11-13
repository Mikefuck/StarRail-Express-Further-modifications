package com.habitrain.core.scene.replay;

import com.habitrain.core.network.replay.ReplayBoardSettingsPackets;
import com.habitrain.core.scene.block.ReplayBoardSettings;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayBoardSettingsTest {
    private static final float MAP_BASE = 0.239f;

    @Test void oldWorldMarkersUseDefaultsAndTablesKeepIndependentValues() {
        assertEquals(ReplayBoardSettings.DEFAULT, ReplayBoardSettings.load(new CompoundTag()));
        var first = new ReplayBoardSettings(12, 3.5f, -4, 6, 75);
        var second = new ReplayBoardSettings(2, 0.5f, 0, -2, -90);
        CompoundTag storedFirst = first.save(), storedSecond = second.save();
        assertEquals(first, ReplayBoardSettings.load(storedFirst));
        assertEquals(second, ReplayBoardSettings.load(storedSecond));
        storedFirst.putFloat("MapSize", 18);
        assertEquals(second, ReplayBoardSettings.load(storedSecond));
        assertEquals(12, first.mapSize());
    }

    @Test void damagedSavedValuesFallBackIndividually() {
        CompoundTag data = new ReplayBoardSettings(8, 2, 5, -3, 90).save();
        data.putFloat("MapSize", Float.NaN);
        data.putFloat("Height", Float.POSITIVE_INFINITY);
        data.putString("OffsetX", "8");
        data.putFloat("Rotation", 999);
        assertEquals(new ReplayBoardSettings(6.25f, 0.25f, 0, -3, 0), ReplayBoardSettings.load(data));
    }

    @Test void invalidOrUnboundedNetworkValuesAreRejected() {
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0, 32.01f}) {
            assertThrows(IllegalArgumentException.class, () -> new ReplayBoardSettings(value, 1, 0, 0, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new ReplayBoardSettings(6, -0.01f, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReplayBoardSettings(6, 16.01f, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReplayBoardSettings(6, 1, 16.01f, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReplayBoardSettings(6, 1, 0, -16.01f, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReplayBoardSettings(6, 1, 0, 0, 180.01f));
        assertDoesNotThrow(() -> new ReplayBoardSettings(1, 0, -16, 16, -180));
        assertDoesNotThrow(() -> new ReplayBoardSettings(32, 16, 16, -16, 180));
    }

    @Test void saveAndAcknowledgementRoundTripDimensionPositionAndAllSettings() {
        var dim = ResourceLocation.withDefaultNamespace("the_nether");
        var pos = new BlockPos(-123, 78, 456);
        var request = new ReplayBoardSettingsPackets.Save(dim, pos, new ReplayBoardSettings(9.75f, 2, -3, 4, 135));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ReplayBoardSettingsPackets.Save.CODEC.encode(buf, request);
            assertEquals(request, ReplayBoardSettingsPackets.Save.CODEC.decode(buf));
            buf.clear();
            var result = new ReplayBoardSettingsPackets.Result(dim, pos, false);
            ReplayBoardSettingsPackets.Result.CODEC.encode(buf, result);
            assertEquals(result, ReplayBoardSettingsPackets.Result.CODEC.decode(buf));
        } finally { buf.release(); }
    }

    @Test void malformedWireSettingsCannotBypassTheValueValidation() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeResourceLocation(ResourceLocation.withDefaultNamespace("overworld"));
            buf.writeBlockPos(BlockPos.ZERO);
            buf.writeFloat(Float.NaN); buf.writeFloat(1); buf.writeFloat(0); buf.writeFloat(0); buf.writeFloat(0);
            assertThrows(IllegalArgumentException.class, () -> ReplayBoardSettingsPackets.Save.CODEC.decode(buf));
        } finally { buf.release(); }
    }

    @Test void mapBottomHeightIsIndependentOfScaleRotationAndTableFacing() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (float size : new float[]{1, 6.25f, 32}) {
                var table = new Matrix4f().translate(10.5f, 70, -2.5f).rotateY((float) Math.toRadians(-facing.toYRot()));
                var settings = new ReplayBoardSettings(size, 4.5f, 3, -2, 75);
                Vector3f expected = table.transformPosition(3, 4.5f, -2, new Vector3f());
                Vector3f actual = ReplayBoardPlacement.displayMatrix(table, settings, MAP_BASE)
                        .transformPosition(0, MAP_BASE, 0, new Vector3f());
                assertTrue(expected.distance(actual) < 0.0001, facing + " at size " + size);
                assertEquals(70, table.m31(), "building the display must not mutate the tabletop anchor");
            }
        }
    }

    @Test void longEdgeIsMeasuredInBlocksWhileAllAxesKeepTheirProportions() {
        var settings = new ReplayBoardSettings(10, 3, 0, 0, 45);
        Matrix4f display = ReplayBoardPlacement.displayMatrix(new Matrix4f(), settings, MAP_BASE);
        Vector3f a = display.transformPosition(-3.125f, MAP_BASE, 0, new Vector3f());
        Vector3f b = display.transformPosition(3.125f, MAP_BASE, 0, new Vector3f());
        assertEquals(10, a.distance(b), 0.0001);
        Vector3f mapPoint = display.transformPosition(1, MAP_BASE + 0.5f, -1, new Vector3f());
        Vector3f bottomCenter = display.transformPosition(0, MAP_BASE, 0, new Vector3f());
        assertEquals(1.5f * settings.scale(), bottomCenter.distance(mapPoint), 0.0001);
        assertEquals(3 + 0.5f * settings.scale(), mapPoint.y, 0.0001);
        Vector3f depthA = display.transformPosition(0, MAP_BASE, -1, new Vector3f());
        Vector3f depthB = display.transformPosition(0, MAP_BASE, 1, new Vector3f());
        assertEquals(6.25f / 2, a.distance(b) / depthA.distance(depthB), 0.0001);
    }
}
