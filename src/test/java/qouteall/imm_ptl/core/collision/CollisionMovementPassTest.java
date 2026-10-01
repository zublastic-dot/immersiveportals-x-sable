package qouteall.imm_ptl.core.collision;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CollisionMovementPassTest {
    @Test void rejectedSquidMovementStillInitializesCollisionBookkeeping() {
        Vec3 reportedMotion = new Vec3(-46.624117401917836, 0, 45.732176067581605);
        AtomicReference<Object> collisionInfo = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        Vec3 result = CollisionMovementPass.run(null, reportedMotion, args -> {
            calls.incrementAndGet();
            assertEquals(Vec3.ZERO, args[1], "Never query the huge displacement");
            collisionInfo.set(new Object());
            // Even inherited sub-level motion must not undo IP's rejection.
            return new Vec3(1, 2, 3);
        });
        assertNotNull(collisionInfo.get(), "The downstream movement consumer needs a collision record");
        assertEquals(1, calls.get());
        assertEquals(Vec3.ZERO, result);
    }

    @Test void ordinaryCollisionRetainsTheOperationResult() {
        Vec3 motion = new Vec3(1, -0.1, 2);
        Vec3 collided = new Vec3(1, 0, 2);
        AtomicInteger calls = new AtomicInteger();
        assertSame(collided, CollisionMovementPass.run(null, motion, args -> {
            calls.incrementAndGet();
            assertSame(motion, args[1]);
            return collided;
        }));
        assertEquals(1, calls.get());
    }

    @Test void limitBoundaryAndZeroStillReachTheCollisionOperation() {
        for (Vec3 motion : new Vec3[]{Vec3.ZERO, new Vec3(60, 0, 0)}) {
            AtomicInteger calls = new AtomicInteger();
            assertSame(motion, CollisionMovementPass.run(null, motion, args -> {
                calls.incrementAndGet();
                assertSame(motion, args[1]);
                return motion;
            }));
            assertEquals(1, calls.get());
        }
    }
}
