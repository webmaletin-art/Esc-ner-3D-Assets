package app.universal3d.inspector.render

object Shaders {
    fun pbrVertex(maxJoints: Int) = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_nrm;
layout(location = 2) in vec2 a_uv0;
layout(location = 3) in vec2 a_uv1;
layout(location = 4) in vec4 a_col;
layout(location = 5) in uvec4 a_joints;
layout(location = 6) in vec4 a_weights;
uniform mat4 u_viewProj;
uniform mat4 u_model;
uniform mat3 u_normalMat;
uniform int u_skinned;
uniform mat4 u_joints[$maxJoints];
out vec3 v_pos;
out vec3 v_nrm;
out vec2 v_uv0;
out vec2 v_uv1;
out vec4 v_col;
void main() {
    vec4 p = vec4(a_pos, 1.0);
    vec3 n = a_nrm;
    if (u_skinned != 0) {
        mat4 s = a_weights.x * u_joints[min(int(a_joints.x), $maxJoints - 1)]
               + a_weights.y * u_joints[min(int(a_joints.y), $maxJoints - 1)]
               + a_weights.z * u_joints[min(int(a_joints.z), $maxJoints - 1)]
               + a_weights.w * u_joints[min(int(a_joints.w), $maxJoints - 1)];
        p = s * p;
        n = mat3(s) * n;
    }
    vec4 w = u_model * p;
    v_pos = w.xyz;
    v_nrm = u_normalMat * n;
    v_uv0 = a_uv0;
    v_uv1 = a_uv1;
    v_col = a_col;
    gl_Position = u_viewProj * w;
}
"""

    /**
     * PBR metal-rugosidad simplificado: luz clave + relleno + ambiente hemisférico; "entorno" procedural para reflejos.
     * Banderas (u_flags): 1 base, 2 metal/rug, 4 normal, 8 oclusión, 16 emisivo, 32 color de vértice, 64 hay normales,
     * 128 base usa UV1, 256 MR UV1, 512 normal UV1, 1024 occ UV1, 2048 emisivo UV1.
     */
    const val PBR_FRAGMENT = """#version 300 es
precision highp float;
in vec3 v_pos;
in vec3 v_nrm;
in vec2 v_uv0;
in vec2 v_uv1;
in vec4 v_col;
uniform vec3 u_eye;
uniform vec4 u_baseColor;
uniform float u_metallic;
uniform float u_rough;
uniform vec3 u_emissive;
uniform float u_normalScale;
uniform float u_occStrength;
uniform float u_alphaCutoff;
uniform int u_alphaMode;
uniform int u_flags;
uniform vec3 u_tint;
uniform sampler2D u_texBase;
uniform sampler2D u_texMR;
uniform sampler2D u_texNormal;
uniform sampler2D u_texOcc;
uniform sampler2D u_texEmis;
out vec4 o_color;

const float PI = 3.14159265;

vec2 uvFor(int bit) { return ((u_flags & bit) != 0) ? v_uv1 : v_uv0; }
vec3 toLinear(vec3 c) { return pow(c, vec3(2.2)); }

vec3 envColor(vec3 d, float rough) {
    float t = d.y * 0.5 + 0.5;
    vec3 sky = vec3(0.62, 0.72, 0.88);
    vec3 horizon = vec3(0.55, 0.55, 0.55);
    vec3 ground = vec3(0.18, 0.16, 0.14);
    vec3 c = mix(ground, horizon, smoothstep(0.0, 0.5, t));
    c = mix(c, sky, smoothstep(0.5, 1.0, t));
    return mix(c, vec3(dot(c, vec3(0.333))), rough * 0.6);
}

float distGGX(float nh, float a) { float a2 = a * a; float d = nh * nh * (a2 - 1.0) + 1.0; return a2 / (PI * d * d + 1e-5); }
float geomSmith(float nv, float nl, float r) { float k = (r + 1.0) * (r + 1.0) / 8.0; return (nv / (nv * (1.0 - k) + k)) * (nl / (nl * (1.0 - k) + k)); }

void main() {
    vec4 base = u_baseColor;
    if ((u_flags & 1) != 0) { vec4 t = texture(u_texBase, uvFor(128)); base *= vec4(toLinear(t.rgb), t.a); }
    if ((u_flags & 32) != 0) base *= v_col;
    if (u_alphaMode == 1 && base.a < u_alphaCutoff) discard;
    float metallic = u_metallic; float rough = u_rough;
    if ((u_flags & 2) != 0) { vec4 mr = texture(u_texMR, uvFor(256)); rough *= mr.g; metallic *= mr.b; }
    rough = clamp(rough, 0.045, 1.0);

    vec3 pos = v_pos;
    vec3 N;
    vec3 dpx = dFdx(pos); vec3 dpy = dFdy(pos);
    if ((u_flags & 64) != 0) N = normalize(v_nrm); else N = normalize(cross(dpx, dpy));
    if (!gl_FrontFacing) N = -N;
    if ((u_flags & 4) != 0) {
        vec2 uv = uvFor(512);
        vec2 duvx = dFdx(uv); vec2 duvy = dFdy(uv);
        vec3 T = dpx * duvy.t - dpy * duvx.t;
        float det = duvx.s * duvy.t - duvy.s * duvx.t;
        if (abs(det) > 1e-12) {
            T = normalize(T - N * dot(N, T));
            vec3 B = cross(N, T) * sign(det);
            vec3 tn = texture(u_texNormal, uv).xyz * 2.0 - 1.0;
            tn.xy *= u_normalScale;
            N = normalize(mat3(T, B, N) * tn);
        }
    }
    vec3 V = normalize(u_eye - pos);
    float nv = max(dot(N, V), 1e-3);
    vec3 F0 = mix(vec3(0.04), base.rgb, metallic);
    vec3 diffuseColor = base.rgb * (1.0 - metallic);

    float occ = 1.0;
    if ((u_flags & 8) != 0) occ = 1.0 + u_occStrength * (texture(u_texOcc, uvFor(1024)).r - 1.0);

    vec3 col = vec3(0.0);
    // luces: clave (arriba-derecha de la cámara) y relleno
    vec3 camRight = normalize(cross(V, vec3(0.0, 1.0, 0.0)) + vec3(1e-4));
    vec3 L1 = normalize(V * 0.6 + vec3(0.0, 0.8, 0.0) - camRight * 0.5);
    vec3 L2 = normalize(V * 0.7 - vec3(0.0, 0.2, 0.0) + camRight * 0.7);
    for (int i = 0; i < 2; i++) {
        vec3 L = (i == 0) ? L1 : L2;
        vec3 radiance = (i == 0) ? vec3(2.6) : vec3(0.9);
        vec3 H = normalize(V + L);
        float nl = max(dot(N, L), 0.0);
        float nh = max(dot(N, H), 0.0);
        float vh = max(dot(V, H), 0.0);
        vec3 F = F0 + (1.0 - F0) * pow(1.0 - vh, 5.0);
        float D = distGGX(nh, rough * rough);
        float G = geomSmith(nv, nl, rough);
        vec3 spec = D * G * F / max(4.0 * nv * nl, 1e-3);
        col += (diffuseColor / PI * (1.0 - F) + spec) * radiance * nl;
    }
    // ambiente + reflejo de entorno procedural
    vec3 amb = envColor(N, 1.0);
    vec3 R = reflect(-V, N);
    vec3 envSpec = envColor(R, rough);
    vec3 Fr = F0 + (max(vec3(1.0 - rough), F0) - F0) * pow(1.0 - nv, 5.0);
    col += (diffuseColor * amb * 0.9 + envSpec * Fr * mix(1.0, 0.6, rough)) * occ;
    vec3 emis = u_emissive;
    if ((u_flags & 16) != 0) emis *= toLinear(texture(u_texEmis, uvFor(2048)).rgb);
    col += emis;
    col += u_tint;
    // tono (ACES aproximado) y gamma
    col = (col * (2.51 * col + 0.03)) / (col * (2.43 * col + 0.59) + 0.14);
    col = pow(clamp(col, 0.0, 1.0), vec3(1.0 / 2.2));
    o_color = vec4(col, (u_alphaMode == 2) ? base.a : 1.0);
}
"""

    fun lineVertex(maxJoints: Int) = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 a_pos;
layout(location = 5) in uvec4 a_joints;
layout(location = 6) in vec4 a_weights;
uniform mat4 u_viewProj;
uniform mat4 u_model;
uniform int u_skinned;
uniform mat4 u_joints[$maxJoints];
void main() {
    vec4 p = vec4(a_pos, 1.0);
    if (u_skinned != 0) {
        mat4 s = a_weights.x * u_joints[min(int(a_joints.x), $maxJoints - 1)]
               + a_weights.y * u_joints[min(int(a_joints.y), $maxJoints - 1)]
               + a_weights.z * u_joints[min(int(a_joints.z), $maxJoints - 1)]
               + a_weights.w * u_joints[min(int(a_joints.w), $maxJoints - 1)];
        p = s * p;
    }
    gl_Position = u_viewProj * (u_model * p);
}
"""

    const val LINE_FRAGMENT = """#version 300 es
precision mediump float;
uniform vec4 u_color;
out vec4 o_color;
void main() { o_color = u_color; }
"""
}
