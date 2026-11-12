#version 330
#extension GL_ARB_separate_shader_objects : require

// 经 RenderPipeline 绘制的实例化物品，OpenGL 与 Vulkan 后端共用。
// GENERIC：通用物品提交（每实例变换矩阵）；PHYSICS：物理化掉落物；默认：掉落物自旋与浮动；GLINT：附魔光效在同一次绘制中叠加。
// 26.3 以独立着色器对象编译：输入按名称匹配顶点格式，位置在此显式给出（实例属性接在原版实体格式之后，共 16 个）。
#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <carbon:carbon_draw.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
layout(location = 5) in vec3 Normal;
#ifdef GENERIC
layout(location = 6) in mat4 Instance;
layout(location = 10) in vec4 Animation;
layout(location = 11) in vec2 Data;
layout(location = 12) in mat3 InstanceNormal;
layout(location = 15) in vec3 CopyOffset;
#else
layout(location = 6) in vec3 Translation;
layout(location = 7) in uvec2 Light;
layout(location = 8) in vec2 Animation;
layout(location = 9) in vec4 Copy;
#endif

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec2 texCoord0;
#ifdef GLINT
layout(location = 5) out vec2 texCoordGlint;
#endif

#ifdef PHYSICS
vec4 multiplyRotation(vec4 a, vec4 b) {
    return vec4(a.w * b.xyz + b.w * a.xyz + cross(a.xyz, b.xyz), a.w * b.w - dot(a.xyz, b.xyz));
}

vec3 rotatePoint(vec4 q, vec3 p) {
    return p + 2.0 * cross(q.xyz, cross(q.xyz, p) + q.w * p);
}

vec4 physicalRotation(float phase, float roll, float rate) {
    vec4 yaw = vec4(0.0, sin(phase * 0.5), 0.0, cos(phase * 0.5));
    vec4 resting = CarbonParams.z != 0.0 ? multiplyRotation(yaw, vec4(0.70710678118, 0.0, 0.0, 0.70710678118)) : yaw;
    float angle = roll + rate * fract(CarbonCamera.w);
    resting = multiplyRotation(resting, vec4(0.0, sin(angle * 0.5), 0.0, cos(angle * 0.5)));
    return normalize(resting);
}

vec3 physicalOffset(vec4 q, vec3 copy) {
    vec3 right = rotatePoint(q, vec3(1.0, 0.0, 0.0));
    vec3 up = rotatePoint(q, vec3(0.0, 1.0, 0.0));
    vec3 forward = rotatePoint(q, vec3(0.0, 0.0, 1.0));
    float height = dot(abs(vec3(right.y, up.y, forward.y)), CarbonHalfSize.xyz);
    bool pile = copy.z < 0.0;
    vec3 spread = rotatePoint(q, vec3(copy.xy, pile ? copy.z + 1.0 : 0.0));
    spread.y = height + (pile ? abs(spread.y) : copy.z * (height * 2.0 + 0.002)) + 0.002;
    return spread - rotatePoint(q, CarbonCenter.xyz);
}
#else
mat3 spin(float angle) {
    float s = sin(angle);
    float c = cos(angle);
    return mat3(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c);
}
#endif

void main() {
    float clock = CarbonCamera.w;
#ifdef GENERIC
    vec3 position = Position + CopyOffset;
    vec3 normal = Normal;
    if (Animation.z == 1.0) {
        float age = clock + Animation.x;
        mat3 rotation = spin(age / 20.0 + Animation.y);
        position = rotation * position;
        normal = rotation * normal;
        position.y += 0.25 * CarbonParams.x;
        if (Animation.w != 0.0) {
            position.y += sin(age / 10.0 + Animation.y) * 0.1 + 0.1;
        }
    }
    position = (Instance * vec4(position, 1.0)).xyz - CarbonCamera.xyz;
    normal = normalize(InstanceNormal * normal);
    ivec2 light = max(UV2, ivec2(Data));
#elif defined(PHYSICS)
    vec4 rotation = physicalRotation(Animation.y, Animation.x, Copy.w);
    vec3 position = rotatePoint(rotation, Position) + Translation + physicalOffset(rotation, Copy.xyz) - CarbonCamera.xyz;
    vec3 normal = normalize(rotatePoint(rotation, Normal));
    ivec2 light = max(UV2, ivec2(Light));
#else
    float age = clock + Animation.x;
    mat3 rotation = spin(age / 20.0 + Animation.y);
    vec3 position = rotation * (Position + Copy.xyz) + Translation - CarbonCamera.xyz;
    position.y += 0.25 * CarbonParams.x;
    if (Copy.w != 0.0) {
        position.y += sin(age / 10.0 + Animation.y) * 0.1 + 0.1;
    }
    vec3 normal = normalize(rotation * Normal);
    ivec2 light = max(UV2, ivec2(Light));
#endif
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);
    vertexColor = UV1.x == 0 ? Color : minecraft_mix_light(Light0_Direction, Light1_Direction, normal, Color);
    lightMapColor = UV1.y == 0 ? vec4(1.0) : sample_lightmap(Sampler2, light);
    texCoord0 = UV0;
#ifdef GLINT
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
}
