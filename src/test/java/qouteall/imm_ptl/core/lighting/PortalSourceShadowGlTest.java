package qouteall.imm_ptl.core.lighting;

import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Production copy/cache tests against an actual hidden GL 3.3 context, with no Minecraft world. */
@EnabledIfSystemProperty(named = "ipsable.glTests", matches = "true")
class PortalSourceShadowGlTest {
    private static long window;
    private final List<Integer> textures = new ArrayList<>(), framebuffers = new ArrayList<>(), buffers = new ArrayList<>(), samplers = new ArrayList<>();
    private final List<PortalSourceShadow.Store> stores = new ArrayList<>();

    @BeforeAll static void context() {
        assertTrue(glfwInit()); glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(16, 16, "Source shadow snapshot tests", 0, 0);
        assertNotEquals(0, window); glfwMakeContextCurrent(window); GL.createCapabilities();
    }
    @AfterAll static void end() {
        GL.setCapabilities(null); glfwMakeContextCurrent(0); glfwDestroyWindow(window); glfwTerminate();
    }
    @AfterEach void cleanup() {
        stores.forEach(PortalSourceShadow.Store::clear);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glDisable(GL_SCISSOR_TEST); glDepthMask(true); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glActiveTexture(GL_TEXTURE0); glBindSampler(3, 0);
        framebuffers.forEach(id -> glDeleteFramebuffers(id)); textures.forEach(id -> glDeleteTextures(id));
        buffers.forEach(id -> glDeleteBuffers(id)); samplers.forEach(id -> glDeleteSamplers(id));
        assertEquals(GL_NO_ERROR, glGetError());
    }

    private int depth(int size, float value) {
        int texture = glGenTextures(); textures.add(texture); glBindTexture(GL_TEXTURE_2D, texture);
        var data = BufferUtils.createFloatBuffer(size * size);
        for (int i = 0; i < size * size; i++) data.put(value);
        data.flip(); glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, size, size, 0, GL_DEPTH_COMPONENT, GL_FLOAT, data);
        return texture;
    }
    private float[] contents(int texture, int size) {
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, texture);
            var data = BufferUtils.createFloatBuffer(size * size);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT, data);
            float[] result = new float[size * size]; data.get(result); return result;
        } finally { glBindTexture(GL_TEXTURE_2D, previous); }
    }
    private PortalSourceShadow.Store store(int worlds, long bytes) {
        var store = new PortalSourceShadow.Store(worlds, bytes); stores.add(store); return store;
    }
    private static PortalSourceShadow.Capture capture(int texture, int size, long game, long day) {
        return new PortalSourceShadow.Capture(texture, size, new Vec3(117.25, 182.5, 218.75),
            new Matrix4f().rotateX((float) Math.PI / 2), new Matrix4f().setOrthoSymmetric(192, 192, -256, 256),
            96, 1, .25f, game, day);
    }

    @Test void ownedNativeDepthSurvivesSourceOverwriteAndDeletion() {
        int source = depth(16, .3f); var store = store(4, PortalSourceShadow.MAX_BYTES); Object world = new Object();
        var snapshot = store.capture(world, new Object(), capture(source, 16, 100, 9000), 1_000);
        assertNotNull(snapshot); assertNotEquals(source, snapshot.texture()); assertEquals(16, snapshot.resolution());
        glBindTexture(GL_TEXTURE_2D, source);
        var overwrite = BufferUtils.createFloatBuffer(16 * 16);
        for (int i = 0; i < 16 * 16; i++) overwrite.put(.8f);
        overwrite.flip(); glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 16, 16, GL_DEPTH_COMPONENT, GL_FLOAT, overwrite);
        glDeleteTextures(source);
        for (float actual : contents(snapshot.texture(), 16)) assertEquals(.3f, actual, 1e-6);
        glBindTexture(GL_TEXTURE_2D, snapshot.texture());
        assertEquals(GL_DEPTH_COMPONENT32F, glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT));
        assertEquals(GL_NONE, glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE));
    }

    @Test void copyRestoresHostStateAndIgnoresHostScissorAndUnpackBuffer() {
        int source = depth(16, .65f), sentinel = depth(4, .1f);
        int read = glGenFramebuffers(), draw = glGenFramebuffers(), sampler = glGenSamplers(), buffer = glGenBuffers();
        framebuffers.add(read); framebuffers.add(draw); samplers.add(sampler); buffers.add(buffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glReadBuffer(GL_NONE);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw); glDrawBuffer(GL_NONE);
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, sentinel); glBindSampler(3, sampler);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, buffer); glBufferData(GL_PIXEL_UNPACK_BUFFER, 8, GL_STATIC_DRAW);
        glEnable(GL_SCISSOR_TEST); glScissor(1, 2, 1, 1); glViewport(3, 4, 7, 8); glDepthMask(false);
        var store = store(4, PortalSourceShadow.MAX_BYTES);
        var snapshot = store.capture(new Object(), new Object(), capture(source, 16, 0, 9000), 1_000);
        assertNotNull(snapshot);
        assertEquals(GL_TEXTURE3, glGetInteger(GL_ACTIVE_TEXTURE)); assertEquals(sentinel, glGetInteger(GL_TEXTURE_BINDING_2D));
        assertEquals(sampler, glGetInteger(GL_SAMPLER_BINDING)); assertEquals(buffer, glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING));
        assertEquals(read, glGetInteger(GL_READ_FRAMEBUFFER_BINDING)); assertEquals(draw, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
        assertEquals(GL_NONE, glGetInteger(GL_READ_BUFFER)); assertEquals(GL_NONE, glGetInteger(GL_DRAW_BUFFER));
        assertTrue(glIsEnabled(GL_SCISSOR_TEST)); assertFalse(glGetBoolean(GL_DEPTH_WRITEMASK));
        int[] box = new int[4]; glGetIntegerv(GL_SCISSOR_BOX, box); assertArrayEquals(new int[]{1, 2, 1, 1}, box);
        glGetIntegerv(GL_VIEWPORT, box); assertArrayEquals(new int[]{3, 4, 7, 8}, box);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        for (float actual : contents(snapshot.texture(), 16)) assertEquals(.65f, actual, 1e-6);
    }

    @Test void identityOwnershipAndPipelineInvalidationCannotReuseReplacementWorld() {
        record WorldName(String dimension) {}
        Object first = new WorldName("overworld"), replacement = new WorldName("overworld"), pipeline = new Object(), otherPipeline = new Object();
        int source = depth(8, .4f); var store = store(4, PortalSourceShadow.MAX_BYTES);
        var a = store.capture(first, pipeline, capture(source, 8, 10, 9000), 100);
        var b = store.capture(replacement, otherPipeline, capture(source, 8, 10, 9000), 100);
        assertNotNull(a); assertNotNull(b); assertNotSame(a, b);
        assertSame(a, store.get(first, 10, 9000, 101)); assertSame(b, store.get(replacement, 10, 9000, 101));
        store.invalidatePipeline(pipeline);
        assertNull(store.get(first, 10, 9000, 102)); assertEquals(0, a.texture());
        assertSame(b, store.get(replacement, 10, 9000, 102));
        store.retain(List.of(first)); assertNull(store.get(replacement, 10, 9000, 103)); assertEquals(0, b.texture());
    }

    @Test void metadataAndMatricesAreCoherentDefensiveCopies() {
        int source = depth(8, .4f); var store = store(4, PortalSourceShadow.MAX_BYTES);
        var input = capture(source, 8, 123, 9000);
        var snapshot = store.capture(new Object(), new Object(), input, 5_000);
        assertNotNull(snapshot);
        ((Matrix4f) input.modelView()).identity(); ((Matrix4f) input.projection()).identity();
        assertEquals(0, snapshot.towardLight().x, 1e-6); assertEquals(1, snapshot.towardLight().y, 1e-6);
        assertEquals(0, snapshot.towardLight().z, 1e-6);
        snapshot.modelView().zero(); snapshot.projection().zero(); snapshot.inverseProjectionModelView().zero();
        assertTrue(new Matrix4f(snapshot.projection()).mul(snapshot.modelView()).mul(snapshot.inverseProjectionModelView()).equals(new Matrix4f(), 1e-4f));
        assertEquals(123, snapshot.sourceGameTime()); assertEquals(9000, snapshot.sourceDayTime());
        assertEquals(.25f, snapshot.sourceSunAngle()); assertEquals(96, snapshot.shadowDistance());
        assertEquals(new Vec3(117.25, 182.5, 218.75), snapshot.camera());
    }

    @Test void ageClockJumpAndWorldClockRollbackInvalidateSnapshot() {
        int source = depth(8, .4f); Object world = new Object(); var store = store(4, PortalSourceShadow.MAX_BYTES);
        var snapshot = store.capture(world, new Object(), capture(source, 8, 100, 9000), 1_000);
        assertNotNull(snapshot);
        assertSame(snapshot, store.get(world, 101, 9000, 1_001), "Stopped daylight is allowed");
        assertNull(store.get(world, 101, 8000, 1_001), "A time command cannot reuse the old shadow map");
        assertNull(store.get(world, 99, 9000, 1_001));
        assertNull(store.get(world, 100, 9000, 999));
        assertNull(store.get(world, 100, 9000, 1_001 + PortalSourceShadow.MAX_AGE_NANOS));
        assertNull(store.get(world, Long.MAX_VALUE, Long.MIN_VALUE, 1_001));
    }

    @Test void nativeIrisDayAndMoonMatricesPointTowardTheRenderedLight() {
        int source = depth(8, .4f); Object world = new Object(), pipeline = new Object();
        var store = store(4, 2048);
        for (float shadowAngle : new float[]{.05f, .25f, .3656841f, .37751198f, .45f}) {
            for (float rotation : new float[]{-60, -40, 0, 45}) {
                for (boolean moon : new boolean[]{false, true}) {
                    float sunAngle = shadowAngle + (moon ? .5f : 0);
                    var pose = new PoseStack();
                    ShadowMatrices.createModelViewMatrix(pose, shadowAngle, 2, rotation,
                        117.25, 182.5, 218.75, -256, 256);
                    var nativeCapture = new PortalSourceShadow.Capture(source, 8,
                        new Vec3(117.25, 182.5, 218.75), pose.last().pose(),
                        ShadowMatrices.createOrthoMatrix(96, -256, 256), 96, 1, sunAngle, 123, 9000);
                    var snapshot = store.capture(world, pipeline, nativeCapture, 5_000);
                    assertNotNull(snapshot);
                    double theta = 2 * Math.PI * (shadowAngle - .25), tilt = Math.toRadians(rotation);
                    String phase = "sun=" + sunAngle + ", rotation=" + rotation;
                    assertEquals(-Math.sin(theta), snapshot.towardLight().x, 2e-6, phase);
                    assertEquals(Math.cos(theta) * Math.cos(tilt), snapshot.towardLight().y, 2e-6, phase);
                    assertEquals(-Math.cos(theta) * Math.sin(tilt), snapshot.towardLight().z, 2e-6, phase);
                    assertTrue(snapshot.towardLight().y > 0, "The rendered sun/moon must be above the horizon: " + phase);
                    assertEquals(sunAngle, snapshot.sourceSunAngle());
                }
            }
        }
    }

    @Test void aggregateBudgetIncludesReplacementAndWorldEvictionReleasesTextures() {
        int source = depth(8, .4f); var store = store(2, 2L * 8 * 8 * 4);
        Object a = new Object(), b = new Object(), c = new Object(), pipeline = new Object();
        var first = store.capture(a, pipeline, capture(source, 8, 0, 9000), 1);
        var second = store.capture(b, pipeline, capture(source, 8, 0, 9000), 1);
        assertNotNull(first); assertNotNull(second); assertEquals(512, store.bytes());
        var replaced = store.capture(a, pipeline, capture(source, 8, 0, 9000), 2);
        assertNotNull(replaced); assertEquals(0, first.texture()); assertTrue(replaced.generation() > first.generation());
        assertEquals(512, store.bytes());
        assertNotNull(store.capture(c, pipeline, capture(source, 8, 0, 9000), 3));
        assertEquals(0, second.texture()); assertEquals(2, store.size()); assertEquals(512, store.bytes());
        store.clear(); assertEquals(0, store.size()); assertEquals(0, store.bytes()); assertEquals(0, replaced.texture());
    }

    @Test void oversizedWrongFormatAndResolutionMismatchKeepExistingPublication() {
        int source = depth(8, .4f), tooLarge = depth(16, .2f); var store = store(2, 512);
        Object world = new Object(), pipeline = new Object();
        var existing = store.capture(world, pipeline, capture(source, 8, 0, 9000), 1);
        assertNotNull(existing);
        assertNull(store.capture(world, pipeline, capture(tooLarge, 16, 0, 9000), 2));
        assertNull(store.capture(world, pipeline, capture(source, 16, 0, 9000), 2));
        int color = glGenTextures(); textures.add(color); glBindTexture(GL_TEXTURE_2D, color);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        assertNull(store.capture(world, pipeline, capture(color, 8, 0, 9000), 2));
        assertSame(existing, store.get(world, 0, 9000, 3)); assertEquals(256, store.bytes());
    }

    @Test void sourceSamplerParametersRemainNative() {
        int source = depth(8, .4f); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LESS); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        var store = store(2, 512);
        assertNotNull(store.capture(new Object(), new Object(), capture(source, 8, 0, 9000), 1));
        glBindTexture(GL_TEXTURE_2D, source);
        assertEquals(GL_COMPARE_REF_TO_TEXTURE, glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE));
        assertEquals(GL_LESS, glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC));
        assertEquals(GL_LINEAR, glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER));
    }

    @Test void successivePublicationsReuseOnlyRetiredStorage() {
        int source = depth(8, .4f); Object world = new Object(), pipeline = new Object();
        var store = store(4, 2048);
        var first = store.capture(world, pipeline, capture(source, 8, 0, 9000), 1);
        assertNotNull(first); int firstTexture = first.texture();
        var second = store.capture(world, pipeline, capture(source, 8, 0, 9000), 2);
        assertNotNull(second); int secondTexture = second.texture();
        assertNotEquals(firstTexture, secondTexture); assertEquals(0, first.texture());
        assertTrue(glIsTexture(firstTexture), "Retired storage is reusable, not reallocated every frame");
        var third = store.capture(world, pipeline, capture(source, 8, 0, 9000), 3);
        assertNotNull(third); assertEquals(firstTexture, third.texture()); assertEquals(0, second.texture());
        var fourth = store.capture(world, pipeline, capture(source, 8, 0, 9000), 4);
        assertNotNull(fourth); assertEquals(secondTexture, fourth.texture()); assertEquals(512, store.bytes());
        assertEquals(1, store.size()); store.retain(List.of());
        assertEquals(0, store.bytes()); assertFalse(glIsTexture(firstTexture)); assertFalse(glIsTexture(secondTexture));
    }

    @Test void invalidNewPipelineCannotKeepPreviousGenerationAlive() {
        int source = depth(8, .4f); Object world = new Object(); var store = store(4, 2048);
        var previous = store.capture(world, new Object(), capture(source, 8, 0, 9000), 1);
        assertNotNull(previous);
        assertNull(store.capture(world, new Object(), capture(source, 16, 0, 9000), 2));
        assertEquals(0, previous.texture()); assertNull(store.get(world, 0, 9000, 3));
    }

    @Test void singleImageBudgetRetiresPublicationBeforeReusingStorage() {
        int source = depth(8, .4f), replacementSource = depth(8, .7f);
        Object world = new Object(), pipeline = new Object(); var store = store(1, 256);
        var first = store.capture(world, pipeline, capture(source, 8, 0, 9000), 1);
        assertNotNull(first); int texture = first.texture();
        var second = store.capture(world, pipeline, capture(replacementSource, 8, 0, 9000), 2);
        assertNotNull(second); assertEquals(texture, second.texture()); assertEquals(0, first.texture());
        assertSame(second, store.get(world, 0, 9000, 3)); assertEquals(256, store.bytes());
        for (float value : contents(second.texture(), 8)) assertEquals(.7f, value, 1e-6);
        store.clear(); assertFalse(glIsTexture(texture)); assertEquals(0, store.bytes());
    }

    @Test void copyDoesNotInterruptAnActiveOcclusionQuery() {
        int source = depth(8, .4f), query = glGenQueries(); var store = store(4, 2048);
        glBeginQuery(GL_ANY_SAMPLES_PASSED, query);
        try {
            assertNotNull(store.capture(new Object(), new Object(), capture(source, 8, 0, 9000), 1));
            assertEquals(query, glGetQueryi(GL_ANY_SAMPLES_PASSED, GL_CURRENT_QUERY));
        } finally { glEndQuery(GL_ANY_SAMPLES_PASSED); glDeleteQueries(query); }
    }
}
