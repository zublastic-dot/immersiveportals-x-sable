// Original IP/Sable compatibility code. No shader-pack source is distributed here.
uniform int ipSunCount;
uniform int ipSunPortalView;
uniform sampler3D ipSunAtlas;
uniform sampler2DArray ipSunSourceShadow;
uniform sampler2DShadow ipSunDepth0;
uniform sampler2DShadow ipSunDepth1;
uniform sampler2DShadow ipSunDepth2;
uniform sampler2DShadow ipSunDepth3;
uniform int ipSunDepthValid[4];
uniform mat4 ipSunSourceProjection[4];
uniform mat4 ipSunSourceInverse[4];
uniform vec3 ipSunSourcePlane[4];
uniform vec3 ipSunSourceFacing[4];
uniform vec3 ipSunSourceU[4];
uniform vec3 ipSunSourceV[4];
uniform vec3 ipSunSourceLight[4];
uniform float ipSunShadowBias[4];
uniform float ipSunShadowDistance[4];
uniform vec3 ipSunOrigin[4];
uniform vec3 ipSunAmbientMin[4];
uniform vec3 ipSunAmbientMax[4];
uniform vec3 ipSunPlane[4];
uniform vec3 ipSunInward[4];
uniform vec3 ipSunU[4];
uniform vec3 ipSunV[4];
uniform vec2 ipSunHalfSize[4];
uniform vec3 ipSunDirection[4];
uniform vec3 ipSunToSourceX[4];
uniform vec3 ipSunToSourceY[4];
uniform vec3 ipSunToSourceZ[4];
uniform vec3 ipSunSourceSkyColor[4];
uniform float ipSunSourceSunAngle[4];
uniform int ipSunSourceWorldTime[4];
uniform float ipSunSourceRain[4];
uniform vec3 ipSunSourceWeather[4];
uniform float ipSunSourceMoonPhase[4];

vec3 ipSunSourceNormal(int i, vec3 normal) {
    return mat3(ipSunToSourceX[i],ipSunToSourceY[i],ipSunToSourceZ[i])*normal;
}
bool ipSunInGrid(ivec3 p) { return all(greaterThanEqual(p,ivec3(0))) && all(lessThan(p,ivec3(32))); }
vec4 ipSunVoxel(int i,ivec3 p) {
    if(!ipSunInGrid(p)) return vec4(0.0);
    return texelFetch(ipSunAtlas,p+ivec3(0,0,i*32),0);
}
vec4 ipSunData(int i,vec3 point) {
    vec3 local=point-ipSunOrigin[i];
    vec3 center=floor(local)+0.5;
    // The observed portal-plane layer carries occlusion only, not ambient light.
    // Keep the original field's normalized edge interpolation independent of it.
    if(any(lessThan(center,ipSunAmbientMin[i])) || any(greaterThan(center,ipSunAmbientMax[i]))) return vec4(0.0);
    // An opaque or unobserved voxel cannot borrow ambient from its neighbor.
    if(ipSunVoxel(i,ivec3(floor(local))).a<0.5) return vec4(0.0);
    vec3 coord=clamp(local,ipSunAmbientMin[i],ipSunAmbientMax[i]);
    vec4 value=texture(ipSunAtlas,vec3(coord.xy/32.0,(coord.z+float(i)*32.0)/128.0));
    if(value.a<0.0001) return vec4(0.0);
    return vec4(value.rgb/value.a,1.0);
}
float ipSunWeight(vec3 point) {
    float weight=0.0;
    for(int i=0;i<min(ipSunCount,4);i++) weight=max(weight,ipSunData(i,point).b);
    return clamp(weight,0.0,1.0);
}
vec2 ipSunLightmap(vec3 point,vec3 normal,vec2 original) {
    vec2 result=original;
    for(int i=0;i<min(ipSunCount,4);i++) {
        vec4 d=ipSunData(i,point+normal*0.04);
        if(d.a>0.5) {
            result.x=max(result.x,d.g);
            result.y=max(result.y,d.r);
        }
    }
    return result;
}
// Every filter tap traverses the same original receiving point to its own aperture hit.
// Unknown/opaque start cells and the entire receiving segment remain strict.
float ipSunReceiverPath(int i,vec3 point,vec3 apertureHit) {
    vec3 displacement=apertureHit-point;
    float distance=length(displacement);
    if(distance<0.000001) return 0.0;
    vec3 ray=displacement/distance;
    vec3 local=point-ipSunOrigin[i];
    ivec3 cell=ivec3(floor(local));
    ivec3 stepV=ivec3(sign(ray));
    vec3 delta=1.0/max(abs(ray),vec3(0.0000001));
    vec3 edge=vec3(cell)+step(vec3(0.0),ray);
    vec3 next=abs(edge-local)*delta;
    // Parallel axes never select a boundary even when the start lies on that face.
    if(abs(ray.x)<0.0000001) next.x=1e20;
    if(abs(ray.y)<0.0000001) next.y=1e20;
    if(abs(ray.z)<0.0000001) next.z=1e20;
    for(int n=0;n<100;n++) {
        float t=min(next.x,min(next.y,next.z));
        if(ipSunVoxel(i,cell).a<0.5) return 0.0;
        if(t>=distance-0.00001) return 1.0;
        // Supercover: check every voxel touched at a simultaneous edge/corner.
        bvec3 tied=lessThanEqual(next,vec3(t+0.000001));
        if(tied.x && ipSunVoxel(i,cell+ivec3(stepV.x,0,0)).a<0.5 && ipSunInGrid(cell+ivec3(stepV.x,0,0))) return 0.0;
        if(tied.y && ipSunVoxel(i,cell+ivec3(0,stepV.y,0)).a<0.5 && ipSunInGrid(cell+ivec3(0,stepV.y,0))) return 0.0;
        if(tied.z && ipSunVoxel(i,cell+ivec3(0,0,stepV.z)).a<0.5 && ipSunInGrid(cell+ivec3(0,0,stepV.z))) return 0.0;
        // A three-axis tie touches pairwise neighbors as well as the final diagonal.
        // Testing only the single-axis neighbors would let light cut opaque corners.
        if(tied.x && tied.y && ipSunVoxel(i,cell+ivec3(stepV.xy,0)).a<0.5 && ipSunInGrid(cell+ivec3(stepV.xy,0))) return 0.0;
        if(tied.x && tied.z && ipSunVoxel(i,cell+ivec3(stepV.x,0,stepV.z)).a<0.5 && ipSunInGrid(cell+ivec3(stepV.x,0,stepV.z))) return 0.0;
        if(tied.y && tied.z && ipSunVoxel(i,cell+ivec3(0,stepV.yz)).a<0.5 && ipSunInGrid(cell+ivec3(0,stepV.yz))) return 0.0;
        if(tied.x) { cell.x+=stepV.x;next.x+=delta.x; }
        if(tied.y) { cell.y+=stepV.y;next.y+=delta.y; }
        if(tied.z) { cell.z+=stepV.z;next.z+=delta.z; }
    }
    return 0.0;
}
// The source pack distorts its shadow projection before writing depth. These are
// the supported adapter's reversible projection equations, not a world-space ray cap.
vec3 ipSunProject(int i,vec3 sourcePoint) {
    vec4 clip=ipSunSourceProjection[i]*vec4(sourcePoint,1.0);
    vec3 q=clip.xyz/clip.w;
    q.xy/=length(q.xy)*ipSunShadowBias[i]+1.0-ipSunShadowBias[i];
    q.z*=0.2;
    return q*0.5+0.5;
}
float ipSunDepthCompare(int i,vec3 coord) {
    // Constant sampler indices work on GL 3.3 drivers. Units may alias one source snapshot.
    if(i==0) return texture(ipSunDepth0,coord);
    if(i==1) return texture(ipSunDepth1,coord);
    if(i==2) return texture(ipSunDepth2,coord);
    return texture(ipSunDepth3,coord);
}
#ifndef IP_SUN_PACK_PCF
float ipSunPackCoverage(int i,vec3 point,float viewDistance,float visibility,bool covered) { return covered?visibility:0.0; }
#endif
float ipSunPathTap(int i,vec3 point,vec3 sampleCoord) {

    vec3 raw=sampleCoord*2.0-1.0;
    float denominator=1.0-ipSunShadowBias[i]*length(raw.xy);
    if(denominator<=0.000001) return 0.0;
    raw.xy*= (1.0-ipSunShadowBias[i])/denominator;
    raw.z/=0.2;
    vec4 unprojected=ipSunSourceInverse[i]*vec4(raw,1.0);
    if(abs(unprojected.w)<0.000001) return 0.0;
    vec3 sourcePoint=unprojected.xyz/unprojected.w;
    float incidence=dot(ipSunSourceLight[i],ipSunSourceFacing[i]);
    if(incidence<=0.000001) return 0.0;
    sourcePoint-=ipSunSourceLight[i]*dot(sourcePoint-ipSunSourcePlane[i],ipSunSourceFacing[i])/incidence;
    vec3 relative=sourcePoint-ipSunSourcePlane[i];
    vec2 uv=vec2(dot(relative,ipSunSourceU[i]),dot(relative,ipSunSourceV[i]));
    if(any(greaterThanEqual(abs(uv),ipSunHalfSize[i]))) return 0.0;
    vec3 hit=ipSunPlane[i]+ipSunU[i]*uv.x+ipSunV[i]*uv.y;
    if(ipSunReceiverPath(i,point,hit)<0.5) return 0.0;
    vec3 depthCoord=ipSunProject(i,sourcePoint);
    bool covered=all(greaterThan(depthCoord,vec3(0.0))) && all(lessThan(depthCoord,vec3(1.0)));
    vec3 sourceReceiver=sourcePoint+ipSunSourceNormal(i,point-hit);
    // Compare at the aperture, never at the destination receiver: source continuation
    // behind the opening must not cast a fictitious shadow into the other world.
    float visibility=covered?ipSunDepthCompare(i,vec3(depthCoord.xy,depthCoord.z-0.000005)):0.0;
    return ipSunPackCoverage(i,point,length(sourceReceiver),visibility,covered);
}
#ifndef IP_SUN_PACK_PCF
float ipSunPackFiltered(int i,vec3 point,vec3 coord) { return ipSunPathTap(i,point,coord); }
#endif
float ipSunVisibility(int i,vec3 point) {
    vec3 ray=ipSunDirection[i];
    float denom=dot(ray,ipSunInward[i]);
    float side=dot(point-ipSunPlane[i],ipSunInward[i]);
    if(denom>=-0.000001 || side<=0.0) return 0.0;
    if(ipSunVoxel(i,ivec3(floor(point-ipSunOrigin[i]))).a<0.5) return 0.0;
    vec3 hit=point+ray*(-side/denom);
    vec3 relative=hit-ipSunPlane[i];
    vec2 uv=vec2(dot(relative,ipSunU[i]),dot(relative,ipSunV[i]));
    if(ipSunDepthValid[i]!=0) {
        vec3 sourceHit=ipSunSourcePlane[i]+ipSunSourceU[i]*uv.x+ipSunSourceV[i]*uv.y;
        vec3 coord=ipSunProject(i,sourceHit);
        if(any(lessThanEqual(coord,vec3(0.0))) || any(greaterThanEqual(coord,vec3(1.0)))) {
            // Native map coverage is finite; it is not an opaque wall at its boundary.
            if(any(greaterThanEqual(abs(uv),ipSunHalfSize[i])) || ipSunReceiverPath(i,point,hit)<0.5) return 0.0;
            vec3 sourceReceiver=sourceHit+ipSunSourceNormal(i,point-hit);
            return ipSunPackCoverage(i,point,length(sourceReceiver),0.0,false);
        }
        return ipSunPackFiltered(i,point,coord);
    }
    // No valid captured source pass: retain the strict, observed CPU source mask.
    if(any(greaterThanEqual(abs(uv),ipSunHalfSize[i]))) return 0.0;
    float source=texture(ipSunSourceShadow,vec3(uv/(2.0*ipSunHalfSize[i])+0.5,float(i))).r;
    return source<=0.0?0.0:source*ipSunReceiverPath(i,point,hit);
}
vec3 ipSunApply(vec3 point,vec3 normal,vec3 original,inout vec3 blockLighting,float lightmapXM,float emission) {
    vec3 incoming=vec3(0.0);
    float weight=0.0, incomingBlockMultiplier=0.0;
    for(int i=0;i<min(ipSunCount,4);i++) {
        vec3 airPoint=point+normal*0.04;
        vec4 data=ipSunData(i,airPoint);
        if(data.a<0.5 || data.b<=0.0) continue;
        float visibility=ipSunVisibility(i,airPoint);
        float blockMultiplier;
        vec3 contribution=ipSunPackScene(i,data.r,visibility,normal,length(point),lightmapXM,emission,blockMultiplier);
        incomingBlockMultiplier=max(incomingBlockMultiplier,blockMultiplier*data.b);
        // Overlapping transport volumes do not add the same sky twice.
        incoming=max(incoming,contribution*data.b);
        weight=max(weight,data.b);
    }
    blockLighting *= 1.0-clamp(weight,0.0,1.0)+incomingBlockMultiplier;
    return original*(1.0-clamp(weight,0.0,1.0))+incoming;
}
vec3 ipSunApply(vec3 point,vec3 normal,vec3 original) {
    vec3 unusedBlock=vec3(0.0);
    return ipSunApply(point,normal,original,unusedBlock,0.0,0.0);
}
float ipSunDirectionShade(vec3 point,vec3 normal,float original) {
    float weight=0.0, incoming=0.0;
    for(int i=0;i<min(ipSunCount,4);i++) {
        vec4 data=ipSunData(i,point+normal*0.04);
        if(data.a<0.5 || data.b<=0.0) continue;
        incoming=max(incoming,ipSunPackDirectionShade(i,normal)*data.b);
        weight=max(weight,data.b);
    }
    return original*(1.0-clamp(weight,0.0,1.0))+incoming;
}
