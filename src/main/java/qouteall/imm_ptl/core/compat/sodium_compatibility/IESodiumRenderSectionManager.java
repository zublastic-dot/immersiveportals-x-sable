package qouteall.imm_ptl.core.compat.sodium_compatibility;

public interface IESodiumRenderSectionManager {
    void ip_swapContext(SodiumRenderingContext context);

    SodiumInterface.PortalLightRebuild ip_schedulePortalLightRebuild(int x, int y, int z);
}
