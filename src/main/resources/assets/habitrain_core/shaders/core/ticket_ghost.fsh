#version 150

// MVP 人像从票面角落的黑暗里浮现：自下而上烟雾状地溶解出来，先是一团只剩轮廓光的黑色剪影，
// 再像相纸显影一样慢慢透出暗淡、去色的细节；腰部以下沉进黑暗。Glitch 时整条整条地横向错位并带色散。
// 输出为预乘 alpha（混合 ONE, ONE_MINUS_SRC_ALPHA）。

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform vec4 GhostRect;   // 纹理坐标下人像区域 (u0, v0, u1, v1)；v0 对应上缘
uniform vec2 TexelSize;   // 画布单个像素的纹理坐标尺寸
uniform float Reveal;     // 0..1 自下而上浮现
uniform float Develop;    // 0..1 由剪影显影为人像
uniform float Glitch;     // 0..1 横向撕裂
uniform float Time;
uniform vec4 Tint;        // rgb 着色，a 去色程度
uniform vec4 Rim;         // rgb 轮廓光颜色，a 强度

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
               mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}

float coverage(vec2 uv) {
    float halo = 0.0;
    for (int i = 0; i < 12; i++) {
        float ang = float(i) * 0.52359878;
        vec2 dir = vec2(cos(ang), sin(ang)) * TexelSize;
        halo += texture(Sampler0, uv + dir * 3.5).a * 0.6;
        halo += texture(Sampler0, uv + dir * 9.0).a * 0.4;
    }
    return clamp(halo / 12.0 * 1.7, 0.0, 1.0);
}

void main() {
    vec2 size = GhostRect.zw - GhostRect.xy;
    vec2 local = (texCoord0 - GhostRect.xy) / size;

    // 横向撕裂：随机几条横带整体错位
    float frame = floor(Time * 22.0);
    float band = floor(local.y * 30.0);
    float torn = step(0.7, hash12(vec2(band, frame)));
    float shift = (hash12(vec2(band + 7.3, frame)) - 0.5) * 0.08 * Glitch * torn;
    vec2 uv = texCoord0 + vec2(shift * size.x, 0.0);
    vec4 tex = texture(Sampler0, uv);

    // 轮廓光：周围采样的覆盖率减去自身；撕裂时红青两色分开
    float split = 0.006 * Glitch * size.x;
    float haloR = coverage(uv + vec2(split, 0.0));
    float haloB = coverage(uv - vec2(split, 0.0));
    float halo = max(haloR, haloB);
    vec3 rim = vec3(clamp(haloR - tex.a, 0.0, 1.0) * Rim.r,
                    clamp(halo - tex.a, 0.0, 1.0) * Rim.g,
                    clamp(haloB - tex.a, 0.0, 1.0) * Rim.b);

    // 浮现：烟雾状的溶解前沿自下而上推进
    float smoke = noise(local * vec2(9.0, 14.0) + vec2(0.0, -Time * 0.8)) * 0.65
                + noise(local * vec2(23.0, 31.0) + vec2(Time * 0.3, -Time * 1.6)) * 0.35;
    float edge = mix(1.3, -0.3, Reveal);
    float shown = smoothstep(edge - 0.16, edge + 0.04, local.y + (smoke - 0.5) * 0.4);
    float front = exp(-abs(local.y + (smoke - 0.5) * 0.25 - edge) * 14.0) * (1.0 - step(0.999, Reveal));
    // 腰部以下沉进黑暗，两侧收边
    float sink = 1.0 - smoothstep(0.58, 0.97, local.y + (smoke - 0.5) * 0.12);
    float sides = smoothstep(0.0, 0.05, local.x) * (1.0 - smoothstep(0.95, 1.0, local.x)) * smoothstep(0.0, 0.03, local.y);
    float mask = shown * sink * sides;

    // 显影：先是近乎全黑的剪影，再透出暗淡、偏冷、去色的细节
    float lum = dot(tex.rgb, vec3(0.299, 0.587, 0.114));
    vec3 col = mix(tex.rgb, vec3(lum) * Tint.rgb, Tint.a);
    col *= mix(0.03, 0.78, Develop * Develop);
    col *= 0.9 + 0.1 * sin(gl_FragCoord.y * 1.3 + Time * 3.0);

    float alpha = tex.a * mask * vertexColor.a;
    vec3 rgb = col * alpha;
    rgb += rim * Rim.a * mask * vertexColor.a;
    rgb += Rim.rgb * front * max(tex.a, halo) * 0.3 * sides * vertexColor.a;
    if (alpha < 0.002 && max(rgb.r, max(rgb.g, rgb.b)) < 0.002) {
        discard;
    }
    fragColor = vec4(rgb, alpha) * ColorModulator;
}
