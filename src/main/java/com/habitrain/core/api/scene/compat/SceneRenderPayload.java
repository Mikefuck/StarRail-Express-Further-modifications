package com.habitrain.core.api.scene.compat;

import com.habitrain.core.scene.SceneLimits;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.io.*;
import java.util.*;

/**
 * 移动场景第三方方块视觉数据载荷（受限白名单、单方块 256 KiB、整份资产 32 MiB）。
 * <p>
 * 仅允许包含重建静态模型所需的几何体与受限视觉参数，严格禁止携带容器物品、战利品表、命令等非视觉数据。
 */
public final class SceneRenderPayload {
    public enum PrimitiveType {
        QUADS(4),
        TRIANGLES(3);

        private final int verticesPerPolygon;

        PrimitiveType(int verticesPerPolygon) {
            this.verticesPerPolygon = verticesPerPolygon;
        }

        public int verticesPerPolygon() {
            return verticesPerPolygon;
        }
    }

    public record VisualVertex(
            float x, float y, float z,
            float u, float v,
            int color,
            int light,
            float nx, float ny, float nz
    ) {
        public static VisualVertex of(float x, float y, float z, float u, float v, int color) {
            return new VisualVertex(x, y, z, u, v, color, -1, 0f, 1f, 0f);
        }

        public static VisualVertex of(float x, float y, float z, float u, float v, int color, int light) {
            return new VisualVertex(x, y, z, u, v, color, light, 0f, 1f, 0f);
        }
    }

    public record VisualMesh(
            ResourceLocation texture,
            PrimitiveType primitive,
            List<VisualVertex> vertices
    ) {
        public VisualMesh {
            if (texture == null) throw new IllegalArgumentException("texture cannot be null");
            if (primitive == null) primitive = PrimitiveType.QUADS;
            vertices = vertices != null ? List.copyOf(vertices) : List.of();
        }
    }

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "items", "inventory", "loottable", "loottableseed",
            "command", "owner", "skullowner", "customname", "lock", "recipes"
    );

    private final List<VisualMesh> meshes;
    private final CompoundTag customData;

    public SceneRenderPayload(List<VisualMesh> meshes, CompoundTag customData) {
        this.meshes = meshes != null ? List.copyOf(meshes) : List.of();
        this.customData = customData != null ? customData.copy() : null;
    }

    public List<VisualMesh> meshes() {
        return meshes;
    }

    public CompoundTag customData() {
        return customData != null ? customData.copy() : null;
    }

    public boolean isEmpty() {
        return meshes.isEmpty() && (customData == null || customData.isEmpty());
    }

    public void validate() throws IOException {
        int totalVertices = 0;
        if (meshes != null) {
            if (meshes.size() > SceneLimits.MAX_MATERIALS_PER_BLOCK) {
                throw new IOException("Too many materials in payload: " + meshes.size() + " > " + SceneLimits.MAX_MATERIALS_PER_BLOCK);
            }
            for (VisualMesh mesh : meshes) {
                if (mesh.texture().toString().length() > SceneLimits.MAX_PAYLOAD_STRING_LENGTH) {
                    throw new IOException("Texture identifier exceeds maximum length: " + mesh.texture());
                }
                int vertexCount = mesh.vertices().size();
                if (vertexCount <= 0 || vertexCount % mesh.primitive().verticesPerPolygon() != 0) {
                    throw new IOException("Vertex count " + vertexCount + " is not aligned to "
                            + mesh.primitive() + " polygons");
                }
                for (VisualVertex vertex : mesh.vertices()) {
                    validateVertex(vertex);
                }
                totalVertices += vertexCount;
            }
            if (totalVertices > SceneLimits.MAX_VERTICES_PER_BLOCK) {
                throw new IOException("Total vertices in payload exceeds limit: " + totalVertices + " > " + SceneLimits.MAX_VERTICES_PER_BLOCK);
            }
        }
        if (customData != null) {
            validateCompoundTag(customData, 0);
        }
        byte[] bytes = toByteArray();
        if (bytes.length > SceneLimits.MAX_BLOCK_PAYLOAD_BYTES) {
            throw new IOException("Payload encoded size exceeds 256 KiB: " + bytes.length + " bytes");
        }
    }

    private static void validateCompoundTag(CompoundTag tag, int depth) throws IOException {
        if (depth > SceneLimits.MAX_PAYLOAD_NBT_DEPTH) {
            throw new IOException("NBT payload depth exceeds limit: " + depth + " > " + SceneLimits.MAX_PAYLOAD_NBT_DEPTH);
        }
        for (String key : tag.getAllKeys()) {
            if (FORBIDDEN_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new IOException("Forbidden security key found in visual payload NBT: " + key);
            }
            if (key.length() > SceneLimits.MAX_PAYLOAD_STRING_LENGTH) {
                throw new IOException("NBT key exceeds maximum length: " + key);
            }
            Tag child = tag.get(key);
            validateTag(child, depth + 1);
        }
    }

    private static void validateTag(Tag tag, int depth) throws IOException {
        if (tag == null) return;
        if (depth > SceneLimits.MAX_PAYLOAD_NBT_DEPTH) {
            throw new IOException("NBT payload depth exceeds limit: " + depth + " > "
                    + SceneLimits.MAX_PAYLOAD_NBT_DEPTH);
        }
        if (tag instanceof CompoundTag nested) {
            validateCompoundTag(nested, depth);
        } else if (tag instanceof net.minecraft.nbt.ListTag listTag) {
            if (listTag.size() > SceneLimits.MAX_PAYLOAD_ARRAY_LENGTH) {
                throw new IOException("NBT list exceeds maximum length");
            }
            for (int index = 0; index < listTag.size(); index++) {
                validateTag(listTag.get(index), depth + 1);
            }
        } else if (tag instanceof net.minecraft.nbt.StringTag stringTag) {
            if (stringTag.getAsString().length() > SceneLimits.MAX_PAYLOAD_STRING_LENGTH) {
                throw new IOException("NBT string value exceeds maximum length");
            }
        } else if (tag instanceof net.minecraft.nbt.ByteArrayTag bat) {
            if (bat.getAsByteArray().length > SceneLimits.MAX_PAYLOAD_ARRAY_LENGTH) {
                throw new IOException("NBT byte array exceeds maximum length");
            }
        } else if (tag instanceof net.minecraft.nbt.IntArrayTag iat) {
            if (iat.getAsIntArray().length > SceneLimits.MAX_PAYLOAD_ARRAY_LENGTH) {
                throw new IOException("NBT int array exceeds maximum length");
            }
        } else if (tag instanceof net.minecraft.nbt.LongArrayTag lat) {
            if (lat.getAsLongArray().length > SceneLimits.MAX_PAYLOAD_ARRAY_LENGTH) {
                throw new IOException("NBT long array exceeds maximum length");
            }
        }
    }

    private static void validateVertex(VisualVertex vertex) throws IOException {
        if (vertex == null) throw new IOException("Visual payload contains a null vertex");
        if (!Float.isFinite(vertex.x()) || !Float.isFinite(vertex.y()) || !Float.isFinite(vertex.z())
                || !Float.isFinite(vertex.u()) || !Float.isFinite(vertex.v())
                || !Float.isFinite(vertex.nx()) || !Float.isFinite(vertex.ny())
                || !Float.isFinite(vertex.nz())) {
            throw new IOException("Visual payload contains a non-finite vertex value");
        }
        int light = vertex.light();
        if (light < -1 || (light >= 0 && (light & ~0x00F000F0) != 0)) {
            throw new IOException("Visual payload contains an invalid packed-light value: " + light);
        }
    }

    public void encode(DataOutput out) throws IOException {
        int meshCount = meshes != null ? meshes.size() : 0;
        out.writeInt(meshCount);
        if (meshes != null) {
            for (VisualMesh mesh : meshes) {
                out.writeUTF(mesh.texture().toString());
                out.writeByte(mesh.primitive().ordinal());
                out.writeInt(mesh.vertices().size());
                for (VisualVertex v : mesh.vertices()) {
                    out.writeFloat(v.x());
                    out.writeFloat(v.y());
                    out.writeFloat(v.z());
                    out.writeFloat(v.u());
                    out.writeFloat(v.v());
                    out.writeInt(v.color());
                    out.writeInt(v.light());
                    out.writeFloat(v.nx());
                    out.writeFloat(v.ny());
                    out.writeFloat(v.nz());
                }
            }
        }
        if (customData != null) {
            out.writeBoolean(true);
            NbtIo.write(customData, out);
        } else {
            out.writeBoolean(false);
        }
    }

    public static SceneRenderPayload decode(DataInput in) throws IOException {
        int meshCount = in.readInt();
        if (meshCount < 0 || meshCount > SceneLimits.MAX_MATERIALS_PER_BLOCK) {
            throw new IOException("Invalid mesh count in payload: " + meshCount);
        }
        List<VisualMesh> meshes = new ArrayList<>(meshCount);
        int totalVertices = 0;
        for (int m = 0; m < meshCount; m++) {
            String textureStr = in.readUTF();
            if (textureStr.length() > SceneLimits.MAX_PAYLOAD_STRING_LENGTH) {
                throw new IOException("Texture string exceeds limit: " + textureStr.length());
            }
            ResourceLocation texture = ResourceLocation.tryParse(textureStr);
            if (texture == null) {
                throw new IOException("Invalid texture ResourceLocation: " + textureStr);
            }
            int primitiveOrdinal = in.readUnsignedByte();
            if (primitiveOrdinal < 0 || primitiveOrdinal >= PrimitiveType.values().length) {
                throw new IOException("Invalid primitive type ordinal: " + primitiveOrdinal);
            }
            PrimitiveType primitive = PrimitiveType.values()[primitiveOrdinal];
            int vertexCount = in.readInt();
            if (vertexCount < 0 || vertexCount > SceneLimits.MAX_VERTICES_PER_BLOCK) {
                throw new IOException("Invalid vertex count: " + vertexCount);
            }
            if (vertexCount == 0 || vertexCount % primitive.verticesPerPolygon() != 0) {
                throw new IOException("Vertex count " + vertexCount + " is not aligned to "
                        + primitive + " polygons");
            }
            totalVertices += vertexCount;
            if (totalVertices > SceneLimits.MAX_VERTICES_PER_BLOCK) {
                throw new IOException("Total vertices in payload exceeds limit: " + totalVertices);
            }
            List<VisualVertex> vertices = new ArrayList<>(vertexCount);
            for (int v = 0; v < vertexCount; v++) {
                float x = in.readFloat();
                float y = in.readFloat();
                float z = in.readFloat();
                float u = in.readFloat();
                float vCoord = in.readFloat();
                int color = in.readInt();
                int light = in.readInt();
                float nx = in.readFloat();
                float ny = in.readFloat();
                float nz = in.readFloat();
                VisualVertex vertex = new VisualVertex(x, y, z, u, vCoord, color, light, nx, ny, nz);
                validateVertex(vertex);
                vertices.add(vertex);
            }
            meshes.add(new VisualMesh(texture, primitive, vertices));
        }

        boolean hasCustomData = in.readBoolean();
        CompoundTag customData = null;
        if (hasCustomData) {
            customData = NbtIo.read(in, NbtAccounter.create(SceneLimits.MAX_BLOCK_PAYLOAD_BYTES));
            if (customData != null) {
                validateCompoundTag(customData, 0);
            }
        }
        return new SceneRenderPayload(meshes, customData);
    }

    public byte[] toByteArray() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            encode(dos);
        }
        return baos.toByteArray();
    }

    public static SceneRenderPayload fromByteArray(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return new SceneRenderPayload(Collections.emptyList(), null);
        }
        if (bytes.length > SceneLimits.MAX_BLOCK_PAYLOAD_BYTES) {
            throw new IOException("Block payload exceeds 256 KiB limit: " + bytes.length + " bytes");
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             DataInputStream dis = new DataInputStream(bais)) {
            SceneRenderPayload payload = decode(dis);
            if (dis.available() != 0) {
                throw new IOException("Trailing bytes after visual payload: " + dis.available());
            }
            payload.validate();
            return payload;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<VisualMesh> meshes = new ArrayList<>();
        private CompoundTag customData;

        public Builder addMesh(VisualMesh mesh) {
            if (mesh != null) meshes.add(mesh);
            return this;
        }

        public Builder addQuad(ResourceLocation texture, VisualVertex v0, VisualVertex v1, VisualVertex v2, VisualVertex v3) {
            meshes.add(new VisualMesh(texture, PrimitiveType.QUADS, List.of(v0, v1, v2, v3)));
            return this;
        }

        public Builder addTriangle(ResourceLocation texture, VisualVertex v0, VisualVertex v1, VisualVertex v2) {
            meshes.add(new VisualMesh(texture, PrimitiveType.TRIANGLES, List.of(v0, v1, v2)));
            return this;
        }

        public Builder customData(CompoundTag tag) {
            this.customData = tag != null ? tag.copy() : null;
            return this;
        }

        public SceneRenderPayload build() throws IOException {
            SceneRenderPayload payload = new SceneRenderPayload(meshes, customData);
            payload.validate();
            return payload;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SceneRenderPayload other)) return false;
        return Objects.equals(meshes, other.meshes) && Objects.equals(customData, other.customData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(meshes, customData);
    }
}
