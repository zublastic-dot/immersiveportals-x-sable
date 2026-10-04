package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.core.render.RenderParams;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL20;

import java.util.Arrays;

/** Read-only GPU evidence; invoked solely inside the finite DH trace sampling budget. */
public final class DhIrisProjectionDiagnostics {
    private DhIrisProjectionDiagnostics() {}

    public static String capture(int program, RenderParams params) {
        if (params == null || program == 0 || GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM) != program)
            return "matrices=unavailable:not-current-program-or-params";
        if (net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered())
            return "matrices=not-sampled:shadow-projection";
        var state = CapturedRenderingState.INSTANCE;
        Matrix4fc capturedProjection = state.getGbufferProjection(), capturedView = state.getGbufferModelView();
        if (capturedProjection == null || capturedView == null) return "matrices=unavailable:captured-state";
        // This is the exact opaque/translucent LodRendererEvents$13 construction.
        // The common dhProjection is independently supplied by DHCompat and cached
        // PER_FRAME; supported packs can use it instead of iris_ProjectionMatrix.
        Matrix4f eventProjection = new Matrix4f().setPerspective(capturedProjection.perspectiveFov(),
            capturedProjection.m11() / capturedProjection.m00(), params.nearClipPlane, params.farClipPlane);
        return captureMatrices(program, eventProjection, DHCompat.getProjection(), capturedView,
            convert(params.dhProjectionMatrix), convert(params.dhModelViewMatrix))
            + " paramsNear=" + params.nearClipPlane + " paramsFar=" + params.farClipPlane;
    }

    static String captureMatrices(int program, Matrix4fc eventProjection, Matrix4fc commonProjection,
                                  Matrix4fc currentView, Matrix4fc apiProjection, Matrix4fc apiView) {
        if (program == 0 || GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM) != program)
            return "matrices=unavailable:not-current-program";
        StringBuilder result = new StringBuilder("matrixLayout=column-major");
        append(result, "eventProjection", values(eventProjection));
        append(result, "commonProjectionNow", values(commonProjection));
        append(result, "capturedViewNow", values(currentView));
        append(result, "apiProjection", values(apiProjection));
        append(result, "apiView", values(apiView));
        uniform(result, program, "dhProjection", commonProjection);
        uniform(result, program, "dhProjectionInverse", new Matrix4f(commonProjection).invert());
        uniform(result, program, "gbufferModelView", currentView);
        uniform(result, program, "gbufferModelViewInverse", new Matrix4f(currentView).invert());
        uniform(result, program, "iris_ProjectionMatrix", eventProjection);
        uniform(result, program, "iris_ModelViewMatrix", currentView);
        return result.toString();
    }

    private static Matrix4f convert(DhApiMat4f matrix) {
        float[] raw = new float[16];
        matrix.putValuesInArray(raw);
        return new Matrix4f().setTransposed(raw);
    }

    private static float[] values(Matrix4fc matrix) { return matrix.get(new float[16]); }
    private static void append(StringBuilder result, String name, float[] values) {
        result.append(' ').append(name).append('=').append(Arrays.toString(values));
    }
    private static void uniform(StringBuilder result, int program, String name, Matrix4fc expected) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0) { result.append(' ').append(name).append("=inactive"); return; }
        float[] actual = new float[16];
        GL20.glGetUniformfv(program, location, actual);
        append(result, name, actual);
        result.append(' ').append(name).append("MaxDelta=").append(maxDelta(actual, values(expected)));
    }
    static float maxDelta(float[] actual, float[] expected) {
        float delta = 0;
        for (int i = 0; i < 16; i++) delta = Math.max(delta, Math.abs(actual[i] - expected[i]));
        return delta;
    }
}
