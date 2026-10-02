#version 150

// 结算纪念车票的贴屏着色器：离屏画布上的车票 → 做旧（泛黄、焦边、水渍、折痕、霉斑）→ 燃烧（焦黄、焦黑、火线、烧穿）。
// 画布内容为预乘 alpha；输出同样是预乘 alpha（混合 ONE, ONE_MINUS_SRC_ALPHA），alpha 为 0 而颜色非 0 的像素即纯加光。
// 顶点色 r = 漫反射 / 2（平放为 0.5），g = 高光，a = 不透明度。
// 车厢灯光：Lamp 是票面上方一盏吊灯（票面局部坐标的位置、衰减、强度），Exposure 是整体亮度（闪烁）。

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform vec4 TicketRect;   // 纹理坐标下车票的 (u0, v0, u1, v1)；v0 对应票面上缘
uniform vec2 TicketSize;   // 车票宽高（GUI 像素），用于各向同性的噪声与距离
uniform float Age;         // 0..1 做旧
uniform float Burn;        // 0..1 燃烧进度，0 时不燃烧
uniform float Front;       // 燃烧前沿在燃烧场里的位置（TicketFire.front）
uniform float Time;        // 秒，火苗闪烁
uniform float Seed;
uniform vec4 Lamp;         // (x, y, falloff, strength)，x/y 为票面局部坐标
uniform float Exposure;    // 灯光整体亮度，1 为正常

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// ---- 噪声：与 TicketFire.java 的 CPU 版本逐行对应 ----

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * noise(p);
        p = p * 2.03 + vec2(17.1, 9.7);
        amp *= 0.5;
    }
    return sum / 0.9375;
}

// 燃烧场：两处底边起火点（右侧稍晚），火势向上蔓延更快，边缘由噪声撕得参差不齐。
float burnField(vec2 q, float aspect) {
    vec2 d1 = (q - vec2(aspect * 0.18, 1.06)) * vec2(1.0, 0.72);
    vec2 d2 = (q - vec2(aspect * 0.90, 1.04)) * vec2(1.0, 0.72);
    float r = min(length(d1), length(d2) + 0.30);
    return r + (fbm(q * 3.0 + vec2(Seed, Seed * 0.7)) - 0.5) * 0.5
             + (noise(q * 11.0 + vec2(Seed * 1.3, 4.0)) - 0.5) * 0.06;
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    vec2 local = (texCoord0 - TicketRect.xy) / (TicketRect.zw - TicketRect.xy);
    float aspect = TicketSize.x / TicketSize.y;
    vec2 q = vec2(local.x * aspect, local.y);
    float paper = tex.a;
    vec3 col = paper > 0.001 ? tex.rgb / paper : vec3(0.0);

    if (Age > 0.0) {
        // 斑驳地渐次泛黄：不同区域先后变旧
        float patchy = fbm(q * 1.7 + vec2(Seed * 3.1, 2.0));
        float a = clamp(Age * 1.6 - patchy * 0.6, 0.0, 1.0);
        float lum = dot(col, vec3(0.299, 0.587, 0.114));
        vec3 sepia = vec3(lum * 1.08 + 0.10, lum * 0.90 + 0.07, lum * 0.62 + 0.035);
        col = mix(col, sepia, a * 0.82);
        // 纸纤维
        col *= 1.0 + (noise(q * vec2(150.0, 40.0)) - 0.5) * 0.16 * a;
        // 边缘泛黄发焦
        vec2 ed = min(local, 1.0 - local) * vec2(aspect, 1.0);
        float rim = 1.0 - smoothstep(0.0, 0.05 + 0.10 * fbm(q * 4.0 + vec2(7.0, 7.0)), min(ed.x, ed.y));
        col = mix(col, col * vec3(0.50, 0.36, 0.22), rim * a * 0.9);
        // 水渍圈：少而大的几块，边缘一圈更深
        float s = fbm(q * 1.3 + vec2(Seed * 5.3, 11.0));
        float ring = 1.0 - smoothstep(0.0, 0.016, abs(s - 0.68));
        float pool = smoothstep(0.68, 0.78, s);
        col = mix(col, col * vec3(0.82, 0.68, 0.50), (pool * 0.22 + ring * 0.4) * a);
        // 对折折痕：一横一竖，磨掉油墨处发白
        float fx = abs(q.x - aspect * 0.5 + (noise(vec2(q.y * 5.0, 3.0)) - 0.5) * 0.012);
        float fy = abs(q.y - 0.5 + (noise(vec2(q.x * 5.0, 9.0)) - 0.5) * 0.012);
        float fold = max(exp(-fx * 300.0), exp(-fy * 300.0));
        col = mix(col, col * 1.25 + vec3(0.06, 0.05, 0.03), fold * 0.45 * a);
        // 霉斑
        float fox = smoothstep(0.82, 0.9, noise(q * 34.0 + vec2(Seed * 2.0, 5.0)));
        col = mix(col, vec3(0.32, 0.20, 0.10), fox * 0.35 * a);
    }

    vec3 glow = vec3(0.0);
    if (Burn > 0.0) {
        float d = burnField(q, aspect) - Front;
        float flick = 0.7 + 0.3 * noise(q * 22.0 + vec2(Time * 1.3, -Time * 4.0));
        if (d < 0.0) {
            // 已烧穿：只留一圈向内衰减的余晖（预乘 alpha 为 0，纯加光）
            glow = vec3(1.0, 0.42, 0.10) * exp(d * 45.0) * 0.55 * flick * paper;
            paper = 0.0;
        } else {
            float ember = 1.0 - smoothstep(0.0, 0.016, d);
            float charred = 1.0 - smoothstep(0.004, 0.075, d);
            float scorch = 1.0 - smoothstep(0.02, 0.22, d);
            col = mix(col, col * vec3(0.66, 0.46, 0.28), scorch * 0.75);
            col = mix(col, vec3(0.055, 0.035, 0.022), charred * 0.93);
            col += vec3(1.0, 0.32, 0.06) * (charred - ember) * 0.35 * flick;
            vec3 fire = mix(vec3(1.0, 0.36, 0.06), vec3(1.0, 0.88, 0.55), ember * ember);
            col = mix(col, fire * (0.85 + 0.3 * flick), ember);
        }
    }

    // 纸面弯折的明暗与高光（高光是冷色的漫射光泽，烧焦处没有）
    float diffuse = vertexColor.r * 2.0;
    float spec = vertexColor.g * (1.0 - smoothstep(0.0, 0.6, Burn));
    col = col * diffuse + vec3(0.55, 0.62, 0.80) * spec * 0.55;
    // 吊灯：离灯越远越暗；整体亮度随闪烁变化
    vec2 dl = (local - Lamp.xy) * vec2(aspect, 1.0);
    float lamp = mix(1.0, 1.0 / (1.0 + dot(dl, dl) * Lamp.z), Lamp.w);
    col *= lamp * Exposure;

    float a = vertexColor.a;
    vec3 rgb = col * paper * a + glow * a;
    float alpha = paper * a;
    if (alpha < 0.002 && max(rgb.r, max(rgb.g, rgb.b)) < 0.002) {
        discard;
    }
    fragColor = vec4(rgb, alpha) * ColorModulator;
}
