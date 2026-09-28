package ipl.sable.network;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class HostedMovementRoutingTest {
    /** Two queues reproduce the independent normal and local/UDP delivery paths. */
    private static final class Connection {
        final Queue<Runnable> normal = new ArrayDeque<>(), shortcut = new ArrayDeque<>();
        final List<String> received = new ArrayList<>();
        String container;
        void start(String dimension) { normal.add(() -> { container = dimension; received.add("start:" + dimension); }); }
        void stop() { normal.add(() -> { container = null; received.add("stop"); }); }
        void move(boolean unordered, String dimension) {
            (unordered ? shortcut : normal).add(() -> {
                received.add(Objects.equals(container, dimension) ? "move:" + dimension : "missing-sublevel");
            });
        }
        void drain(Queue<Runnable> channel) { while (!channel.isEmpty()) channel.remove().run(); }
    }

    @Test void firstAssemblyMovementCannotOvertakeBootstrap() {
        var old = new Connection();
        old.start("overworld"); old.move(true, "overworld");
        old.drain(old.shortcut); old.drain(old.normal);
        assertTrue(old.received.contains("missing-sublevel"), "reproduce the separate-channel race");

        var fixed = new Connection();
        fixed.start("overworld");
        fixed.move(HostedMovementRouting.useUnorderedTransport(true, true, () -> true), "overworld");
        fixed.drain(fixed.shortcut); fixed.drain(fixed.normal);
        assertEquals(List.of("start:overworld", "move:overworld"), fixed.received);
    }

    @Test void delayedSourceMovementCannotArriveAfterRehomeRemoval() {
        var fixed = new Connection();
        fixed.start("overworld"); fixed.drain(fixed.normal);
        fixed.move(HostedMovementRouting.useUnorderedTransport(true, true, () -> true), "overworld");
        fixed.stop(); fixed.start("hosting");
        fixed.move(HostedMovementRouting.useUnorderedTransport(false, true, () -> true), "hosting");
        fixed.drain(fixed.normal); fixed.drain(fixed.shortcut);
        assertEquals(List.of("start:overworld", "move:overworld", "stop", "start:hosting", "move:hosting"), fixed.received);
    }

    @Test void legacyModeRetainsUdpButCrossDimensionNeverUsesIt() {
        assertTrue(HostedMovementRouting.useUnorderedTransport(true, false, () -> true));
        assertFalse(HostedMovementRouting.useUnorderedTransport(true, false, () -> false));
        BooleanSupplier mustNotConsult = () -> { fail("must use stamped ordered connection"); return true; };
        assertFalse(HostedMovementRouting.useUnorderedTransport(false, false, mustNotConsult));
        assertFalse(HostedMovementRouting.useUnorderedTransport(true, true, mustNotConsult));
    }
}
