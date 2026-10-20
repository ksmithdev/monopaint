package io.github.mpdairy.monopaint;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A temporary line overlay drawn straight into the presented raster, so it reaches the
 * e-ink panel like any canvas change. The pixels under its lines are saved first and put
 * back exactly when the overlay is hidden or replaced.
 *
 * <p>The overlay is given as polylines (each an {@code x,y,x,y...} array). Each is cut
 * into short pieces along the line so a long or moving overlay damages only a band around
 * itself, never the whole page.</p>
 *
 * <p>The tiles an overlay covers are worked out once and kept, so {@link #redraw} can put
 * back and re-take just the part inside a small area. A long selection border crossing a
 * stroke that way costs a handful of tiles per frame instead of its whole length.</p>
 */
final class Overlay {
    /** What the overlay draws over its saved tiles. */
    interface Ink { void draw(Canvas canvas); }

    /** A segment longer than this is cut into pieces. */
    private static final float MAX_PIECE_DP = 48;
    /** Room around a piece for the halo drawn around the line. */
    private static final float PAD_DP = 6;

    /** Pixels taken from the raster under one tile, waiting to be put back. */
    private static final class Saved {
        final Rect rect; final int[] pixels;
        Saved(Rect rect, int[] pixels) { this.rect = rect; this.pixels = pixels; }
    }

    private final Context context;
    /** Every tile this overlay covers, newest last; the order it restores in. */
    private final List<Rect> tiles = new ArrayList<>();
    /** The tiles currently holding saved pixels, in the same order. */
    private final List<Saved> saved = new ArrayList<>();
    private int[] buffer = new int[0];

    Overlay(Context context) { this.context = context; }
    private int dp(float value) { return Ui.dp(context, value); }
    boolean showing() { return !saved.isEmpty(); }

    /**
     * Put the pixels under {@code lines} back, then draw {@code ink} over freshly saved
     * ones. Every tile touched is handed to {@code queue}.
     */
    void show(Bitmap target, float[][] lines, Consumer<Rect> queue, Ink ink) { show(target, lines, null, queue, ink); }
    /**
     * As above; {@code extra} saves and restores one filled rectangle too, for overlays
     * that paint an area rather than only a line.
     */
    void show(Bitmap target, float[][] lines, Rect extra, Consumer<Rect> queue, Ink ink) {
        // hide() already puts back every saved tile, so only the re-take and ink are left.
        hide(target, queue);
        measure(lines, extra);
        rearm(target, null, queue, ink);
    }

    /**
     * Save the overlay's tiles inside {@code area} from what the raster holds now and draw
     * {@code ink} over them, clipped to {@code area}. Call this after the area has been
     * re-rendered, so the overlay is saved over the new artwork; {@link #restore} the same
     * area first, or the new artwork would be painted over pixels it already replaced.
     */
    void rearm(Bitmap target, Rect area, Consumer<Rect> queue, Ink ink) {
        if (target == null) return;
        clipTo(target);
        List<Rect> inside = new ArrayList<>();
        for (Rect tile : tiles) if (inside(area, tile)) inside.add(tile);
        int needed = 0;
        for (Rect tile : inside) needed += tile.width() * tile.height();
        if (buffer.length < needed) buffer = new int[needed];
        // Save every tile before drawing: overlapping tiles must restore in reverse.
        int offset = 0;
        for (Rect tile : inside) {
            int count = tile.width() * tile.height();
            target.getPixels(buffer, offset, tile.width(), tile.left, tile.top, tile.width(), tile.height());
            saved.add(new Saved(tile, java.util.Arrays.copyOfRange(buffer, offset, offset + count)));
            offset += count;
        }
        Canvas canvas = new Canvas(target);
        if (area != null) canvas.clipRect(area);
        ink.draw(canvas);
        for (Rect tile : inside) queue.accept(tile);
    }

    /** Put back the saved pixels inside {@code area} (all of them when null), queuing each. */
    void restore(Rect area, Bitmap target, Consumer<Rect> queue) {
        if (target == null) return;
        // Newest saved first: where tiles overlap, the most recent save is the true background.
        for (int i = saved.size() - 1; i >= 0; i--) {
            Saved tile = saved.get(i);
            if (!inside(area, tile.rect)) continue;
            target.setPixels(tile.pixels, 0, tile.rect.width(), tile.rect.left, tile.rect.top,
                    tile.rect.width(), tile.rect.height());
            saved.remove(i);
            queue.accept(tile.rect);
        }
    }

    /** Restore the saved pixels, handing each restored tile to {@code queue}. The overlay's
     * measured tiles are kept so it can be redrawn over a new area without measuring again. */
    void hide(Bitmap target, Consumer<Rect> queue) { restore(null, target, queue); }

    /** Forget the saved pixels without restoring them, for when a fresh render replaced them. */
    void discard() { tiles.clear(); saved.clear(); }

    private boolean inside(Rect area, Rect tile) { return area == null || area.intersects(tile.left, tile.top, tile.right, tile.bottom); }

    /** Work out which tiles the lines cover, shortest pieces first so damage stays local. */
    private void measure(float[][] lines, Rect extra) {
        tiles.clear();
        float max = dp(MAX_PIECE_DP), pad = dp(PAD_DP);
        RectF bounds = new RectF();
        for (float[] line : lines) for (int i = 0; i + 3 < line.length; i += 2) {
            float x0 = line[i], y0 = line[i+1], x1 = line[i+2], y1 = line[i+3];
            int pieces = Math.max(1, (int) Math.ceil(Math.hypot(x1-x0, y1-y0) / max));
            for (int p = 0; p < pieces; p++) {
                float a = (float) p / pieces, b = (float) (p+1) / pieces;
                float ax = x0+(x1-x0)*a, ay = y0+(y1-y0)*a;
                float bx = x0+(x1-x0)*b, by = y0+(y1-y0)*b;
                bounds.set(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by));
                bounds.inset(-pad, -pad);
                Rect tile = new Rect();
                bounds.roundOut(tile);
                // Tiles must stay inside the raster, or getPixels/setPixels throw.
                if (tile.width() > 0 && tile.height() > 0) tiles.add(tile);
            }
        }
        if (extra != null) tiles.add(new Rect(extra));
    }

    /** Clip the measured tiles to the raster before any pixel access. */
    void clipTo(Bitmap target) {
        for (java.util.Iterator<Rect> it = tiles.iterator(); it.hasNext(); )
            if (!it.next().intersect(0, 0, target.getWidth(), target.getHeight())) it.remove();
    }
}
