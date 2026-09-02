package com.habitrain.core.scene.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

import java.util.Objects;

/**
 * 场景源模板包围盒定义。
 * <p>
 * 内部统一采用 min（包含）与 maxExclusive（不包含）形式表达。
 */
public final class SceneBounds {
    public static final SceneBounds EMPTY = new SceneBounds(0, 0, 0, 0, 0, 0);

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public SceneBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            this.minX = Math.min(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxX = Math.max(minX, maxX);
            this.maxY = Math.max(minY, maxY);
            this.maxZ = Math.max(minZ, maxZ);
        } else {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    public static SceneBounds fromPoints(BlockPos p1, BlockPos p2) {
        if (p1 == null || p2 == null) return EMPTY;
        int minX = Math.min(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxX = Math.max(p1.getX(), p2.getX()) + 1;
        int maxY = Math.max(p1.getY(), p2.getY()) + 1;
        int maxZ = Math.max(p1.getZ(), p2.getZ()) + 1;
        return new SceneBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    public int sizeX() { return maxX - minX; }
    public int sizeY() { return maxY - minY; }
    public int sizeZ() { return maxZ - minZ; }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public boolean isEmpty() {
        return sizeX() <= 0 || sizeY() <= 0 || sizeZ() <= 0;
    }

    public int minSectionX() { return SectionPos.blockToSectionCoord(minX); }
    public int minSectionY() { return SectionPos.blockToSectionCoord(minY); }
    public int minSectionZ() { return SectionPos.blockToSectionCoord(minZ); }

    public int maxSectionX() { return isEmpty() ? minSectionX() : SectionPos.blockToSectionCoord(maxX - 1); }
    public int maxSectionY() { return isEmpty() ? minSectionY() : SectionPos.blockToSectionCoord(maxY - 1); }
    public int maxSectionZ() { return isEmpty() ? minSectionZ() : SectionPos.blockToSectionCoord(maxZ - 1); }

    public int sectionCountX() { return isEmpty() ? 0 : maxSectionX() - minSectionX() + 1; }
    public int sectionCountY() { return isEmpty() ? 0 : maxSectionY() - minSectionY() + 1; }
    public int sectionCountZ() { return isEmpty() ? 0 : maxSectionZ() - minSectionZ() + 1; }

    public int sectionsX() { return sectionCountX(); }
    public int sectionsY() { return sectionCountY(); }
    public int sectionsZ() { return sectionCountZ(); }

    public int totalSections() {
        return sectionCountX() * sectionCountY() * sectionCountZ();
    }

    public AABB toAABB() {
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x < maxX
                && y >= minY && y < maxY
                && z >= minZ && z < maxZ;
    }

    public boolean contains(BlockPos pos) {
        if (pos == null) return false;
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        JsonArray minArr = new JsonArray();
        minArr.add(minX);
        minArr.add(minY);
        minArr.add(minZ);
        json.add("min", minArr);

        JsonArray maxArr = new JsonArray();
        maxArr.add(maxX);
        maxArr.add(maxY);
        maxArr.add(maxZ);
        json.add("maxExclusive", maxArr);
        return json;
    }

    public static SceneBounds fromJson(JsonObject json) {
        if (json == null) return EMPTY;
        int minX = 0, minY = 0, minZ = 0;
        int maxX = 0, maxY = 0, maxZ = 0;

        if (json.has("min") && json.get("min").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("min");
            if (arr.size() >= 3) {
                minX = arr.get(0).getAsInt();
                minY = arr.get(1).getAsInt();
                minZ = arr.get(2).getAsInt();
            }
        }
        if (json.has("maxExclusive") && json.get("maxExclusive").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("maxExclusive");
            if (arr.size() >= 3) {
                maxX = arr.get(0).getAsInt();
                maxY = arr.get(1).getAsInt();
                maxZ = arr.get(2).getAsInt();
            }
        }
        return new SceneBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneBounds other)) return false;
        return minX == other.minX && minY == other.minY && minZ == other.minZ
                && maxX == other.maxX && maxY == other.maxY && maxZ == other.maxZ;
    }

    @Override
    public int hashCode() {
        return Objects.hash(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public String toString() {
        return "SceneBounds[" + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + "]";
    }
}
