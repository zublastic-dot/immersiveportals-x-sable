package qouteall.imm_ptl.core.compat.dh_compatibility;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

public final class DhPortalMotion {
    private DhPortalMotion() {}
    public static void crossed(Portal portal) {
        boolean rebased = ((DhCameraSpeedHistory)ClientApi.INSTANCE).ip_rebaseCameraSpeed(previous -> {
            Vec3 point = portal.transformPoint(new Vec3(previous.x, previous.y, previous.z));
            return new DhVec3d(point.x, point.y, point.z);
        });
        if (rebased) com.mojang.logging.LogUtils.getLogger().info(
            "IP/Sable DH: camera-speed sample rebased for portal crossing");
    }
}
