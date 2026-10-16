package io.github.mpdairy.monopaint;

/**
 * A picture the artist traces under every layer: the picture at its own size plus where
 * it sits on the page. Every transform resamples the original, never a previous
 * resample, so moving it around never wears the picture down.
 *
 * <p>Pure Java so the host checks can exercise the rasterizer without a device; the
 * tablet gets the C version of the same loop from {@code reference_raster.c}.
 */
final class ReferenceImage {
    /** The picture at its own size, never resampled. */
    final byte[] lum, alpha;
    final int width, height;
    /** Image→page affine {a,b,c,d,e,f} as {@code x' = a*x + b*y + c}. Never mutated in place. */
    final float[] transform;

    /** True when the C rasterizer is available; host checks fall back to {@link #raster}. */
    static final boolean NATIVE;
    static {
        boolean loaded;
        try { System.loadLibrary("monopaint_display"); loaded = true; }
        catch (Throwable unavailable) { loaded = false; }
        NATIVE = loaded;
    }
    private static native void nativeRaster(byte[] lum, byte[] alpha, int imageWidth, int imageHeight,
                                          float[] transform, byte[] tones, byte[] coverage,
                                          int pageWidth, int pageHeight,
                                          int left, int top, int right, int bottom);

    ReferenceImage(byte[] lum, byte[] alpha, int width, int height, float[] transform) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("A reference image needs some pixels");
        if (lum == null || alpha == null || lum.length != width * height || alpha.length != width * height)
            throw new IllegalArgumentException("A reference image needs one byte per pixel");
        if (transform == null || transform.length != 6) throw new IllegalArgumentException("A reference needs a placement");
        this.lum = lum; this.alpha = alpha; this.width = width; this.height = height;
        this.transform = transform;
    }

    /** Placement putting the image center at {@code (cx,cy)}, rotated and scaled about it. */
    static float[] place(float cx, float cy, int width, int height, double scale, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians) * scale, sin = Math.sin(radians) * scale;
        double ox = width / 2.0, oy = height / 2.0;
        return new float[]{(float)cos, (float)-sin, (float)(cx - (cos * ox - sin * oy)),
                (float)sin, (float)cos, (float)(cy - (sin * ox + cos * oy))};
    }

    /** Inverse of {@link #place}: {@code {centerX, centerY, scale, degrees}}. */
    static float[] decompose(float[] m, int width, int height) {
        double scale = Math.hypot(m[0], m[3]);
        double degrees = Math.toDegrees(Math.atan2(m[3], m[0]));
        double ox = width / 2.0, oy = height / 2.0;
        double cx = m[2] + (double)m[0] * ox + (double)m[1] * oy;
        double cy = m[5] + (double)m[3] * ox + (double)m[4] * oy;
        return new float[]{(float)cx, (float)cy, (float)scale, (float)degrees};
    }

    /** The same placement slid by whole or part page pixels. */
    static float[] translated(float[] m, double dx, double dy) {
        float[] next = m.clone();
        next[2] += (float)dx; next[5] += (float)dy;
        return next;
    }

    /** The transformed image box, clipped to the page: {@code {left, top, right, bottom}}. */
    static int[] bounds(ReferenceImage ref, int pageWidth, int pageHeight) {
        float[] m = ref.transform;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        int[] xs = {0, ref.width, ref.width, 0}, ys = {0, 0, ref.height, ref.height};
        for (int i = 0; i < 4; i++) {
            double x = (double)m[0] * xs[i] + (double)m[1] * ys[i] + m[2];
            double y = (double)m[3] * xs[i] + (double)m[4] * ys[i] + m[5];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        int left = clamp((int)Math.floor(minX), 0, pageWidth);
        int top = clamp((int)Math.floor(minY), 0, pageHeight);
        int right = clamp((int)Math.ceil(maxX), left, pageWidth);
        int bottom = clamp((int)Math.ceil(maxY), top, pageHeight);
        return new int[]{left, top, right, bottom};
    }

    /**
     * Fill a page layer region with the placed picture: the C loop on the tablet, the
     * Java reference implementation everywhere else.
     */
    static void rasterInto(ReferenceImage ref, byte[] tones, byte[] coverage,
                          int pageWidth, int pageHeight, int left, int top, int right, int bottom) {
        left = Math.max(0, left); top = Math.max(0, top);
        right = Math.min(pageWidth, right); bottom = Math.min(pageHeight, bottom);
        if (right <= left || bottom <= top) return;
        if (NATIVE) {
            nativeRaster(ref.lum, ref.alpha, ref.width, ref.height, ref.transform,
                    tones, coverage, pageWidth, pageHeight, left, top, right, bottom);
            return;
        }
        raster(ref.lum, ref.alpha, ref.width, ref.height, ref.transform,
                tones, coverage, pageWidth, pageHeight, left, top, right, bottom);
    }

    /**
     * The Java reference rasterizer: bilinear resample of the placed picture into a page
     * rect, sampled in premultiplied gray so turned edges stay clean.
     */
    static void raster(byte[] lum, byte[] alpha, int imageWidth, int imageHeight, float[] m,
                      byte[] tones, byte[] coverage, int pageWidth, int pageHeight,
                      int left, int top, int right, int bottom) {
        double det = (double)m[0] * m[4] - (double)m[1] * m[3];
        boolean degenerate = !Double.isFinite(det) || Math.abs(det) < 1e-9;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            int at = y * pageWidth + x;
            if (degenerate) { tones[at] = (byte)255; coverage[at] = 0; continue; }
            // The page pixel center maps back through the placement; image pixels span
            // [-0.5, w-0.5], so drop a half pixel before filtering.
            double rx = x + 0.5 - m[2], ry = y + 0.5 - m[5];
            double u = ((double)m[4] * rx - (double)m[1] * ry) / det - 0.5;
            double v = (-(double)m[3] * rx + (double)m[0] * ry) / det - 0.5;
            if (u < -0.5 || u > imageWidth - 0.5 || v < -0.5 || v > imageHeight - 0.5) {
                tones[at] = (byte)255; coverage[at] = 0; continue;
            }
            int x0 = (int)Math.floor(u), y0 = (int)Math.floor(v);
            double fx = u - x0, fy = v - y0;
            int x1 = x0 + 1, y1 = y0 + 1;
            x0 = clamp(x0, 0, imageWidth - 1); x1 = clamp(x1, 0, imageWidth - 1);
            y0 = clamp(y0, 0, imageHeight - 1); y1 = clamp(y1, 0, imageHeight - 1);
            double w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy);
            double w01 = (1 - fx) * fy, w11 = fx * fy;
            double a00 = (alpha[y0 * imageWidth + x0] & 255), a10 = (alpha[y0 * imageWidth + x1] & 255);
            double a01 = (alpha[y1 * imageWidth + x0] & 255), a11 = (alpha[y1 * imageWidth + x1] & 255);
            double sum = w00 * a00 + w10 * a10 + w01 * a01 + w11 * a11;
            if (sum < 0.5) { tones[at] = (byte)255; coverage[at] = 0; continue; }
            double pigment = w00 * a00 * (lum[y0 * imageWidth + x0] & 255)
                    + w10 * a10 * (lum[y0 * imageWidth + x1] & 255)
                    + w01 * a01 * (lum[y1 * imageWidth + x0] & 255)
                    + w11 * a11 * (lum[y1 * imageWidth + x1] & 255);
            tones[at] = (byte)clamp((int)Math.round(pigment / sum), 0, 255);
            coverage[at] = (byte)clamp((int)Math.round(sum), 0, 255);
        }
    }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
