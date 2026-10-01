package qouteall.imm_ptl.core.lighting;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PortalLightShadersTest {
    @Test void unrecognizedShadersStayByteIdentical() {
        String source="#version 330\nvoid main(){customGI();}";
        assertEquals(source,PortalLightShaders.sodium("shaderpack:terrain",source));
        assertEquals(source,PortalLightShaders.dh("assets/distanthorizons/shaders/terrain/gl/frag.frag",source));
    }
    @Test void transformationsAreIdempotent() {
        String source="#version 330\nvoid main(){color *= v_Color;}";
        String name="sodium:blocks/block_layer_opaque.fsh";
        String patched=PortalLightShaders.sodium(name,source);
        assertNotEquals(source,patched);assertEquals(patched,PortalLightShaders.sodium(name,patched));
        assertTrue(patched.startsWith("#version 330"));
    }
}
