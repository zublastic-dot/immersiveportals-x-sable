package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DhPortalShaderPackAdapterTest {
    @BeforeEach void clear() { DhPortalShaderPackAdapter.clear(); }
    static final String PACK="ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5";
    private List<String> source() { return List.of("#version 330 compatibility",
        "// Complementary Shaders by EminGT", "uniform float far;", "void main() {",
        DhPortalShaderPackAdapter.ANCHOR,"}"); }

    @Test void supportsTerrainAndWaterInEachDimensionWithoutSunlight() {
        for(String dimension:List.of("", "world-1/", "world0/", "world1/", "world42/", "modded_vault/", "dimensions/my_mod/highlands/")) {
            for(String program:List.of("dh_terrain.fsh","dh_water.fsh")) {
                var original=source();String path="/"+dimension+program;
                var result=DhPortalShaderPackAdapter.patch(PACK,path,original);
                assertNotSame(original,result,path);assertSame(result,DhPortalShaderPackAdapter.patch(PACK,path,result));
                String text=String.join("\n",result);
                assertTrue(text.startsWith("#version 330 compatibility\n"));
                assertTrue(text.contains("uniform float far;"));
                assertTrue(text.contains("color.a *= ipDhPortalFade(lengthCylinder, far);"));
                assertFalse(text.contains("ipSun"));assertFalse(text.contains("ipDhCoverageBlocks ="));
            }
        }
    }
    @Test void unknownFamilyProgramsAndChangedOrAmbiguousSignaturesAreIdentity() {
        var original=source();
        assertSame(original,DhPortalShaderPackAdapter.patch("Unknown","/world-1/dh_terrain.fsh",original));
        assertSame(original,DhPortalShaderPackAdapter.patch(PACK,"/world-1/gbuffers_terrain.fsh",original));
        assertSame(original,DhPortalShaderPackAdapter.patch(PACK,"/world-1/dh_terrain.vsh",original));
        var duplicate=new java.util.ArrayList<>(original);duplicate.add(DhPortalShaderPackAdapter.ANCHOR);
        assertSame(duplicate,DhPortalShaderPackAdapter.patch(PACK,"/world-1/dh_terrain.fsh",duplicate));
        var changed=original.stream().map(s->s.replace("far * 0.4","far * 0.3")).toList();
        assertSame(changed,DhPortalShaderPackAdapter.patch(PACK,"/world-1/dh_terrain.fsh",changed));
        assertEquals(6,original.size(),"Original cached includes remain untouched");
    }
    @Test void coverageAdmissionFollowsCompiledProgramsRatherThanDimensionNamesAndReloadRevokesIt() {
        assertFalse(DhPortalShaderPackAdapter.admitted(PACK));
        DhPortalShaderPackAdapter.patch(PACK,"/dimensions/my_mod/highlands/dh_terrain.fsh",source());
        assertTrue(DhPortalShaderPackAdapter.admitted(PACK),"An adapted terrain program must not depend on an unrelated water program");
        assertFalse(DhPortalShaderPackAdapter.admitted("another"));
        var changed=source().stream().map(s->s.replace("far * 0.4","far * 0.3")).toList();
        assertSame(changed,DhPortalShaderPackAdapter.patch(PACK,"/dimensions/my_mod/highlands/dh_water.fsh",changed));
        assertTrue(DhPortalShaderPackAdapter.admitted(PACK),"Only the live adapted program has the coverage uniform");
        DhPortalShaderPackAdapter.clear();
        assertFalse(DhPortalShaderPackAdapter.admitted(PACK));
    }
    @Test void onlyBoundedAbsoluteDhFragmentPathsAreAdapted() {
        var original=source();
        for(String path:List.of("dh_terrain.fsh","//dh_terrain.fsh","/../dh_terrain.fsh",
            "/dimensions/./dh_water.fsh","/dimensions/../dh_water.fsh","/dimension\\dh_terrain.fsh",
            "/dh_terrain.fsh/extra","/"+"a".repeat(1024)+"/dh_terrain.fsh"))
            assertSame(original,DhPortalShaderPackAdapter.patch(PACK,path,original),path);
        assertFalse(DhPortalShaderPackAdapter.admitted(PACK));
    }
}
