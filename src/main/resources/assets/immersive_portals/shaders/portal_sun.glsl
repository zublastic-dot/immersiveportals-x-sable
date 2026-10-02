// Original IP/Sable compatibility code. No shader-pack source is distributed here.
uniform int ipSunCount;
uniform int ipSunPortalView;
uniform sampler3D ipSunAtlas;
uniform sampler2DArray ipSunSourceShadow;
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
// Exact aperture intersection, followed by bounded voxel DDA on the receiving side.
// The atlas includes the explicitly observed layer containing the portal plane.
// No missing voxel is inferred to be an opening: opaque/unknown cells stop light.
float ipSunVisibility(int i,vec3 point) {
    vec3 ray=ipSunDirection[i];
    float denom=dot(ray,ipSunInward[i]);
    float side=dot(point-ipSunPlane[i],ipSunInward[i]);
    if(denom>=-0.000001 || side<=0.0) return 0.0;
    float distance=-side/denom;
    vec3 hit=point+ray*distance-ipSunPlane[i];
    vec2 uv=vec2(dot(hit,ipSunU[i]),dot(hit,ipSunV[i]));
    if(any(greaterThanEqual(abs(uv),ipSunHalfSize[i]))) return 0.0;
    float source=texture(ipSunSourceShadow,vec3(uv/(2.0*ipSunHalfSize[i])+0.5,float(i))).r;
    if(source<=0.0) return 0.0;
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
        if(t>=distance-0.00001) return source;
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
