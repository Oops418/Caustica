#version 460
#extension GL_EXT_ray_tracing : require

// Ray miss = sky. As of P6.3 the sky RADIANCE is computed in world.rgen's miss branch (the dynamic sky
// needs the pushed sun direction, which this miss shader does not receive), so here we only flag the
// miss with hitT < 0; payload.albedo is left at 0 and ignored by raygen.
struct Payload {
    vec3 albedo;
    vec3 normal;
    float hitT;
    float emission;
    vec3 motionPrev;
    float material;
    float roughness;
    float metalness;
    vec3 f0;
};
layout(location = 0) rayPayloadInEXT Payload payload;

void main() {
    payload.albedo = vec3(0.0); // unused: raygen computes the dynamic sky from the pushed sun direction
    payload.hitT = -1.0;
    payload.emission = 0.0;
    payload.motionPrev = vec3(0.0);
    payload.material = 0.0;
    payload.roughness = 1.0;
    payload.metalness = 0.0;
    payload.f0 = vec3(0.0);
}
