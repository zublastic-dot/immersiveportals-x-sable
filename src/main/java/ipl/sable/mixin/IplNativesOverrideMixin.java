package ipl.sable.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import ipl.sable.natives.IplNativeLibrary;
import ipl.sable.natives.IplRapierNatives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * Load IPSable's locally-built Sable natives instead of the ones bundled in the sable jar
 * (phase 4 of the portal-physics spec: aperture contact clipping + contact-impulse readout
 * fidelity live in the Rust layer).
 *
 * <p>Sable re-extracts its bundled {@code .l4z} natives with {@code REPLACE_EXISTING} on
 * every launch, so swapping the extracted file can't stick; and repacking the sable jar
 * couples our deploy loop to theirs. Instead: this mod carries the Atlas native as a
 * resource ({@code /natives_ipl/}), and this mixin — whose handler executes as merged
 * {@code Rapier3D} code, i.e. the exact classloader whose natives the JVM resolves —
 * extracts and {@code System.load}s it at the head of {@code loadLibrary}, cancelling the
 * stock path. Its JNI declarations are checked against the installed Sable release;
 * the shared-world implementation retains chart cleanup on {@code dispose}.
 *
 * <p>The shared Atlas backend is mandatory for hosted physics. Missing/unsupported natives,
 * disabled loading, IO errors and link errors stop startup rather than selecting Sable's
 * incompatible stock separate-world backend.
 */
@Pseudo
@Mixin(value = Rapier3D.class, remap = false)
public abstract class IplNativesOverrideMixin {

    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true, remap = false,
        require = 1, allow = 1)
    private static void ipl$loadCustomNatives(CallbackInfo ci) {
        String arch = System.getProperty("os.arch", "");
        String os = System.getProperty("os.name", "");
        IplNativeLibrary library = IplNativeLibrary.select(os, arch,
            System.getProperty("ipl.sable.customNatives", "true"));

        // Resource lookup notes: this handler executes as MERGED Rapier3D code (sable's
        // module), so the lookup must anchor on a real class in OUR module — and module
        // resource encapsulation can still null it, so fall back to the classloader view.
        String resource = library.resourceName();
        try {
            InputStream in = IplRapierNatives.class.getResourceAsStream(resource);
            if (in == null) {
                in = IplRapierNatives.class.getClassLoader().getResourceAsStream(resource.substring(1));
            }
            try (InputStream stream = in) {
                Path nativeFile = library.extract(stream, Path.of(".sable", "natives-ipl"));
                // Keep this call in merged Rapier3D code, preserving the native classloader binding.
                System.load(nativeFile.toString());
                IplRapierNatives.verifyAndMarkAvailable();
                org.slf4j.LoggerFactory.getLogger("ipl-natives").info(
                    "[IPL-NATIVES] verified Atlas backend for {} / {} from {}", os, arch, nativeFile);
                ci.cancel();
            }
        } catch (java.io.IOException | LinkageError | RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("ipl-natives").error(
                "[IPL-NATIVES] required Atlas backend failed to load; refusing stock fallback", failure);
            throw IplNativeLibrary.unavailable("Could not load and verify " + resource
                + " on " + os + " / " + arch + ".", failure);
        }
    }
}
