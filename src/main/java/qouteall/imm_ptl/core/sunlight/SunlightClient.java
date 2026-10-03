package qouteall.imm_ptl.core.sunlight;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalUniformCompatibility;
import qouteall.q_misc_util.api.McRemoteProcedureCallClient;

import java.util.Optional;

/** Connection-scoped server policy. Never changes Iris config or saved shader options. */
public final class SunlightClient {
    static final class Session {
        Object connection;
        long revision = -1;
        SunlightProfile profile = SunlightProfile.disabled();
        boolean accept(Object current, long incomingRevision, SunlightProfile incoming) {
            if (current == null || incomingRevision < 0) return false;
            boolean newConnection = connection != current;
            SunlightProfile previous = profile;
            if (newConnection) { clear(); connection = current; }
            if (incomingRevision <= revision) return false;
            revision = incomingRevision;
            boolean changed = !previous.equals(incoming) || (newConnection && incoming.enabled());
            profile = incoming;
            return changed;
        }
        void clear() { connection = null; revision = -1; profile = SunlightProfile.disabled(); }
        boolean clearIfConnectionChanged(Object current) {
            if (connection == null || connection == current) return false;
            clear(); return true;
        }
    }
    private static final Session SESSION = new Session();
    private static Optional<SunlightProfile> observedOwner = Optional.empty();
    private static SunlightProfile compiled = SunlightProfile.disabled();
    private static String compilationFailure = "";
    private static boolean reloadPending;
    private static boolean initialized;

    private SunlightClient() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        SunlightServer.setClientSink(SunlightClient::accept);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
        // This event also fires for same-connection respawn/world replacement.
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, event -> checkConnection());
    }

    private static void accept(long revision, SunlightProfile profile) {
        if (SESSION.accept(Minecraft.getInstance().getConnection(), revision, profile)) reloadPending = true;
    }

    public static void disconnect() {
        boolean restore = SESSION.profile.enabled() || compiled.enabled();
        SESSION.clear();
        compiled = SunlightProfile.disabled();
        compilationFailure = "";
        reloadPending |= restore;
    }

    private static void checkConnection() {
        boolean restore = SESSION.profile.enabled() || compiled.enabled();
        if (!SESSION.clearIfConnectionChanged(Minecraft.getInstance().getConnection())) return;
        compiled = SunlightProfile.disabled();
        compilationFailure = "";
        reloadPending |= restore;
    }

    private static void tick() {
        var mc = Minecraft.getInstance();
        checkConnection();
        if (!reloadPending || mc.getOverlay() != null) return;
        reloadPending = false;
        if (!verifiedIris() || !IrisInterface.invoker.isShaders()
            || !SunlightShaderAdapter.supports(IrisInterface.invoker.getShaderpackName())) return;
        try {
            IrisAccess.reload();
        } catch (Exception failure) {
            compilationFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            compiled = SunlightProfile.disabled();
            LogUtils.getLogger().error("[IP shared sunlight] temporary shader adaptation failed", failure);
        }
    }

    /** Called on every native Iris reload. Received server policy survives the reload. */
    public static void clearCompilation() {
        observedOwner = Optional.empty();
        compiled = SunlightProfile.disabled();
        compilationFailure = "";
    }

    public static void observeOwner(String pack, String unmodifiedPreprocessed) {
        SunlightShaderAdapter.observe(pack, unmodifiedPreprocessed).ifPresent(p -> observedOwner = Optional.of(p));
    }

    public static String adapt(String pack, String source) {
        if (!verifiedIris()) return source;
        try {
            String result = SunlightShaderAdapter.patch(pack, source, SESSION.profile);
            if (result != source) compiled = SESSION.profile;
            return result;
        } catch (RuntimeException failure) {
            compilationFailure = failure.getMessage();
            compiled = SunlightProfile.disabled();
            throw failure; // No partially adapted supported pack is reported as ready.
        }
    }

    public static Optional<SunlightProfile> effectiveShaderProfile() {
        return SESSION.profile.enabled() && SESSION.profile.equals(compiled) && compilationFailure.isEmpty() && verifiedIris()
            && IrisInterface.invoker.isShaders() && SunlightShaderAdapter.supports(IrisInterface.invoker.getShaderpackName())
            ? Optional.of(SESSION.profile) : Optional.empty();
    }

    /** Current native source world only; receiving Nether lighting remains aperture-scoped. */
    public static Optional<SunlightProfile.Sample> currentSourceSample() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.dimension() != Level.OVERWORLD) return Optional.empty();
        return effectiveShaderProfile().map(p -> p.sample(level.getDayTime()));
    }

    /** Iris's model-view convention is a quarter-turn offset from the solar vector. */
    public static float shadowAngle(SunlightProfile profile, Vec3 shadowDirection) {
        double rotation = Math.toRadians(profile.pathRotationDegrees());
        double angle = Math.atan2(-shadowDirection.x,
            shadowDirection.y * Math.cos(rotation) - shadowDirection.z * Math.sin(rotation)) / (Math.PI * 2) + .25;
        return (float) (angle - Math.floor(angle));
    }

    public static String publishObserved() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return "No server connection; nothing published.";
        if (!mc.player.hasPermissions(2)) return "Publishing shared sunlight requires server operator permission 2.";
        if (!verifiedIris() || !IrisInterface.invoker.isShaders() || !SunlightShaderAdapter.supports(IrisInterface.invoker.getShaderpackName()))
            return "Current shader pack is unsupported or disabled; nothing published.";
        if (observedOwner.isEmpty()) return "Native shader sun profile has not been observed; nothing published.";
        SunlightProfile profile = observedOwner.orElseThrow();
        McRemoteProcedureCallClient.tellServerToInvoke(
            "qouteall.imm_ptl.core.sunlight.SunlightServer.RemoteCallables.publish",
            profile.pathRotationDegrees(), profile.clock().name());
        return "Requested experimental shared sunlight opt-in for this world: rotation=" + profile.pathRotationDegrees()
            + ", clock=" + profile.clock() + ". If authorized, this enables the world profile for all players. Await server confirmation.";
    }

    public static String diagnostics() {
        String capability = !IrisInterface.invoker.isShaders() ? "shaders_off_server_gameplay_only"
            : !verifiedIris() ? "unsupported_iris_version"
            : !SunlightShaderAdapter.supports(IrisInterface.invoker.getShaderpackName()) ? "unsupported_shader_pack"
            : !compilationFailure.isEmpty() ? "adapter_failed"
            : reloadPending ? "reload_pending"
            : effectiveShaderProfile().isPresent() ? "shared_shader_profile_active" : "native_shader_profile";
        return "experimentalSharedSunlight={revision=" + SESSION.revision + ", profile=" + SESSION.profile
            + ", capability=" + capability + ", ownerNative=" + observedOwner
            + ", failure=" + compilationFailure + "}";
    }

    private static boolean verifiedIris() {
        var mods = net.neoforged.fml.loading.LoadingModList.get();
        if (mods == null) return false;
        var file = mods.getModFileById("iris");
        return file != null && file.getMods().stream().anyMatch(mod -> mod.getModId().equals("iris")
            && IrisPortalUniformCompatibility.supports(mod.getVersion().toString()));
    }

    /** Lazy linkage: Iris remains optional. */
    private static final class IrisAccess {
        static void reload() throws java.io.IOException {
            net.irisshaders.iris.Iris.reload();
            if (net.irisshaders.iris.Iris.isFallback()) throw new IllegalStateException("Iris entered fallback");
        }
    }
}
