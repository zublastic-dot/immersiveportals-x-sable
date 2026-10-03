package qouteall.imm_ptl.core.lighting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately narrow, in-memory adapter for the owner's installed shader family.
 * The source pack supplies its own colour equations and option-expanded constants;
 * none of that pack's source is bundled in this mod.
 */
public final class PortalShaderPackAdapter {
    private static final String PACK = "ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5";
    private static final String MARKER = "// IP_PORTAL_SUN_ADAPTER_V1";
    private static final String SCENE = "vec3 sceneLighting = lightColorM * shadowLightMult + ambientColorM * ambientMult;";
    private static final Pattern ROTATION = Pattern.compile("\\bconst\\s+float\\s+sunPathRotation\\s*=\\s*([-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)\\s*;");
    private static volatile double sunPathRotation = Double.NaN;
    public enum ClockMode { SUN_ANGLE, WORLD_TIME }
    private static volatile ClockMode clockMode;
    private static final Map<String, String> STATUS = new LinkedHashMap<>();

    private PortalShaderPackAdapter() {}

    public static boolean supports(String name) {
        return PACK.equals(name) || (PACK + ".zip").equals(name);
    }

    public static OptionalDouble sunPathRotationDegrees() {
        double value = sunPathRotation;
        return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    public static Optional<ClockMode> clockMode() { return Optional.ofNullable(clockMode); }

    public static void clear() { sunPathRotation = Double.NaN; clockMode = null; synchronized (STATUS) { STATUS.clear(); } }

    public static Map<String, String> status() { synchronized (STATUS) { return Map.copyOf(STATUS); } }

    private static void report(String path, String status) {
        synchronized (STATUS) {
            if (status.equals(STATUS.get(path))) return;
            if (!STATUS.containsKey(path) && STATUS.size() >= 32) return;
            STATUS.put(path, status);
        }
        org.slf4j.LoggerFactory.getLogger(PortalShaderPackAdapter.class)
            .info("IP portal shader sunlight: {}: {}", path, status);
    }

    /** Called on Iris' preprocessed input, before its AST transform can rename things. */
    public static void observePreprocessed(String pack, String source) {
        if (!supports(pack) || source == null) return;
        Matcher matcher = ROTATION.matcher(source);
        if (!matcher.find()) return;
        double value = Double.parseDouble(matcher.group(1));
        if (Double.isFinite(value) && Math.abs(value) <= 360 && !matcher.find()) {
            String compact = source.replaceAll("\\s+", "");
            ClockMode observed = (compact.contains("floattimeAngle=worldTimeSmooth/24000.0;")
                || compact.contains("floattimeAngle=float(worldTime)/24000.0;")) ? ClockMode.WORLD_TIME
                : compact.contains("floattAmin=fract(sunAngle-0.033333333);") ? ClockMode.SUN_ANGLE : null;
            if (observed != null) { clockMode = observed; sunPathRotation = value; }
        }
    }

    public static List<String> patch(String pack, String path, List<String> input) {
        if (!supports(pack) || input == null || !path.endsWith(".fsh")) return input;
        try (var stream = PortalShaderPackAdapter.class.getResourceAsStream(
            "/assets/immersive_portals/shaders/portal_sun.glsl")) {
            if (stream == null) { report(path, "unavailable: portal_sun.glsl missing"); return input; }
            return patch(pack, path, input, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            report(path, "unavailable: portal_sun.glsl could not be read");
            return input;
        }
    }

    static List<String> patch(String pack, String path, List<String> input, String resource) {
        if (!supports(pack) || input == null || !path.endsWith(".fsh")) return input;
        String source = String.join("\n", input);
        if (source.length() > 8 * 1024 * 1024 || source.contains(MARKER)
            || !source.contains("#define NETHER") || !source.contains("// Complementary Shaders by EminGT")) return input;
        if (!source.contains("void DoLighting(")) {
            try {
                String fog = patchFog(source, resource, false);
                if (!fog.equals(source)) report(path, "adapted bounded Nether fog");
                return fog.equals(source) ? input : List.copyOf(fog.lines().toList());
            } catch (IllegalArgumentException rejected) {
                report(path, "unavailable: " + rejected.getMessage());return input;
            }
        }
        if (occurrences(source, SCENE) != 1 || occurrences(source, "void DoLighting(") != 1
            || occurrences(source, "vec2 oldLightmap = lightmap.xy;") != 1) {
            report(path, "unavailable: ambiguous lighting anchors");return input;
        }
        try {
            String colours = overworldColourBranch(source);
            String time = conditionalContaining(source, "float timeAngle = worldTimeSmooth / 24000.0;");
            String angles = declaration(source, "overworldAngleRaw") + declaration(source, "overworldAngle");
            String common = declaration(source, "noonFactorRaw") + declaration(source, "noonFactor")
                + declaration(source, "invNoonFactor") + declaration(source, "sunFactor")
                + declaration(source, "sunVisibility") + declaration(source, "sunVisibility2")
                + declaration(source, "shadowTimeVar1") + declaration(source, "shadowTimeVar2")
                + declaration(source, "shadowTime");
            String directional = between(source, "// Directional Shading", "// Scene Lighting Stuff");
            // Only this copied expression block changes dimension predicates. The
            // surrounding shader continues compiling/running as its real dimension.
            directional = directional.replace("#ifdef OVERWORLD", "#if 1")
                .replace("defined OVERWORLD", "1").replace("defined NETHER", "0");
            String tweaks = conditionalContaining(source, "ambientMult = mix(lightmapYM, pow2(lightmapYM) * lightmapYM, rainFactor);").replace("#ifdef OVERWORLD", "#if 1") + "\n";
            String invNoon2 = declaration(source, "invNoonFactor2");
            String moon = overworldMoonBranch(source);
            String aoSection = between(source, "// Vanilla Ambient Occlusion", "// Light Highlight");
            String sourceAo = between(aoSection, "#ifdef OVERWORLD", "#elif defined NETHER");
            String nativeAo = between(aoSection.substring(aoSection.indexOf("#elif defined NETHER")), "#elif defined NETHER", "#else");
            String noon = declaration(source, "noonFactorRaw") + declaration(source, "noonFactor");
            colours += "\n"; directional += "\n"; moon += "\n"; sourceAo += "\n";
            String helper = environmentHelper(time, angles, common, colours, directional, moon, tweaks, invNoon2) + aoHelper(sourceAo, noon) + shadowFilterHelper(source);
            String injected = MARKER + "\n#ifdef NETHER\n#define IP_SUN_PACK_PCF\nfloat ipSunPackFiltered(int region, vec3 point, vec3 coord);\nfloat ipSunPackCoverage(int region, vec3 point, float viewDistance, float visibility, bool covered);\n"
                + "vec3 ipSunPackScene(int region, float sky, float directVisibility, vec3 worldNormal, float lViewPos, float lightmapXM, float emission, out float blockMultiplier);\n"
                + "float ipSunPackDirectionShade(int region, vec3 worldNormal);\n"
                + resource + "\n" + helper + "\n#endif\n";
            source = source.replace("void DoLighting(", injected + "\nvoid DoLighting(");
            source = source.replace("vec2 oldLightmap = lightmap.xy;", """
                #ifdef NETHER
                    lightmap = ipSunLightmap(playerPos, worldGeoNormal, lightmap);
                #endif
                vec2 oldLightmap = lightmap.xy;
                """);
            source = source.replace(SCENE, SCENE + "\n#ifdef NETHER\n"
                + "sceneLighting = ipSunApply(playerPos, worldGeoNormal, sceneLighting, blockLighting, lightmapXM, emission);\n"
                + "if (!noDirectionalShading) directionShade = ipSunDirectionShade(playerPos, worldGeoNormal, directionShade);\n#endif\n");
            if (occurrences(source, nativeAo) != 1) throw new IllegalArgumentException("Ambiguous native AO");
            source = source.replace(nativeAo, "\nfloat ipSunBaseAo = vanillaAO;\n" + nativeAo
                + "\nvanillaAO = ipSunAmbientOcclusion(playerPos, worldGeoNormal, ipSunBaseAo, vanillaAO, lightmapY2, dotSceneLighting);\n");
            report(path, "adapted source ambient, direct light, directional shading and AO");
            return List.copyOf(patchFog(source, resource, true).lines().toList());
        } catch (IllegalArgumentException rejected) {
            // Missing/ambiguous source anchors leave the complete program untouched.
            report(path, "unavailable: " + rejected.getMessage());
            return input;
        }
    }

    /** Retain the owner's option-conditioned PCF kernel, but apply each tap to the whole path. */
    private static String shadowFilterHelper(String source) {
        String projection=function(source,"vec3 GetShadowPos(vec3 playerPos)");
        String compact=projection.replaceAll("\\s+", "");
        if(!compact.contains("floatdistortFactor=distb*shadowMapBias+(1.0-shadowMapBias);")
            || !compact.contains("shadowPos.xy/=distortFactor;") || !compact.contains("shadowPos.z*=0.2;")
            || !source.replaceAll("\\s+", "").contains("constfloatshadowMapBias=1.0-25.6/shadowDistance;"))
            throw new IllegalArgumentException("Source shadow projection changed");
        String lighting=function(source,"void DoLighting(");
        String coverage=declaration(lighting,"shadowLength")+declaration(lighting,"shadowSmooth");
        String skyFallback=conditionalContaining(lighting,"float skyLightShadowMult = pow2(pow2(lightmapY2));").replace("#ifdef OVERWORLD","#if 1");
        String offset=conditionalContaining(lighting,"float offset = 0.00098;");
        String samples=conditionalContaining(lighting,"int shadowSamples = 2 + 2 * shadowSampleBooster;");
        String noise=function(source,"float InterleavedGradientNoiseForShadows()");
        String distribution=function(source,"vec2 offsetDist(float x, int s)");
        String taa=function(source,"vec3 SampleTAAFilteredShadow(");
        String cross=function(source,"vec3 SampleFilteredShadow(");
        String basic=function(source,"vec3 SampleBasicFilteredShadow(");
        String offsets=statement(source,"vec2 shadowOffsets[4]");
        if(!offsets.contains("vec2[4]("))
            throw new IllegalArgumentException("Shadow filter offsets changed");
        String get=function(source,"vec3 GetShadow(");
        if(get.indexOf("float lightmapY2")<0) throw new IllegalArgumentException("Shadow weather section changed");
        String weather=get.substring(get.indexOf('{')+1,get.indexOf("float lightmapY2"))
            .replace("#ifdef OVERWORLD","#if 1");
        String selection=conditionalContaining(get,"vec3 shadow = SampleTAAFilteredShadow(shadowPos, offset, shadowSamples, leaves, colorMult, colorPow);");
        // This runtime source remains owned by the installed pack. Only calls at
        // its sampling boundary are redirected; noise/options/rain/TAA are preserved.
        String functions=noise+distribution+offsets+taa+cross+basic;
        functions=functions.replace("SampleTAAFilteredShadow(","ipSunPackTaa(int region, vec3 point, ")
            .replace("SampleFilteredShadow(","ipSunPackCross(int region, vec3 point, ")
            .replace("SampleBasicFilteredShadow(","ipSunPackBasic(int region, vec3 point, ")
            .replace("SampleShadow(","ipSunPackTap(region, point, ")
            .replace("shadow2D(shadowtex0, vec3(offset * shadowOffsets[i] + shadowPos.st, shadowPos.z)).x",
                "ipSunPathTap(region, point, vec3(offset * shadowOffsets[i] + shadowPos.st, shadowPos.z))")
            .replace("InterleavedGradientNoiseForShadows","ipSunPackNoise")
            .replace("offsetDist","ipSunPackOffset").replace("shadowOffsets","ipSunPackOffsets");
        if(functions.contains("SampleShadow(") || functions.contains("shadow2D("))
            throw new IllegalArgumentException("Unsupported native shadow sample");
        selection=selection.replace("SampleTAAFilteredShadow(","ipSunPackTaa(region, point, ")
            .replace("SampleFilteredShadow(","ipSunPackCross(region, point, ")
            .replace("SampleBasicFilteredShadow(","ipSunPackBasic(region, point, ");
        return """
            float ipSunPackCoverage(int region, vec3 point, float lViewPos, float visibility, bool covered) {
                float shadowDistance = ipSunShadowDistance[region];
                float lightmapY2 = pow2(clamp(ipSunData(region, point).r, 0.0, 1.0));
            """+coverage+"\n"+skyFallback+"\n"+"""
                float shadowMixer = covered ? clamp(shadowLength / shadowSmooth, 0.0, 1.0) : 0.0;
                return mix(skyLightShadowMult, visibility, shadowMixer);
            }
            #if SHADOW_QUALITY >= 0
            vec3 ipSunPackTap(int region, vec3 point, vec3 coord, float unusedColor, float unusedPower) {
                return vec3(ipSunPathTap(region, point, coord));
            }
            """+functions+"""
            #endif
            float ipSunPackFiltered(int region, vec3 point, vec3 shadowPos) {
                #if SHADOW_QUALITY >= 0
                    float rainFactor2 = ipSunSourceRain[region] * ipSunSourceRain[region];
                    int shadowSampleBooster = 0;
                    bool leaves = false;
                    float colorMult = 1.0, colorPow = 1.0;
            """+offset+"\n"+samples+"\n"+weather+"\n"+selection+"\n"+"""
                    return shadow.r;
                #else
                    return ipSunPathTap(region, point, shadowPos);
                #endif
            }
            """;
    }

    private static String statement(String source,String prefix) {
        String result=null;
        for(int from=0;(from=source.indexOf(prefix,from))>=0;) {
            int end=source.indexOf(';',from);
            if(end<0) throw new IllegalArgumentException("Incomplete shadow statement");
            String next=source.substring(from,end+1);
            if(result!=null && !result.equals(next)) throw new IllegalArgumentException("Ambiguous shadow statement: "+prefix);
            result=next;from=end+1;
        }
        if(result==null) throw new IllegalArgumentException("Missing shadow statement: "+prefix);
        return result+"\n";
    }

    private static String function(String source,String signature) {
        String result=null;
        for(int from=0;(from=source.indexOf(signature,from))>=0;) {
            int opening=source.indexOf('{',from),depth=0,end=-1;
            if(opening<0) throw new IllegalArgumentException("Missing shadow function body");
            for(int i=opening;i<source.length();i++) {
                if(source.charAt(i)=='{') depth++;
                if(source.charAt(i)=='}' && --depth==0) {end=i+1;break;}
            }
            if(end<0) throw new IllegalArgumentException("Incomplete shadow function");
            String next=source.substring(from,end)+"\n";
            // Iris' raw include graph may expand the same guarded include more than once.
            if(result!=null && !result.equals(next)) throw new IllegalArgumentException("Ambiguous shadow function: "+signature);
            result=next;from=end;
        }
        if(result==null) throw new IllegalArgumentException("Shadow function changed: "+signature);
        return result;
    }

    private static String patchFog(String source, String resource, boolean hasLighting) {
        String border = "void DoBorderFog(inout vec4 color, inout float skyFade, float lPos, float VdotU, float VdotS, float dither) {";
        String borderCall = "DoBorderFog(color, skyFade, max(length(playerPos.xz), abs(playerPos.y)), VdotU, VdotS, dither);";
        String storm = "netherStorm.a += stormSample;";
        boolean hasBorder = occurrences(source, border) == 1 && occurrences(source, borderCall) == 1
            && occurrences(source, "fog *= BORDER_FOG_DENSITY;") == 1;
        boolean hasStorm = occurrences(source, "vec4 GetNetherStorm(") == 1 && occurrences(source, storm) == 1;
        if (!hasBorder && !hasStorm) return source;
        String declarations = "";
        if (!hasLighting) {
            declarations = MARKER + "\n#ifdef NETHER\n"
                + "vec3 ipSunPackScene(int region, float sky, float directVisibility, vec3 normal, float viewDistance, float localBlock, float emission, out float blockMultiplier) { blockMultiplier = 1.0; return vec3(0.0); }\n"
                + "float ipSunPackDirectionShade(int region, vec3 normal) { return 1.0; }\n"
                + resource + "\n#endif\n";
        }
        String first = hasBorder ? border : "vec4 GetNetherStorm(";
        source = source.replace(first, declarations + "\n#ifdef NETHER\n" + FOG_HELPER + "\n#endif\n" + first);
        if (hasBorder) {
            source = source.replace(border, border.replace("float dither)", "float dither, vec3 ipSunFogPoint)"));
            source = source.replace(borderCall, borderCall.replace("dither);", "dither, playerPos);"));
            source = source.replace("fog *= BORDER_FOG_DENSITY;", """
                fog *= BORDER_FOG_DENSITY;
                #ifdef NETHER
                    if (isEyeInWater == 0) fog = 1.0 - pow(max(1.0 - clamp(fog, 0.0, 1.0), 0.0), 1.0 - ipSunFogFraction(ipSunFogPoint));
                #endif
                """);
        }
        if (hasStorm) {
            String signature = "vec4 GetNetherStorm(vec3 color, vec3 translucentMult, vec3 nPlayerPos, vec3 playerPos, float lViewPos, float lViewPos1, float dither) {";
            if (occurrences(source, signature) != 1) throw new IllegalArgumentException("Storm signature changed");
            source = source.replace(signature, signature + "\nfloat ipSunFogClipDistance = length(playerPos) * ipSunFogPrefix(playerPos);\n");
            source = source.replace(storm,
                "netherStorm.a += stormSample * (lTracePos < ipSunFogClipDistance ? 0.0 : 1.0 - ipSunWeight(tracedPlayerPos));");
        }
        return source;
    }

    // Integrate only the bounded portions of the view ray intersecting a field.
    // It does not suppress exterior, liquid, blindness or darkness fog.
    static final String FOG_HELPER = """
        float ipSunFogPrefix(vec3 point) {
            float prefix=0.0;
            if(ipSunPortalView == 0) return prefix;
            for (int i=0;i<min(ipSunCount,4);i++) {
                float startSide=dot(-ipSunPlane[i],ipSunInward[i]);
                float endSide=dot(point-ipSunPlane[i],ipSunInward[i]);
                if(startSide<0.0 && endSide>0.0) {
                    float crossing=-startSide/(endSide-startSide);
                    vec3 hit=point*crossing-ipSunPlane[i];
                    vec2 uv=vec2(dot(hit,ipSunU[i]),dot(hit,ipSunV[i]));
                    if(all(lessThan(abs(uv),ipSunHalfSize[i]))) prefix=max(prefix,crossing);
                }
            }
            return prefix;
        }
        float ipSunFogFraction(vec3 point) {
            if (ipSunCount <= 0 || length(point) < 0.0001) return 0.0;
            // Receiving-dimension medium begins at the real aperture, never at
            // the virtual camera outside it. Main-world fog remains separate.
            float first=1.0,last=0.0,prefix=ipSunFogPrefix(point);
            for (int i=0;i<min(ipSunCount,4);i++) {
                vec3 safeRay = mix(point,vec3(0.0000001),lessThan(abs(point),vec3(0.0000001)));
                vec3 a=ipSunOrigin[i]/safeRay, b=(ipSunOrigin[i]+vec3(32.0))/safeRay;
                vec3 nearT=min(a,b),farT=max(a,b);
                float enter=max(0.0,max(nearT.x,max(nearT.y,nearT.z)));
                float leave=min(1.0,min(farT.x,min(farT.y,farT.z)));
                if(leave>enter){first=min(first,enter);last=max(last,leave);}
            }
            first=max(first,prefix);
            if(last<=first)return clamp(prefix,0.0,1.0);
            float total=0.0;
            for(int s=0;s<16;s++)total+=ipSunWeight(point*mix(first,last,(float(s)+0.5)/16.0));
            return clamp(prefix+total*(last-first)/16.0,0.0,1.0);
        }
        """;

    private static String environmentHelper(String time, String angles, String common, String colours, String directional, String moon, String tweaks, String invNoon2) {
        return """
            float ipSunPackTime(int region) {
                float sunAngle = ipSunSourceSunAngle[region];
                int worldTime = ipSunSourceWorldTime[region];
                float worldTimeSmooth = float(ipSunSourceWorldTime[region]);
            """ + time + "\nreturn timeAngle;\n}\n" + """
            void ipSunPackEnvironment(int region, out vec3 direct, out vec3 ambient, out float directFade, out float noon) {
                float timeAngle = ipSunPackTime(region);
            """ + angles + """
                vec3 ipSourceSun = normalize(vec3(-sin(overworldAngle), cos(overworldAngle) * SUN_ROTATION_DATA));
                float SdotU = ipSourceSun.y;
            """ + common + """
                vec3 skyColor = ipSunSourceSkyColor[region];
                float rainFactor = ipSunSourceRain[region];
                float inRainy = ipSunSourceWeather[region].x;
                float inSnowy = ipSunSourceWeather[region].y;
                float inDry = ipSunSourceWeather[region].z;
            """ + colours + """
                #ifdef MOON_PHASE_INF_LIGHT
                    int moonPhase = int(ipSunSourceMoonPhase[region]);
            """ + moon + """
                    lightColor *= moonPhaseInfluence;
                    ambientColor *= moonPhaseInfluence;
                #endif
                direct = lightColor;
                ambient = ambientColor;
                directFade = shadowTime;
                noon = noonFactor;
            }
            void ipSunPackSurface(int region, float sky, vec3 worldNormal,
                                  out vec3 direct, out vec3 ambient, out float shade, out float directFade) {
                float noonFactor;
                ipSunPackEnvironment(region, direct, ambient, directFade, noonFactor);
                vec3 sourceNormal = ipSunSourceNormal(region, worldNormal);
                float NdotU = sourceNormal.y;
                float NdotUmax0 = max(NdotU, 0.0);
                float NdotN = sourceNormal.z;
                float absNdotN = abs(NdotN);
                float absNdotE = abs(sourceNormal.x);
                float NPdotU = abs(NdotU);
                float lightmapY2 = sky * sky;
                bool noDirectionalShading = false;
                int subsurfaceMode = 0;
                vec3 minLighting = vec3(0.0);
                vec3 lightColorM = direct, ambientColorM = ambient;
            """ + directional + """
                direct = lightColorM;
                ambient = ambientColorM;
                shade = directionShade;
            }
            float ipSunPackDirectionShade(int region, vec3 worldNormal) {
                vec3 direct, ambient;
                float shade, directFade;
                ipSunPackSurface(region, 0.0, worldNormal, direct, ambient, shade, directFade);
                return shade;
            }
            vec3 ipSunPackScene(int region, float sky, float directVisibility, vec3 worldNormal,
                                float lViewPos, float lightmapXM, float emission, out float blockMultiplier) {
                vec3 lightColorM, ambientColorM;
                float directFade, ignoredNoon;
                ipSunPackEnvironment(region, lightColorM, ambientColorM, directFade, ignoredNoon);
                float timeAngle = ipSunPackTime(region);
            """ + angles + """
                vec3 ipSourceSun = normalize(vec3(-sin(overworldAngle), cos(overworldAngle) * SUN_ROTATION_DATA));
                float SdotU = ipSourceSun.y;
            """ + common + invNoon2 + """
                vec3 sourceNormal = ipSunSourceNormal(region, worldNormal);
                float NdotU = sourceNormal.y;
                float NdotUmax0 = max(NdotU, 0.0), NdotN = sourceNormal.z;
                float absNdotN = abs(NdotN), absNdotE = abs(sourceNormal.x), NPdotU = abs(NdotU);
                float lightmapY2 = sky * sky, lightmapYM = smoothstep1(clamp(sky, 0.0, 1.0));
                float rainFactor = ipSunSourceRain[region];
                float NdotLM = max(dot(worldNormal, ipSunDirection[region]), 0.0) * 0.9999;
                #ifdef SIDE_SHADOWING
                    NdotLM = max(dot(worldNormal, ipSunDirection[region]) + 0.4, 0.0) * 0.714;
                #endif
                vec3 shadowMult = vec3(directVisibility * max(NdotLM * directFade, 0.0));
                vec3 shadowLightMult = shadowMult;
                float shadowMultFloat = min1(GetLuminance(shadowMult));
                float ambientMult = 1.0;
                // Preserve the current local block RGB. Only the source pack's
                // scalar daylight response changes across this region.
                vec3 blockLighting = vec3(1.0);
            """ + tweaks + """
                bool noDirectionalShading = false;
                int subsurfaceMode = 0;
                vec3 minLighting = vec3(0.0);
            """ + directional + """
                blockMultiplier = blockLighting.r;
                return lightColorM * shadowLightMult + ambientColorM * ambientMult;
            }
            """;
    }

    private static String aoHelper(String sourceAo, String noon) {
        return """
            float ipSunPackAo(int region, vec3 worldNormal, float vanillaAO, float lightmapY2, float dotSceneLighting) {
                float timeAngle = ipSunPackTime(region);
                float NdotUmax0 = max(ipSunSourceNormal(region, worldNormal).y, 0.0);
            """ + noon + sourceAo + """
                return vanillaAO;
            }
            float ipSunAmbientOcclusion(vec3 point, vec3 normal, float base, float original, float lightmapY2, float dotSceneLighting) {
                float incoming = 0.0, weight = 0.0;
                for(int i=0;i<min(ipSunCount,4);i++) {
                    vec4 data = ipSunData(i,point+normal*0.04);
                    if(data.a<0.5 || data.b<=0.0)continue;
                    incoming=max(incoming,ipSunPackAo(i,normal,base,lightmapY2,dotSceneLighting)*data.b);
                    weight=max(weight,data.b);
                }
                return original*(1.0-clamp(weight,0.0,1.0))+incoming;
            }
            """;
    }

    private static String between(String source, String from, String to) {
        if (occurrences(source, from) != 1 || occurrences(source, to) != 1)
            throw new IllegalArgumentException("Ambiguous section");
        int start = source.indexOf(from), end = source.indexOf(to, start + from.length());
        if (end < 0) throw new IllegalArgumentException("Missing section end");
        return source.substring(start + from.length(), end);
    }

    private static String declaration(String source, String variable) {
        Pattern pattern = Pattern.compile("(?m)^[ \\t]*float[ \\t]+" + Pattern.quote(variable) + "[ \\t]*=[^;\\n]+;");
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) throw new IllegalArgumentException("Missing " + variable);
        String result = matcher.group();
        if (matcher.find()) throw new IllegalArgumentException("Ambiguous " + variable);
        return result + "\n";
    }

    /** Find a complete preprocessor conditional around a unique, known declaration. */
    private static String conditionalContaining(String source, String anchor) {
        if (occurrences(source, anchor) != 1) throw new IllegalArgumentException("Time expression changed");
        String[] lines = source.split("\n", -1);
        var stack = new ArrayList<Integer>();
        int selected = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (isIf(line)) stack.add(i);
            if (line.contains(anchor)) {
                if (stack.isEmpty()) throw new IllegalArgumentException("No time conditional");
                selected = stack.getLast();
            }
            if (line.startsWith("#endif")) {
                if (stack.isEmpty()) throw new IllegalArgumentException("Unbalanced input");
                int start = stack.removeLast();
                if (start == selected) return String.join("\n", java.util.Arrays.copyOfRange(lines, start, i + 1));
            }
        }
        throw new IllegalArgumentException("Incomplete time conditional");
    }

    /** Select the exact pack's OW colour branch while retaining its settings conditionals. */
    private static String overworldColourBranch(String source) {
        int start = source.indexOf("#ifndef INCLUDE_LIGHT_AND_AMBIENT_COLORS");
        if (start < 0) throw new IllegalArgumentException("Missing colour include");
        String[] lines = source.substring(start).split("\n", -1);
        int opening = -1, depth = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (opening < 0) {
                if (line.equals("#if defined OVERWORLD") && i + 1 < lines.length
                    && lines[i + 1].trim().equals("#ifndef COMPOSITE1")) { opening = i; depth = 1; }
                continue;
            }
            if (isIf(line)) depth++;
            if (depth == 1 && line.equals("#elif defined NETHER"))
                return String.join("\n", java.util.Arrays.copyOfRange(lines, opening + 1, i));
            if (line.startsWith("#endif")) depth--;
            if (depth == 0) break;
        }
        throw new IllegalArgumentException("Colour branch changed");
    }

    private static String overworldMoonBranch(String source) {
        int start = source.indexOf("#ifndef INCLUDE_MOON_PHASE_INF");
        if (start < 0) throw new IllegalArgumentException("Missing moon phase include");
        String[] lines = source.substring(start).split("\n", -1);
        int opening = -1, depth = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (opening < 0) {
                if (line.equals("#ifdef OVERWORLD")) { opening = i; depth = 1; }
                continue;
            }
            if (isIf(line)) depth++;
            if (depth == 1 && line.equals("#else"))
                return String.join("\n", java.util.Arrays.copyOfRange(lines, opening + 1, i));
            if (line.startsWith("#endif")) depth--;
            if (depth == 0) break;
        }
        throw new IllegalArgumentException("Moon phase branch changed");
    }

    private static boolean isIf(String line) {
        return line.startsWith("#if ") || line.startsWith("#if\t")
            || line.startsWith("#ifdef ") || line.startsWith("#ifndef ");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int from = 0; (from = source.indexOf(needle, from)) >= 0; from += needle.length()) count++;
        return count;
    }
}
