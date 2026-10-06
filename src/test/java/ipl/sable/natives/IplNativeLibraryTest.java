package ipl.sable.natives;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IplNativeLibraryTest {
    @TempDir Path temporary;

    @Test void selectsExactSupportedResourcesAndJavaArchAliases() {
        for (String arch : List.of("amd64", "x86_64", "AMD64")) {
            assertEquals("/natives_ipl/libsable_rapier_x86_64_linux.so",
                IplNativeLibrary.select("Linux", arch, "true").resourceName());
            assertEquals("/natives_ipl/sable_rapier_x86_64_windows.dll",
                IplNativeLibrary.select("Windows Server 2022", arch, "true").resourceName());
        }
    }

    @Test void rejectsUnsupportedPlatformsIncludingDarwinRatherThanMistakingItForWindows() {
        for (String os : List.of("Darwin", "Mac OS X", "FreeBSD", "")) {
            assertTrue(assertThrows(IllegalStateException.class,
                () -> IplNativeLibrary.select(os, "amd64", "true"))
                .getMessage().contains("Unsupported platform"));
        }
        for (String arch : List.of("aarch64", "arm64", "x86", "i386", "")) {
            assertThrows(IllegalStateException.class,
                () -> IplNativeLibrary.select("Linux", arch, "true"));
        }
        assertThrows(IllegalStateException.class, () -> IplNativeLibrary.select(null, null, "true"));
    }

    @Test void obsoleteKillSwitchExplainsWhyStockFallbackCannotBeUsed() {
        String error = assertThrows(IllegalStateException.class,
            () -> IplNativeLibrary.select("Linux", "amd64", "FALSE")).getMessage();
        assertTrue(error.contains("-Dipl.sable.customNatives=false"));
        assertTrue(error.contains("Remove that option"));
        assertTrue(error.contains("terrain collision"));
    }

    @Test void extractsCompleteContentAddressedNativeAndReusesItWithoutReplacingLoadedFile() throws Exception {
        byte[] bytes = {1, 2, 3, 4, 5};
        Path library = extract(bytes);
        assertArrayEquals(bytes, Files.readAllBytes(library));
        assertTrue(library.isAbsolute());
        assertTrue(library.getFileName().toString().matches("[0-9a-f]{64}-libsable_rapier_x86_64_linux\\.so"));
        FileTime knownTime = FileTime.fromMillis(1000000000L);
        Files.setLastModifiedTime(library, knownTime);
        assertEquals(library, extract(bytes));
        assertEquals(knownTime, Files.getLastModifiedTime(library));
        try (var files = Files.list(temporary)) {
            assertEquals(1, files.count());
        }
    }

    @Test void changedBuildUsesDifferentFileAndPreservesPreviousLibrary() throws Exception {
        Path first = extract(new byte[]{1, 2, 3});
        Path second = extract(new byte[]{1, 2, 4});
        assertNotEquals(first, second);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(first));
        assertArrayEquals(new byte[]{1, 2, 4}, Files.readAllBytes(second));
    }

    @Test void rejectsSameSizeCacheTamperingWithoutOverwritingEvidence() throws Exception {
        byte[] expected = {1, 2, 3};
        Path library = extract(expected);
        byte[] damaged = {3, 2, 1};
        Files.write(library, damaged);
        assertTrue(assertThrows(IOException.class, () -> extract(expected))
            .getMessage().contains("failed verification"));
        assertArrayEquals(damaged, Files.readAllBytes(library));
    }

    @Test void rejectsTruncatedCacheEntry() throws Exception {
        Path library = extract(new byte[]{1, 2, 3});
        Files.write(library, new byte[]{1});
        assertThrows(IOException.class, () -> extract(new byte[]{1, 2, 3}));
    }

    @Test void missingOrEmptyResourceFailsBeforeCreatingCache() {
        Path cache = temporary.resolve("uncreated");
        IOException missing = assertThrows(IOException.class,
            () -> IplNativeLibrary.LINUX_X86_64.extract(null, cache));
        assertTrue(missing.getMessage().contains(IplNativeLibrary.LINUX_X86_64.resourceName()));
        assertThrows(IOException.class,
            () -> IplNativeLibrary.LINUX_X86_64.extract(new ByteArrayInputStream(new byte[0]), cache));
        assertFalse(Files.exists(cache));
    }

    @Test void refusesOversizedResourceAfterBoundedRead() {
        long[] read = {0};
        InputStream endless = new InputStream() {
            @Override public int read() { read[0]++; return 1; }
            @Override public int read(byte[] b, int off, int len) {
                Arrays.fill(b, off, off + len, (byte) 1);
                read[0] += len;
                return len;
            }
        };
        assertThrows(IOException.class,
            () -> IplNativeLibrary.LINUX_X86_64.extract(endless, temporary.resolve("uncreated")));
        assertEquals(IplNativeLibrary.MAX_LIBRARY_BYTES + 1L, read[0]);
        assertFalse(Files.exists(temporary.resolve("uncreated")));
    }

    @Test void concurrentLaunchesCanUseSameVerifiedArtifact() throws Exception {
        byte[] bytes = new byte[65536];
        Arrays.fill(bytes, (byte) 7);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> extract(bytes));
            var second = workers.submit(() -> extract(bytes));
            assertEquals(first.get(), second.get());
            assertArrayEquals(bytes, Files.readAllBytes(first.get()));
        }
        try (var files = Files.list(temporary)) {
            assertEquals(1, files.count());
        }
    }

    @Test void rejectsLinkedCacheFile() throws Exception {
        byte[] bytes = {1, 2, 3};
        Path library = extract(bytes);
        Path other = temporary.resolve("other");
        Files.move(library, other);
        createLinkOrSkip(library, other);
        assertThrows(IOException.class, () -> extract(bytes));
    }

    @Test void rejectsLinkedCacheDirectory() throws Exception {
        Path real = Files.createDirectory(temporary.resolve("real"));
        Path link = temporary.resolve("link");
        createLinkOrSkip(link, real);
        assertThrows(IOException.class,
            () -> IplNativeLibrary.LINUX_X86_64.extract(
                new ByteArrayInputStream(new byte[]{1}), link.resolve("nested")));
    }

    @Test void capabilityProbeMustRejectInvalidBodyAndPreservesOriginalFailureCause() {
        assertDoesNotThrow(() -> IplNativeLibrary.checkAtlasProbeResult(false));
        assertThrows(IllegalStateException.class, () -> IplNativeLibrary.checkAtlasProbeResult(true));
        var cause = new UnsatisfiedLinkError("missing extension");
        var failure = IplNativeLibrary.unavailable("Probe failed.", cause);
        assertSame(cause, failure.getCause());
        assertTrue(failure.getMessage().contains("Startup is stopped"));
    }

    @Test void unverifiedBackendCannotBeRequiredOrBecomeAvailableAfterFailedNativeProbe() {
        assertFalse(IplRapierNatives.isAvailable());
        assertThrows(IllegalStateException.class, IplRapierNatives::requireAvailable);
        assertThrows(UnsatisfiedLinkError.class, IplRapierNatives::verifyAndMarkAvailable);
        assertFalse(IplRapierNatives.isAvailable());
    }

    private Path extract(byte[] bytes) throws IOException {
        return IplNativeLibrary.LINUX_X86_64.extract(new ByteArrayInputStream(bytes), temporary);
    }

    private void createLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            assumeTrue(false, "Host cannot create symbolic links: " + unavailable.getMessage());
        }
    }
}
