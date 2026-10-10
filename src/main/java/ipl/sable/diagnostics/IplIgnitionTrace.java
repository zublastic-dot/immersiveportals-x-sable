package ipl.sable.diagnostics;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;

/** Observational trace only. Client and integrated-server scopes are deliberately separate. */
public final class IplIgnitionTrace {
    public enum Side { CLIENT, SERVER }
    private static final Logger LOG = LogUtils.getLogger();
    private static final Holder CLIENT = new Holder(), SERVER = new Holder();
    private static final Scope NOOP = () -> {};
    private static final class Holder {
        volatile IgnitionTraceWindow window;
        final ThreadLocal<Current> current = new ThreadLocal<>();
    }
    private record Current(IgnitionTraceWindow window, IgnitionTraceWindow.Attempt attempt) {}
    public interface Scope extends AutoCloseable { @Override void close(); }
    private IplIgnitionTrace() {}
    private static Holder holder(Side side) { return side == Side.CLIENT ? CLIENT : SERVER; }

    public static void arm(Side side, IgnitionTraceWindow window) {
        clear(side);
        holder(side).window = window;
        LOG.info("[IPL-IGNITION-TRACE] side={} armed {}", side, window.status());
    }
    public static IgnitionTraceWindow window(Side side) { return holder(side).window; }
    public static void clear(Side side) {
        var h = holder(side); var old = h.window;
        if (old != null) old.stop();
        h.window = null; h.current.remove();
    }
    public static boolean isTracing(Side side) {
        var h = holder(side); var c = h.current.get();
        return c != null && c.window == h.window && c.window.alive();
    }
    public static Scope begin(Side side, Player player, BlockHitResult hit, String origin) {
        var h = holder(side); var window = h.window;
        if (window == null || player == null || hit == null) return NOOP;
        if (isTracing(side)) return NOOP;
        var pos = hit.getBlockPos();
        var attempt = window.begin(player.getUUID(), player.level().dimension().location().toString(),
            pos.getX(), pos.getY(), pos.getZ());
        if (attempt == null) return NOOP;
        var current = new Current(window, attempt);
        h.current.set(current);
        event(side, "begin", "origin", origin, "hit", pos.toShortString(), "face", hit.getDirection(),
            "eye", player.getEyePosition(), "sneaking", player.isShiftKeyDown(), "pose", player.getPose(),
            "main_item", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()),
            "off_item", BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem()));
        return () -> {
            try { event(side, "end", "origin", origin); }
            finally { attempt.close(); if (h.current.get() == current) h.current.remove(); }
        };
    }
    public static void event(Side side, String stage, Object... fields) {
        if (!isTracing(side)) return;
        var c = holder(side).current.get();
        if (!c.attempt.event()) return;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i + 1 < fields.length && i < 40; i += 2)
            line.append(' ').append(clean(fields[i])).append('=').append(clean(fields[i + 1]));
        LOG.info("[IPL-IGNITION-TRACE] token={} side={} attempt={} stage={}{}",
            c.window.token, side, c.attempt.number, clean(stage), line);
    }
    private static String clean(Object value) {
        try {
            String text = String.valueOf(value).replaceAll("[\\r\\n\\t\\x00-\\x1f]", " ");
            return text.length() > 256 ? text.substring(0, 256) : text;
        } catch (RuntimeException ignored) { return "<unavailable>"; }
    }
}
