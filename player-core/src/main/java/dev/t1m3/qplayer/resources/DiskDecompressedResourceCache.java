package dev.t1m3.qplayer.resources;

import dev.t1m3.qplayer.util.Logger;
import io.github.timer_err.qml4j.render.ResourceLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Resource-loader wrapper that persists the expanded form of XZ assets.
 *
 * <p>Bundled CJK fonts save considerable installer space when compressed, but
 * expanding the two UI weights on every launch costs seconds. The compressed
 * content hash is the cache key, so an updated asset selects a new entry without
 * timestamps or an application-version dependency. A payload checksum protects
 * against interrupted writes and disk corruption.
 */
public final class DiskDecompressedResourceCache implements ResourceLoader {

    private static final int MAGIC = 0x51445243; // QDRC
    private static final int FORMAT_VERSION = 1;
    private static final int CHECKSUM_SIZE = 32;
    private static final int MAX_RESOURCE_BYTES = 64 * 1024 * 1024;
    private static final int IO_CHUNK_BYTES = 64 * 1024;

    private final ResourceLoader delegate;
    private final Path directory;
    // Consumers copy font bytes into native Typefaces. Share arrays while a caller
    // still owns them, but do not retain another full CJK file until heap pressure.
    // Later requests can read the validated disk entry without decompressing again.
    private final ConcurrentHashMap<String, WeakReference<byte[]>> expandedInProcess =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    // Short-lived strong pins used during cold startup prewarm until the UI consumes
    // the Typefaces, avoiding GC eviction between prewarm and surfaceChanged().
    private final ConcurrentHashMap<String, byte[]> prewarmed = new ConcurrentHashMap<>();

    public DiskDecompressedResourceCache(ResourceLoader delegate, Path directory) {
        this.delegate = delegate;
        this.directory = directory;
    }

    /** Pre-load and validate bundled resources in parallel before the scene requests them. */
    public void prewarmAsync(Executor executor, String... sources) {
        if (executor == null || sources == null) return;
        for (String source : sources) {
            if (source == null || source.isEmpty()) continue;
            try {
                executor.execute(() -> {
                    try {
                        byte[] data = load(source);
                        if (data != null) prewarmed.put(source, data);
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    /** Release startup strong pins once consumers have copied bytes into native Typefaces. */
    public void releasePrewarmed() {
        prewarmed.clear();
    }

    @Override
    public byte[] load(String source) {
        if (source == null) return null;
        byte[] pinned = prewarmed.get(source);
        if (pinned != null) return pinned;
        WeakReference<byte[]> reference = expandedInProcess.get(source);
        byte[] inMemory = reference != null ? reference.get() : null;
        if (inMemory != null) return inMemory;

        Object lock = keyLocks.computeIfAbsent(source, k -> new Object());
        synchronized (lock) {
            pinned = prewarmed.get(source);
            if (pinned != null) return pinned;
            reference = expandedInProcess.get(source);
            inMemory = reference != null ? reference.get() : null;
            if (inMemory != null) return inMemory;

            byte[] plain = delegate.load(source);
            if (plain != null) {
                expandedInProcess.put(source, new WeakReference<>(plain));
                return plain;
            }

            byte[] compressed = delegate.load(source + ".xz");
            if (compressed == null) return null;
            Path file = directory.resolve(hex(sha256(compressed)) + ".resource");
            try {
                if (Files.isRegularFile(file)) {
                    byte[] cached = decode(file);
                    expandedInProcess.put(source, new WeakReference<>(cached));
                    Logger.info("decompressed resource cache hit: {}", source);
                    return cached;
                }
            } catch (Exception error) {
                Logger.warn("discarding invalid decompressed resource cache {} ({})", file, error);
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
            }

            ResourceLoader compressedOnly = requested ->
                    (source + ".xz").equals(requested) ? compressed : null;
            byte[] expanded = CompressedResources.load(compressedOnly, source);
            if (expanded == null || expanded.length > MAX_RESOURCE_BYTES) return expanded;
            expandedInProcess.put(source, new WeakReference<>(expanded));
            store(file, expanded);
            return expanded;
        }
    }

    private void store(Path file, byte[] payload) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
            encode(temporary, payload);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            Logger.info("decompressed resource cache stored: {}", file.getFileName());
        } catch (Exception error) {
            Logger.warn("cannot store decompressed resource cache ({})", error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void encode(Path file, byte[] payload) throws IOException {
        // java.io streams, not Files.newOutputStream: a channel write of the whole
        // payload would leave a payload-sized direct buffer cached on this thread.
        MessageDigest digest = newSha256();
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file.toFile()), IO_CHUNK_BYTES))) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(payload.length);
            int offset = 0;
            while (offset < payload.length) {
                int chunk = Math.min(IO_CHUNK_BYTES, payload.length - offset);
                output.write(payload, offset, chunk);
                digest.update(payload, offset, chunk);
                offset += chunk;
            }
            output.write(digest.digest());
        }
    }

    private static byte[] decode(Path file) throws IOException {
        long fileSize = Files.size(file);
        if (fileSize < Integer.BYTES * 3 + CHECKSUM_SIZE) {
            throw new IOException("truncated cache entry");
        }
        // A CJK font is about 13 MiB. Read directly into its final array instead
        // of holding a second, full-file copy while an Activity rebuilds its UI.
        // FileInputStream rather than a channel stream, which would cache another
        // font-sized temporary direct buffer on every thread that loads a font.
        MessageDigest digest = newSha256();
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file.toFile()), IO_CHUNK_BYTES))) {
            if (input.readInt() != MAGIC) throw new IOException("wrong cache magic");
            if (input.readInt() != FORMAT_VERSION) throw new IOException("wrong cache version");
            int length = input.readInt();
            if (length < 0 || length > MAX_RESOURCE_BYTES
                    || fileSize != (long) Integer.BYTES * 3 + length + CHECKSUM_SIZE) {
                throw new IOException("invalid resource length " + length);
            }
            byte[] payload = new byte[length];
            int offset = 0;
            while (offset < length) {
                int chunk = Math.min(IO_CHUNK_BYTES, length - offset);
                input.readFully(payload, offset, chunk);
                digest.update(payload, offset, chunk);
                offset += chunk;
            }
            byte[] checksum = new byte[CHECKSUM_SIZE];
            input.readFully(checksum);
            if (input.read() != -1) throw new IOException("trailing cache data");
            if (!MessageDigest.isEqual(checksum, digest.digest())) {
                throw new IOException("cache checksum mismatch");
            }
            return payload;
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] sha256(byte[] value) {
        return newSha256().digest(value);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
