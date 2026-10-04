package qouteall.imm_ptl.core.sunlight;

import java.util.Optional;
import java.util.regex.Pattern;

/** Exact, temporary source adaptation for the supported Complementary/Euphoria revision. */
public final class SunlightShaderAdapter {
    public static final String PACK = "ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5";
    private static final String MARKER = "// IP_SHARED_SUNLIGHT_V1";
    private static final String ROTATION_BEGIN = "// Thanks to SpacEagle17 and isuewo for the sun angle handling";
    private static final String ROTATION_END = "#if SHADOW_QUALITY >= 2";
    private static final Pattern ROTATION = Pattern.compile("const\\s+float\\s+sunPathRotation\\s*=\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*;");
    private static final Pattern CLOCK = Pattern.compile("#if\\s+SHADOW_QUALITY\\s*==\\s*-1\\s+float\\s+timeAngle\\s*=\\s*worldTimeSmooth\\s*/\\s*24000\\.0;(?s:.*?)#endif");
    private SunlightShaderAdapter() {}

    public static boolean supports(String pack) { return PACK.equals(pack) || (PACK + ".zip").equals(pack); }

    /** Read the owner's unmodified preprocessed program, never an inherited server override. */
    public static Optional<SunlightProfile> observe(String pack, String source) {
        if (!supports(pack)) return Optional.empty();
        var rotation = ROTATION.matcher(source);
        if (!rotation.find()) return Optional.empty();
        double degrees = Double.parseDouble(rotation.group(1));
        if (rotation.find() || !Double.isFinite(degrees) || Math.abs(degrees) > 180) return Optional.empty();
        String compact = source.replaceAll("\\s+", "");
        SunlightProfile.Clock clock;
        if (compact.contains("floattimeAngle=worldTimeSmooth/24000.0;")) clock = SunlightProfile.Clock.WORLD_TIME;
        else if (compact.contains("floattAmin=fract(sunAngle-0.033333333);")) clock = SunlightProfile.Clock.SUN_ANGLE;
        else return Optional.empty();
        return Optional.of(new SunlightProfile(true, degrees, clock));
    }

    public static boolean sourceDimension(String source) {
        String header = source.substring(0, Math.min(512, source.length()));
        return header.contains("#define OVERWORLD") || header.contains("#define NETHER");
    }

    /** Runs after includes/options, before JCPP. No file or option queue is modified. */
    public static String patch(String pack, String source, SunlightProfile profile) {
        if (!supports(pack) || !profile.enabled() || !sourceDimension(source)) return source;
        if (source.contains(MARKER)) return source;
        // Some utility programs do not include the pack's common lighting module.
        if (!source.contains(ROTATION_BEGIN)) return source;
        int begin = source.indexOf(ROTATION_BEGIN), end = source.indexOf(ROTATION_END, begin);
        if (source.indexOf(ROTATION_BEGIN, begin + 1) >= 0 || end < begin)
            throw new IllegalStateException("Shared sunlight: supported pack rotation anchors changed");
        var clock = CLOCK.matcher(source);
        if (!clock.find()) throw new IllegalStateException("Shared sunlight: supported pack clock anchor changed");
        // The pack tests SUN_ANGLE in preprocessor branches such as cloud shadows.
        // Use a zero/nonzero sentinel there; the actual continuous angle is the constant.
        String rotation = MARKER + "\n#undef SUN_ANGLE\n#define SUN_ANGLE "
            + (profile.pathRotationDegrees() == 0 ? "0" : "1") + "\nconst float sunPathRotation = "
            + Double.toString(profile.pathRotationDegrees()) + ";\n"
            + (profile.pathRotationDegrees() == 0 ? "#define PERPENDICULAR_TWEAKS\n" : "") + "\n";
        String result = source.substring(0, begin) + rotation + source.substring(end);
        // Also replace the copied source-environment clock inside the portal helper.
        // It has a local worldTime, so receiving-dimension time cannot leak into it.
        return CLOCK.matcher(result).replaceAll(match -> {
            if (profile.clock() == SunlightProfile.Clock.WORLD_TIME)
                return "float timeAngle = float(worldTime) / 24000.0;";
            int branch = match.group().indexOf("#else");
            if (branch < 0) throw new IllegalStateException("Shared sunlight: clock branch changed");
            return match.group().substring(branch + 5, match.group().lastIndexOf("#endif"));
        });
    }
}
