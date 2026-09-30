package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.core.util.math.DhVec3d;
import java.util.function.UnaryOperator;

/** Changes coordinate frame without adding a speed sample or discarding real movement history. */
public interface DhCameraSpeedHistory {
    boolean ip_rebaseCameraSpeed(UnaryOperator<DhVec3d> transform);
}
