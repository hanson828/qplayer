package dev.t1m3.qplayer.lyric.skia;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LyricColumnMaskTest {
    @Test
    public void fractionalDensityLeavesNoBrightPixelsAtEitherFadeBoundary() {
        LyricCompositor compositor = new LyricCompositor();
        Rect bounds = Rect.makeLTRB(8.25f, 11.3f, 72.75f, 91.9f);
        float fade = bounds.getHeight() * 0.2f;
        try (Paint white = new Paint().setColor(0xFFFFFFFF);
             Shader shader = Shader.makeLinearGradient(0, bounds.getTop(), 0, bounds.getBottom(),
                     new int[]{0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                     new float[]{0, 0.2f, 0.8f, 1})) {
            for (float density : new float[]{1, 1.25f, 1.5f, 2, 2.625f, 2.75f, 3.5f}) {
                try (Surface surface = Surface.makeRasterN32Premul(288, 360);
                     Bitmap pixels = new Bitmap()) {
                    Canvas canvas = surface.getCanvas();
                    canvas.clear(0);
                    int saved = canvas.save();
                    canvas.scale(density, density);
                    canvas.saveLayer(bounds, null);
                    // A glyph crossing either boundary can cover every pixel of a scanline.
                    canvas.drawPaint(white);
                    compositor.applyColumnMask(canvas, shader);
                    canvas.restoreToCount(saved);
                    pixels.allocN32Pixels(288, 360);
                    assertTrue(surface.readPixels(pixels, 0, 0));

                    int x = (int) ((bounds.getLeft() + bounds.getRight()) * 0.5f * density);
                    int middle = (int) ((bounds.getTop() + bounds.getBottom()) * 0.5f * density);
                    assertEquals("the readable plateau must remain opaque", 255,
                            pixels.getColor(x, middle) >>> 24);
                    for (int y = 0; y < 360; y++) {
                        // Use the maximum alpha anywhere inside this device pixel, allowing
                        // two quantization levels but not an unmasked antialias fringe.
                        float distance = Math.min(y + 1f - bounds.getTop() * density,
                                bounds.getBottom() * density - y);
                        int ceiling = (int) Math.ceil(255 * Math.max(0, Math.min(1,
                                distance / (fade * density))));
                        int alpha = pixels.getColor(x, y) >>> 24;
                        assertTrue("density=" + density + ", y=" + y + ", alpha=" + alpha
                                        + ", ceiling=" + ceiling,
                                alpha <= ceiling + 2);
                    }
                }
            }
        } finally {
            compositor.dispose();
        }
    }

    @Test
    public void edgeBlurCompositeShaderCompilesAndBlendsFocusBand() throws Exception {
        String source;
        try (java.io.InputStream in = LyricCompositor.class.getResourceAsStream(
                "/shaders/lyric/edge_blur_composite.sksl")) {
            assertTrue("edge_blur_composite.sksl resource missing", in != null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            source = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (io.github.humbleui.skija.RuntimeEffect effect =
                     io.github.humbleui.skija.RuntimeEffect.makeForShader(source);
             Surface sharpSurf = Surface.makeRasterN32Premul(100, 100);
             Surface blurSurf = Surface.makeRasterN32Premul(50, 50);
             Surface outSurf = Surface.makeRasterN32Premul(100, 100);
             Paint white = new Paint().setColor(0xFFFFFFFF);
             Paint green = new Paint().setColor(0xFF00FF00);
             Paint compositePaint = new Paint();
             Bitmap pixels = new Bitmap()) {
            sharpSurf.getCanvas().clear(0xFFFFFFFF);
            blurSurf.getCanvas().clear(0xFF00FF00);
            try (io.github.humbleui.skija.Image sharpImg = sharpSurf.makeImageSnapshot();
                 io.github.humbleui.skija.Image blurImg = blurSurf.makeImageSnapshot();
                 Shader sharpShader = sharpImg.makeShader(
                         io.github.humbleui.skija.FilterTileMode.DECAL,
                         io.github.humbleui.skija.FilterTileMode.DECAL,
                         io.github.humbleui.skija.SamplingMode.DEFAULT, null);
                 Shader blurShader = blurImg.makeShader(
                         io.github.humbleui.skija.FilterTileMode.DECAL,
                         io.github.humbleui.skija.FilterTileMode.DECAL,
                         io.github.humbleui.skija.SamplingMode.LINEAR, null);
                 io.github.humbleui.skija.RuntimeEffectBuilder builder =
                         new io.github.humbleui.skija.RuntimeEffectBuilder(effect)) {
                builder.setChild("sharpTex", sharpShader);
                builder.setChild("blurTex", blurShader);
                builder.setUniform("colTopY", 0f);
                builder.setUniform("colHeight", 100f);
                builder.setUniform("devOrigin", 0f, 0f);
                builder.setUniform("ctmScale", 1f, 1f);
                builder.setUniform("ctmTrans", 0f, 0f);
                builder.setUniform("blurRatio", 0.5f, 0.5f);
                builder.setUniform("bandStops", 0.15f, 0.35f, 0.65f, 0.85f);
                builder.setUniform("fadeFrac", 0.1f);
                builder.setUniform("overallAlpha", 1.0f);
                try (Shader compiled = builder.makeShader()) {
                    compositePaint.setShader(compiled);
                    outSurf.getCanvas().clear(0);
                    outSurf.getCanvas().drawRect(Rect.makeWH(100, 100), compositePaint);
                }
            }
            pixels.allocN32Pixels(100, 100);
            assertTrue(outSurf.readPixels(pixels, 0, 0));
            // Top & bottom edge rows (pixel center y=0.5 / 99.5, t=0.005/0.995 -> alpha <= 15).
            assertTrue((pixels.getColor(50, 0) >>> 24) <= 15);
            assertTrue((pixels.getColor(50, 99) >>> 24) <= 15);
            // Blur band outside the sharp focus band (y=12, t=0.125): pure green.
            assertEquals(0xFF00FF00, pixels.getColor(50, 12));
            // Inside sharp plateau (y=50, t=0.505): pure white.
            assertEquals(0xFFFFFFFF, pixels.getColor(50, 50));
            // Ramp between blur and sharp (y=25, t=0.255 -> sharpW ≈ 0.525): blended green+white.
            int rampPx = pixels.getColor(50, 25);
            assertEquals(255, (rampPx >>> 8) & 0xFF);
            int rampRed = (rampPx >>> 16) & 0xFF;
            assertTrue("expected ramp red between 100 and 160, got " + rampRed,
                    rampRed >= 100 && rampRed <= 160);
        }
    }
}
