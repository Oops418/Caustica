package dev.comfyfluffy.candela.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.candela.CandelaConfig;
import dev.comfyfluffy.candela.CandelaConfig.BooleanSetting;
import dev.comfyfluffy.candela.CandelaConfig.FloatSetting;
import dev.comfyfluffy.candela.CandelaConfig.IntSetting;
import dev.comfyfluffy.candela.CandelaConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link CandelaConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dcandela.*} startup surface. DLSS-RR quality is the exception: the render resolution
 * is queried from NGX for the chosen quality mode on every resize (see
 * {@code RtDlssRr.queryOptimalRenderSize}), and the RR feature itself is recreated live whenever
 * {@code quality} changes (see {@code RtDlssRr.ensureFeature}), so it is safe to expose here.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /** Runtime-tunable RT options, in display order. Paired two-per-row by {@code OptionsList.addSmall}. */
    public static OptionInstance<?>[] runtimeOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
            spp(),
            maxBounces(),
            sunSize(),
            entities(),
            particles(),
            waterWaves(),
            dlssQuality(),
            hdrEnabled(),
            hdrPaperWhite(),
            hdrPeak(),
            debugView(),
        };
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CandelaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "candela.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("candela.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("candela.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = CandelaConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "candela.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("candela.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CandelaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "candela.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("candela.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CandelaConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "candela.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("candela.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.value(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        // Stored in radians via the degrees->radians sanitizer; the slider works in tenths of a degree.
        FloatSetting setting = CandelaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "candela.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("candela.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> entities() {
        return bool("candela.options.rt.entities", CandelaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("candela.options.rt.particles", CandelaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("candela.options.rt.waterWaves", CandelaConfig.Rt.Composite.WATER_WAVES);
    }

    // NVSDK_NGX_PerfQuality_Value, ordered performance -> quality for the slider. Per NVIDIA's DLSS-RR
    // programming guide, Ray Reconstruction only supports Performance(0), Balanced(1), Quality(2),
    // Ultra-Performance(3), and DLAA(5) — Ultra Quality(4) is not a valid PerfQualityValue for RR (its
    // optimal-settings query returns a zeroed render size for it) and is deliberately excluded here.
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = CandelaConfig.Rt.DlssRr.QUALITY;
        int initialQuality = DLSS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = DLSS_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "candela.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("candela.options.rt.dlssQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("candela.options.rt.dlssQuality." + DLSS_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, DLSS_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(DLSS_QUALITY_ORDER.get(position)));
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        return bool("candela.options.rt.hdr", CandelaConfig.Rt.Hdr.ENABLED);
    }

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = CandelaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "candela.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("candela.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.value()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> hdrPeak() {
        FloatSetting setting = CandelaConfig.Rt.Hdr.PEAK_NITS;
        return new OptionInstance<>(
            "candela.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("candela.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.value()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CandelaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "candela.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("candela.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("candela.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7), Codec.INT),
            Math.clamp(setting.value(), 0, 7),
            setting::set);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }
}
