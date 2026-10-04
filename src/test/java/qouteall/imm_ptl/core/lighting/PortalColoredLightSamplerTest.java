package qouteall.imm_ptl.core.lighting;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.lighting.PortalColoredLightField.*;
import static qouteall.imm_ptl.core.lighting.PortalLightField.Pos;

class PortalColoredLightSamplerTest {
    private static final Pos ORIGIN = new Pos(0, 0, 0);
    private static Cell emitter(Rgb rgb) { return new Cell(rgb, Rgb.WHITE, 15); }
    @Test void equalDimensionNamesDoNotShareLevelIdentityOrGeometry() {
        record World(String name) {}
        var first = new World("modded:same" ); var second = new World("modded:same");
        assertEquals(first, second);
        var engine = new PortalColoredLightSampler.Engine<World>(world -> p -> p.equals(ORIGIN)
            ? emitter(world == first ? new Rgb(15, 0, 0) : new Rgb(0, 0, 15)) : Cell.UNKNOWN, () -> 0);
        PortalColoredLightSampler.Result a = null, b = null;
        for (long tick = 0; tick < 20; tick++) {
            a = engine.sample(first, List.of(ORIGIN), tick); b = engine.sample(second, List.of(ORIGIN), tick);
        }
        assertTrue(a.ready()); assertTrue(b.ready()); assertEquals(15, a.red()); assertEquals(0, a.blue());
        assertEquals(0, b.red()); assertEquals(15, b.blue());
        for (long tick = 20; tick < 40; tick++) a = engine.sample(first, List.of(ORIGIN.add(1, 0, 0)), tick);
        assertTrue(a.ready()); assertEquals(0, a.red(), "Moved aperture has independent opaque geometry");
    }
    @Test void sameScalarBrightnessColorChangeAndRemovalRefreshWithoutInvalidationSignal() {
        var source = new AtomicReference<>(new Rgb(15, 0, 0));
        var engine = new PortalColoredLightSampler.Engine<Object>(world -> p -> p.equals(ORIGIN) ? emitter(source.get()) : Cell.UNKNOWN, () -> 0);
        Object world = new Object(); PortalColoredLightSampler.Result result = null;
        for (long tick = 0; tick < 10; tick++) result = engine.sample(world, List.of(ORIGIN), tick);
        assertTrue(result.ready()); assertEquals(15, result.red());
        source.set(new Rgb(0, 0, 15));
        for (long tick = 10; tick < 30; tick++) result = engine.sample(world, List.of(ORIGIN), tick);
        assertEquals(0, result.red()); assertEquals(15, result.blue());
        source.set(Rgb.DARK);
        for (long tick = 30; tick < 50; tick++) result = engine.sample(world, List.of(ORIGIN), tick);
        assertEquals(0, result.red()); assertEquals(0, result.blue());
    }
    @Test void allEndpointsShareTickBudgetAndEventuallyCompleteWithoutStarvation() {
        var engine = new PortalColoredLightSampler.Engine<Object>(world -> p -> Cell.UNKNOWN, () -> 0);
        var worlds = new ArrayList<Object>(); for (int i = 0; i < 16; i++) worlds.add(new Object());
        for (long tick = 0; tick < 100; tick++) {
            for (Object world : worlds) engine.sample(world, List.of(ORIGIN), tick);
            assertTrue(engine.lastReads <= PortalColoredLightSampler.MAX_READS_PER_TICK);
            assertTrue(engine.lastSteps <= PortalColoredLightSampler.MAX_STEPS_PER_TICK);
            int before = engine.lastReads;
            for (Object world : worlds) engine.sample(world, List.of(ORIGIN), tick);
            assertEquals(before, engine.lastReads, "Repeated requests cannot obtain another tick budget");
        }
        for (Object world : worlds) assertTrue(engine.sample(world, List.of(ORIGIN), 100).ready());
        assertEquals(16, engine.entries.size());
        engine.sample(new Object(), List.of(ORIGIN), 100); assertEquals(16, engine.entries.size());
        engine.clear(); assertEquals(0, engine.entries.size());
    }
    @Test void readerFailureAndOversizedGeometryRemainUnavailableInsteadOfInventingLight() {
        var engine = new PortalColoredLightSampler.Engine<Object>(world -> null, () -> 0);
        assertFalse(engine.sample(new Object(), List.of(ORIGIN), 0).supported());
        var validReader = new PortalColoredLightSampler.Engine<Object>(world -> p -> Cell.AIR, () -> 0);
        var result = validReader.sample(new Object(), List.of(ORIGIN, ORIGIN.add(90, 90, 90)), 0);
        assertFalse(result.supported()); assertFalse(result.ready()); assertEquals("unsupported volume", result.reason());
    }
    @Test void expensiveNativeReadsYieldAtDeadlineAndCannotSpendTheBudgetTwiceInOneTick() {
        var clock = new AtomicLong(); var calls = new AtomicInteger();
        var engine = new PortalColoredLightSampler.Engine<Object>(world -> p -> {
            calls.incrementAndGet(); clock.addAndGet(500_000); return Cell.AIR;
        }, clock::get);
        Object world = new Object();
        assertFalse(engine.sample(world, List.of(ORIGIN), 0).ready());
        assertEquals(4, calls.get()); assertEquals(4, engine.lastReads);
        assertTrue(engine.deadlineReached); assertEquals(2_000_000, engine.lastNanos);
        engine.sample(world, List.of(ORIGIN), 0); assertEquals(4, calls.get());
        engine.sample(world, List.of(ORIGIN), 1); assertEquals(8, calls.get());
        assertFalse(engine.sample(world, List.of(ORIGIN), 1).ready(), "A partial dark capture is not a final black source");
    }
    @Test void oneSlowNativeCallMayOverrunButNoFurtherReadRunsAndNextEndpointGetsItsTurn() {
        var clock = new AtomicLong(); var firstCalls = new AtomicInteger(); var secondCalls = new AtomicInteger();
        Object first = new Object(), second = new Object();
        var engine = new PortalColoredLightSampler.Engine<Object>(world -> p -> {
            (world == first ? firstCalls : secondCalls).incrementAndGet();
            clock.addAndGet(3_000_000); return Cell.UNKNOWN;
        }, clock::get);
        for (long tick = 0; tick < 6; tick++) {
            engine.sample(first, List.of(ORIGIN), tick); engine.sample(second, List.of(ORIGIN), tick);
            assertEquals(1, engine.lastReads, "Native calls cannot be preempted, but must not be followed by another");
        }
        assertTrue(firstCalls.get() > 0); assertTrue(secondCalls.get() > 0);
        assertEquals(6, firstCalls.get()+secondCalls.get());
        engine.clear(); assertEquals(0, engine.lastNanos); assertFalse(engine.deadlineReached);
    }
}
