#version 150
#moj_import <light.glsl>
#moj_import <fog.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 SceneModelMat;
uniform int FogShape;
out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
float axisShade(vec3 n) {
    return clamp(n.x*n.x*0.6 + n.z*n.z*0.8 + n.y*n.y*(n.y >= 0.0 ? 1.0 : 0.5), 0.5, 1.0);
}
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexDistance = fog_distance((SceneModelMat * vec4(Position, 1.0)).xyz, FogShape);
    // Low light-coordinate bits carry the original diffuse factor. The lightmap
    // still receives its exact captured coordinates, with those metadata bits removed.
    int shadeCode = UV2.x & 15;
    float diffuse = 1.0;
    if (shadeCode > 0) {
        vec3 localNormal = dot(Normal, Normal) > 0.00001 ? normalize(Normal) : vec3(0.0, 1.0, 0.0);
        vec3 worldNormal = normalize(mat3(SceneModelMat) * localNormal);
        float baked = shadeCode == 1 ? 0.5 : shadeCode == 2 ? 0.6 : shadeCode == 3 ? 0.8
                    : shadeCode == 4 ? 1.0 : axisShade(localNormal);
        diffuse = axisShade(worldNormal) / baked;
    }
    vertexColor = Color * minecraft_sample_lightmap(Sampler2, ivec2(UV2.x & ~15, UV2.y));
    vertexColor.rgb *= diffuse;
    texCoord0 = UV0;
}
