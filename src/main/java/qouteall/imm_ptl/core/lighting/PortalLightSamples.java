package qouteall.imm_ptl.core.lighting;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/** Transport uses propagated client light, never a renderer's distance-only read fallback. */
final class PortalLightSamples {
    private PortalLightSamples() {}
    static Pass<ClientLevel> pass() {
        return new Pass<>((world,section)->world.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(section));
    }
    /** ScalableLux returns a new 2 KiB nibble copy per section query. Never retain it across update passes. */
    static final class Pass<W> {
        private static final int MAX_SECTIONS_PER_WORLD=1024;
        private final BiFunction<W,SectionPos,DataLayer> read;
        private final Map<W,Long2ObjectLinkedOpenHashMap<DataLayer>> worlds=new IdentityHashMap<>();
        Pass(BiFunction<W,SectionPos,DataLayer> read) { this.read=read; }
        int block(W world,BlockPos pos) {
            var sections=worlds.computeIfAbsent(world,ignored->new Long2ObjectLinkedOpenHashMap<>());
            long key=SectionPos.asLong(pos.getX()>>4,pos.getY()>>4,pos.getZ()>>4);
            DataLayer data=sections.getAndMoveToLast(key);
            if(data==null && !sections.containsKey(key)) {
                data=read.apply(world,SectionPos.of(pos));
                if(sections.size()==MAX_SECTIONS_PER_WORLD) sections.removeFirst();
                sections.putAndMoveToLast(key,data);
            }
            return PortalLightSamples.block(data,pos);
        }
    }
    static int block(ClientLevel world,BlockPos pos) {
        return block(world.getLightEngine().getLayerListener(LightLayer.BLOCK)
            .getDataLayerData(SectionPos.of(pos)),pos);
    }
    static int block(DataLayer data,BlockPos pos) {
        return data==null?0:data.get(pos.getX()&15,pos.getY()&15,pos.getZ()&15);
    }
}
