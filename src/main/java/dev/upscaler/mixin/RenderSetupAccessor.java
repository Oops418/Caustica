package dev.upscaler.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Exposes {@link RenderSetup}'s package-private {@code textures} map (sampler name → texture binding).
 * The value type {@code RenderSetup.TextureBinding} is package-private, so it's returned raw and its
 * public {@code location()} accessor is invoked reflectively by the caller. Used to recover a render
 * type's {@code Sampler0} texture Identifier for LabPBR {@code _n}/{@code _s} lookup.
 */
@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
    @Accessor("textures")
    Map<String, ?> upscaler$textures();
}
