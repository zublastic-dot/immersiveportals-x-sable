package qouteall.imm_ptl.core.compat.dh_compatibility;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.net.URL;
import static org.junit.jupiter.api.Assertions.*;
class DhPortalTaaResourceTest {
    /** Model separate mod resource visibility: IP's loader cannot see any DH assets. */
    private static class IsolatedModLoader extends ClassLoader {
        IsolatedModLoader() { super(DhPortalTaaResourceTest.class.getClassLoader()); }
        @Override public URL getResource(String name) { return null; }
        @Override public InputStream getResourceAsStream(String name) { return null; }
        Class<?> define(byte[] bytes) { return defineClass(null,bytes,0,bytes.length); }
    }
    @Test void shaderLookupUsesDhOwningLoaderWhenPortalModuleCannotSeeAssets() throws Exception {
        byte[] bytes;
        try(var in=getClass().getResourceAsStream("/qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalTaaPipeline.class")) {
            assertNotNull(in);bytes=in.readAllBytes();
        }
        var type=new IsolatedModLoader().define(bytes);
        assertNull(type.getResourceAsStream("/assets/distanthorizons/shaders/antialias/gl/taa.frag"));
        var load=type.getDeclaredMethod("loadShader",String.class);load.setAccessible(true);
        assertTrue(((String)load.invoke(null,"taa.frag")).contains("TemporalAntiAlias"));
        assertTrue(((String)load.invoke(null,"sharpen.frag")).contains("uCasAmount"));
    }
}
