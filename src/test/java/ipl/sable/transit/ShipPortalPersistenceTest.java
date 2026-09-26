package ipl.sable.transit;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShipPortalPersistenceTest {
    private static IplShipPortalAnchor.Anchor anchor(boolean nether) {
        return new IplShipPortalAnchor.Anchor(UUID.randomUUID(), nether ? Level.NETHER : Level.OVERWORLD,
            new Vector3d(20481029, 125, 20483080), DQuaternion.identity, new DQuaternion(0, 1, 0, 0));
    }

    @Test void oldSingleEndFormatAndTwoIndependentEndsRoundTrip() {
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        var anchors = new HashMap<UUID, IplShipPortalAnchor.Anchor>();
        anchors.put(a, anchor(false));
        assertEquals(anchors, IplShipPortalAnchor.decodeAnchors(IplShipPortalAnchor.encodeAnchors(anchors)));
        anchors.put(b, anchor(true));
        assertEquals(anchors, IplShipPortalAnchor.decodeAnchors(IplShipPortalAnchor.encodeAnchors(anchors)));
    }

    @Test void shutdownClearingRuntimeStateCannotEraseTheSavedAttachments() {
        var anchors = new HashMap<UUID, IplShipPortalAnchor.Anchor>();
        anchors.put(UUID.randomUUID(), anchor(false)); anchors.put(UUID.randomUUID(), anchor(true));
        var expected = Map.copyOf(anchors);
        var data = new IplShipPortalAnchor.AnchorSavedData();
        data.capture(anchors);
        anchors.clear();
        IplShipPortalAnchor.clearAll();
        var saved = data.save(new CompoundTag(), null);
        assertEquals(expected, IplShipPortalAnchor.decodeAnchors(saved.getList("anchors", 10)));
        saved.getList("anchors", 10).clear();
        assertEquals(2, data.save(new CompoundTag(), null).getList("anchors", 10).size());
    }

    @Test void detachingOneEndPersistsOnlyTheSurvivorIncludingItsUpdatedLock() {
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        var first = anchor(false); var second = anchor(true);
        var lastLock = new DQuaternion(0, 0, 1, 0);
        var anchors = new HashMap<>(Map.of(a, first, b, second));
        var data = new IplShipPortalAnchor.AnchorSavedData(); data.capture(anchors);
        anchors.remove(a); anchors.put(b, second.withDestLock(lastLock)); data.capture(anchors);
        var restored = IplShipPortalAnchor.decodeAnchors(data.save(new CompoundTag(), null).getList("anchors", 10));
        assertEquals(Map.of(b, second.withDestLock(lastLock)), restored);
    }
}
