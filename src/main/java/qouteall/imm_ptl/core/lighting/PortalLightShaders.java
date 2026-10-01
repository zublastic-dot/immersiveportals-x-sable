package qouteall.imm_ptl.core.lighting;

import java.util.regex.Pattern;

/** Known vanilla-lightmap terrain adapters. Never rewrite an arbitrary pack's HDR outputs. */
public final class PortalLightShaders {
    private static final Pattern MAIN=Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern SODIUM_LIGHT=Pattern.compile(
        "v_Color\\s*=\\s*_vert_color\\s*\\*\\s*(texture\\(u_LightTex,\\s*_vert_tex_light_coord\\)"
            +"|colorful_sample_lightmap\\(\\s*u_LightTex,\\s*_vert_colorful_light,\\s*_vert_tex_light_coord\\s*\\))\\s*;");
    private static final String VERTEX_LIGHT="out vec3 ipPortalNativeLight;\nout vec3 ipPortalBaseColor;";
    private static final String FRAGMENT_LIGHT="in vec3 ipPortalNativeLight;\nin vec3 ipPortalBaseColor;";
    private static final String COLORFUL_VERTEX_LIGHT="out vec3 ipPortalNativeSky;\nout vec3 ipPortalEmitter;";
    private static final String COLORFUL_FRAGMENT_LIGHT="in vec3 ipPortalNativeSky;\nin vec3 ipPortalEmitter;";
    /** Exact packed-light ABI of the known Colorful Sodium shader; fallback stays vanilla. */
    private static final String COLORFUL_DECODE="""
        ipPortalNativeSky=texture(u_LightTex,vec2(0.5/16.0,_vert_tex_light_coord.y)).rgb;
        ipPortalEmitter=vec3(0);
        uint ipPortalRed=_vert_colorful_light.r;
        uint ipPortalGreen=_vert_colorful_light.g;
        uint ipPortalBlue=((_vert_colorful_light.a & 0xFu) << 4u) | (_vert_colorful_light.b >> 4u);
        if((_vert_colorful_light.a >> 4u)==0xFu && (ipPortalRed | ipPortalGreen | ipPortalBlue)!=0u) {
            uint ipPortalSky=_vert_colorful_light.b & 0xFu;
            ipPortalNativeSky=colorful_sample_vanilla_lightmap(u_LightTex,ivec2(0,int(ipPortalSky << 4u))).rgb;
            ipPortalEmitter=pow(vec3(ipPortalRed,ipPortalGreen,ipPortalBlue)/255.0,vec3(1.3));
        }
        """;
    public static final String FUNCTION="""
        uniform sampler3D ipPortalLightAtlas;
        uniform int ipPortalLightCount;
        uniform vec3 ipPortalLightOrigin[4];
        vec3 ipPortalLightDelta(vec3 position, vec3 nativeLight, vec3 nativeSky, vec3 emitter) {
            // Offset into the visible air cell, not through the roof into the source.
            vec3 towardEye=-position/max(length(position),0.0001);
            vec3 point=position+towardEye*0.025;
            vec3 offset=vec3(0); bool found=false;
            for(int i=0;i<4;i++) {
                if(i>=ipPortalLightCount) break;
                vec3 local=point-ipPortalLightOrigin[i];
                if(any(lessThan(local,vec3(0))) || any(greaterThanEqual(local,vec3(32)))) continue;
                ivec3 cell=ivec3(floor(local)); cell.z+=i*32;
                if(texelFetch(ipPortalLightAtlas,cell,0).a<0.5) continue;
                // Normalize interpolation by occupancy, but require exact-cell membership
                // first: filtering must not allow light to bleed through an opaque roof.
                vec3 filtered=clamp(local,vec3(0.5),vec3(31.5));
                float layer=filtered.z+float(i*32);
                vec4 sampleValue=texture(ipPortalLightAtlas,vec3(filtered.xy/32.0,layer/256.0));
                vec3 value=sampleValue.rgb/max(sampleValue.a,0.00001);
                if(any(greaterThan(emitter,vec3(0)))) {
                    // Imported emission is not ambient. Use its separate bank before
                    // evaluating the local emitter gain suppressed by the native sky.
                    ivec3 ambientCell=cell+ivec3(0,0,128);
                    if(texelFetch(ipPortalLightAtlas,ambientCell,0).a>=0.5) {
                        vec4 ambientSample=texture(ipPortalLightAtlas,vec3(filtered.xy/32.0,(layer+128.0)/256.0));
                        vec3 ambient=ambientSample.rgb/max(ambientSample.a,0.00001);
                        vec3 effectiveSky=max(nativeSky+ambient,vec3(0));
                        float oldGain=max(0.3,1.0-nativeSky.r);
                        float newGain=max(0.3,1.0-effectiveSky.r);
                        value+=emitter*(newGain-oldGain);
                    }
                }
                // Correct each region before merging; never combine ambient from one
                // portal with the total correction belonging to a different portal.
                offset=found?max(offset,value):value; found=true;
            }
            // Use this draw's actual light, including Colorful Lighting's RGB sample.
            // Do not multiply a freshly rebuilt held light by a cached dark-room ratio.
            // Negative offsets may remove ambient, but cannot make light negative.
            return found?max(nativeLight+offset,vec3(0))-nativeLight:vec3(0);
        }
        vec3 ipPortalLightDelta(vec3 position, vec3 nativeLight) {
            return ipPortalLightDelta(position,nativeLight,vec3(0),vec3(0));
        }
        """;
    private PortalLightShaders() {}
    public static String sodiumGeometryName(String name) {
        return name.equals("colorful_lighting_sodium_compat:blocks/block_layer_opaque.vsh")
            ? "sodium:blocks/block_layer_opaque.vsh" : name;
    }
    private static String declarations(String source,String declarations) {
        var main=MAIN.matcher(source);
        return main.find()?source.substring(0,main.start())+declarations+"\n"+source.substring(main.start()):source;
    }
    public static String sodium(String name,String source) {
        name=sodiumGeometryName(name);
        if(source.contains("ipPortalLightDelta") || source.contains("out vec3 ipPortalLightPosition;")) return source;
        if(name.equals("sodium:blocks/block_layer_opaque.vsh") && source.contains("vec3 position = _vert_position + translation;")) {
            var light=SODIUM_LIGHT.matcher(source);
            if(!light.find()) return source;
            boolean colorful=light.group(1).startsWith("colorful_sample_lightmap");
            String patched=light.replaceFirst(java.util.regex.Matcher.quoteReplacement(
                "vec4 ipPortalSampledLight="+light.group(1)+";\n"
                    +"    ipPortalNativeLight=ipPortalSampledLight.rgb;\n"
                    +"    ipPortalBaseColor=_vert_color.rgb;\n"
                    +(colorful?COLORFUL_DECODE:"    ipPortalNativeSky=vec3(0); ipPortalEmitter=vec3(0);\n")
                    +"    v_Color=_vert_color*ipPortalSampledLight;"));
            return declarations(patched,"out vec3 ipPortalLightPosition;\n"+VERTEX_LIGHT+"\n"+COLORFUL_VERTEX_LIGHT)
                .replace("vec3 position = _vert_position + translation;",
                    "vec3 position = _vert_position + translation;\n    ipPortalLightPosition=position;");
        }
        if(name.equals("sodium:blocks/block_layer_opaque.fsh") && source.contains("color *= v_Color;"))
            return declarations(source,"in vec3 ipPortalLightPosition;\n"+FRAGMENT_LIGHT+"\n"+COLORFUL_FRAGMENT_LIGHT+"\n"+FUNCTION)
                .replace("color *= v_Color;", "vec3 ipPortalDiffuse=color.rgb;\n    color *= v_Color;\n"
                    +"    color.rgb += ipPortalDiffuse*ipPortalBaseColor*ipPortalLightDelta(ipPortalLightPosition,ipPortalNativeLight,ipPortalNativeSky,ipPortalEmitter);");
        return source;
    }
    public static String dh(String path,String source) {
        if(source.contains("ipPortalLightDelta") || source.contains("out vec3 ipPortalNativeLight;")) return source;
        String light="vertexColor = vec4(texture(uLightMap, vec2(skyLight, blockLight)).xyz, 1.0);";
        if(path.equals("assets/distanthorizons/shaders/terrain/gl/vert.vert") && source.contains(light))
            return declarations(source,VERTEX_LIGHT).replace(light,light+"\n"
                +"    ipPortalNativeLight=vertexColor.rgb;\n"
                +"    ipPortalBaseColor=uIsWhiteWorld?vec3(1):color.rgb;");
        if(path.equals("assets/distanthorizons/shaders/terrain/gl/frag.frag")
            && source.contains("in vec3 vertexWorldPos;") && source.contains("fragColor = vertexColor;"))
            return declarations(source,FRAGMENT_LIGHT+"\n"+FUNCTION).replace("fragColor = vertexColor;",
                "fragColor = vertexColor;\n    fragColor.rgb += ipPortalBaseColor*ipPortalLightDelta(vertexWorldPos,ipPortalNativeLight);");
        return source;
    }
}
