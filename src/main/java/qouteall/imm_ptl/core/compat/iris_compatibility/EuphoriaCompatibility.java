package qouteall.imm_ptl.core.compat.iris_compatibility;

/** Opt in only for the APIs verified against the installed release bytecode. */
public final class EuphoriaCompatibility {
    private EuphoriaCompatibility() {}

    public static boolean supports(String euphoriaVersion, String irisVersion) {
        return "1.10.5-r5.9.3-neoforge".equals(euphoriaVersion)
            && "1.8.14-beta.1+mc1.21.1".equals(irisVersion);
    }
}
