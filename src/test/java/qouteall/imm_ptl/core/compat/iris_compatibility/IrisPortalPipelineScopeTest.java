package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production scope without starting Minecraft or initializing Iris. */
class IrisPortalPipelineScopeTest {
    private record Pipeline(String name) {}
    private record Pose(double x, double yaw, float partialTick) {}

    private static final class State {
        Pose pose = new Pose(1, 10, .1f);
        final List<Pose> restored = new ArrayList<>();
    }

    private static final class Backend implements IrisPortalPipelineScope.Selection<String, Pipeline> {
        final Map<String, Pipeline> pipelines = Map.of(
            "A", new Pipeline("A"), "B", new Pipeline("B"), "C", new Pipeline("C")
        );
        final List<String> prepared = new ArrayList<>();
        String dimension = "A";
        Pipeline selected = pipelines.get("A");
        Pipeline replacement;
        Throwable prepareFailure;

        @Override public String dimension() { return dimension; }
        @Override public Pipeline pipeline() { return selected; }
        @Override public Pipeline prepare(String dimension) {
            prepared.add(dimension);
            if (prepareFailure instanceof RuntimeException failure) throw failure;
            if (prepareFailure instanceof Error failure) throw failure;
            if (replacement != null) return selected = replacement;
            return selected = pipelines.get(dimension);
        }
    }

    private static IrisPortalPipelineScope<String, Pipeline> capture(Backend backend, State state) {
        Pose saved = state.pose;
        return new IrisPortalPipelineScope<>(backend, () -> {
            state.pose = saved;
            state.restored.add(saved);
        });
    }

    /** Minecraft's world is restored before the scope closes; the global manager is still the child. */
    private static void renderChild(Backend backend, State state, String dimension, Pose pose, Runnable render) {
        String parent = backend.dimension;
        try (var scope = capture(backend, state)) {
            try {
                backend.dimension = dimension;
                backend.prepare(dimension);
                state.pose = pose;
                render.run();
            } finally {
                backend.dimension = parent;
            }
        }
    }

    @Test void nestedScopesRestoreBThenAAndTheirCapturedPoses() {
        var backend = new Backend();
        var state = new State();
        Pose a = state.pose, b = new Pose(20, 40, .2f), c = new Pose(300, 80, .3f);
        long depth = diagnostic("activeDepth"), mismatches = diagnostic("restoredMismatches");

        renderChild(backend, state, "B", b, () -> {
            assertSame(backend.pipelines.get("B"), backend.pipeline());
            renderChild(backend, state, "C", c, () -> {
                assertSame(backend.pipelines.get("C"), backend.pipeline());
                assertEquals(c, state.pose);
                assertEquals(depth + 2, diagnostic("activeDepth"));
            });
            assertEquals("B", backend.dimension());
            assertSame(backend.pipelines.get("B"), backend.pipeline());
            assertEquals(b, state.pose);
        });

        assertSame(backend.pipelines.get("A"), backend.pipeline());
        assertEquals("A", backend.dimension());
        assertEquals(a, state.pose);
        assertEquals(List.of(b, a), state.restored);
        assertEquals(List.of("B", "C", "B", "A"), backend.prepared);
        assertEquals(depth, diagnostic("activeDepth"));
        assertEquals(mismatches + 2, diagnostic("restoredMismatches"));
    }

    @Test void nestedRenderExceptionUnwindsBothRealScopesAndPreservesOriginalFailure() {
        var backend = new Backend();
        var state = new State();
        Pose a = state.pose, b = new Pose(20, 40, .2f), c = new Pose(300, 80, .3f);
        long depth = diagnostic("activeDepth"), failures = diagnostic("failures");
        var failure = new IllegalArgumentException("child render failed");

        assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
            renderChild(backend, state, "B", b, () ->
                renderChild(backend, state, "C", c, () -> { throw failure; }))));

        assertSame(backend.pipelines.get("A"), backend.pipeline());
        assertEquals("A", backend.dimension());
        assertEquals(List.of(b, a), state.restored);
        assertEquals(List.of("B", "C", "B", "A"), backend.prepared);
        assertEquals(a, state.pose);
        assertEquals(depth, diagnostic("activeDepth"));
        assertEquals(failures, diagnostic("failures"));
    }

    @Test void sameDimensionPortalStillRestoresItsDistinctCapturedPose() {
        var backend = new Backend();
        var state = new State();
        Pose outer = state.pose;
        long mismatches = diagnostic("restoredMismatches");

        renderChild(backend, state, "A", new Pose(800, 170, .75f), () -> {
            assertSame(backend.pipelines.get("A"), backend.pipeline());
            assertNotEquals(outer, state.pose);
        });

        assertEquals(outer, state.pose);
        assertEquals(List.of(outer), state.restored);
        assertEquals(List.of("A", "A"), backend.prepared);
        assertEquals(mismatches, diagnostic("restoredMismatches"));
    }

    @Test void closeIsIdempotentEvenAfterAnotherPipelineWasSelected() {
        var backend = new Backend();
        var state = new State();
        long depth = diagnostic("activeDepth");
        var scope = capture(backend, state);
        backend.prepare("B");
        scope.close();
        backend.prepare("C");
        scope.close();

        assertEquals(List.of("B", "A", "C"), backend.prepared);
        assertSame(backend.pipelines.get("C"), backend.pipeline());
        assertEquals(1, state.restored.size());
        assertEquals(depth, diagnostic("activeDepth"));
    }

    @Test void prepareRuntimeFailureAndErrorAreReportedAndStillRestoreCapturedState() {
        for (Throwable failure : List.of(new IllegalStateException("prepare failed"), new AssertionError("prepare error"))) {
            var backend = new Backend();
            var state = new State();
            Pose outer = state.pose;
            long depth = diagnostic("activeDepth"), failures = diagnostic("failures");
            var scope = capture(backend, state);
            backend.prepare("B");
            state.pose = new Pose(90, 120, .9f);
            backend.prepareFailure = failure;

            assertSame(failure, assertThrows(Throwable.class, scope::close));
            assertEquals(outer, state.pose);
            assertEquals(List.of(outer), state.restored);
            assertSame(backend.pipelines.get("B"), backend.pipeline());
            assertEquals(failures + 1, diagnostic("failures"));
            assertEquals(depth, diagnostic("activeDepth"));
            assertTrue(IrisPortalPipelineScope.diagnostics().contains("after=restore-failed"));
            assertDoesNotThrow(scope::close);
            assertEquals(1, state.restored.size());
        }
    }

    @Test void capturedStateFailureAlsoUnwindsDepthAfterRestoringPipeline() {
        var backend = new Backend();
        long depth = diagnostic("activeDepth"), failures = diagnostic("failures");
        var failure = new IllegalArgumentException("captured state failed");
        var scope = new IrisPortalPipelineScope<>(backend, () -> { throw failure; });
        backend.prepare("B");

        assertSame(failure, assertThrows(IllegalArgumentException.class, scope::close));
        assertSame(backend.pipelines.get("A"), backend.pipeline());
        assertEquals(depth, diagnostic("activeDepth"));
        assertEquals(failures + 1, diagnostic("failures"));
        assertDoesNotThrow(scope::close);
    }

    @Test void equalButDistinctReplacementPipelineIsRejectedAndCapturedStateStillRestored() {
        var backend = new Backend();
        var state = new State();
        Pose outer = state.pose;
        long depth = diagnostic("activeDepth"), failures = diagnostic("failures");
        var scope = capture(backend, state);
        backend.prepare("B");
        state.pose = new Pose(90, 120, .9f);
        backend.replacement = new Pipeline("A");
        assertEquals(backend.pipelines.get("A"), backend.replacement);
        assertNotSame(backend.pipelines.get("A"), backend.replacement);

        assertThrows(IllegalStateException.class, scope::close);
        assertEquals(outer, state.pose);
        assertEquals(List.of(outer), state.restored);
        assertSame(backend.replacement, backend.pipeline());
        assertEquals(failures + 1, diagnostic("failures"));
        assertEquals(depth, diagnostic("activeDepth"));
        assertDoesNotThrow(scope::close);
        assertEquals(1, state.restored.size());
    }

    @Test void wrongReplacementRemainsPrimaryWhenCapturedStateFinallyAlsoThrows() {
        var backend = new Backend();
        long depth = diagnostic("activeDepth"), failures = diagnostic("failures");
        var stateFailure = new IllegalArgumentException("captured state failed");
        int[] restoreCalls = {0};
        var scope = new IrisPortalPipelineScope<>(backend, () -> {
            restoreCalls[0]++;
            throw stateFailure;
        });
        backend.prepare("B");
        backend.replacement = new Pipeline("A");

        var failure = assertThrows(IllegalStateException.class, scope::close);
        assertArrayEquals(new Throwable[] {stateFailure}, failure.getSuppressed());
        assertEquals(1, restoreCalls[0]);
        assertEquals(failures + 1, diagnostic("failures"));
        assertEquals(depth, diagnostic("activeDepth"));
        assertDoesNotThrow(scope::close);
        assertEquals(1, restoreCalls[0]);
    }

    @Test void simultaneousPrepareAndStateFailuresKeepPreparePrimaryAndSuppressStateOnce() {
        for (Throwable prepareFailure : List.of(new IllegalStateException("prepare failed"), new AssertionError("prepare error"))) {
            var backend = new Backend();
            long depth = diagnostic("activeDepth"), failures = diagnostic("failures");
            var stateFailure = new IllegalArgumentException("captured state failed");
            int[] restoreCalls = {0};
            var scope = new IrisPortalPipelineScope<>(backend, () -> {
                restoreCalls[0]++;
                throw stateFailure;
            });
            backend.prepare("B");
            backend.prepareFailure = prepareFailure;

            assertSame(prepareFailure, assertThrows(Throwable.class, scope::close));
            assertArrayEquals(new Throwable[] {stateFailure}, prepareFailure.getSuppressed());
            assertSame(backend.pipelines.get("B"), backend.pipeline());
            assertEquals(1, restoreCalls[0]);
            assertEquals(failures + 1, diagnostic("failures"));
            assertEquals(depth, diagnostic("activeDepth"));
            assertDoesNotThrow(scope::close);
            assertEquals(1, restoreCalls[0]);
            assertEquals(1, prepareFailure.getSuppressed().length);
        }
    }

    private static long diagnostic(String field) {
        var matcher = Pattern.compile("\\b" + field + "=(\\d+)").matcher(IrisPortalPipelineScope.diagnostics());
        assertTrue(matcher.find(), field);
        return Long.parseLong(matcher.group(1));
    }
}
