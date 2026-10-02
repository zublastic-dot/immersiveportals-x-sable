package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.layer.GbufferPrograms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A failed optional source render must not leave a native entity phase open. */
@Mixin(value = GbufferPrograms.class, remap = false)
public interface IEIrisGbufferPrograms {
    @Accessor("entities") static boolean ip_entities() { throw new AssertionError(); }
    @Accessor("entities") static void ip_entities(boolean value) { throw new AssertionError(); }
    @Accessor("blockEntities") static boolean ip_blockEntities() { throw new AssertionError(); }
    @Accessor("blockEntities") static void ip_blockEntities(boolean value) { throw new AssertionError(); }
    @Accessor("outline") static boolean ip_outline() { throw new AssertionError(); }
    @Accessor("outline") static void ip_outline(boolean value) { throw new AssertionError(); }
}
