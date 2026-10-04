package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.lighting.PortalBloomAperture;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/** Binds the aperture for this fullscreen pass, independently of light transport and dimension IDs. */
public final class PortalBloomBinding {
    private PortalBloomBinding() {}

    public static void bind() {
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        if (program == 0) return;
        int count = glGetUniformLocation(program, "ipBloomEdgeCount");
        if (count < 0) return;
        // Always reset: Iris reuses programs between root and nested views.
        glUniform1i(count, 0);
        if (!PortalRendering.isRendering()) return;
        var path = PortalRendering.getPortalPath();
        if (path.size() > 8 || path.stream().anyMatch(p -> !(p.getPortalShape() instanceof RectangularPortalShape))) return;
        var captured = CapturedRenderingState.INSTANCE;
        if (captured.getGbufferProjection() == null || captured.getGbufferModelView() == null) return;
        var camera = PortalRendering.getRenderingCameraPos();
        var openings = new ArrayList<List<Vector4f>>();
        for (int i = 0; i < path.size(); i++) {
            var portal = path.get(i);
            var vertices = portal.getFourVerticesLocal(0);
            var opening = new ArrayList<Vector4f>();
            for (int index : new int[]{0, 1, 3, 2}) {
                var point = vertices[index].add(portal.getOriginPos());
                for (int j = i; j < path.size(); j++) point = path.get(j).transformPoint(point);
                point = point.subtract(camera);
                opening.add(new Vector4f((float) point.x, (float) point.y, (float) point.z, 1));
            }
            openings.add(opening);
        }
        var window = Minecraft.getInstance().getWindow();
        var mask = PortalBloomAperture.project(openings,
            new Matrix4f(captured.getGbufferProjection()).mul(captured.getGbufferModelView()),
            window.getWidth(), window.getHeight());
        if (mask == null) return;
        glUniform3fv(glGetUniformLocation(program, "ipBloomEdges[0]"), mask.edges());
        glUniform2f(glGetUniformLocation(program, "ipBloomInterior"), mask.interior().x(), mask.interior().y());
        glUniform1i(count, mask.count());
    }
}
