#version 330

// 掉落物圆形阴影：每个实例是一个接收面上的四边形，四个角由逐顶点的 Corner 选择。
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <carbon:carbon_draw.glsl>

in vec2 Corner;
in vec4 Bounds;
in vec4 Shadow;
in vec4 Parameters;

out vec2 texCoord0;
out float shadowAlpha;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

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
