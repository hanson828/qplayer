package dev.t1m3.qplayer.android.app;

/**
 * Idempotent Skija native bring-up shared between {@link QPlayerApplication}'s
 * early startup thread and {@link QPlayerActivity} / {@code QmlGLSurfaceView}.
 */
public final class SkijaLoader {
    private static volatile boolean loaded;

    private SkijaLoader() { }

    public static void ensureLoaded() {
        if (loaded) return;
        synchronized (SkijaLoader.class) {
            if (loaded) return;
            // Same Skija JNI bring-up as the qml4j demo: we ship the .so via
            // jniLibs and bypass the auto-loader, so _nAfterLoad() must run
            // explicitly or every object-returning native call crashes.
            System.setProperty("skija.staticLoad", "false");
            System.loadLibrary("skija");
            io.github.humbleui.skija.impl.Library._nAfterLoad();
            try {
                Class.forName("io.github.humbleui.skija.ImageInfo");
                Class.forName("io.github.humbleui.skija.ColorInfo");
                Class.forName("io.github.humbleui.skija.ColorSpace");
                Class.forName("io.github.humbleui.skija.Color4f");
                Class.forName("io.github.humbleui.skija.Image");
                Class.forName("io.github.humbleui.skija.Canvas");
                Class.forName("io.github.humbleui.skija.Paint");
                Class.forName("io.github.humbleui.skija.Font");
                Class.forName("io.github.humbleui.types.Rect");
                Class.forName("io.github.humbleui.types.IRect");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            loaded = true;
        }
    }
}
