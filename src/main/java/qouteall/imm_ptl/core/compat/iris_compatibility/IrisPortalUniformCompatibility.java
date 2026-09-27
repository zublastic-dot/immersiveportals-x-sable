package qouteall.imm_ptl.core.compat.iris_compatibility;

/** The optional hook is verified against this Iris release's sampling bytecode. */
public final class IrisPortalUniformCompatibility {
    private IrisPortalUniformCompatibility() {}

    public static boolean supports(String irisVersion) {
        return "1.8.14-beta.1+mc1.21.1".equals(irisVersion);
    }
}
