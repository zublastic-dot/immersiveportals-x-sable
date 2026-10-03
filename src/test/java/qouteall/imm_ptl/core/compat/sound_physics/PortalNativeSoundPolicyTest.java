package qouteall.imm_ptl.core.compat.sound_physics;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.PortalBlockTestBootstrap;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PortalNativeSoundPolicyTest {
    @Test void installedSpaOwnershipLeavesNotesAndRecordsRoutableButProtectsManagedVoices() throws Exception {
        String configured = System.getProperty("ip.portal.test.spaJar");
        if (configured == null || configured.isBlank()) {
            System.out.println("NOT_EXECUTED installed SPA native ownership contract: set ip.portal.test.spaJar");
            return;
        }
        PortalBlockTestBootstrap.initialize();
        Class<?> carrier = Class.forName("com.sonicether.soundphysics.propagation.presentation.ManagedSourcePlayback$Carrier");
        assertEquals(Path.of(configured).toRealPath(),
            Path.of(carrier.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        assertTrue(carrier.isAssignableFrom(Class.forName("com.sonicether.soundphysics.propagation.presentation.ManagedPropagatedLoopSound", false, carrier.getClassLoader())));
        assertTrue(carrier.isAssignableFrom(Class.forName("com.sonicether.soundphysics.propagation.presentation.ManagedSonicBoomSound", false, carrier.getClassLoader())));
        PortalNativeSoundPolicy.clear();
        try {
            AtomicInteger reads = new AtomicInteger();
            SoundInstance note = sound("minecraft:block.note_block.harp", SoundSource.BLOCKS, reads);
            SoundInstance record = sound("minecraft:music_disc.cat", SoundSource.RECORDS, new AtomicInteger());
            assertFalse(PortalNativeSoundPolicy.keepsNativeOwnership(note, note));
            assertFalse(PortalNativeSoundPolicy.keepsNativeOwnership(record, record));
            int classifiedReads = reads.get();
            assertFalse(PortalNativeSoundPolicy.keepsNativeOwnership(note, note));
            assertEquals(classifiedReads, reads.get(), "A retained sound must not repeat SPA policy reflection each tick");
            for (String marker : List.of("propagation.presentation.ManagedSourcePlayback$Carrier",
                "longrange.ManagedLongRangeSound", "propeller.PhysicalPropellerEmitterSound")) {
                SoundInstance managed = sound("minecraft:block.note_block.harp", SoundSource.BLOCKS, new AtomicInteger(),
                    Class.forName("com.sonicether.soundphysics." + marker));
                assertTrue(PortalNativeSoundPolicy.keepsNativeOwnership(managed, managed), marker);
                SoundInstance delegate = sound("minecraft:block.note_block.harp", SoundSource.BLOCKS, new AtomicInteger());
                assertTrue(PortalNativeSoundPolicy.keepsNativeOwnership(delegate, managed), "Unwrapped Sable ownership: " + marker);
            }
            SoundInstance partner = sound("aeronautics:block.propeller_bearing.large_loop", SoundSource.BLOCKS, new AtomicInteger());
            assertTrue(PortalNativeSoundPolicy.keepsNativeOwnership(partner, partner), "SPA's exact native propeller policy");
            PortalNativeSoundPolicy.retain(List.of(record));
            assertFalse(PortalNativeSoundPolicy.keepsNativeOwnership(note, note));
            assertTrue(reads.get() > classifiedReads, "Retired instances must be pruned from the admission cache");
            System.out.println("EXECUTED installed SPA native ownership contract: ordinary voices, markers, native propeller, delegation, cache lifetime");
        } finally { PortalNativeSoundPolicy.clear(); }
    }

    private static SoundInstance sound(String location, SoundSource category, AtomicInteger reads, Class<?>... markers) {
        Class<?>[] interfaces = new Class<?>[markers.length + 1];
        interfaces[0] = SoundInstance.class;
        System.arraycopy(markers, 0, interfaces, 1, markers.length);
        return (SoundInstance) Proxy.newProxyInstance(SoundInstance.class.getClassLoader(), interfaces, (self, method, args) -> {
            reads.incrementAndGet();
            return switch (method.getName()) {
                case "getLocation" -> ResourceLocation.parse(location);
                case "getSource" -> category;
                case "getAttenuation" -> SoundInstance.Attenuation.LINEAR;
                case "getVolume", "getPitch" -> 1F;
                case "getX", "getY", "getZ" -> 0D;
                case "getDelay" -> 0;
                case "isRelative", "isLooping", "canStartSilent" -> false;
                case "canPlaySound" -> true;
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == args[0];
                case "toString" -> location;
                default -> null;
            };
        });
    }
}
