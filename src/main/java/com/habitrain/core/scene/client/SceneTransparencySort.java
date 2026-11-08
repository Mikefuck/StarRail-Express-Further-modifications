package com.habitrain.core.scene.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/** Retains CPU primitive centers so camera movement only needs a new index buffer. */
final class SceneTransparencySort {
    private final MeshData.SortState quads;
    private final Vector3f[] triangles;
    private final VertexFormat.IndexType indexType;
    private final int indexBytes;

    private SceneTransparencySort(MeshData.SortState quads, Vector3f[] triangles,
                                  VertexFormat.IndexType indexType, int indexBytes) {
        this.quads = quads;
        this.triangles = triangles;
        this.indexType = indexType;
        this.indexBytes = indexBytes;
    }

    static SceneTransparencySort capture(MeshData data, ByteBufferBuilder scratch, VertexSorting initial) {
        var draw = data.drawState();
        int bytes = Math.multiplyExact(draw.indexCount(), draw.indexType().bytes);
        if (draw.mode() == VertexFormat.Mode.QUADS) {
            return new SceneTransparencySort(data.sortQuads(scratch, initial), null, draw.indexType(), bytes);
        }
        if (draw.mode() != VertexFormat.Mode.TRIANGLES) return null;
        if (draw.vertexCount() % 3 != 0) throw new IllegalArgumentException("Incomplete translucent triangle");
        int stride = draw.format().getVertexSize();
        var vertices = data.vertexBuffer();
        Vector3f[] centers = new Vector3f[draw.vertexCount() / 3];
        for (int i = 0; i < centers.length; i++) {
            int a = vertices.position() + i * 3 * stride;
            centers[i] = new Vector3f(
                    (float) (((double) vertices.getFloat(a) + vertices.getFloat(a + stride)
                            + vertices.getFloat(a + 2 * stride)) / 3.0),
                    (float) (((double) vertices.getFloat(a + 4) + vertices.getFloat(a + stride + 4)
                            + vertices.getFloat(a + 2 * stride + 4)) / 3.0),
                    (float) (((double) vertices.getFloat(a + 8) + vertices.getFloat(a + stride + 8)
                            + vertices.getFloat(a + 2 * stride + 8)) / 3.0));
        }
        return new SceneTransparencySort(null, centers, draw.indexType(), bytes);
    }

    ByteBufferBuilder.Result buildSortedIndexBuffer(ByteBufferBuilder scratch, VertexSorting sorting) {
        if (quads != null) return quads.buildSortedIndexBuffer(scratch, sorting);
        long cursor = scratch.reserve(indexBytes);
        for (int triangle : sorting.sort(triangles)) {
            for (int vertex = 0; vertex < 3; vertex++) {
                int index = triangle * 3 + vertex;
                if (indexType == VertexFormat.IndexType.SHORT) MemoryUtil.memPutShort(cursor, (short) index);
                else MemoryUtil.memPutInt(cursor, index);
                cursor += indexType.bytes;
            }
        }
        return scratch.build();
    }

    int indexBytes() {
        return indexBytes;
    }
}
