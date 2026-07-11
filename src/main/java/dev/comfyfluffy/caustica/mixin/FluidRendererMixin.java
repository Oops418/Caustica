package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.rt.terrain.RtFluidRendererHooks;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla water never renders above 8/9 block height unless the block above holds the same fluid, so a
 * water column under a solid ceiling keeps a phantom 1/9 air gap with a rendered surface quad inside it.
 * The rasterizer just alpha-blends that quad; the path tracer treats every water quad as an air↔water
 * dielectric interface, so viewed from underwater the quad totally-internally-reflects past ~49° incidence
 * and the ceiling block's bottom face reads as a mirror showing the floor instead of the block.
 *
 * <p>Fix at the height source: when a max-height water column sits under a neighbor whose down face fully
 * occludes, report full height. Everything downstream then self-corrects — {@code tesselate}'s up-face
 * occlusion check culls the now-flush surface quad against that same full occluder (the snap condition and
 * the cull condition are the same shape test, so a snapped column can never leave a coincident quad
 * z-fighting the ceiling), and {@code calculateAverageHeight} short-circuits 1.0 neighbors to 1.0 shared
 * corners, keeping covered↔open rims watertight (no light-leak seams).
 *
 * <p>Scope: water only (lava stays stock); max-height columns only — a partially-full flowing column under
 * a bridge has a real air gap above it and keeps it; glass and partial ceilings are excluded automatically
 * (their down-face occlusion shape isn't the full block, and their flush quad wouldn't be culled anyway).
 * Gated per-instance via {@link RtFluidRendererHooks}: only the RT mesher's thread-confined instances
 * ({@code RtTerrain.dispatchSection}) opt in, so the vanilla raster path — live when RT output is toggled
 * off at runtime — meshes bit-identically to stock, even with both meshers running concurrently.
 */
@Mixin(FluidRenderer.class)
public class FluidRendererMixin implements RtFluidRendererHooks {
    /** Own height of a source/max-level column ({@code 8/9}); below this the air gap is real, not phantom. */
    @Unique
    private static final float CAUSTICA$MAX_OWN_HEIGHT = 0.888F;

    @Unique
    private boolean caustica$snapCoveredWater;

    @Override
    public void caustica$enableSnapCoveredWater() {
        this.caustica$snapCoveredWater = true;
    }

    /**
     * Injects the per-column height lookup (the 5-arg overload): both {@code tesselate}'s center/neighbor
     * columns and the corner-average path (via the 3-arg overload) funnel through it, so one injection
     * keeps every consumer of the height consistent.
     */
    @Inject(
            method = "getHeight(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)F",
            at = @At("RETURN"),
            cancellable = true)
    private void caustica$snapCoveredWaterHeight(BlockAndTintGetter level, Fluid fluidType, BlockPos pos,
                                                 BlockState state, FluidState fluidState,
                                                 CallbackInfoReturnable<Float> cir) {
        if (!this.caustica$snapCoveredWater) {
            return;
        }
        float height = cir.getReturnValueF();
        // < MAX: partial column (real gap) or non-fluid sentinel (0 / -1); >= 1: already full under water.
        if (height < CAUSTICA$MAX_OWN_HEIGHT || height >= 1.0F || !fluidState.is(FluidTags.WATER)) {
            return;
        }
        // Same shape test tesselate's up-face cull uses (isFaceOccludedByState with height 1.0): snapping
        // exactly when the full occluder guarantees the cull means no flush quad can survive.
        BlockState above = level.getBlockState(pos.above());
        if (above.getFaceOcclusionShape(Direction.DOWN) == Shapes.block()) {
            cir.setReturnValue(1.0F);
        }
    }
}
