#ifndef CARBON_DRAW_GLSL
#define CARBON_DRAW_GLSL

// Carbon 每次绘制的参数：xyz 为相机相对 Carbon 坐标原点的偏移，w 为动画时钟；
// Params.x 为落地缩放偏移，Params.z 非零表示平面物品（物理化）；Center / HalfSize 为模型包围盒。
layout(std140) uniform CarbonDraw {
    vec4 CarbonCamera;
    vec4 CarbonParams;
    vec4 CarbonCenter;
    vec4 CarbonHalfSize;
};

#endif
