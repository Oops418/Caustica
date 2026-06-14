#version 460
#extension GL_EXT_ray_tracing : require

// P1 step 2: flat gray with a little distance shading so block shapes read. No normals/AO/
// sky-light yet (that needs a material stream + sky-visibility rays — P1 step 3). The point
// here is to verify the camera rays and geometry align with vanilla via the 50/50 blend.
layout(location = 0) rayPayloadInEXT vec3 payload;
hitAttributeEXT vec2 attribs;

void main() {
    float shade = exp(-gl_HitTEXT * 0.02);
    payload = vec3(0.8) * (0.4 + 0.6 * shade);
}
