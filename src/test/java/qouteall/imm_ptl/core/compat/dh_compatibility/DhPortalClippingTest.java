package qouteall.imm_ptl.core.compat.dh_compatibility;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class DhPortalClippingTest {
    public static class Hooks {
        static Vector4f plane;
        static int location, lookups;
        static final List<Vector4f> uploads = new ArrayList<>();
        public static Vector4f getGeometryClipPlane() { return plane; }
        public static int tryGetUniformLocation(CharSequence name) {
            assertEquals(DhPortalClipping.UNIFORM,name.toString());
            lookups++; return location;
        }
        public static void glUniform4f(int uniform,float x,float y,float z,float w) {
            assertEquals(location,uniform);
            uploads.add(new Vector4f(x,y,z,w));
        }
    }
    private static class Loader extends ClassLoader {
        Loader() { super(DhPortalClippingTest.class.getClassLoader()); }
        Class<?> define(byte[] code) { return defineClass(null,code,0,code.length); }
    }
    private Object hook() throws Exception {
        var node = new ClassNode();
        try(var stream=getClass().getResourceAsStream("/qouteall/imm_ptl/core/compat/mixin/dh/MixinDhGeometryClipping.class")) {
            assertNotNull(stream); new ClassReader(stream).accept(node,0);
        }
        node.access &= ~Opcodes.ACC_ABSTRACT;
        int replaced=0;
        for(var method:node.methods) {
            if(method.name.equals("tryGetUniformLocation")) {
                method.access &= ~Opcodes.ACC_ABSTRACT;
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD,1));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,Hooks.class.getName().replace('.','/'),
                    method.name,method.desc,false));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
                method.maxStack=1; method.maxLocals=2;
            } else for(var instruction:method.instructions) {
                if(instruction instanceof MethodInsnNode call &&
                    (call.owner.equals("org/lwjgl/opengl/GL20") ||
                     call.owner.equals("qouteall/imm_ptl/core/compat/dh_compatibility/DhPortalRendering"))) {
                    call.owner=Hooks.class.getName().replace('.','/'); replaced++;
                }
            }
        }
        assertEquals(2,replaced,"Substitute only live view lookup and GL upload");
        var writer=new ClassWriter(0); node.accept(writer);
        Hooks.lookups=0; Hooks.uploads.clear(); Hooks.plane=null;
        return new Loader().define(writer.toByteArray()).getConstructor().newInstance();
    }
    private void bind(Object hook) throws Exception {
        var method=hook.getClass().getDeclaredMethod("ip_setThisViewPlane",CallbackInfo.class);
        method.setAccessible(true); method.invoke(hook,new Object[]{null});
    }
    @Test void compiledBindHookResetsThePlaneBetweenPortalParentAndSiblingViews() throws Exception {
        Hooks.location=17; Object hook=hook();
        Hooks.plane=new Vector4f(1,2,3,4); bind(hook);
        Hooks.plane=null; bind(hook);
        Hooks.plane=new Vector4f(-1,0,1,8); bind(hook);
        Hooks.plane=null; bind(hook);
        assertEquals(List.of(new Vector4f(1,2,3,4),new Vector4f(0,0,0,0),
            new Vector4f(-1,0,1,8),new Vector4f(0,0,0,0)),Hooks.uploads);
        assertEquals(1,Hooks.lookups);
    }
    @Test void otherShaderProgramsDoNotReceiveClipUniformUploads() throws Exception {
        Hooks.location=-1; Object hook=hook();
        Hooks.plane=new Vector4f(1,0,0,1); bind(hook); bind(hook);
        assertTrue(Hooks.uploads.isEmpty()); assertEquals(1,Hooks.lookups);
    }
    @Test void clipHalfSpaceSurvivesRotationReflectionScaleAndBothDepthConventions() {
        for(boolean reverse:new boolean[]{false,true}) for(float mirror:new float[]{-1,1}) {
            Matrix4f projection=new Matrix4f().perspective(1.2f,1.7f,reverse?4096:7.5f,reverse?7.5f:4096,reverse);
            Matrix4f view=new Matrix4f().rotateX(.7f).rotateY(2.1f).scale(mirror*2,2,2);
            Vector4f plane=new Vector4f(1,0,0,-.0001f);
            Vector4f clipPlane=DhPortalClipping.clipSpacePlane(projection,view,plane);
            assertNotNull(clipPlane);
            for(float x:new float[]{-.5f,0,.5f,100}) {
                Vector4f point=new Vector4f(x,2,-50,1);
                float expected=plane.dot(point);
                float actual=clipPlane.dot(new Matrix4f(projection).mul(view).transform(point));
                assertEquals(expected,actual,.00003f);
                assertEquals(expected<0,actual<0);
            }
        }
    }
}
