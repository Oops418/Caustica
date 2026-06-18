package dev.upscaler.rt;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * P6.1 heuristic PBR material classifier. Vanilla blocks carry only albedo (+ biome tint), so until
 * LabPBR per-texel ingestion lands (P6.2) we assign each block a {@code (roughness, metalness)} pair
 * from cheap, stable signals — the block's {@link SoundType} (metal/glass) plus a small set of known
 * smooth dielectrics — the same trick shaderpacks use. Extraction stores the pair in the free per-prim
 * {@code mat} slot ({@link RtTerrain}/{@link RtEntityCapture}); the path tracer's GGX BRDF reads it.
 *
 * <p>The {@code -Dupscaler.rt.pbr} flag does NOT gate this classification (the per-prim layout is
 * unconditional) — it gates the <em>shader</em> BRDF via a push bit, so {@code pbr=false} reverts to the
 * old matte Lambertian look regardless of what is stored here.
 */
public final class RtMaterials {
    private RtMaterials() {}

    /** Master toggle for the GGX BRDF + material guides in the path tracer (pushed to the shader). */
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("upscaler.rt.pbr", "true"));

    // Perceptual roughness (0 = mirror, 1 = fully matte) per material class. Defaults sit high so the
    // bulk of the world keeps its current matte look; only metals/glass/polished blocks turn glossy.
    private static final float DEFAULT_ROUGH = 0.9f;
    private static final float METAL_ROUGH = 0.3f;
    private static final float GLASS_ROUGH = 0.1f;
    private static final float SMOOTH_ROUGH = 0.35f;

    /** Water roughness (used by the dielectric guide); near-smooth so RR resolves stable reflections. */
    public static final float WATER_ROUGH = 0.08f;
    /** Lava: opaque emitter, moderately rough (handled by the opaque BRDF branch, not the dielectric one). */
    public static final float LAVA_ROUGH = 0.7f;
    /** Default entity roughness — mobs/items skew matte; refined per-texel in P6.2. */
    public static final float ENTITY_ROUGH = 0.8f;

    // Smooth dielectric blocks that don't report a glass sound but read as polished/glossy.
    private static final Set<Block> SMOOTH = Set.of(
            Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR,
            Blocks.SMOOTH_STONE, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
            Blocks.POLISHED_GRANITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_ANDESITE,
            Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_BLACKSTONE,
            Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE);

    /** Perceptual roughness for this block's surface. */
    public static float roughness(BlockState state) {
        if (state == null) {
            return DEFAULT_ROUGH;
        }
        SoundType sound = state.getSoundType();
        if (isMetal(sound)) {
            return METAL_ROUGH;
        }
        if (sound == SoundType.GLASS) {
            return GLASS_ROUGH;
        }
        if (SMOOTH.contains(state.getBlock())) {
            return SMOOTH_ROUGH;
        }
        return DEFAULT_ROUGH;
    }

    /** Metalness (1 = conductor: F0 tinted by albedo, no diffuse; 0 = dielectric). */
    public static float metalness(BlockState state) {
        return state != null && isMetal(state.getSoundType()) ? 1f : 0f;
    }

    private static boolean isMetal(SoundType sound) {
        return sound == SoundType.METAL || sound == SoundType.COPPER
                || sound == SoundType.NETHERITE_BLOCK || sound == SoundType.ANVIL
                || sound == SoundType.CHAIN;
    }
}
