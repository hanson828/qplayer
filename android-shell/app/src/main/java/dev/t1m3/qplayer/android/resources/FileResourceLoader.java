package dev.t1m3.qplayer.android.resources;

import android.content.res.AssetManager;

import io.github.timer_err.qml4j.render.ResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Resource loader that serves absolute filesystem paths (and {@code file://} URIs)
 * from disk, and everything else from bundled assets. The stock asset-only loader
 * can't read the disk cache, so a QML {@code Image} pointed at a cached cover path
 * (e.g. the now-playing art offline) would fail; this lets such paths resolve.
 */
public final class FileResourceLoader implements ResourceLoader {

    private final AssetManager assets;

    public FileResourceLoader(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public byte[] load(String source) {
        if (source == null) return null;
        if (source.startsWith("file://")) {
            return readFile(source.substring("file://".length()));
        }
        if (source.startsWith("/")) {
            return readFile(source);
        }
        String name = source.startsWith("asset:") ? source.substring("asset:".length()) : source;
        try (InputStream in = assets.open(name)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readFile(String path) {
        File f = new File(path);
        if (!f.isFile()) return null;
        long len = f.length();
        if (len > 0 && len <= 64L * 1024L * 1024L) {
            byte[] exact = new byte[(int) len];
            try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
                in.readFully(exact);
                return exact;
            } catch (IOException e) {
                return null;
            }
        }
        try (FileInputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        int hint = 0;
        try {
            hint = in.available();
        } catch (IOException ignored) {
        }
        int cap = Math.max(8192, Math.min(hint, 32 * 1024 * 1024));
        ByteArrayOutputStream out = new ByteArrayOutputStream(cap);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
