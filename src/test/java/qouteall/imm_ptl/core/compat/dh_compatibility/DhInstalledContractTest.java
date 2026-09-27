package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/** Read bytecode without initializing DH or a Minecraft/GL runtime. */
class DhInstalledContractTest {
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
