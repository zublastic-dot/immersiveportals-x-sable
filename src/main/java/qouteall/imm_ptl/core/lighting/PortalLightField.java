package qouteall.imm_ptl.core.lighting;

import java.util.*;

/** Bounded voxel transport with local visibility, not a sealed-room classifier. */
public final class PortalLightField {
    public record Pos(int x, int y, int z) {
        public Pos add(int x, int y, int z) { return new Pos(this.x+x, this.y+y, this.z+z); }
        int component(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
    }
    public enum Cell { OPEN, CLOSED, UNKNOWN }
    public interface World { Cell cell(Pos pos); }
    public record Light(int sky, int block) {
        public Light { sky = Math.clamp(sky, 0, 15); block = Math.clamp(block, 0, 15); }
        Light step() { return new Light(sky-1, block-1); }
        Light max(Light other) { return new Light(Math.max(sky, other.sky), Math.max(block, other.block)); }
    }
    /** replacement is the fraction of native ambient replaced at each cell, in [0,1]. */
    public record Result(Map<Pos, Light> cells, Map<Pos, Float> replacement, String reason) {
        public boolean available() { return reason.equals("transport"); }
    }
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static final List<Ray> RAYS = rays();
    private static final int DEPTH = 16, HALO = 4;
    private PortalLightField() {}

    public static Result solve(World world, Map<Pos, Light> seeds, Pos inward, int maxExtent) {
        if(seeds.isEmpty()) return rejected("no aperture");
        if(Math.abs(inward.x)+Math.abs(inward.y)+Math.abs(inward.z)!=1 || maxExtent<1 || maxExtent>32)
            return rejected("unsupported bounds");
        int axis=inward.x!=0?0:inward.y!=0?1:2, sign=inward.component(axis);
        int plane=seeds.keySet().iterator().next().component(axis);
        if(seeds.keySet().stream().anyMatch(p->p.component(axis)!=plane)) return rejected("nonplanar aperture");
        int[] min={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE};
        int[] max={Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        for(Pos p:seeds.keySet()) for(int a=0;a<3;a++) {
            min[a]=Math.min(min[a],p.component(a));max[a]=Math.max(max[a],p.component(a));
        }
        for(int a=0;a<3;a++) {
            if(a==axis) {
                if(sign>0) max[a]+=Math.min(DEPTH,maxExtent)-1;else min[a]-=Math.min(DEPTH,maxExtent)-1;
            } else {
                int width=max[a]-min[a]+1;
                if(width>maxExtent) return rejected("unsupported bounds");
                int pad=Math.min(HALO,(maxExtent-width)/2);min[a]-=pad;max[a]+=pad;
            }
        }
        var grid=new Grid(world,min,max,axis,sign,seeds.keySet());
        var cells=new HashMap<Pos,Light>();
        var queue=new ArrayDeque<Pos>();
        for(var seed:seeds.entrySet()) if(grid.cell(seed.getKey())==Cell.OPEN) {
            cells.put(seed.getKey(),seed.getValue());queue.add(seed.getKey());
        }
        if(cells.isEmpty()) return rejected("blocked or unloaded aperture");
        // Fixed support bounds work even for open terrain. Neither a hole nor an
        // unknown boundary discards other known cells. No unbounded flood is attempted.
        while(!queue.isEmpty()) {
            Pos p=queue.removeFirst();
            for(int[] d:DIRECTIONS) {
                Pos next=p.add(d[0],d[1],d[2]);
                if(grid.contains(next) && !cells.containsKey(next) && grid.cell(next)==Cell.OPEN) {
                    cells.put(next,new Light(0,0));queue.add(next);
                }
            }
        }
        for(Pos p:seeds.keySet()) if(cells.containsKey(p)) queue.add(p);
        while(!queue.isEmpty()) {
            Pos p=queue.removeFirst();Light propagated=cells.get(p).step();
            for(int[] d:DIRECTIONS) {
                Pos next=p.add(d[0],d[1],d[2]);Light old=cells.get(next);
                if(old==null) continue;
                Light merged=old.max(propagated);
                if(!old.equals(merged)) { cells.put(next,merged);queue.add(next); }
            }
        }
        var replacement=new HashMap<Pos,Float>();
        for(Pos p:cells.keySet()) {
            int localViews=0,portalViews=0;
            for(Ray ray:RAYS) {
                int exit=grid.trace(p,ray);
                if(exit==1) localViews++;
                else if(exit==2) portalViews++;
            }
            // Opaque walls are not ambient sources. Unknown space counts as local
            // exposure, rather than fabricating a wall or disabling the whole field.
            float weight=localViews==0?1f:(float)portalViews/(localViews+portalViews);
            replacement.put(p,weight*(localViews==0?1:grid.edgeFade(p)));
        }
        return new Result(Map.copyOf(cells),Map.copyOf(replacement),"transport");
    }

    /** Dense immutable geometry makes the visibility loop allocation-free. */
    private static final class Grid {
        final int[] min,max,size;
        final int axis,sign;
        final Cell[] cells;
        final boolean[] aperture;
        Grid(World world,int[] min,int[] max,int axis,int sign,Set<Pos> seeds) {
            this.min=min;this.max=max;this.axis=axis;this.sign=sign;
            size=new int[]{max[0]-min[0]+3,max[1]-min[1]+3,max[2]-min[2]+3};
            cells=new Cell[size[0]*size[1]*size[2]];aperture=new boolean[cells.length];
            for(int z=min[2]-1;z<=max[2]+1;z++) for(int y=min[1]-1;y<=max[1]+1;y++) for(int x=min[0]-1;x<=max[0]+1;x++) {
                Pos p=new Pos(x,y,z);int i=index(x,y,z);
                cells[i]=world.cell(p);aperture[i]=seeds.contains(p);
            }
        }
        int index(int x,int y,int z) {
            return ((z-min[2]+1)*size[1]+y-min[1]+1)*size[0]+x-min[0]+1;
        }
        boolean contains(Pos p) { return contains(p.x,p.y,p.z); }
        boolean contains(int x,int y,int z) {
            return x>=min[0]&&x<=max[0]&&y>=min[1]&&y<=max[1]&&z>=min[2]&&z<=max[2];
        }
        Cell cell(Pos p) { return cells[index(p.x,p.y,p.z)]; }
        float edgeFade(Pos p) {
            // Keep the portal face intact; smoothly end this finite correction volume.
            int distance=Integer.MAX_VALUE;
            for(int a=0;a<3;a++) {
                if(a!=axis || sign<0) distance=Math.min(distance,p.component(a)-min[a]);
                if(a!=axis || sign>0) distance=Math.min(distance,max[a]-p.component(a));
            }
            return Math.clamp(distance/2f,0,1);
        }
        /** 0 opaque; 1 local environment/unknown; 2 portal. DDA visits every crossed voxel. */
        int trace(Pos start,Ray ray) {
            int x=start.x,y=start.y,z=start.z;
            double tx=ray.dx*.5,ty=ray.dy*.5,tz=ray.dz*.5;
            for(int i=0;i<96;i++) {
                int a=tx<=ty&&tx<=tz?0:ty<=tz?1:2;
                int step=a==0?ray.sx:a==1?ray.sy:ray.sz;
                if(a==axis && step==-sign && aperture[index(x,y,z)]) return 2;
                if(a==0) { x+=step;tx+=ray.dx; }
                else if(a==1) { y+=step;ty+=ray.dy; }
                else { z+=step;tz+=ray.dz; }
                Cell cell=cells[index(x,y,z)];
                if(cell==Cell.CLOSED) return 0;
                if(cell==Cell.UNKNOWN || !contains(x,y,z)) return 1;
            }
            return 1;
        }
    }
    private record Ray(int sx,int sy,int sz,double dx,double dy,double dz) {}
    private static List<Ray> rays() {
        // 50 deterministic, cubically symmetric directions. This is a local
        // visibility approximation, not a path tracer or general indirect-light solver.
        var result=new ArrayList<Ray>();
        for(int x=-2;x<=2;x++) for(int y=-2;y<=2;y++) for(int z=-2;z<=2;z++) {
            int sum=Math.abs(x)+Math.abs(y)+Math.abs(z),max=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));
            if(sum==0 || (max==2 && sum!=3)) continue;
            result.add(new Ray(x>0?1:-1,y>0?1:-1,z>0?1:-1,Math.abs(1.0/x),Math.abs(1.0/y),Math.abs(1.0/z)));
        }
        return List.copyOf(result);
    }
    static Result rejected(String reason) { return new Result(Map.of(),Map.of(),reason); }
}
