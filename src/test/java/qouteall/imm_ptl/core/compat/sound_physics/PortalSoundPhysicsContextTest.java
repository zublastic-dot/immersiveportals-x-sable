package qouteall.imm_ptl.core.compat.sound_physics;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.PortalBlockTestBootstrap;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PortalSoundPhysicsContextTest {
    @Test void installedSpaRefreshKeepsRecordIdentityAndCountsUpdatesInsteadOfStarts() throws Exception {
        var api = installedApi();
        if (api == null) return;
        var selected = new AtomicReference<>(selected(true));
        SoundInstance sound = sound("minecraft:music_disc.13", SoundSource.RECORDS, selected,
            new AtomicInteger(), new AtomicBoolean());
        Object context = api.capture(sound, selected.get());
        Class<?> type = context.getClass();
        assertEquals(false, type.getMethod("startEvent").invoke(context));
        assertEquals(true, type.getMethod("streaming").invoke(context));
        assertEquals(sound.getClass().getName(), type.getMethod("soundInstanceClassName").invoke(context));
        assertEquals(ResourceLocation.parse("minecraft:music_disc.13"), type.getMethod("soundId").invoke(context));
        // Assert the installed refresh overload exists without initializing its native audio subsystem.
        Class.forName("com.sonicether.soundphysics.SoundPhysics", false, type.getClassLoader()).getMethod(
            "processSound", int.class, double.class, double.class, double.class, SoundSource.class,
            ResourceLocation.class, boolean.class, type);

        Class<?> diagnostics = Class.forName("com.sonicether.soundphysics.RecordDiagnostics");
        var observe = diagnostics.getMethod("observeSource", int.class, Vec3.class, type);
        diagnostics.getMethod("reset").invoke(null);
        try {
            observe.invoke(null, 21, Vec3.ZERO, type.getMethod("withStartEvent", boolean.class).invoke(context, true));
            observe.invoke(null, 21, new Vec3(1, 2, 3), context);
            observe.invoke(null, 21, new Vec3(2, 3, 4), context);
            String status = (String) diagnostics.getMethod("statusText").invoke(null);
            assertTrue(status.contains("startEvents=1,"), status);
            assertTrue(status.contains("movingUpdates=2,"), status);
            assertTrue(status.contains("trackedSources=1,"), status);
        } finally { diagnostics.getMethod("reset").invoke(null); }

        SoundInstance ordinary = sound("minecraft:block.piston.extend", SoundSource.BLOCKS,
            new AtomicReference<>(selected(false)), new AtomicInteger(), new AtomicBoolean());
        Object update = api.capture(ordinary, ordinary.getSound());
        Class<?> policy = Class.forName("com.sonicether.soundphysics.SoundPhysicsSoundPolicy");
        Object reason = policy.getMethod("acousticContinuityReason", type).invoke(null, update);
        assertEquals("MOVING_UPDATE", reason.toString(), "Refreshes must not acquire one-shot acoustic policy");
        System.out.println("EXECUTED installed SPA refresh contract: stream/class identity, update counters, continuity policy");
    }

    @Test void installedSpaContextPublicationIsCachedAndRetiredWithNativeIdentity() throws Exception {
        var api = installedApi();
        if (api == null) return;
        var cache = new PortalSoundPhysics.RefreshContexts();
        var selected = new AtomicReference<>(selected(true));
        var resolves = new AtomicInteger();
        var forbidReads = new AtomicBoolean();
        SoundInstance sound = sound("minecraft:music_disc.13", SoundSource.RECORDS, selected, resolves, forbidReads);
        Object first = cache.capture(sound, api);
        int capturedReads = resolves.get();
        assertSame(first, cache.capture(sound, api));
        assertEquals(capturedReads, resolves.get(), "Retained metadata must not be resolved each tick");

        forbidReads.set(true);
        assertSame(first, cache.get(sound), "Audio consumer reads immutable context without touching live sound");
        forbidReads.set(false);
        selected.set(selected(false));
        Object changed = cache.capture(sound, api);
        assertNotSame(first, changed);
        assertEquals(false, changed.getClass().getMethod("streaming").invoke(changed));
        assertTrue(resolves.get() > capturedReads);
        cache.retain(List.of(sound));
        assertSame(changed, cache.get(sound));
        cache.retain(List.of());
        assertNull(cache.get(sound));
        cache.capture(sound, api);
        cache.clear();
        assertNull(cache.get(sound));
        System.out.println("EXECUTED installed SPA context publication contract: bounded-lifetime cache and selected sound refresh");
    }

    private static PortalSoundPhysics.ContextApi installedApi() throws Exception {
        String configured = System.getProperty("ip.portal.test.spaJar");
        if (configured == null || configured.isBlank()) {
            System.out.println("NOT_EXECUTED installed SPA refresh context contract: set ip.portal.test.spaJar");
            return null;
        }
        PortalBlockTestBootstrap.initialize();
        var api = PortalSoundPhysics.ContextApi.load(PortalSoundPhysics.class.getClassLoader());
        assertEquals(Path.of(configured).toRealPath(), Path.of(api.context().getDeclaringClass()
            .getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        return api;
    }

    private static Sound selected(boolean stream) {
        return new Sound(ResourceLocation.parse("minecraft:test"), ConstantFloat.of(1), ConstantFloat.of(1),
            1, Sound.Type.FILE, stream, false, 16);
    }

    private static SoundInstance sound(String location, SoundSource category, AtomicReference<Sound> selected,
                                       AtomicInteger resolves, AtomicBoolean forbidReads) {
        return (SoundInstance) Proxy.newProxyInstance(SoundInstance.class.getClassLoader(), new Class<?>[]{SoundInstance.class},
            (self, method, args) -> {
                assertFalse(forbidReads.get(), "Audio consumer must not resolve live sound metadata");
                return switch (method.getName()) {
                    case "getSound" -> selected.get();
                    case "getLocation" -> { resolves.incrementAndGet(); yield ResourceLocation.parse(location); }
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
