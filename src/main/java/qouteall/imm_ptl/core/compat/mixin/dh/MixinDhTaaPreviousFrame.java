package qouteall.imm_ptl.core.compat.mixin.dh;

import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import org.spongepowered.asm.mixin.*;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhTaaHistory;
import qouteall.imm_ptl.core.compat.dh_compatibility.DhTaaPreviousFrame;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaShader_neoforge", remap = false)
public class MixinDhTaaPreviousFrame implements DhTaaPreviousFrame {
    @Shadow @Final private DhMat4f previousDhProjMvmMatrix;
    @Shadow @Final private DhVec3d previousCameraPos;
    @Override public void ip_acceptPortalHistory(DhTaaHistory.Snapshot snapshot) {
        previousDhProjMvmMatrix.set(snapshot.combined());
        var camera = snapshot.camera(); previousCameraPos.set(camera.x, camera.y, camera.z);
    }
}
