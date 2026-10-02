package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual world-stack wrapper and checks its optional Iris binary contracts. */
class IrisSourceRefreshContractTest {
    public static class Info {
        public ClientLevel world;
        public Vec3 cameraPos=Vec3.ZERO;
        public int renderDistance=8;
        public boolean doRenderHand;
        public static int depth;
        public static void pushRenderInfo(Info info) { depth++; }
        public static void popRenderInfo() { depth--; }
    }
    public static class Backend {
        static int observed;
        public static void switchAndRenderTheWorld(ClientLevel world,Vec3 now,Vec3 previous,Consumer<Runnable> wrapper,int distance,boolean hand) {
            observed=Info.depth;
            wrapper.accept(()->{});
        }
    }
    private static ClassNode read(String name) throws Exception {
        try(var input=IrisSourceRefreshContractTest.class.getResourceAsStream('/'+name+".class")) {
            assertNotNull(input,name); var result=new ClassNode(); new ClassReader(input).accept(result,0); return result;
        }
    }
    private static String name(Class<?> type) { return type.getName().replace('.','/'); }
    private static class Loader extends ClassLoader {
        Loader() { super(IrisSourceRefreshContractTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null,bytes,0,bytes.length); }
    }
    @Test void actualWorldStackWrapperUnwindsWhenNativeRenderOrCallbackFails() throws Exception {
        var original=read("qouteall/imm_ptl/core/render/MyGameRenderer");
        var method=original.methods.stream().filter(m->m.name.equals("renderWorldNew")).findFirst().orElseThrow();
        for(var instruction:method.instructions) if(instruction instanceof MethodInsnNode call && call.name.equals("switchAndRenderTheWorld")) call.owner=name(Backend.class);
        var isolated=new ClassNode(); isolated.version=Opcodes.V21; isolated.access=Opcodes.ACC_PUBLIC;
        isolated.name="test/NativeWorldStackWrapper"; isolated.superName="java/lang/Object"; isolated.methods.add(method);
        var writer=new ClassWriter(0);
        isolated.accept(new ClassRemapper(writer,new SimpleRemapper(Map.of("qouteall/imm_ptl/core/render/context_management/WorldRenderInfo",name(Info.class)))));
        var type=new Loader().define(writer.toByteArray());
        var run=type.getMethod("renderWorldNew",Info.class,Consumer.class);
        Info.depth=2; var failure=new IllegalArgumentException("source setup failed");
        var thrown=assertThrows(InvocationTargetException.class,()->run.invoke(null,new Info(),(Consumer<Runnable>)draw->{throw failure;}));
        assertSame(failure,thrown.getCause()); assertEquals(3,Backend.observed); assertEquals(2,Info.depth);
        run.invoke(null,new Info(),(Consumer<Runnable>)Runnable::run);
        assertEquals(2,Info.depth); Info.depth=0;
    }
    @Test void realWorldMutationAndLightmapSetupAreInsideTheRecoveryHandler() throws Exception {
        var method=read("qouteall/imm_ptl/core/render/MyGameRenderer").methods.stream()
            .filter(m->m.name.equals("switchAndRenderTheWorld")).findFirst().orElseThrow();
        for(String operation:new String[]{"ip_setWorldRenderer","ip_setCamera","switchContextWithCurrentWorldRenderer","updateLightTexture"}) {
            var call=java.util.stream.StreamSupport.stream(method.instructions.spliterator(),false)
                .filter(i->i instanceof MethodInsnNode m&&m.name.equals(operation)).findFirst().orElseThrow();
            int index=method.instructions.indexOf(call);
            assertTrue(method.tryCatchBlocks.stream().anyMatch(block->block.type==null
                && method.instructions.indexOf(block.start)<=index && index<method.instructions.indexOf(block.end)),operation);
        }
    }
    @Test void phaseRecoveryAccessorMatchesActualIrisPrivateStaticFields() throws Exception {
        var actual=read("net/irisshaders/iris/layer/GbufferPrograms");
        var accessor=read("qouteall/imm_ptl/core/compat/mixin/iris/IEIrisGbufferPrograms");
        for(String field:new String[]{"entities","blockEntities","outline"}) {
            assertTrue(actual.fields.stream().anyMatch(f->f.name.equals(field)&&f.desc.equals("Z")&&(f.access&Opcodes.ACC_STATIC)!=0));
            assertTrue(accessor.methods.stream().anyMatch(m->m.name.equals("ip_"+field)&&m.desc.equals("()Z")));
            assertTrue(accessor.methods.stream().anyMatch(m->m.name.equals("ip_"+field)&&m.desc.equals("(Z)V")));
        }
    }
    @Test void auxiliaryFrustumHookMatchesNativeMethodAndHasAnEarlyCommonGuard() throws Exception {
        var target=read("net/irisshaders/iris/shadows/ShadowRenderer");
        String holder="Lnet/irisshaders/iris/shadows/frustum/FrustumHolder;";
        assertTrue(target.methods.stream().anyMatch(m->m.name.equals("createShadowFrustum")&&m.desc.equals("(F"+holder+")"+holder)));
        var hook=read("qouteall/imm_ptl/core/compat/mixin/iris/MixinIrisSourceShadowCapture").methods.stream()
            .filter(m->m.name.equals("ip_sourceCastersIndependentOfView")).findFirst().orElseThrow();
        int guard=-1,earlyReturn=-1,replace=-1;
        for(int i=0;i<hook.instructions.size();i++) {
            var op=hook.instructions.get(i);
            if(op instanceof MethodInsnNode call&&call.owner.endsWith("/PortalSourceRefreshPolicy")&&call.name.equals("isRendering")) guard=i;
            if(op.getOpcode()==Opcodes.RETURN&&earlyReturn<0) earlyReturn=i;
            if(op instanceof MethodInsnNode call&&call.name.equals("select")) replace=i;
        }
        assertTrue(guard>=0&&guard<earlyReturn&&earlyReturn<replace,"Main/visible portal passes return before optional frustum replacement");
        var inject=hook.visibleAnnotations.stream().filter(a->a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(java.util.List.of("createShadowFrustum"),inject.values.get(inject.values.indexOf("method")+1));
    }
}
