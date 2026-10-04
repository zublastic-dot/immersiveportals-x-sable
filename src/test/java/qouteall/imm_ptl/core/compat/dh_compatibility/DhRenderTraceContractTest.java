package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.Type;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DhRenderTraceContractTest {
    private ClassNode read(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/" + name + ".class")) {
            assertNotNull(in, name); var node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); return node;
        }
    }
    @Test void installedDhHooksObserveSetupAndFinalIrisSelectionAtTheirRealCallSites() throws IOException {
        var meta = read("com/seibel/distanthorizons/common/render/openGl/GlDhMetaRenderer_neoforge");
        assertTrue(meta.methods.stream().anyMatch(m -> m.name.equals("runRenderPassSetup")
            && m.desc.equals("(Lcom/seibel/distanthorizons/core/render/RenderParams;)V")));
        var terrain = read("com/seibel/distanthorizons/common/render/openGl/terrain/GlDhTerrainShaderProgram_neoforge");
        var render = terrain.methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
        assertEquals("(Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/util/objects/SortedArraySet;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V", render.desc);
        var eventTypes = new ArrayList<String>(); String event = null;
        for (var instruction : render.instructions) {
            if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Type type) event = type.getInternalName();
            if (instruction instanceof MethodInsnNode call && call.name.equals("fireAllEvents")) {
                assertEquals("(Ljava/lang/Class;Ljava/lang/Object;)Z", call.desc);
                eventTypes.add(event);
            }
        }
        assertEquals(List.of("com/seibel/distanthorizons/api/methods/events/abstractEvents/DhApiBeforeRenderPassEvent",
            "com/seibel/distanthorizons/api/methods/events/abstractEvents/DhApiBeforeBufferRenderEvent"), eventTypes);
    }

    @Test void installedIrisExposesPublicPipelineLocalDepthTextureSuppliers() throws IOException {
        var compat = read("net/irisshaders/iris/compat/dh/DHCompat");
        for (String name : List.of("getDepthTex", "getDepthTexNoTranslucent"))
            assertTrue(compat.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals("()I") && (m.access & 1) != 0));
        var sampler = read("net/irisshaders/iris/samplers/IrisSamplers");
        for (String name : List.of("dhDepthTex", "dhDepthTex1")) {
            boolean found = sampler.methods.stream().flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                .anyMatch(i -> i instanceof LdcInsnNode c && name.equals(c.cst));
            assertTrue(found, name);
        }
    }
}
