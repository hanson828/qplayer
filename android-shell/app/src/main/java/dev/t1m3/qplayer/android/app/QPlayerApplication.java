package dev.t1m3.qplayer.android.app;

import dev.t1m3.qplayer.android.playback.OverlayLyricWindow;
import dev.t1m3.qplayer.android.playback.PlaybackService;
import dev.t1m3.qplayer.android.security.AndroidKeystoreKeyProtector;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Process;

import dev.t1m3.qplayer.util.Logger;
import dev.t1m3.qplayer.store.AppDirs;
import dev.t1m3.qplayer.store.CredentialKeyProtection;
import dev.t1m3.qplayer.android.resources.FileResourceLoader;
import dev.t1m3.qplayer.resources.DiskDecompressedResourceCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Installs a process-wide uncaught-exception handler whose only job is to
 * best-effort cancel the playback notification before the crash takes the
 * process down. {@link PlaybackService#onDestroy()} does the same cleanup, but
 * has no guarantee of running when the crash originates outside its own
 * thread/lifecycle (render thread, a worker, another component entirely) —
 * without this, the foreground-service notification is left stuck in the
 * status bar until the user force-clears the app.
 */
public final class QPlayerApplication extends Application {

    private static final AtomicInteger STARTUP_THREAD_SEQ = new AtomicInteger();
    private static final ExecutorService STARTUP_POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "qplayer-boot-" + STARTUP_THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private static volatile long processCreateNanos;

    private DiskDecompressedResourceCache sceneResources;

    /** Shared daemon executor used to parallelize cold-start I/O and library bring-up. */
    public static ExecutorService startupExecutor() {
        return STARTUP_POOL;
    }

    /** Process creation timestamp in {@link System#nanoTime()} units, for startup profiling. */
    public static long processCreateNanos() {
        return processCreateNanos;
    }

    /** Assets and expanded fonts belong to the process, not to a recreated Activity. */
    public synchronized DiskDecompressedResourceCache sceneResources() {
        if (sceneResources == null) {
            sceneResources = new DiskDecompressedResourceCache(
                    new FileResourceLoader(getAssets()),
                    new java.io.File(getCacheDir(), "expanded-resources").toPath());
        }
        return sceneResources;
    }

    @Override
    public void onCreate() {
        processCreateNanos = System.nanoTime();
        super.onCreate();
        // Install credential protection at process start, before an Activity or a
        // restarted service can initialize the delayed credential-migration reader.
        AppDirs.setBase(getFilesDir().getAbsolutePath());
        CredentialKeyProtection.install(new AndroidKeystoreKeyProtector());

        // Overlap native Skija JNI initialization and parallel UI font verification/
        // decompression with Android's Activity creation and Binder IPC.
        STARTUP_POOL.execute(SkijaLoader::ensureLoaded);
        sceneResources().prewarmAsync(STARTUP_POOL,
                "fonts/PingFangSC-Regular.otf",
                "fonts/PingFangSC-Medium.otf",
                "fonts/MaterialSymbolsRounded.ttf");

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                Logger.error("uncaught exception on {}: {}", thread.getName(), ex.toString());
            } catch (Throwable ignored) {
            }
            try {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(PlaybackService.NOTIF_ID);
            } catch (Throwable ignored) {
                // Best-effort — cleanup must never mask or interfere with the real crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, ex);
            } else {
                // No previous handler installed (unusual) — fall back to killing the
                // process ourselves so the crash doesn't silently hang.
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        OverlayLyricWindow.onConfigurationChanged();
    }
}
