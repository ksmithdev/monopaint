package io.github.mpdairy.monopaint;

/**
 * A selected region of one page: its pixels plus the box they fill. While a page has a
 * selection, the pen only changes pixels inside it. The selection is working state, not
 * artwork: it is not saved with the page, and undo does not move it.
 */
final class Selection {
    /** One bit per page pixel, indexed {@code y*stride+x}. */
    final java.util.BitSet pixels;
    final int left, top, right, bottom;
    /** The page width these indices were made for; a resized page drops its selection. */
    final int stride;
    /**
     * The outline to draw for this selection as polylines (each an {@code x,y,x,y...}
     * array of page coordinates), or null to draw the pixel boundary. A freehand lasso
     * sets this to the line the artist traced: the pixel boundary of a lasso is a
     * 1-pixel staircase of thousands of tiny steps that reads as noise, while the
     * traced line, drawn as a continuous path, reads as the outline they drew.
     */
    final float[][] outline;
    /**
     * Page-indexed: the pixels that belong to this selection's content, or null meaning
     * "whatever is painted inside the area". A placed copy needs this: its area covers
     * blank paper and other people's artwork alike, and a move that reads the whole area
     * from the layer would carry that artwork away and wipe it. Null for a fresh lasso or
     * fill, where the artist means everything they enclosed.
     */
    final java.util.BitSet owned;

    Selection(java.util.BitSet pixels, int left, int top, int right, int bottom, int stride) {
        this(pixels, left, top, right, bottom, stride, null, null);
    }
    Selection(java.util.BitSet pixels, int left, int top, int right, int bottom, int stride, float[][] outline) {
        this(pixels, left, top, right, bottom, stride, outline, null);
    }
    Selection(java.util.BitSet pixels, int left, int top, int right, int bottom, int stride,
              float[][] outline, java.util.BitSet owned) {
        this.pixels = pixels; this.left = left; this.top = top;
        this.right = right; this.bottom = bottom; this.stride = stride; this.outline = outline;
        this.owned = owned;
    }

    /** Bounds of the set bits; an empty bit set makes an empty selection. */
    static Selection of(java.util.BitSet pixels, int stride) {
        int left = -1, top = -1, right = -1, bottom = -1;
        for (int i = pixels.nextSetBit(0); i >= 0; i = pixels.nextSetBit(i + 1)) {
            int x = i % stride, y = i / stride;
            if (left < 0) { left = x; top = y; right = x + 1; bottom = y + 1; }
            else {
                if (x < left) left = x;
                if (x + 1 > right) right = x + 1;
                if (y < top) top = y;
                if (y + 1 > bottom) bottom = y + 1;
            }
        }
        return new Selection(pixels, Math.max(left, 0), Math.max(top, 0),
                Math.max(right, 0), Math.max(bottom, 0), stride);
    }

    boolean empty() { return pixels.cardinality() == 0; }

    boolean contains(int x, int y) {
        return x >= left && x < right && y >= top && y < bottom && pixels.get(y * stride + x);
    }

    /** Whether moving this selection carries the pixel: see {@link #owned}. */
    boolean owns(int x, int y) {
        return owned == null || owned.get(y * stride + x);
    }

    /**
     * The page content this selection was found covering where it currently lies, as a
     * patch of the copy's box, or null on a plain lasso or fill. A placed copy overwrites
     * the artwork beneath it, so moving that copy away could only uncover a blank hole;
     * this record lets the move put the artwork back instead. Set after construction
     * because it is captured at the moment the copy is written down.
     */
    Clipboard backing;

    /**
     * The outline of this selection as polylines of page coordinates. A boundary is drawn
     * wherever exactly one side of it is selected, and runs are merged, so a big region's
     * border is few long lines. A freehand lasso brings its own smoother outline instead.
     */
    float[][] border() {
        if (outline != null) return outline;
        if (cachedBorder != null) return cachedBorder;
        java.util.ArrayList<float[]> edges = new java.util.ArrayList<>();
        for (int x = left; x <= right; x++) {
            int start = -1;
            for (int y = top; y <= bottom; y++) {
                boolean boundary = side(x-1, y) != side(x, y);
                if (boundary && start < 0) start = y;
                else if (!boundary && start >= 0) { edges.add(new float[]{x, start, x, y}); start = -1; }
            }
        }
        for (int y = top; y <= bottom; y++) {
            int start = -1;
            for (int x = left; x <= right; x++) {
                boolean boundary = side(x, y-1) != side(x, y);
                if (boundary && start < 0) start = x;
                else if (!boundary && start >= 0) { edges.add(new float[]{start, y, x, y}); start = -1; }
            }
        }
        return cachedBorder = edges.toArray(new float[0][]);
    }
    /** The pixel border is derived, so it is computed once and kept: a big selection's box is rescanned on every paint frame otherwise. */
    private float[][] cachedBorder;

    /** Whether the page pixel is selected; outside the box and off the page count as not selected. */
    private boolean side(int x, int y) {
        return x >= 0 && y >= 0 && x < stride && y * stride + x < pixels.size() && contains(x, y);
    }

    /**
     * The area enclosed by the closed loop the pen traced, clipped to the page. The loop is
     * closed by joining its last point back to its first, and filled by even-odd crossing,
     * so a self-crossing trace selects the regions a lasso would. The outline is the traced
     * line itself, not a band around it.
     */
    static Selection lasso(float[] path, int points, int width, int height) {
        java.util.BitSet pixels = new java.util.BitSet(width * height);
        if (points >= 3) {
            int minX = width, maxX = -1, minY = height, maxY = -1;
            for (int i = 0; i < points; i++) {
                int x = (int)path[i*2], y = (int)path[i*2+1];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            float[] crossings = new float[points + 1];
            for (int y = Math.max(0, minY); y <= Math.min(height - 1, maxY); y++) {
                float yc = y + .5f;
                int count = 0;
                for (int i = 0; i < points; i++) {
                    int j = (i + 1) % points;
                    float ay = path[i*2+1], by = path[j*2+1];
                    if ((ay <= yc && by > yc) || (by <= yc && ay > yc)) {
                        float t = (yc - ay) / (by - ay);
                        crossings[count++] = path[i*2] + (path[j*2] - path[i*2]) * t;
                    }
                }
                java.util.Arrays.sort(crossings, 0, count);
                for (int c = 0; c + 1 < count; c += 2) {
                    int x0 = Math.max(0, (int)Math.ceil(crossings[c] - .5f));
                    int x1 = Math.min(width, (int)Math.ceil(crossings[c+1] - .5f));
                    for (int x = x0; x < x1; x++) pixels.set(y * width + x);
                }
            }
        }
        Selection lasso = of(pixels, width);
        if (lasso.empty()) return lasso;
        // The outline is the traced line, closed back on itself.
        float[] outline = new float[(points + 1) * 2];
        System.arraycopy(path, 0, outline, 0, points * 2);
        outline[points*2] = path[0]; outline[points*2+1] = path[1];
        return new Selection(lasso.pixels, lasso.left, lasso.top, lasso.right, lasso.bottom,
                lasso.stride, new float[][]{ outline });
    }
}

/**
 * Pixels copied out of a layer: the box they came from, their tones and coverage, and
 * which pixels in that box were selected. Pasting draws only the selected ones, so a
 * selection with holes copies and pastes as it was chosen.
 */
final class Clipboard {
    final int left, top, width, height;
    final byte[] tones, alpha;
    /** Box-indexed: {@code row*width+col}. Only pixels that hold paint, so a paste never
     * lays white paper over what it lands on. */
    final java.util.BitSet pixels;
    /**
     * Box-indexed: every pixel the source selection enclosed, painted or not. This is what
     * the selection becomes once the copy is placed, so the artist can draw anywhere in the
     * area they selected. Using {@link #pixels} instead would leave them able to draw only
     * where paint already was.
     */
    final java.util.BitSet region;
    /** The source selection's drawable outline in page pixels, or null. Moves with the copy. */
    final float[][] outline;

    Clipboard(int left, int top, int width, int height, byte[] tones, byte[] alpha, java.util.BitSet pixels) {
        this(left, top, width, height, tones, alpha, pixels, pixels, null);
    }
    Clipboard(int left, int top, int width, int height, byte[] tones, byte[] alpha, java.util.BitSet pixels,
             java.util.BitSet region, float[][] outline) {
        this.left = left; this.top = top; this.width = width; this.height = height;
        this.tones = tones; this.alpha = alpha; this.pixels = pixels; this.region = region; this.outline = outline;
    }

    boolean empty() { return pixels.cardinality() == 0; }

    /** This patch's selection once it is drawn {@code dx} by {@code dy} pixels away: the whole
     * area it covers, not only the pixels that hold paint. */
    Selection selectionMovedBy(int dx, int dy, int pageWidth, int pageHeight) {
        return movedBy(region, pixels, dx, dy, pageWidth, pageHeight);
    }

    /** This patch's pixels once it is drawn {@code dx} by {@code dy} pixels away on the page. */
    Selection movedBy(int dx, int dy, int pageWidth, int pageHeight) {
        return movedBy(pixels, null, dx, dy, pageWidth, pageHeight);
    }

    /**
     * The selection covering {@code source} after the patch moves, remembering {@code owned}
     * as the pixels that travel with it so the next move cannot pick up other artwork.
     */
    private Selection movedBy(java.util.BitSet source, java.util.BitSet ownedSource,
                             int dx, int dy, int pageWidth, int pageHeight) {
        java.util.BitSet moved = new java.util.BitSet(pageWidth * pageHeight);
        java.util.BitSet owned = ownedSource == null ? null : new java.util.BitSet(pageWidth * pageHeight);
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
            int at = row * width + col;
            if (!source.get(at)) continue;
            int x = left + col + dx, y = top + row + dy;
            if (x < 0 || y < 0 || x >= pageWidth || y >= pageHeight) continue;
            moved.set(y * pageWidth + x);
            if (owned != null && ownedSource.get(at)) owned.set(y * pageWidth + x);
        }
        Selection selection = Selection.of(moved, pageWidth);
        float[][] at = null;
        if (outline != null && !selection.empty()) {
            at = new float[outline.length][];
            for (int i = 0; i < outline.length; i++) {
                at[i] = outline[i].clone();
                for (int j = 0; j < at[i].length; j += 2) { at[i][j] += dx; at[i][j+1] += dy; }
            }
        }
        return new Selection(selection.pixels, selection.left, selection.top, selection.right,
                selection.bottom, selection.stride, at, owned);
    }
}
