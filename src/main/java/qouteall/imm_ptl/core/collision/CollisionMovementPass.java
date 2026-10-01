package qouteall.imm_ptl.core.collision;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Keeps collision bookkeeping alive when an excessive displacement is rejected. */
public final class CollisionMovementPass {
    private CollisionMovementPass() {}

    public static boolean isExcessive(Vec3 movement) {
        return movement.lengthSqr() > 60 * 60;
    }

    public static Vec3 run(Entity entity, Vec3 movement, Operation<Vec3> collision) {
        boolean excessive = isExcessive(movement);
        // Sable creates the CollisionInfo subsequently consumed by Entity.move
        // inside this operation. Returning early would skip that initialization.
        Vec3 result = collision.call(entity, excessive ? Vec3.ZERO : movement);
        return excessive ? Vec3.ZERO : result;
    }
}
