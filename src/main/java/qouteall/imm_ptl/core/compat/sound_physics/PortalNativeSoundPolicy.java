package qouteall.imm_ptl.core.compat.sound_physics;

import com.mojang.logging.LogUtils;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/** Owner-thread admission policy. SPA's independently controlled voices keep native ownership. */
public final class PortalNativeSoundPolicy {
    private static final String PREFIX = "com.sonicether.soundphysics.";
    private static final int CAPACITY = 2048;
    private static final Map<SoundInstance, Boolean> decisions = new IdentityHashMap<>();
    private static final Api API = load();
    private static boolean warned;

    private PortalNativeSoundPolicy() {}

    /** Original and Sable-unwrapped identities are supplied without changing their coordinates. */
    public static boolean keepsNativeOwnership(SoundInstance original, SoundInstance unwrapped) {
        if (API == null) return false;
        Boolean cached = decisions.get(original);
        if (cached != null) return cached;
        // Admission is bounded, like the portal source registry. A capacity fallback
        // leaves a voice native rather than interfering with unknown native ownership.
        if (decisions.size() >= CAPACITY) return true;
        boolean nativeOwner;
        try {
            nativeOwner = API.managed(original) || original != unwrapped && API.managed(unwrapped);
        } catch (ReflectiveOperationException | LinkageError failure) {
            if (!warned) {
                warned = true;
                LogUtils.getLogger().warn("[IP sound] SPA ownership classification failed; keeping affected voice native", failure);
            }
            nativeOwner = true;
        }
        decisions.put(original, nativeOwner);
        return nativeOwner;
    }

    public static void retain(Collection<SoundInstance> sounds) { decisions.keySet().removeIf(s -> !sounds.contains(s)); }
    public static void clear() { decisions.clear(); }

    private record Api(Class<?> carrier, Class<?> managed, Class<?> physicalPropeller,
                       Method resolve, Method view, Method context, Method[] policies) {
        boolean managed(SoundInstance sound) throws ReflectiveOperationException {
            if (carrier.isInstance(sound) || managed.isInstance(sound) || physicalPropeller.isInstance(sound)) return true;
            Object resolved = resolve.invoke(null, sound);
            if (view.invoke(null, sound, resolved) != null) return true;
            Object info = context.invoke(null, resolved, sound, sound.getSource(), false, true);
            for (Method policy : policies) if ((boolean) policy.invoke(null, info)) return true;
            return false;
        }
    }

    private static Api load() {
        ClassLoader loader = PortalNativeSoundPolicy.class.getClassLoader();
        try {
            Class<?> carrier = Class.forName(PREFIX + "propagation.presentation.ManagedSourcePlayback$Carrier", false, loader);
            Class<?> managed = Class.forName(PREFIX + "longrange.ManagedLongRangeSound", false, loader);
            Class<?> propeller = Class.forName(PREFIX + "propeller.PhysicalPropellerEmitterSound", false, loader);
            Class<?> resolver = Class.forName(PREFIX + "SoundInstanceResolver", false, loader);
            Class<?> resolved = Class.forName(PREFIX + "SoundInstanceResolver$ResolvedSound", false, loader);
            Class<?> emitter = Class.forName(PREFIX + "longrange.ManagedLongRangeEmitterResolver", false, loader);
            Class<?> policy = Class.forName(PREFIX + "SoundPhysicsSoundPolicy", false, loader);
            Class<?> context = Class.forName(PREFIX + "SoundPhysicsSoundPolicy$SoundContext", false, loader);
            String[] names = {"isKnownPropeller", "isCreatePropulsionThrusterLoop", "isCreateNativeTrainBodyLoop",
                "isManagedStationaryWhistleMarker", "isManagedMovingTrainWhistleMarker"};
            Method[] methods = new Method[names.length];
            for (int i = 0; i < names.length; i++) methods[i] = policy.getMethod(names[i], context);
            return new Api(carrier, managed, propeller, resolver.getMethod("resolve", SoundInstance.class),
                emitter.getMethod("resolve", SoundInstance.class, resolved),
                context.getMethod("fromResolved", resolved, SoundInstance.class, SoundSource.class, boolean.class, boolean.class), methods);
        } catch (ClassNotFoundException absent) {
            return null; // SPA is optional; do not initialize Minecraft or acquire a hard dependency.
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            LogUtils.getLogger().warn("[IP sound] SPA native ownership API unavailable", incompatible);
            return null;
        }
    }
}
