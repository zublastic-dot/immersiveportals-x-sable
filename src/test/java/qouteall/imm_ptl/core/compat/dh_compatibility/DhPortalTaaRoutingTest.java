package qouteall.imm_ptl.core.compat.dh_compatibility;
import com.seibel.distanthorizons.core.render.RenderParams;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.junit.jupiter.api.Assertions.*;
class DhPortalTaaRoutingTest {
    public static class Hooks {
        static boolean portal; static int draws, frees, seeded;
        public static boolean isRendering() { return portal; }
        public static void render(RenderParams p) { draws++; }
        public static void seedMainHistory(int f, int w, int h) { seeded=f; assertEquals(800,w); assertEquals(600,h); }
        public static void clear() { frees++; }
    }
    private static class Loader extends ClassLoader {
        Class<?> define(byte[] b) { return defineClass(null,b,0,b.length); }
    }
    @Test void compiledMixinRoutesOnlyPortalCallsAndFreesPrivateResources() throws Exception {
        var n = new ClassNode();
        try(var in = getClass().getResourceAsStream("/qouteall/imm_ptl/core/compat/mixin/dh/MixinDhAntiAliasing.class")) {
            assertNotNull(in); new ClassReader(in).accept(n,0);
        }
        int replaced=0;
        for(var m:n.methods) for(var instruction:m.instructions) {
            if(instruction instanceof MethodInsnNode call && (call.owner.endsWith("/PortalRendering") || call.owner.endsWith("/DhPortalTaa"))) {
                call.owner=Hooks.class.getName().replace('.','/');replaced++;
            }
        }
        assertEquals(4,replaced);var writer=new ClassWriter(0);n.accept(writer);
        var type=new Loader().define(writer.toByteArray());var instance=type.getConstructor().newInstance();
        var render=type.getDeclaredMethod("ip_noCrossDimensionHistory",RenderParams.class,CallbackInfo.class);render.setAccessible(true);
        Hooks.draws=Hooks.frees=0;
        Hooks.portal=false;var main=new CallbackInfo("render",true);render.invoke(instance,null,main);
        assertFalse(main.isCancelled());assertEquals(0,Hooks.draws);
        Hooks.portal=true;var portal=new CallbackInfo("render",true);render.invoke(instance,null,portal);
        assertTrue(portal.isCancelled());assertEquals(1,Hooks.draws);
        var free=type.getDeclaredMethod("ip_freePortalHistory",CallbackInfo.class);free.setAccessible(true);
        free.invoke(instance,new CallbackInfo("free",false));assertEquals(1,Hooks.frees);
        for(var entry:java.util.Map.of("framebufferA",41,"framebufferB",42,"width",800,"height",600).entrySet()) {
            var field=type.getDeclaredField(entry.getKey());field.setAccessible(true);field.setInt(instance,entry.getValue());
        }
        var side=type.getDeclaredField("textureAIsHistory");side.setAccessible(true);
        var seed=type.getDeclaredMethod("ip_seedCrossingHistory",RenderParams.class,CallbackInfo.class);seed.setAccessible(true);
        side.setBoolean(instance,true);seed.invoke(instance,null,new CallbackInfo("render",false));assertEquals(41,Hooks.seeded);
        side.setBoolean(instance,false);seed.invoke(instance,null,new CallbackInfo("render",false));assertEquals(42,Hooks.seeded);
    }
}
