package com.habitrain.core.api.scene.model;

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
        int maxX = saturatedAdd(Math.max(p1.getX(), p2.getX()), 1);
        int maxY = saturatedAdd(Math.max(p1.getY(), p2.getY()), 1);
        int maxZ = saturatedAdd(Math.max(p1.getZ(), p2.getZ()), 1);
        return new SceneBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static int saturatedAdd(int a, int b) {
        long r = (long) a + (long) b;
        if (r > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (r < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) r;
    }

    private static int saturatedSub(int a, int b) {
        long r = (long) a - (long) b;
        if (r > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (r < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) r;
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    public int sizeX() { return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) maxX - (long) minX)); }
    public int sizeY() { return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) maxY - (long) minY)); }
    public int sizeZ() { return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) maxZ - (long) minZ)); }

    public long volume() {
        if (isEmpty()) return 0L;
        long sx = (long) maxX - (long) minX;
        long sy = (long) maxY - (long) minY;
        long sz = (long) maxZ - (long) minZ;
        try {
            return Math.multiplyExact(Math.multiplyExact(sx, sy), sz);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    public boolean isEmpty() {
        return maxX <= minX || maxY <= minY || maxZ <= minZ;
    }

    public SceneBounds inflate(int amount) {
        if (isEmpty() || amount <= 0) return this;
        return new SceneBounds(
                saturatedSub(minX, amount), saturatedSub(minY, amount), saturatedSub(minZ, amount),
                saturatedAdd(maxX, amount), saturatedAdd(maxY, amount), saturatedAdd(maxZ, amount));
    }

    public int minSectionX() { return SectionPos.blockToSectionCoord(minX); }
    public int minSectionY() { return SectionPos.blockToSectionCoord(minY); }
    public int minSectionZ() { return SectionPos.blockToSectionCoord(minZ); }

    public int maxSectionX() { return isEmpty() ? minSectionX() : SectionPos.blockToSectionCoord(maxX - 1); }
    public int maxSectionY() { return isEmpty() ? minSectionY() : SectionPos.blockToSectionCoord(maxY - 1); }
    public int maxSectionZ() { return isEmpty() ? minSectionZ() : SectionPos.blockToSectionCoord(maxZ - 1); }

    public int sectionCountX() { return isEmpty() ? 0 : (int) Math.min(Integer.MAX_VALUE, (long) maxSectionX() - (long) minSectionX() + 1L); }
    public int sectionCountY() { return isEmpty() ? 0 : (int) Math.min(Integer.MAX_VALUE, (long) maxSectionY() - (long) minSectionY() + 1L); }
    public int sectionCountZ() { return isEmpty() ? 0 : (int) Math.min(Integer.MAX_VALUE, (long) maxSectionZ() - (long) minSectionZ() + 1L); }

    public int sectionsX() { return sectionCountX(); }
    public int sectionsY() { return sectionCountY(); }
    public int sectionsZ() { return sectionCountZ(); }

    public int totalSections() {
        if (isEmpty()) return 0;
        long xy = (long) sectionCountX() * (long) sectionCountY();
        if (xy >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        long xyz = xy * (long) sectionCountZ();
        return (int) Math.min(Integer.MAX_VALUE, xyz);
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
                minX = getIntAt(arr, 0, 0);
                minY = getIntAt(arr, 1, 0);
                minZ = getIntAt(arr, 2, 0);
            }
        }
        if (json.has("maxExclusive") && json.get("maxExclusive").isJsonArray()) {
            JsonArray arr = json.getAsJsonArray("maxExclusive");
            if (arr.size() >= 3) {
                maxX = getIntAt(arr, 0, 0);
                maxY = getIntAt(arr, 1, 0);
                maxZ = getIntAt(arr, 2, 0);
            }
        }
        return new SceneBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static int getIntAt(JsonArray arr, int index, int fallback) {
        if (index < 0 || index >= arr.size() || arr.get(index) == null || !arr.get(index).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return arr.get(index).getAsInt();
        } catch (NumberFormatException e) {
            return fallback;
        }
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
