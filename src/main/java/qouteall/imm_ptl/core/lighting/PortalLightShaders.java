package qouteall.imm_ptl.core.lighting;

import java.util.regex.Pattern;

/** Known vanilla-lightmap terrain adapters. Never rewrite an arbitrary pack's HDR outputs. */
public final class PortalLightShaders {
    private static final Pattern MAIN=Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");
    public static final String FUNCTION="""
        uniform sampler3D ipPortalLightAtlas;
        uniform int ipPortalLightCount;
        uniform vec3 ipPortalLightOrigin[4];
        vec3 ipPortalLightGain(vec3 position) {
            // Offset into the visible air cell, not through the roof into the source.
            vec3 towardEye=-position/max(length(position),0.0001);
            vec3 point=position+towardEye*0.025;
            vec3 gain=vec3(0); bool found=false;
            for(int i=0;i<4;i++) {
                if(i>=ipPortalLightCount) break;
                vec3 local=point-ipPortalLightOrigin[i];
                if(any(lessThan(local,vec3(0))) || any(greaterThanEqual(local,vec3(32)))) continue;
                ivec3 cell=ivec3(floor(local)); cell.z+=i*32;
                if(texelFetch(ipPortalLightAtlas,cell,0).a<0.5) continue;
                // Normalize interpolation by occupancy, but require exact-cell membership
                // first: filtering must not allow light to bleed through an opaque roof.
                vec3 filtered=clamp(local,vec3(0.5),vec3(31.5));
                vec4 sampleValue=texture(ipPortalLightAtlas,vec3(filtered.xy/32.0,(filtered.z+float(i*32))/128.0));
                vec3 value=sampleValue.rgb/max(sampleValue.a,0.00001);
                gain=max(gain,value); found=true;
            }
            return found?gain:vec3(1);
        }
        """;
    private PortalLightShaders() {}
    private static String declarations(String source,String declarations) {
        var main=MAIN.matcher(source);
        return main.find()?source.substring(0,main.start())+declarations+"\n"+source.substring(main.start()):source;
    }
    public static String sodium(String name,String source) {
        if(source.contains("ipPortalLightGain") || source.contains("out vec3 ipPortalLightPosition;")) return source;
        if(name.equals("sodium:blocks/block_layer_opaque.vsh") && source.contains("vec3 position = _vert_position + translation;"))
            return declarations(source,"out vec3 ipPortalLightPosition;").replace("vec3 position = _vert_position + translation;",
                "vec3 position = _vert_position + translation;\n    ipPortalLightPosition=position;");
        if(name.equals("sodium:blocks/block_layer_opaque.fsh") && source.contains("color *= v_Color;"))
            return declarations(source,"in vec3 ipPortalLightPosition;\n"+FUNCTION).replace("color *= v_Color;",
                "color *= v_Color;\n    color.rgb *= ipPortalLightGain(ipPortalLightPosition);");
        return source;
    }
    public static String dh(String path,String source) {
        if(source.contains("ipPortalLightGain")) return source;
        if(path.equals("assets/distanthorizons/shaders/terrain/gl/frag.frag")
            && source.contains("in vec3 vertexWorldPos;") && source.contains("fragColor = vertexColor;"))
            return declarations(source,FUNCTION).replace("fragColor = vertexColor;",
                "fragColor = vertexColor;\n    fragColor.rgb *= ipPortalLightGain(vertexWorldPos);");
        return source;
    }
}
