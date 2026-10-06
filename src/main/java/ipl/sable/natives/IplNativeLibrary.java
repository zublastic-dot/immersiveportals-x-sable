package ipl.sable.natives;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Platform selection and extraction only. System.load must remain in the Rapier3D mixin. */
public enum IplNativeLibrary {
    WINDOWS_X86_64("sable_rapier_x86_64_windows.dll"),
    LINUX_X86_64("libsable_rapier_x86_64_linux.so");

    static final int MAX_LIBRARY_BYTES = 64 * 1024 * 1024;
    private final String fileName;

    IplNativeLibrary(String fileName) {
        this.fileName = fileName;
    }

    public String resourceName() {
        return "/natives_ipl/" + this.fileName;
    }

    public static IplNativeLibrary select(String osName, String architecture, String enabled) {
        if ("false".equalsIgnoreCase(enabled)) {
            throw unavailable("-Dipl.sable.customNatives=false disables the required Atlas backend. "
                + "Remove that option; stock Sable natives cannot simulate this fork's hosted bodies.", null);
        }
        String os = osName == null ? "" : osName.trim().toLowerCase(Locale.ROOT);
        String arch = architecture == null ? "" : architecture.trim().toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            if (os.startsWith("windows")) return WINDOWS_X86_64;
            if (os.equals("linux")) return LINUX_X86_64;
        }
        throw unavailable("Unsupported platform " + osName + " / " + architecture
            + ". This build supports Windows and Linux x86_64 only.", null);
    }

    /**
     * Publish a complete, content-addressed copy without overwriting an already loaded library.
     * A damaged cache entry is an error rather than an invitation to load different native code.
     */
    public Path extract(InputStream source, Path cacheDirectory) throws IOException {
        if (source == null) throw new IOException("Missing bundled native resource " + resourceName());
        byte[] bytes = source.readNBytes(MAX_LIBRARY_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_LIBRARY_BYTES) {
            throw new IOException("Invalid native resource size for " + resourceName()
                + ": expected 1.." + MAX_LIBRARY_BYTES + " bytes");
        }
        String digest = digest(bytes);
        Path directory = cacheDirectory.toAbsolutePath().normalize();
        rejectLinkedAncestors(directory);
        Files.createDirectories(directory);
        rejectLinkedAncestors(directory);
        Path target = directory.resolve(digest + "-" + this.fileName);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Path staging = Files.createTempFile(directory, ".ipl-native-", ".tmp");
            try {
                Files.write(staging, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    // No REPLACE_EXISTING: a concurrent launch may have published this hash.
                    Files.move(staging, target);
                } catch (FileAlreadyExistsException concurrentLaunch) {
                    // Verify the winning file below before using it.
                }
            } finally {
                Files.deleteIfExists(staging);
            }
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
            || Files.size(target) != bytes.length
            || !digest.equals(digest(Files.readAllBytes(target)))) {
            throw new IOException("Native cache entry failed verification: " + target
                + ". Restore or remove that cache entry before restarting.");
        }
        return target;
    }

    static void checkAtlasProbeResult(boolean acceptedInvalidBody) {
        if (acceptedInvalidBody) {
            throw unavailable("The native Atlas capability probe returned an invalid result.", null);
        }
    }

    public static IllegalStateException unavailable(String detail, Throwable cause) {
        return new IllegalStateException("[IPL-NATIVES] IP-Sable requires its Atlas physics backend. "
            + detail + " Startup is stopped because falling back to stock natives would leave "
            + "hosted bodies without correct physics and terrain collision. Install a complete "
            + "IP-Sable build for this platform.", cause);
    }

    private static void rejectLinkedAncestors(Path directory) throws IOException {
        for (Path cursor = directory; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("Native cache must not traverse a symbolic link: " + cursor);
            }
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Java requires SHA-256", impossible);
        }
    }
}
