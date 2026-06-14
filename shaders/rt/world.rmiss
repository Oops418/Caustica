#version 460
#extension GL_EXT_ray_tracing : require

// Simple sky gradient by ray height — placeholder until P3 lighting / a real sky model.
layout(location = 0) rayPayloadInEXT vec3 payload;

void main() {
    float t = clamp(gl_WorldRayDirectionEXT.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 horizon = vec3(0.62, 0.71, 0.86);
    vec3 zenith = vec3(0.22, 0.45, 0.85);
    payload = mix(horizon, zenith, t);
}
