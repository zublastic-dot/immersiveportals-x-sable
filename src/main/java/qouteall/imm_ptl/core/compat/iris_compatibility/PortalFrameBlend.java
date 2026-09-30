package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL33.*;

/** Experimental display-space seam correction, not cross-dimensional lighting. */
public final class PortalFrameBlend {
    public static final String VERTEX = """
        #version 330 core
        out vec2 uv;
        void main(){ vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.0-1.0,0,1); }
        """;
    public static final String FRAGMENT = """
        #version 330 core
        in vec2 uv; out vec4 result;
        uniform sampler2D image, mask, depths;
        uniform mat4 inverseProjection;
        vec3 position(vec2 p) { vec4 v=inverseProjection*vec4(p*2-1,texture(depths,p).r*2-1,1);return v.xyz/v.w; }
        float side(vec2 p){return step(.5,texture(mask,p).r);}
        vec3 localColor(vec2 p,vec2 pixel,float s){
            vec3 sum=vec3(0);float count=0;
            for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){
                vec2 q=p+vec2(x,y)*pixel*2;
                if(side(q)==s && distance(position(q),position(p))<.6){sum+=texture(image,q).rgb;count++;}
            }
            return sum/max(count,1);
        }
        void main(){
            vec4 original=texture(image,uv);result=original;
            if(texture(depths,uv).r>=.999999) return;
            vec2 pixel=1.0/vec2(textureSize(image,0));float s=side(uv);
            if(side(uv+vec2(12,0)*pixel)==s && side(uv-vec2(12,0)*pixel)==s
                && side(uv+vec2(0,12)*pixel)==s && side(uv-vec2(0,12)*pixel)==s)return;
            vec2 other=uv;float closest=13;
            // Short screen-space band; never sample a distant surface across a silhouette.
            for(int d=1;d<=12;d++)for(int axis=0;axis<4;axis++){
                vec2 direction=axis==0?vec2(1,0):axis==1?vec2(-1,0):axis==2?vec2(0,1):vec2(0,-1);
                vec2 q=uv+direction*pixel*float(d);
                if(any(lessThan(q,vec2(0)))||any(greaterThan(q,vec2(1))))continue;
                if(float(d)<closest && side(q)!=s && texture(depths,q).r<.999999 && distance(position(q),position(uv))<.6){other=q;closest=float(d);}
            }
            if(closest>12)return;
            vec3 a=localColor(uv,pixel,s),b=localColor(other,pixel,1-s);
            // Reject bright/emissive edges; this trial targets the dark portal frame.
            if(max(max(a.r,a.g),a.b)>.65 || max(max(b.r,b.g),b.b)>.65)return;
            float amount=.5*(1-smoothstep(1,12,closest));
            vec3 gain=clamp((mix(a,b,amount)+.02)/(a+.02),vec3(.5),vec3(2));
            result=vec4(clamp(original.rgb*gain,0,1),original.a);
        }
        """;
    private int color, depth, mask, vao, blend, solid, width, height;
    public void clear() {
        if(color!=0){glDeleteTextures(color);glDeleteTextures(depth);glDeleteTextures(mask);glDeleteVertexArrays(vao);glDeleteProgram(blend);glDeleteProgram(solid);}
        color=depth=mask=vao=blend=solid=width=height=0;
    }
    private static int program(String fragment){
        int p=glCreateProgram();
        try {
            for(int type:new int[]{GL_VERTEX_SHADER,GL_FRAGMENT_SHADER}){
                int s=glCreateShader(type);glShaderSource(s,type==GL_VERTEX_SHADER?VERTEX:fragment);glCompileShader(s);
                if(glGetShaderi(s,GL_COMPILE_STATUS)==0){String error=glGetShaderInfoLog(s);glDeleteShader(s);throw new IllegalStateException(error);}
                glAttachShader(p,s);glDeleteShader(s);
            }
            glLinkProgram(p);if(glGetProgrami(p,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(p));return p;
        }catch(RuntimeException e){glDeleteProgram(p);throw e;}
    }
    private static int texture(){int id=glGenTextures();glBindTexture(GL_TEXTURE_2D,id);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return id;}
    public void apply(int framebuffer,int w,int h,Matrix4f inverseProjection){
        if(w<=0||h<=0||inverseProjection==null)return;
        try(var state=new State()){
            glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
            if(glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL_TEXTURE)return;
            int target=glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int oldReadBuffer=glGetInteger(GL_READ_BUFFER),oldDrawBuffer=glGetInteger(GL_DRAW_BUFFER);
            try {
            glActiveTexture(GL_TEXTURE0);
            if(color==0){color=texture();depth=texture();mask=texture();vao=glGenVertexArrays();blend=program(FRAGMENT);solid=program("#version 330 core\nout vec4 result;void main(){result=vec4(1);}");}
            glReadBuffer(GL_COLOR_ATTACHMENT0);glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glBindTexture(GL_TEXTURE_2D,color);
            if(width!=w||height!=h)glCopyTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,0,0,w,h,0);else glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,w,h);
            glBindTexture(GL_TEXTURE_2D,depth);
            if(width!=w||height!=h)glCopyTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT32F,0,0,w,h,0);else glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,w,h);
            glBindTexture(GL_TEXTURE_2D,mask);
            if(width!=w||height!=h)glTexImage2D(GL_TEXTURE_2D,0,GL_R8,w,h,0,GL_RED,GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
            width=w;height=h;
            glViewport(0,0,w,h);glDisable(GL_SCISSOR_TEST);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_DEPTH_TEST);glDisable(GL_FRAMEBUFFER_SRGB);glDisable(GL_CLIP_DISTANCE0);glDepthMask(false);glColorMask(true,true,true,true);glBindVertexArray(vao);
            try{
                glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,mask,0);
                glClearBufferfv(GL_COLOR,0,new float[]{0,0,0,0});
                glEnable(GL_STENCIL_TEST);glStencilMask(0);glStencilFunc(GL_NOTEQUAL,0,255);glStencilOp(GL_KEEP,GL_KEEP,GL_KEEP);
                glUseProgram(solid);glDrawArrays(GL_TRIANGLES,0,3);
            }finally{glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,target,0);}
            glDisable(GL_STENCIL_TEST);glUseProgram(blend);
            int[] textures={color,mask,depth};String[] names={"image","mask","depths"};
            for(int i=0;i<3;i++){glActiveTexture(GL_TEXTURE0+i);glBindSampler(i,0);glBindTexture(GL_TEXTURE_2D,textures[i]);glUniform1i(glGetUniformLocation(blend,names[i]),i);}
            glUniformMatrix4fv(glGetUniformLocation(blend,"inverseProjection"),false,inverseProjection.get(new float[16]));glDrawArrays(GL_TRIANGLES,0,3);
            } finally { glReadBuffer(oldReadBuffer);glDrawBuffer(oldDrawBuffer); }
        }
    }
    private static final class State implements AutoCloseable {
        final int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),program=glGetInteger(GL_CURRENT_PROGRAM),vao=glGetInteger(GL_VERTEX_ARRAY_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE);
        final int[] viewport=new int[4],textures=new int[3],samplers=new int[3];
        final int[] caps={GL_SCISSOR_TEST,GL_BLEND,GL_CULL_FACE,GL_DEPTH_TEST,GL_FRAMEBUFFER_SRGB,GL_CLIP_DISTANCE0,GL_STENCIL_TEST};final boolean[] enabled=new boolean[caps.length];
        final boolean depthMask=glGetBoolean(GL_DEPTH_WRITEMASK);final java.nio.ByteBuffer colorMask=org.lwjgl.BufferUtils.createByteBuffer(4);
        final int[] stencil=new int[14];
        State(){
            glGetIntegerv(GL_VIEWPORT,viewport);glGetBooleanv(GL_COLOR_WRITEMASK,colorMask);
            for(int i=0;i<caps.length;i++)enabled[i]=glIsEnabled(caps[i]);
            int[] keys={GL_STENCIL_FUNC,GL_STENCIL_REF,GL_STENCIL_VALUE_MASK,GL_STENCIL_WRITEMASK,GL_STENCIL_FAIL,GL_STENCIL_PASS_DEPTH_FAIL,GL_STENCIL_PASS_DEPTH_PASS,GL_STENCIL_BACK_FUNC,GL_STENCIL_BACK_REF,GL_STENCIL_BACK_VALUE_MASK,GL_STENCIL_BACK_WRITEMASK,GL_STENCIL_BACK_FAIL,GL_STENCIL_BACK_PASS_DEPTH_FAIL,GL_STENCIL_BACK_PASS_DEPTH_PASS};
            for(int i=0;i<14;i++)stencil[i]=glGetInteger(keys[i]);
            for(int i=0;i<3;i++){glActiveTexture(GL_TEXTURE0+i);textures[i]=glGetInteger(GL_TEXTURE_BINDING_2D);samplers[i]=glGetInteger(GL_SAMPLER_BINDING);}glActiveTexture(active);
        }
        public void close(){
            for(int i=0;i<3;i++){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,textures[i]);glBindSampler(i,samplers[i]);}glActiveTexture(active);
            for(int i=0;i<2;i++){int face=i==0?GL_FRONT:GL_BACK,o=i*7;glStencilFuncSeparate(face,stencil[o],stencil[o+1],stencil[o+2]);glStencilMaskSeparate(face,stencil[o+3]);glStencilOpSeparate(face,stencil[o+4],stencil[o+5],stencil[o+6]);}
            for(int i=0;i<caps.length;i++)if(enabled[i])glEnable(caps[i]);else glDisable(caps[i]);
            glDepthMask(depthMask);glColorMask(colorMask.get(0)!=0,colorMask.get(1)!=0,colorMask.get(2)!=0,colorMask.get(3)!=0);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glUseProgram(program);glBindVertexArray(vao);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);
        }
    }
}
