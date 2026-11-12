#version 330
#extension GL_ARB_separate_shader_objects : require

// 掉落物圆形阴影：每个实例是一个接收面上的四边形，四个角由逐顶点的 Corner 选择。
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <carbon:carbon_draw.glsl>

layout(location = 0) in vec2 Corner;
layout(location = 1) in vec4 Bounds;
layout(location = 2) in vec4 Shadow;
layout(location = 3) in vec4 Parameters;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out float shadowAlpha;
layout(location = 2) out float sphericalVertexDistance;
layout(location = 3) out float cylindricalVertexDistance;

void main() {
    float x = Corner.x == 0.0 ? Bounds.x : Bounds.w;
    float z = Corner.y == 0.0 ? Bounds.z : Shadow.x;
    vec3 relative = vec3(x, Bounds.y, z) - CarbonCamera.xyz;
    sphericalVertexDistance = fog_spherical_distance(relative);
    cylindricalVertexDistance = fog_cylindrical_distance(relative);
    vec4 position = ModelViewMat * vec4(relative, 1.0);
    position.xyz *= 0.99975586;
    gl_Position = ProjMat * position;
    texCoord0 = (Shadow.yz - vec2(x, z)) / (2.0 * Parameters.x) + 0.5;
    vec3 itemPosition = vec3(Shadow.y, Parameters.z, Shadow.z) - CarbonCamera.xyz;
    float distanceSquared = dot(itemPosition, itemPosition);
    float weight = (1.0 - distanceSquared / 256.0) * 0.75;
    shadowAlpha = floor(clamp((weight - Parameters.y * 0.5) * 0.5 * Shadow.w, 0.0, 1.0) * 255.0) / 255.0;
    if (distanceSquared >= 256.0 || shadowAlpha <= 0.0) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    }
}
