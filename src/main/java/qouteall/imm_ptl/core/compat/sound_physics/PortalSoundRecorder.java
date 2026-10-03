package qouteall.imm_ptl.core.compat.sound_physics;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded, opt-in diagnostic capture using only SPA's public recorder/configuration API. */
public final class PortalSoundRecorder {
    static final long LIMIT_NANOS = 60_000_000_000L;
    static final String HIGH_KEY = "key.sound_physics_remastered.flight_recorder.high_detail";
    static final String EXTREME_KEY = "key.sound_physics_remastered.flight_recorder.extreme_detail";
    private static Lease active;
    private static boolean registered;

    private PortalSoundRecorder() {}
    public record Result(boolean success, String message) {}
    record Status(boolean active, String sessionId) {}

    public static Result start() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || mc.player == null || mc.level == null) {
            return new Result(false, "A loaded client world is required for portal sound recording.");
        }
        if (active != null) return new Result(false, "Portal sound recording already owns a capture; stop it first.");
        try {
            Api api = Api.load(PortalSoundRecorder.class.getClassLoader());
            KeyMapping high = findKey(mc, HIGH_KEY), extreme = findKey(mc, EXTREME_KEY);
            if (high == null || extreme == null) return new Result(false, "SPA recorder key mappings are unavailable.");
            Object config = api.config().get(null);
            if (config == null) return new Result(false, "SPA configuration is unavailable.");
            Object entry = api.highInputMode().get(config);
            register();
            active = Lease.acquire(new NativeBackend(mc, api, entry, high, extreme), System.nanoTime());
            return new Result(true, "Portal sound HIGH recording started for at most 60 seconds; it also stops on world change. Previous input settings will be restored.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return new Result(false, "Portal sound recording unavailable: " + failure.getMessage());
        }
    }

    public static Result stop() { return stop("explicit stop"); }
    private static Result stop(String reason) {
        Lease lease = active;
        active = null;
        return lease == null ? new Result(false, "No portal-owned sound recording is active.") : lease.close(reason);
    }
    private static void register() {
        if (registered) return;
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, event -> cleanup("client world cleanup"));
        NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent.class, event -> cleanup("game shutdown"));
        registered = true;
    }
    private static void tick() {
        Lease lease = active;
        if (lease == null) return;
        String reason;
        try { reason = lease.expiryReason(System.nanoTime()); }
        catch (ReflectiveOperationException | LinkageError failure) { reason = "recorder API became unavailable"; }
        if (reason != null) cleanup(reason);
    }
    private static void cleanup(String reason) {
        if (active == null) return;
        Result result = stop(reason);
        LogUtils.getLogger().info("[IP sound recording] {}", result.message());
    }
    private static KeyMapping findKey(Minecraft mc, String name) {
        for (KeyMapping key : mc.options.keyMappings) if (name.equals(key.getName())) return key;
        return null;
    }

    interface Backend {
        Status status() throws ReflectiveOperationException;
        Result startBounded() throws ReflectiveOperationException;
        Result stop() throws ReflectiveOperationException;
        String highMode() throws ReflectiveOperationException;
        void highMode(String mode) throws ReflectiveOperationException;
        boolean highDown();
        void highDown(boolean down);
        boolean extremeDown();
        Object level();
        Object connection();
    }

    /** Client-thread lease: restoration compares current values and never stops a replacement session. */
    static final class Lease {
        private final Backend backend;
        private final String previousMode;
        private final Object level, connection;
        private final long deadline;
        private String sessionId;
        private boolean closed;

        private Lease(Backend backend, String previousMode, long now) {
            this.backend = backend;
            this.previousMode = previousMode;
            level = backend.level(); connection = backend.connection();
            deadline = now + LIMIT_NANOS;
        }
        static Lease acquire(Backend backend, long now) throws ReflectiveOperationException {
            if (backend.status().active()) throw new IllegalStateException("SPA already has an active recording; leaving it untouched.");
            if (backend.highDown() || backend.extremeDown()) throw new IllegalStateException("A SPA recorder key is already held; leaving it untouched.");
            if (backend.level() == null || backend.connection() == null) throw new IllegalStateException("Client world is unavailable.");
            Lease lease = new Lease(backend, backend.highMode(), now);
            boolean startSucceeded = false;
            try {
                backend.highMode("HOLD"); // runtime only: deliberately never call ConfigEntry.save/saveSync
                backend.highDown(true);
                Result started = backend.startBounded();
                if (!started.success()) throw new IllegalStateException(started.message());
                startSucceeded = true;
                Status status = backend.status();
                lease.sessionId = status.sessionId();
                if (!status.active() || lease.sessionId == null) throw new IllegalStateException("SPA did not report an active capture.");
                return lease;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                if (startSucceeded && lease.sessionId == null) {
                    // Acquisition has not yielded the client thread or published this lease.
                    // Even if status() failed, the session just started here is still ours.
                    try { backend.stop(); }
                    catch (ReflectiveOperationException | LinkageError | RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                lease.close("capture start failed");
                throw failure;
            }
        }
        String expiryReason(long now) throws ReflectiveOperationException {
            if (closed) return "already stopped";
            if (now - deadline >= 0) return "60-second limit";
            if (backend.level() != level || backend.connection() != connection) return "client world changed";
            Status status = backend.status();
            if (!status.active() || !Objects.equals(sessionId, status.sessionId())) return "SPA recording ended or was replaced";
            if (!backend.highDown() || !"HOLD".equals(backend.highMode()) || backend.extremeDown()) return "recorder input changed";
            return null;
        }
        Result close(String reason) {
            if (closed) return new Result(true, "Portal sound recording already stopped.");
            closed = true;
            List<String> failures = new ArrayList<>();
            attempt(failures, () -> {
                if (sessionId == null) return;
                Status status = backend.status();
                if (status.active() && sessionId.equals(status.sessionId())) {
                    Result stopped = backend.stop();
                    if (!stopped.success()) throw new IllegalStateException(stopped.message());
                }
            });
            attempt(failures, () -> { if (backend.highDown()) backend.highDown(false); });
            attempt(failures, () -> { if ("HOLD".equals(backend.highMode())) backend.highMode(previousMode); });
            return new Result(failures.isEmpty(), "Portal sound recording stopped (" + reason + ")."
                + (failures.isEmpty() ? " Previous owned input settings restored." : " Cleanup errors: " + String.join("; ", failures)));
        }
    }
    private interface Action { void run() throws ReflectiveOperationException; }
    private static void attempt(List<String> failures, Action action) {
        try { action.run(); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { failures.add(failure.toString()); }
    }

    record Api(Method status, Method statusActive, Method statusSessionId,
               Method startBounded, Method stop, Method success, Method message,
               Field config, Field highInputMode, Method get, Method set) {
        static Api load(ClassLoader loader) throws ReflectiveOperationException {
            String prefix = "com.sonicether.soundphysics.";
            Class<?> service = Class.forName(prefix + "flightrecorder.SpraFlightRecorderService", false, loader);
            Class<?> status = Class.forName(prefix + "flightrecorder.SpraFlightRecorderSession$Status", false, loader);
            Class<?> result = Class.forName(prefix + "flightrecorder.SpraFlightRecorderService$Result", false, loader);
            Class<?> mod = Class.forName(prefix + "SoundPhysicsMod", false, loader);
            Class<?> config = Class.forName(prefix + "config.SoundPhysicsConfig", false, loader);
            Class<?> entry = Class.forName("de.maxhenkel.sound_physics_remastered.configbuilder.entry.ConfigEntry", false, loader);
            return new Api(service.getMethod("status"), status.getMethod("active"), status.getMethod("sessionId"),
                service.getMethod("startBounded"), service.getMethod("stop"), result.getMethod("success"), result.getMethod("message"),
                mod.getField("CONFIG"), config.getField("flightRecorderHighDetailInputMode"), entry.getMethod("get"), entry.getMethod("set", Object.class));
        }
    }
    private record NativeBackend(Minecraft mc, Api api, Object entry, KeyMapping high, KeyMapping extreme) implements Backend {
        public Status status() throws ReflectiveOperationException {
            Object status = api.status().invoke(null);
            if (status == null) return new Status(false, null);
            return new Status((boolean) api.statusActive().invoke(status), (String) api.statusSessionId().invoke(status));
        }
        public Result startBounded() throws ReflectiveOperationException { return result(api.startBounded().invoke(null)); }
        public Result stop() throws ReflectiveOperationException { return result(api.stop().invoke(null)); }
        private Result result(Object value) throws ReflectiveOperationException {
            return new Result((boolean) api.success().invoke(value), (String) api.message().invoke(value));
        }
        public String highMode() throws ReflectiveOperationException { return (String) api.get().invoke(entry); }
        public void highMode(String mode) throws ReflectiveOperationException { api.set().invoke(entry, mode); }
        public boolean highDown() { return high.isDown(); }
        public void highDown(boolean down) { high.setDown(down); }
        public boolean extremeDown() { return extreme.isDown(); }
        public Object level() { return mc.player == null ? null : mc.player.level(); }
        public Object connection() { return mc.getConnection(); }
    }
}
