package qouteall.imm_ptl.core.lighting;

import java.util.*;

/** Bounded, read-only voxel transport. Unknown space never proves an enclosure. */
public final class PortalLightField {
    public record Pos(int x, int y, int z) {
        public Pos add(int x, int y, int z) { return new Pos(this.x+x, this.y+y, this.z+z); }
    }
    public enum Cell { OPEN, CLOSED, UNKNOWN }
    public interface World { Cell cell(Pos pos); }
    public record Light(int sky, int block) {
        public Light { sky = Math.clamp(sky, 0, 15); block = Math.clamp(block, 0, 15); }
        Light step() { return new Light(sky-1, block-1); }
        Light max(Light other) { return new Light(Math.max(sky, other.sky), Math.max(block, other.block)); }
    }
    public record Result(Map<Pos, Light> cells, String reason) {
        public boolean enclosed() { return reason.equals("enclosed"); }
    }
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private PortalLightField() {}

    public static Result solve(World world, Map<Pos, Light> seeds, int maxCells, int maxExtent) {
        if (seeds.isEmpty()) return rejected("no aperture");
        var cells = new HashMap<Pos, Light>();
        var queue = new ArrayDeque<Pos>();
        for (var seed : seeds.entrySet()) {
            if (world.cell(seed.getKey()) == Cell.UNKNOWN) return rejected("unloaded aperture");
            if (world.cell(seed.getKey()) != Cell.OPEN) continue;
            cells.put(seed.getKey(), seed.getValue()); queue.add(seed.getKey());
        }
        if (cells.isEmpty()) return rejected("blocked aperture");
        Pos first = queue.getFirst();
        int minX=first.x, minY=first.y, minZ=first.z, maxX=first.x, maxY=first.y, maxZ=first.z;
        // First establish the whole enclosure, even beyond the light's reach.
        while (!queue.isEmpty()) {
            Pos p = queue.removeFirst();
            minX=Math.min(minX,p.x); minY=Math.min(minY,p.y); minZ=Math.min(minZ,p.z);
            maxX=Math.max(maxX,p.x); maxY=Math.max(maxY,p.y); maxZ=Math.max(maxZ,p.z);
            if (cells.size()>maxCells || maxX-minX>=maxExtent || maxY-minY>=maxExtent || maxZ-minZ>=maxExtent)
                return rejected("open or over budget");
            for (int[] d : DIRECTIONS) {
                Pos next=p.add(d[0],d[1],d[2]);
                if (cells.containsKey(next)) continue;
                Cell cell=world.cell(next);
                if (cell==Cell.UNKNOWN) return rejected("unloaded boundary");
                if (cell==Cell.OPEN) { cells.put(next,new Light(0,0)); queue.add(next); }
            }
        }
        queue.addAll(seeds.keySet());
        while (!queue.isEmpty()) {
            Pos p=queue.removeFirst();
            Light here=cells.get(p);
            if (here==null) continue;
            Light propagated=here.step();
            for (int[] d : DIRECTIONS) {
                Pos next=p.add(d[0],d[1],d[2]); Light old=cells.get(next);
                if (old==null) continue;
                Light merged=old.max(propagated);
                if (!old.equals(merged)) { cells.put(next,merged); queue.add(next); }
            }
        }
        return new Result(Map.copyOf(cells),"enclosed");
    }
    private static Result rejected(String reason) { return new Result(Map.of(),reason); }
}
