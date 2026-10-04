package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;

/** Restores Iris's global selection after MyGameRenderer has restored Minecraft's world. */
public final class IrisPortalPipelineScope<D, P> implements AutoCloseable {
    interface Selection<D, P> {
        D dimension();
        P pipeline();
        P prepare(D dimension);
    }

    private static int activeDepth, maxDepth;
    private static long restoredMismatches, failures;
    private static String lastOuterDimension = "none", lastExpected = "none", lastBefore = "none", lastAfter = "none";
    private final Selection<D, P> selection;
    private final D outerDimension;
    private final P outerPipeline;
    private final Runnable restoreCapturedState;
    private boolean closed;

    IrisPortalPipelineScope(Selection<D, P> selection, Runnable restoreCapturedState) {
        this.selection = selection;
        this.outerDimension = selection.dimension();
        this.outerPipeline = selection.pipeline();
        this.restoreCapturedState = restoreCapturedState;
        maxDepth = Math.max(maxDepth, ++activeDepth);
    }

    public static IrisPortalPipelineScope<NamespacedId, WorldRenderingPipeline> begin() {
        if (!IrisInterface.invoker.isShaders() || Minecraft.getInstance().level == null) return null;
        var manager = Iris.getPipelineManager();
        // An auxiliary refresh may start before Iris's primary setup immediately
        // after crossing dimensions. Establish the actual outer world first.
        var dimension = Iris.getCurrentDimension();
        var parent = manager.preparePipeline(dimension);
        // CapturedRenderingState is global too: returning only to the parent program
        // would still let later uniforms consume the child's camera, fog and phase.
        var state = new IrisSourceRefreshState();
        boolean renderingWorld = parent instanceof IEIrisNewWorldRenderingPipeline access && access.ip_getIsRenderingWorld();
        return new IrisPortalPipelineScope<>(new Selection<>() {
            @Override public NamespacedId dimension() { return dimension; }
            @Override public WorldRenderingPipeline pipeline() { return manager.getPipelineNullable(); }
            @Override public WorldRenderingPipeline prepare(NamespacedId dimension) {
                var child = manager.getPipelineNullable();
                if (child != parent && child instanceof IEIrisNewWorldRenderingPipeline access)
                    access.ip_setIsRenderingWorld(false);
                // Also restores Euphoria's dimension pack. Do not assign a hard-coded
                // Overworld/Nether category or merely update LevelRenderer.pipeline.
                return manager.preparePipeline(dimension);
            }
        }, () -> {
            state.close();
            if (parent instanceof IEIrisNewWorldRenderingPipeline access)
                access.ip_setIsRenderingWorld(renderingWorld);
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        P before = null;
        Throwable primaryFailure = null;
        try {
            before = selection.pipeline();
            P selected = selection.prepare(outerDimension);
            if (selected != outerPipeline || selected != selection.pipeline())
                throw new IllegalStateException("Iris did not retain the restored parent pipeline");
            if (before != outerPipeline) {
                restoredMismatches++;
                lastOuterDimension = bounded(String.valueOf(outerDimension));
                lastExpected = identity(outerPipeline); lastBefore = identity(before); lastAfter = identity(selected);
            }
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            failures++;
            lastOuterDimension = bounded(String.valueOf(outerDimension));
            lastExpected = identity(outerPipeline); lastBefore = identity(before); lastAfter = "restore-failed";
            throw failure;
        } finally {
            try { restoreCapturedState.run(); }
            catch (RuntimeException | Error failure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(failure);
                else {
                    failures++;
                    lastOuterDimension = bounded(String.valueOf(outerDimension));
                    lastExpected = identity(outerPipeline); lastBefore = identity(before); lastAfter = "captured-state-restore-failed";
                    throw failure;
                }
            }
            finally { activeDepth--; }
        }
    }

    private static String bounded(String value) { return value.length() <= 160 ? value : value.substring(0, 160); }
    private static String identity(Object value) {
        return value == null ? "null" : bounded(value.getClass().getSimpleName()) + "@" + Integer.toHexString(System.identityHashCode(value));
    }
    public static String diagnostics() {
        return "Iris pipeline scope: activeDepth=" + activeDepth + " maxDepth=" + maxDepth
            + " restoredMismatches=" + restoredMismatches + " failures=" + failures
            + " lastOuterDimension=" + lastOuterDimension + " expected=" + lastExpected
            + " before=" + lastBefore + " after=" + lastAfter;
    }
}
