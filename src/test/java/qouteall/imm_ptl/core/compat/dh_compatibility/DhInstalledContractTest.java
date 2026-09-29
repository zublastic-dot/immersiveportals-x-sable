package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.io.IOException;
import java.net.JarURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Read bytecode without initializing DH or a Minecraft/GL runtime. */
class DhInstalledContractTest {
    @Test void testedArtifactActuallyEnablesPortalCompatibility() throws IOException {
        // Contract tests alone previously passed while the runtime gate excluded the update.
        var resource = getClass().getResource("/com/seibel/distanthorizons/core/api/internal/ClientApi.class");
        assertNotNull(resource);
        var connection = (JarURLConnection) resource.openConnection();
        connection.setUseCaches(false);
        try (var jar = connection.getJarFile();
             var stream = jar.getInputStream(jar.getJarEntry("META-INF/neoforge.mods.toml"))) {
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            var version = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"").matcher(metadata);
            assertTrue(version.find(), "DH artifact must declare its NeoForge mod version");
            assertTrue(DhCompatibility.supports(version.group(1)),
                "Contract-tested DH " + version.group(1) + " must enable our portal mixins");
        }
    }

    private void hasMethod(String owner, String method, String descriptor) throws IOException {
        String path = "/com/seibel/distanthorizons/" + owner + ".class";
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE);
            assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(method)
                && m.desc.equals(descriptor)), path + "#" + method + descriptor);
        }
    }
    @Test void eventAndBothLevelLifecyclesMatchInspectedArtifact() throws IOException {
        hasMethod("core/wrapperInterfaces/modAccessor/AbstractImmersivePortalsAccessor$BeforeRenderEvent",
            "beforeRender", "(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiCancelableEventParam;)V");
        for (String level : new String[]{"DhClientLevel", "DhClientServerLevel"}) {
            hasMethod("core/level/" + level, "clientTick", "()V");
            hasMethod("core/level/" + level, "close", "()V");
        }
        hasMethod("core/level/DhClientServerLevel", "getClientLevelWrapper",
            "()Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;");
    }
    @Test void scopedRenderAndLightmapTargetsExist() throws IOException {
        hasMethod("core/api/internal/ClientApi", "renderLodLayer", "(Z)V");
        hasMethod("common/wrappers/minecraft/MinecraftRenderWrapper_neoforge", "getLightmapClientLevelWrapper",
            "()Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;");
        hasMethod("common/render/openGl/postProcessing/antialiasing/GlDhTaaRenderer_neoforge", "render",
            "(Lcom/seibel/distanthorizons/core/render/RenderParams;)V");
    }

    @Test void destinationViewAndBothRenderPassTargetsExist() throws IOException {
        String client = "common/wrappers/minecraft/MinecraftClientWrapper_neoforge";
        hasMethod(client, "getWrappedClientLevel", "(Z)Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;");
        hasMethod(client, "getPlayerBlockPos", "()Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos;");
        hasMethod(client, "getPlayerChunkPos", "()Lcom/seibel/distanthorizons/core/pos/DhChunkPos;");
        String render = "common/wrappers/minecraft/MinecraftRenderWrapper_neoforge";
        hasMethod(render, "getCameraExactPosition", "()Lcom/seibel/distanthorizons/core/util/math/DhVec3d;");
        hasMethod(render, "getRenderDistance", "()I");
        hasMethod("core/render/RenderParams", "update", "(Lcom/seibel/distanthorizons/api/enums/rendering/EDhApiRenderPass;"
            + "Lcom/seibel/distanthorizons/core/api/internal/rendering/DhRenderState;)V");
        for (String pass : new String[]{"render", "renderDeferred"}) {
            hasMethod("core/render/renderer/LodRenderer", pass, "(Lcom/seibel/distanthorizons/core/render/RenderParams;"
                + "Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V");
        }
    }

    @Test void portalZoomIsolationStillWrapsTheActualCallSite() throws IOException {
        try (var stream = getClass().getResourceAsStream("/com/seibel/distanthorizons/core/api/internal/ClientApi.class")) {
            assertNotNull(stream);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var method = node.methods.stream().filter(m -> m.name.equals("renderLodLayer") && m.desc.equals("(Z)V"))
                .findFirst().orElseThrow();
            int matches = 0;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/seibel/distanthorizons/core/render/CameraZoom")
                    && call.name.equals("update")
                    && call.desc.equals("(Lcom/seibel/distanthorizons/core/api/internal/rendering/DhRenderState;)V")) {
                    matches++;
                }
            }
            assertEquals(1, matches, "MixinDhClientApi must intercept the render layer's CameraZoom update");
        }
    }

    @Test void inspectedTerrainShaderSupportsUnjitteredUniformAndPhaseRestoration() throws IOException {
        String owner = "common/render/openGl/terrain/GlDhTerrainShaderProgram_neoforge";
        hasMethod(owner, "fillUniformData",
            "(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiRenderParam;)V");
        try (var stream = getClass().getResourceAsStream("/com/seibel/distanthorizons/" + owner + ".class")) {
            assertNotNull(stream);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_CODE);
            for (String field : new String[]{"frameIndexMod8", "uFrameMod8"}) {
                assertTrue(node.fields.stream().anyMatch(f -> f.name.equals(field) && f.desc.equals("I")), field);
            }
        }
        try (var stream = getClass().getResourceAsStream("/assets/distanthorizons/shaders/terrain/gl/vert.vert")) {
            assertNotNull(stream);
            String shader = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(shader.contains("if (uFrameMod8 > 0)"));
            assertTrue(shader.contains("gl_Position.xy = TAAJitter(gl_Position.xy, gl_Position.w)"));
        }
    }
}
