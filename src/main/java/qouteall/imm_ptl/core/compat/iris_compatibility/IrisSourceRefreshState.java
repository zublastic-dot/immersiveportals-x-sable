package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.compat.mixin.iris.IEIrisGbufferPrograms;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/** Native globals which are shared across dimension pipelines, unlike the owned shadow image. */
final class IrisSourceRefreshState implements AutoCloseable {
    private final CapturedRenderingState captured = CapturedRenderingState.INSTANCE;
    private final Matrix4f modelView = copy(captured.getGbufferModelView()), projection = copy(captured.getGbufferProjection());
    private final Vector3d fog = new Vector3d(captured.getFogColor());
    private final float density = captured.getFogDensity(), tick = captured.getTickDelta(), realTick = captured.getRealTickDelta(),
        alpha = captured.getCurrentAlphaTest(), darkness = captured.getDarknessLightFactor(), cloud = captured.getCloudTime();
    private final int block = captured.getCurrentRenderedBlockEntity(), entity = captured.getCurrentRenderedEntity(), item = captured.getCurrentRenderedItem();
    private final boolean level = ImmediateState.isRenderingLevel, tessellation = ImmediateState.usingTessellation,
        extended = ImmediateState.renderWithExtendedVertexFormat, bypass = ImmediateState.bypass, merge = ImmediateState.mergeRendering;
    private final Boolean skip = ImmediateState.skipExtension.get();
    private final boolean entities = IEIrisGbufferPrograms.ip_entities(), blockEntities = IEIrisGbufferPrograms.ip_blockEntities(), outline = IEIrisGbufferPrograms.ip_outline();
    private final int resolution = ShadowRenderer.RESOLUTION, distance = ShadowRenderer.renderDistance;
    private final boolean active = ShadowRenderer.ACTIVE;
    private final Matrix4f shadowModelView = ShadowRenderer.MODELVIEW, shadowProjection = ShadowRenderer.PROJECTION;
    private final net.minecraft.client.renderer.culling.Frustum frustum = ShadowRenderer.FRUSTUM;
    private final java.util.List<net.minecraft.world.level.block.entity.BlockEntity> visible = ShadowRenderer.visibleBlockEntities;
    private final Matrix4f basicProjection = RenderStates.basicProjectionMatrix;
    private final boolean renderingEntities = RenderStates.isRenderingEntities, weather = RenderStates.isRenderingPortalWeather,
        disableCull = RenderStates.shouldForceDisableCull;
    private final String debug = RenderStates.debugText;
    private static Matrix4f copy(org.joml.Matrix4fc matrix) { return matrix == null ? null : new Matrix4f(matrix); }

    @Override public void close() {
        captured.setGbufferModelView(modelView); captured.setGbufferProjection(projection);
        captured.setFogColor((float)fog.x, (float)fog.y, (float)fog.z); captured.setFogDensity(density);
        captured.setTickDelta(tick); captured.setRealTickDelta(realTick); captured.setCurrentAlphaTest(alpha);
        captured.setDarknessLightFactor(darkness); captured.setCloudTime(cloud);
        captured.setCurrentBlockEntity(block); captured.setCurrentEntity(entity); captured.setCurrentRenderedItem(item);
        ImmediateState.isRenderingLevel = level; ImmediateState.usingTessellation = tessellation;
        ImmediateState.renderWithExtendedVertexFormat = extended; ImmediateState.bypass = bypass; ImmediateState.mergeRendering = merge;
        if (skip == null) ImmediateState.skipExtension.remove(); else ImmediateState.skipExtension.set(skip);
        IEIrisGbufferPrograms.ip_entities(entities); IEIrisGbufferPrograms.ip_blockEntities(blockEntities); IEIrisGbufferPrograms.ip_outline(outline);
        ShadowRenderer.RESOLUTION = resolution; ShadowRenderer.renderDistance = distance; ShadowRenderer.ACTIVE = active;
        ShadowRenderer.MODELVIEW = shadowModelView; ShadowRenderer.PROJECTION = shadowProjection;
        ShadowRenderer.FRUSTUM = frustum; ShadowRenderer.visibleBlockEntities = visible;
        RenderStates.basicProjectionMatrix = basicProjection; RenderStates.isRenderingEntities = renderingEntities;
        RenderStates.isRenderingPortalWeather = weather; RenderStates.shouldForceDisableCull = disableCull; RenderStates.debugText = debug;
    }
}
