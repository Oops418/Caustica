package dev.comfyfluffy.caustica.rt.terrain;

/**
 * Duck interface merged into {@link net.minecraft.client.renderer.block.FluidRenderer} by {@code
 * FluidRendererMixin}. The RT mesher opts its own (per-tessellation-job, thread-confined) instances into
 * the covered-water height snap; instances the vanilla raster path creates are never opted in, so stock
 * rendering stays bit-identical when RT output is toggled off (see {@code VanillaRenderController}).
 */
public interface RtFluidRendererHooks {
    /** Enable the covered-water full-height snap on this instance (RT mesher instances only). */
    void caustica$enableSnapCoveredWater();
}
