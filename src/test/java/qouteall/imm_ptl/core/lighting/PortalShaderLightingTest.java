package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalShaderLightingTest {
    @Test void irisSunAngleWrapsConsistently(){
        assertEquals(.25,PortalShaderLighting.sunAngle(0),1e-6);
        assertEquals(0,PortalShaderLighting.sunAngle(.75f),1e-6);
        assertEquals(.75,PortalShaderLighting.sunAngle(.5f),1e-6);
    }
    @Test void packDirectionIncludesPathRotationAndMoonSign(){
        var noon=PortalShaderLighting.sourceDirection(.25,0);
        assertTrue(noon.y>.98);assertEquals(0,noon.z,1e-12);
        var rotated=PortalShaderLighting.sourceDirection(.25,40);
        assertEquals(noon.y*Math.cos(Math.toRadians(40)),rotated.y,1e-10);
        assertEquals(-noon.y*Math.sin(Math.toRadians(40)),rotated.z,1e-10);
        assertTrue(PortalShaderLighting.sourceDirection(.75,0).y>.98);
    }
    @Test void directionRemainsUnitLengthThroughDayNight(){
        for(int i=0;i<1000;i++)assertEquals(1,PortalShaderLighting.sourceDirection(i/1000.0,-40).length(),1e-9);
    }
    @Test void shadowlessPackClockUsesWorldTimeInsteadOfTheNonlinearSunAngle(){
        var clock=PortalShaderPackAdapter.ClockMode.WORLD_TIME;
        assertEquals(1,PortalShaderLighting.sourceDirection(.7,6000,0,clock).y,1e-10);
        assertEquals(1,PortalShaderLighting.sourceDirection(.1,18000,0,clock).y,1e-10);
        assertEquals(PortalShaderLighting.sourceDirection(.1,6000,40,clock),
            PortalShaderLighting.sourceDirection(.9,30000,40,clock));
    }
}
