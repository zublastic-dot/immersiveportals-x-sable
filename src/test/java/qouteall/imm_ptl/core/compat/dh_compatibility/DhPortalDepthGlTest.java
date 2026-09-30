package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Renders a raised sand step to real depth, then executes the installed DH AO generator. */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class DhPortalDepthGlTest {
    private static final int SIZE = 512;
    private static long window;
    private static String aoSource;
    private static String geometryVertex, geometryFragment;

    @BeforeAll static void init() throws Exception {
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(SIZE, SIZE, "IP/Sable AO generation regression", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        aoSource = source("assets/distanthorizons/shaders/ssao/gl/ao.frag");
        geometryVertex = source(DhPortalClipping.DIRECT_VERTEX);
        geometryFragment = source(DhPortalClipping.DIRECT_FRAGMENT);
    }
    private static String source(String path) throws Exception {
        try (var stream = DhPortalDepthGlTest.class.getResourceAsStream("/" + path)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @AfterAll static void close() {
        GL.setCapabilities(null);
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
    }
    private static int program(String vs, String fs) {
        int program = glCreateProgram();
        for (int kind : new int[]{GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}) {
            int shader = glCreateShader(kind);
            glShaderSource(shader, kind == GL_VERTEX_SHADER ? vs : fs);
            glCompileShader(shader);
            assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        return program;
    }
    private static void matrix(int program, String uniform, Matrix4f value) {
        glUniformMatrix4fv(glGetUniformLocation(program, uniform), false, value.get(new float[16]));
    }
    private static int texture(int format, int channel) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, format, SIZE, SIZE, 0, channel, GL_FLOAT, (java.nio.FloatBuffer)null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return texture;
    }
    private static float[] draw(Matrix4f projection, Matrix4f view, boolean reverse, Vector4f clipPlane) {
        int geometry = program(DhPortalClipping.patch(DhPortalClipping.DIRECT_VERTEX, geometryVertex),
            DhPortalClipping.patch(DhPortalClipping.DIRECT_FRAGMENT, geometryFragment));
        int ao = program("""
            #version 330 core
            out vec2 texCoord;
            void main() {
                texCoord = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);
            }
            """, aoSource);
        int vao = glGenVertexArrays(), vbo = glGenBuffers(), depthFbo = glGenFramebuffers(), aoFbo = glGenFramebuffers();
        glActiveTexture(GL_TEXTURE0);
        int depth = texture(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT);
        int output = texture(GL_R32F, GL_RED);
        try {
            glBindVertexArray(vao);
            float[] corners = {-180,-90,-10, 180,-90,-10, 180,-90,-40, -180,-90,-40,
                -180,-90,-40, 180,-90,-40, 180,-88,-40, -180,-88,-40,
                -180,-88,-40, 180,-88,-40, 180,-88,-300, -180,-88,-300};
            float[] vertices = new float[54];
            int[] indices = {0,1,2,0,2,3};
            for (int i=0;i<18;i++) System.arraycopy(corners,((i/6)*4+indices[i%6])*3,vertices,i*3,3);
            glBindBuffer(GL_ARRAY_BUFFER,vbo);
            glBufferData(GL_ARRAY_BUFFER,vertices,GL_STATIC_DRAW);
            int position = glGetAttribLocation(geometry,"vPosition");
            glEnableVertexAttribArray(position);
            glVertexAttribPointer(position,3,GL_FLOAT,false,0,0);
            glViewport(0,0,SIZE,SIZE);
            glDisable(GL_BLEND); glDisable(GL_SCISSOR_TEST); glDisable(GL_STENCIL_TEST); glDisable(GL_CULL_FACE);
            glDisable(GL_CLIP_DISTANCE0);
            glBindFramebuffer(GL_FRAMEBUFFER, depthFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
            glDrawBuffer(GL_NONE); glReadBuffer(GL_NONE);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
            glClearDepth(reverse ? 0 : 1);
            glDepthMask(true);
            glClear(GL_DEPTH_BUFFER_BIT);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(reverse ? GL_GREATER : GL_LESS);
            glUseProgram(geometry);
            // Keep the GL 3.3 default depth range. Reverse test uses a [-1,1] projection.
            matrix(geometry, "uTransform", new Matrix4f(projection).mul(view));
            int planeUniform = glGetUniformLocation(geometry,DhPortalClipping.UNIFORM);
            assertTrue(planeUniform>=0);
            if (clipPlane!=null) glUniform4f(planeUniform,clipPlane.x,clipPlane.y,clipPlane.z,clipPlane.w);
            glDrawArrays(GL_TRIANGLES,0,18);
            glBindFramebuffer(GL_FRAMEBUFFER, aoFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,output,0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
            glDisable(GL_DEPTH_TEST);
            glUseProgram(ao);
            glBindTexture(GL_TEXTURE_2D,depth);
            matrix(ao,"uProj",projection);
            matrix(ao,"uInvProj",new Matrix4f(projection).invert());
            glUniform1i(glGetUniformLocation(ao,"uDhDepthTexture"),0);
            glUniform1i(glGetUniformLocation(ao,"uSampleCount"),6);
            glUniform1f(glGetUniformLocation(ao,"uRadius"),4);
            glUniform1f(glGetUniformLocation(ao,"uStrength"),.2f);
            glUniform1f(glGetUniformLocation(ao,"uMinLight"),.25f);
            glUniform1f(glGetUniformLocation(ao,"uBias"),.02f);
            glUniform1f(glGetUniformLocation(ao,"uFadeDistanceInBlocks"),1600);
            glUniform1i(glGetUniformLocation(ao,"uIsReverseZDepth"),reverse?1:0);
            glUniform1i(glGetUniformLocation(ao,"uDepthIsZeroToPositiveOne"),0);
            glDrawArrays(GL_TRIANGLES,0,3);
            float[] pixels = new float[SIZE*SIZE];
            glReadPixels(0,0,SIZE,SIZE,GL_RED,GL_FLOAT,pixels);
            assertEquals(GL_NO_ERROR, glGetError());
            return pixels;
        } finally {
            glUseProgram(0); glBindFramebuffer(GL_FRAMEBUFFER,0); glBindVertexArray(0);
            glDeleteProgram(geometry); glDeleteProgram(ao); glDeleteVertexArrays(vao);
            glDeleteBuffers(vbo);
            glDeleteFramebuffers(depthFbo); glDeleteFramebuffers(aoFbo);
            glDeleteTextures(depth); glDeleteTextures(output);
        }
    }
    @Test void portalStepOcclusionMatchesDirectViewEvenAtTheThreshold() {
        Matrix4f base = new Matrix4f().perspective((float)Math.toRadians(70),1,7.5f,4096);
        Matrix4f view = new Matrix4f().rotationX((float)Math.toRadians(66));
        float[] reference = draw(base,view,false,null);
        double edgeOcclusion=0;
        for (float value : reference) edgeOcclusion += 1-value;
        assertTrue(edgeOcclusion>100,"The actual AO shader must shade the raised step");
        for (float distance : new float[]{5,1,.22f,.02f,.0001f}) {
            Vector4f worldPlane = new Vector4f(0,0,-1,-distance);
            Matrix4f oblique = DhPortalProjection.clip(base,view,worldPlane,false);
            assertNotNull(oblique);
            float[] broken = draw(oblique,view,false,null);
            float[] portal = draw(base,view,false,DhPortalClipping.clipSpacePlane(base,view,worldPlane));
            double error=0; int count=0;
            for (int y=32;y<SIZE-32;y++) for(int x=32;x<SIZE-32;x++) {
                int i=y*SIZE+x;
                error+=Math.abs(reference[i]-broken[i]); count++;
            }
            assertTrue(error/count>.003,"Old portal projection must reproduce the AO mismatch at "+distance);
            assertArrayEquals(reference,portal,.00001f,"Step edges and flat sand must match at "+distance);
        }
    }

    @Test void geometryBehindThePortalIsStillRemoved() {
        Matrix4f base = new Matrix4f().perspective((float)Math.toRadians(70),1,7.5f,4096);
        Matrix4f view = new Matrix4f().rotationX((float)Math.toRadians(66));
        // Reject all sand geometry. AO must see only the cleared sky depth.
        Vector4f plane = DhPortalClipping.clipSpacePlane(base,view,new Vector4f(0,1,0,-1));
        for(float value : draw(base,view,false,plane)) assertEquals(1,value,.00001f);
    }

    @Test void reverseDepthAlsoRetainsStepOcclusion() {
        Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70),1,4096,7.5f);
        Matrix4f view = new Matrix4f().rotationX((float)Math.toRadians(66));
        float[] direct = draw(projection,view,true,null);
        Vector4f plane = DhPortalClipping.clipSpacePlane(projection,view,new Vector4f(0,0,-1,-.0001f));
        assertArrayEquals(direct,draw(projection,view,true,plane),.00001f);
    }

    @Test void everyInstalledGeometryShaderLinksWithItsPairedClipVarying() throws Exception {
        String[][] pairs = {{DhPortalClipping.TERRAIN_VERTEX,DhPortalTextures.TERRAIN_SHADER},
            {DhPortalClipping.DIRECT_VERTEX,DhPortalClipping.DIRECT_FRAGMENT},
            {DhPortalClipping.INSTANCED_VERTEX,DhPortalClipping.INSTANCED_FRAGMENT}};
        for(String[] pair : pairs) {
            String fragment=source(pair[1]);
            if(pair[1].equals(DhPortalTextures.TERRAIN_SHADER)) fragment=DhPortalTextures.patchTerrainShader(fragment);
            int linked=program(DhPortalClipping.patch(pair[0],source(pair[0])),DhPortalClipping.patch(pair[1],fragment));
            assertTrue(glGetUniformLocation(linked,DhPortalClipping.UNIFORM)>=0,pair[0]);
            glDeleteProgram(linked);
        }
    }

    private static float[] cloudFaces(Matrix4f projection, Vector4f plane, boolean nearFirst) {
        int shader=program(DhPortalClipping.patch(DhPortalClipping.DIRECT_VERTEX,geometryVertex),
            DhPortalClipping.patch(DhPortalClipping.DIRECT_FRAGMENT,geometryFragment));
        int vao=glGenVertexArrays(),vbo=glGenBuffers(),fbo=glGenFramebuffers();
        glActiveTexture(GL_TEXTURE0);
        int depth=texture(GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT),output=texture(GL_RGBA32F,GL_RGBA);
        int light=glGenTextures();
        glBindTexture(GL_TEXTURE_2D,light);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,1,1,0,GL_RGBA,GL_FLOAT,new float[]{1,1,1,1});
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        try {
            // Two cloud faces occupy identical pixels, two blocks apart at 1000 blocks.
            float[] vertices=new float[36];
            float[] xy={.1f,-.6f,.8f,-.6f,.8f,.6f,.1f,-.6f,.8f,.6f,.1f,.6f};
            Matrix4f base=new Matrix4f().perspective((float)Math.toRadians(70),1,7.5f,4096);
            for(int face=0;face<2;face++) for(int i=0;i<6;i++) {
                float distance=1000+face*2;
                int at=face*18+i*3;
                vertices[at]=xy[i*2]*distance/base.m00();
                vertices[at+1]=xy[i*2+1]*distance/base.m11();
                vertices[at+2]=-distance;
            }
            glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER,vbo);
            glBufferData(GL_ARRAY_BUFFER,vertices,GL_STATIC_DRAW);
            int attr=glGetAttribLocation(shader,"vPosition");
            glEnableVertexAttribArray(attr); glVertexAttribPointer(attr,3,GL_FLOAT,false,0,0);
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,output,0);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER));
            glViewport(0,0,SIZE,SIZE);
            glDisable(GL_BLEND); glDisable(GL_CULL_FACE); glDisable(GL_STENCIL_TEST); glDisable(GL_SCISSOR_TEST);
            glDepthMask(true); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LESS);
            glClearDepth(1); glClearColor(0,0,0,0); glClear(GL_DEPTH_BUFFER_BIT|GL_COLOR_BUFFER_BIT);
            glUseProgram(shader); matrix(shader,"uTransform",projection);
            int clip=glGetUniformLocation(shader,DhPortalClipping.UNIFORM);
            if(plane!=null) glUniform4f(clip,plane.x,plane.y,plane.z,plane.w);
            glBindTexture(GL_TEXTURE_2D,light); glUniform1i(glGetUniformLocation(shader,"uLightMap"),0);
            for(String side:new String[]{"North","South","East","West","Top","Bottom"})
                glUniform1f(glGetUniformLocation(shader,"u"+side+"Shading"),1);
            for(int face:new int[]{nearFirst?0:1,nearFirst?1:0}) {
                glUniform4f(glGetUniformLocation(shader,"uColor"),face==0?0:1,face==0?1:0,0,1);
                glDrawArrays(GL_TRIANGLES,face*6,6);
            }
            float[] pixels=new float[SIZE*SIZE*4];
            glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_FLOAT,pixels);
            assertEquals(GL_NO_ERROR,glGetError());
            return pixels;
        } finally {
            glUseProgram(0); glBindFramebuffer(GL_FRAMEBUFFER,0); glBindVertexArray(0);
            glDeleteProgram(shader); glDeleteBuffers(vbo); glDeleteVertexArrays(vao); glDeleteFramebuffers(fbo);
            glDeleteTextures(depth); glDeleteTextures(output); glDeleteTextures(light);
        }
    }

    @Test void closePortalCloudFacesKeepTheirDepthOrder() {
        Matrix4f base=new Matrix4f().perspective((float)Math.toRadians(70),1,7.5f,4096);
        Vector4f cameraPlane=new Vector4f(1,0,0,-.0001f);
        Matrix4f old=DhPortalProjection.clip(base,new Matrix4f(),cameraPlane,false);
        assertNotNull(old);
        float[] a=cloudFaces(old,null,true),b=cloudFaces(old,null,false);
        int conflicts=0;
        for(int i=0;i<a.length;i+=4) if(a[i]!=b[i] || a[i+1]!=b[i+1]) conflicts++;
        assertTrue(conflicts>100,"Old oblique depth must reproduce draw-order-sensitive cloud surfaces");
        Vector4f clip=DhPortalClipping.clipSpacePlane(base,new Matrix4f(),cameraPlane);
        float[] first=cloudFaces(base,clip,true),last=cloudFaces(base,clip,false);
        assertArrayEquals(first,last,.00001f,"Cloud visibility must not depend on draw order");
        int pixel=((SIZE/2)*SIZE+SIZE*3/4)*4;
        assertEquals(0,first[pixel],.00001f);
        assertEquals(1,first[pixel+1],.00001f,"The closer cloud face must win");
    }
}
