#version 150
#moj_import <fog.glsl>
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float AlphaCutoff;
// Plane in mesh-local block units; the default (0,0,0,1) never discards.
uniform vec4 SceneClip;
// Source-local XZ limits of the fixed tray interior. Applies to opaque and translucent geometry.
uniform vec4 SceneBounds;
uniform int ReplayFanCount;
// Columns hold (source-local x, foot y, z, rounded-apex radius), up to 128 avatars.
uniform mat4 ReplayFanAnchors[32];
uniform vec4 ReplayFanView;
in vec3 scenePos;
in vec3 sceneNormal;
in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
bool replayCut(vec3 cell) {
    for (int i = 0; i < ReplayFanCount; ++i) {
        vec4 anchor = ReplayFanAnchors[i / 4][i % 4];
        if (cell.y <= anchor.y + 0.01) continue;
        vec2 delta = cell.xz - anchor.xz;
        if (dot(delta, delta) <= anchor.w * anchor.w) return true;
        float forward = dot(delta, ReplayFanView.xy);
        float sideways = abs(delta.x * ReplayFanView.y - delta.y * ReplayFanView.x);
        if (forward >= 0.0 && sideways <= anchor.w + forward * ReplayFanView.z) return true;
    }
    return false;
}
void main() {
    if (scenePos.x < SceneBounds.x || scenePos.z < SceneBounds.y
            || scenePos.x > SceneBounds.z || scenePos.z > SceneBounds.w) discard;
    if (dot(scenePos, SceneClip.xyz) > SceneClip.w) discard;
    if (ReplayFanCount > 0) {
        // Classify the voxel just inside this face. Opposite faces of neighbouring blocks then
        // disagree at a cut boundary, exposing the retained solid face instead of a hollow shell.
        vec3 n = dot(sceneNormal, sceneNormal) > 0.00001 ? normalize(sceneNormal) : vec3(0.0, 1.0, 0.0);
        if (replayCut(floor(scenePos - n * 0.002) + vec3(0.5))) discard;
    }
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < AlphaCutoff) discard;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
