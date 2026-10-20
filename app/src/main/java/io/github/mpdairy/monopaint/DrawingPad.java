package io.github.mpdairy.monopaint;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

/**
 * The canvas. It renders the current page into a page-sized raster ({@link #display}),
 * or a screen-sized one while zoomed ({@link #viewportBitmap}), and presents changed
 * pixels straight to the e-ink panel through {@link DirectEink} when available.
 *
 * <p>Pen input runs one of these gestures at a time:
 * <ul>
 * <li>a {@link DrawingStroke} for ordinary tools ({@link ToolStrokes});</li>
 * <li>a live {@link ShapePreview} for Shapes, committed at pen-up;</li>
 * <li>a fill: tap for a flood fill, or drag a gradient axis and then pick its second color;</li>
 * <li>eyedropper sampling while the color picker is active.</li>
 * </ul>
 * Two fingers pinch and pan when navigation is unlocked. Wet paint keeps blending in
 * idle time between input events, except under a watercolor brush, which waits for pen-up.
 */
@SuppressLint("ViewConstructor")
final class DrawingPad extends View {
    private final PaintActivity app;
    /** See {@link Device#landscapeTilt}. */
    private final boolean landscapeTilt;
    ToneDocument document;
    private Bitmap display;
    private ViewportBitmap viewportBitmap;
    final CanvasViewport viewport = new CanvasViewport();
    final Matrix pageToView = new Matrix();
    private final Matrix viewToPage = new Matrix();
    private final float[] samplePoint = new float[2];
    private boolean unscaledPage = true;
    private int pageRotation = Surface.ROTATION_0;
    private int[] renderPixels = new int[0];
    /** Changed presented pixels awaiting presentation, as separate patches. */
    private final DirtyRegions pending = new DirtyRegions(32);
    private final Rect presenting = new Rect();
    private DirectEink direct;
    /** Nomad's fast black/white session for moving previews; see {@link #beginFastPreview}. */
    private DirectEink previewDirect;
    /** Pixels {@link #previewDirect} showed, re-presented through {@link #direct} when the preview ends. */
    private final Rect previewArea = new Rect();
    /** Panel area of closed controls' fast ink, presented again through {@link #direct}; see {@link #eraseFastInk}. */
    private final RectF fastInk = new RectF();
    private int fastInkRefreshes;
    private final NativePen input;
    private int pointer = -1, retries;
    private long lastPresent;
    private boolean fallbackNotice;
    private int drawCount;
    private int pagePresentCount;
    private final Runnable retry = () -> flush(true);

    // Strokes
    private DrawingStroke stroke;

    // Wet paint
    WetWatercolor wet;
    private boolean wetChanged, wetScheduled;
    private final WetWorkBudget wetBudget = new WetWorkBudget();
    private final android.os.MessageQueue wetQueue = android.os.Looper.getMainLooper().getQueue();
    private final android.os.MessageQueue.IdleHandler wetIdle = () -> { advanceWet(); return false; };
    private final Runnable wetStep = () -> wetQueue.addIdleHandler(wetIdle);
    private long lastWetComputeNanos, lastWetRenderNanos, lastWetPresentNanos, wetMaxSliceNanos;
    /** When running wet paint last reached the panel; flowing water shows itself at most every {@link #WET_SHOW_MS}. */
    private long lastWetShown;
    private static final int WET_SHOW_MS = 120;
    /** When the wet paint's current frame began, to pace flowing water. */
    private long wetFrameStarted;
    private int wetSliceCount;

    // Shapes
    private ShapePreview shapeStroke;
    private float shapeX, shapeY;
    /** This gesture's shape or flat fill is laid as a watercolor wash: see {@link WatercolorWash}. */
    private boolean washing;

    // Moving previews: a shape or the gradient guide, drawn once per display frame
    private boolean previewFrameScheduled;
    private long lastPreviewFrame;
    private int previewFrameCount;
    private final Runnable previewFrame = this::drawPreviewFrame;

    // Fills, gradients and the eyedropper
    FloodFill fill;
    private FloodFill gradientFill;
    boolean fillGesture, gradientWaiting, gradientCommit;
    private boolean gradientReady;
    private int gradientShade, gradientPassShade, pickPointer = -1;
    /** The composite before a gradient preview, so the eyedropper never samples the preview itself. */
    private byte[] gradientSample;
    private float fillStartX, fillStartY, fillEndX, fillEndY;
    private int fillShade, fillOriginalGray, fillTolerance;
    private ToolSettings.Gradient fillGradient = ToolSettings.Gradient.LINEAR;
    private final Paint gradientGuide = new Paint();
    /** The gradient guide's saved pixels, drawn through the shared overlay helper. */
    private final Overlay gradientGuideOverlay;
    private final Runnable gradientStep = this::advanceGradient;
    private final Runnable fillStep = this::advanceFill;

    // Selection
    /** Pixels copied out of a layer, ready to paste or drag to a new place. */
    Clipboard clipboard;
    /** The pen is tracing a freehand selection. */
    private boolean selectGesture;
    /** The pen is dragging the selection or the copy attached to the pen. */
    private boolean moveGesture;
    private float[] trace = new float[512];
    private int tracePoints;
    /**
     * The offset the copy had when the drag began, and the press point it is measured from.
     * Keeping the anchor fixed and rounding once from it matters: adding a rounded delta per
     * event throws away each event's fraction, and over a long drag those fractions add up
     * until the copy lags noticeably behind the pen.
     */
    private int moveBaseX, moveBaseY;
    private float moveAnchorX, moveAnchorY;
    /** The selection's marching border, drawn over the presented raster. */
    private final Overlay selectionBorder;
    /** A region fill started by the selection tool rather than the paint fill. */
    private boolean selectingRegion;
    /** The picking mode to go back to when a Move gesture lets the selection go. */
    private ToolSettings.SelectMode pickMode = ToolSettings.SelectMode.FREEHAND;

    // Finger taps and pinch/pan
    private final MultiFingerTap fingerTaps = new MultiFingerTap();
    private int fingerA = -1, fingerB = -1;
    boolean navigating, touchBlocked;
    private boolean navigationFrameScheduled;
    private long lastNavigationFrame, lastNavigationRasterNanos, lastNavigationPresentNanos;
    private int navigationFrameCount;
    private final Runnable navigationFrame = this::drawNavigationFrame;
    private float fingerX, fingerY, fingerSpan;
    /** Fingers are ignored until this time after the pen was last near the screen. */
    private long penGuardUntil;

    // Dragging the reference image with one finger
    private int referencePointer = -1;
    private float referenceLastX, referenceLastY;
    private boolean referenceDragStarted;
    /** Presented pixels the rotate/scale outline is drawn over; empty when it is not shown. */
    private final Overlay outlineOverlay;
    private float[] outlineTransform;

    DrawingPad(PaintActivity app) {
        super(app); this.app = app; landscapeTilt = app.device.landscapeTilt;
        selectionBorder = new Overlay(app);
        gradientGuideOverlay = new Overlay(app);
        outlineOverlay = new Overlay(app);
        setContentDescription("Painting canvas; use the pen to paint. Unlock Zoom to pinch and pan with two fingers.");
        input = new NativePen(this, (bitmap, region) -> bitmap.recycle());
    }
    private int dp(float value) { return app.dp(value); }
    private static boolean pointerUp(MotionEvent event, int pointer) {
        int action = event.getActionMasked();
        return (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                && event.getPointerId(event.getActionIndex()) == pointer;
    }
    /** Page size for a new blank page that fills this view in the current orientation. */
    private ToneDocument blankPage() {
        return new ToneDocument(app.landscape ? getHeight() : getWidth(), app.landscape ? getWidth() : getHeight());
    }

    // Pages and rasters

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        finishStroke(); disconnectDisplay();
        if (w <= 0 || h <= 0) return;
        if (!app.loading && document == null) {
            document = blankPage();
            app.book = new DrawingBook(document); app.updatePages();
        }
        ensureDisplay(); updateViewport();
        renderAll();
    }
    private void ensureDisplay() {
        int width = document != null ? document.width : app.landscape ? getHeight() : getWidth();
        int height = document != null ? document.height : app.landscape ? getWidth() : getHeight();
        if (width <= 0 || height <= 0) return;
        if (display != null && display.getWidth() == width && display.getHeight() == height) return;
        if (display != null) display.recycle();
        display = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        display.setHasAlpha(false);
    }
    /** Recomputes the page-to-view transform for the app rotation and zoom. */
    void updateViewport() {
        if (display == null || getWidth() <= 0 || getHeight() <= 0) return;
        int width = display.getWidth(), height = display.getHeight();
        // A turn opens the page afresh, so wait for the canvas's turned size: deciding on the
        // old one would spend the opening and fit the page instead of showing it at 100%.
        // The orientation frame updates the viewport again once the turned layout is done.
        if (pageRotation != app.appRotation && isLayoutRequested()) return;
        if (pageRotation != app.appRotation) viewport.reset();
        pageRotation = app.appRotation;
        boolean swapped = pageRotation == Surface.ROTATION_90 || pageRotation == Surface.ROTATION_270;
        viewport.configure(getWidth(), getHeight(), swapped ? height : width, swapped ? width : height);
        // An expanded page fills the screen; open it at 100% under the bars, not shrunk beside them.
        if (viewport.opening() && viewport.fitScale < 1 && homeOnScreen()) viewport.openActualSize(app.prefs.toolboxRight());
        float scale = viewport.scale();
        place(pageToView, scale, viewport.x, viewport.y);
        pageToView.invert(viewToPage);
        unscaledPage = pageRotation == Surface.ROTATION_0 && pageToView.isIdentity();
        boolean wasScaled = viewportBitmap != null;
        // Keep one screen-sized raster and presenter throughout a pinch, including
        // its fitted endpoints. Changing format on each crossing stalls the gesture.
        boolean scaled = navigating || viewport.zoom != 1 || scale != 1 || viewport.x != 0 || viewport.y != 0;
        if (viewportBitmap != null && (!scaled || viewportBitmap.bitmap.getWidth() != getWidth()
                || viewportBitmap.bitmap.getHeight() != getHeight())) { viewportBitmap.close(); viewportBitmap = null; }
        if (scaled && viewportBitmap == null) viewportBitmap = new ViewportBitmap(getWidth(), getHeight());
        if (wasScaled != scaled) renderAll();
        else if (viewportBitmap != null) { viewportBitmap.update(display, pageToView, null); redrawGradientGuide(); }
        pending.clear(); app.toolbar.refreshZoom();
    }
    private void place(Matrix transform, float scale, float x, float y) {
        int width = display.getWidth(), height = display.getHeight();
        transform.reset();
        // Cancel our own UI rotation for the artwork; Android stays in portrait.
        if (pageRotation == Surface.ROTATION_90) { transform.setRotate(-90); transform.postTranslate(0, width); }
        else if (pageRotation == Surface.ROTATION_180) { transform.setRotate(180); transform.postTranslate(width, height); }
        else if (pageRotation == Surface.ROTATION_270) { transform.setRotate(90); transform.postTranslate(height, 0); }
        transform.postScale(scale, scale);
        transform.postTranslate(x, y);
    }
    /** The page at 100%, where its artwork sat when the page began; see {@link #actualSize}. */
    Matrix homeToView() {
        Matrix home = new Matrix();
        place(home, 1, viewport.homeX(app.prefs.toolboxRight()), viewport.homeY());
        return home;
    }
    /** The whole screen, bars included, in this view's coordinates. */
    RectF screenArea() {
        RectF pad = Popups.bounds(app.root, this);
        return new RectF(-pad.left, -pad.top, app.root.getWidth() - pad.left, app.root.getHeight() - pad.top);
    }
    /** Whether the page at 100% in its home placement stays on the screen. */
    private boolean homeOnScreen() {
        RectF page = new RectF(0, 0, display.getWidth(), display.getHeight()); homeToView().mapRect(page);
        RectF screen = screenArea(); screen.inset(-0.5f, -0.5f);
        return screen.contains(page);
    }
    private void viewportChanged() {
        disconnectDisplay(); updateViewport(); invalidate();
        if (!navigating) post(this::connectDisplay);
    }
    void zoomBy(float factor) {
        viewport.gesture(factor, getWidth()/2f, getHeight()/2f, getWidth()/2f, getHeight()/2f);
        viewportChanged();
    }
    void fitPage() { endNavigation(); viewport.reset(); viewportChanged(); }
    /** Reset scale and pan together, returning the artwork to where the page began. */
    void actualSize() { endNavigation(); viewport.actualSize(app.prefs.toolboxRight()); viewportChanged(); }
    /** Starts a new book from this page, or from a blank page when null. */
    void replace(ToneDocument replacement) {
        suspend();
        viewport.reset();
        document = replacement;
        if (document == null && getWidth() > 0 && getHeight() > 0) document = blankPage();
        app.book = document == null ? null : new DrawingBook(document); app.updatePages();
        ensureDisplay(); updateViewport();
        renderAll(); invalidate(); post(this::connectDisplay); post(app.fullscreen::pageChanged);
    }
    /** Shows another page of the current book, presenting it directly when possible. */
    void showPage(ToneDocument page) {
        suspend();
        PageTurnDisplay turn = null;
        // Same-sized fitted pages share a transform. Snapshot the old panel
        // background before rendering the next page, even with Pages open.
        if (document != null && display != null && page.width == document.width && page.height == document.height
                && viewport.untouched() && app.resumed && hasWindowFocus() && !isLayoutRequested()
                && !app.root.isLayoutRequested() && !app.previewFrame.isLayoutRequested()
                && app.rotateButton.getVisibility() != View.VISIBLE) {
            try { turn = new PageTurnDisplay(this, presented(), viewportBitmap == null ? pageToView : new Matrix(), app.pagePanel); }
            catch (RuntimeException | LinkageError error) { Log.w(ProbeActivity.TAG, "Page update uses Android display", error); }
        }
        boolean presented = false;
        try {
            document = page; viewport.reset(); ensureDisplay(); updateViewport(); renderAll();
            if (turn != null) {
                try { presented = turn.present(presented()); }
                catch (RuntimeException error) { Log.w(ProbeActivity.TAG, "Page update uses Android display", error); }
            }
        } finally { if (turn != null) turn.close(); }
        if (presented) {
            pagePresentCount++;
            app.selectionFeedback.retainForNextDraw(this, new Rect(0, 0, getWidth(), getHeight()));
        } else invalidate();
        post(this::connectDisplay);
    }
    /** The raster shown on screen: the page itself, or the zoomed view of it. */
    private Bitmap presented() { return viewportBitmap == null ? display : viewportBitmap.bitmap; }
    private void renderAll() {
        if (display == null) return;
        display.eraseColor(Color.WHITE);
        if (document != null) {
            ViewportBitmap.compose(document, display, viewportBitmap == null);
            drawMask();
            document.clearDirty();
        }
        pending.clear();
        if (viewportBitmap != null) viewportBitmap.update(display, pageToView, null);
        redrawOverlays();
    }
    /** Put the selection border and gradient guide back after a re-render replaced the raster under them. */
    private void redrawOverlays() {
        redrawGradientGuide();
        redrawReferenceOutline();
        // The rendered pixels are fresh; drop the border's saved background rather than
        // restore it over the artwork just drawn.
        selectionBorder.discard();
        if (!moveGesture) drawSelectionBorder();
    }
    /** Draws the masking fluid's hatching over the freshly composed page. */
    private void drawMask() {
        if (document.hasMask()) drawMask(new Rect(0, 0, document.width, document.height));
    }
    /** The mask hatch limited to {@code area}, so a reference band costs only its own pixels. */
    private void drawMask(Rect area) {
        if (!document.hasMask()) return;
        int hatch = ToneDocument.HATCH;
        int left = Math.max(0, area.left), right = Math.min(document.width, area.right);
        for (int y = Math.max(0, area.top); y < Math.min(document.height, area.bottom); y++) {
            // Each row's lines start where x + y is a multiple of the spacing.
            int first = left - ((left + y) % hatch);
            if (first > left) first -= hatch;
            for (int start = first; start < right; start += hatch)
                for (int x = Math.max(left, start); x < Math.min(right, start + ToneDocument.HATCH_LINE); x++) {
                    int line = document.maskHatch(x, y);
                    if (line >= 0) display.setPixel(x, y, line == 0 ? Color.BLACK : Color.WHITE);
                }
        }
    }
    /** Rebuild size-dependent display resources after canvas expansion or its undo. */
    void canvasResized() {
        disconnectDisplay(); ensureDisplay(); updateViewport(); renderAll(); invalidate(); post(this::connectDisplay);
    }
    boolean blocksFingerGesture() {
        return pointer!=-1 || pickPointer!=-1 || fillGesture || selectGesture || moveGesture || hasGradient()
                || SystemClock.uptimeMillis()<penGuardUntil;
    }
    /** Re-renders the document's changed area and queues it for presentation. */
    void renderDirty() {
        if (document == null || display == null) return;
        int[] bounds = document.dirty();
        if (bounds == null) return;
        // The guide's and the outline's saved backgrounds must not cover the newly rendered artwork.
        boolean guide = gradientGuideOverlay.showing();
        boolean outline = outlineOverlay.showing();
        boolean border = selectionBorder.showing() && !moveGesture;
        eraseGradientGuide();
        eraseReferenceOutline();
        Rect dirty = new Rect(Math.max(0, bounds[0] - 2), Math.max(0, bounds[1] - 2),
                Math.min(document.width, bounds[2] + 2), Math.min(document.height, bounds[3] + 2));
        // Only the border crossing the stroke is taken back and redrawn, so a long
        // selection border costs a few tiles per frame rather than its whole length.
        // Its saved pixels must be put back before the render, or the render's artwork
        // would be covered by pixels it already replaced.
        if (border) selectionBorder.restore(viewRect(dirty), presented(), this::queueView);
        else hideSelectionBorder();
        if (dirty.intersect(0, 0, display.getWidth(), display.getHeight())) {
            render(dirty);
            queue(dirty);
        }
        document.clearDirty();
        if (guide) drawGradientGuide();
        if (outline) drawReferenceOutline();
        if (border) touchSelectionBorder(dirty);
    }
    /** Queues page pixels that are already up to date in {@link #display}. */
    private void queue(Rect pageArea) {
        queueView(viewportBitmap == null ? pageArea : viewportBitmap.update(display, pageToView, pageArea));
    }
    private void render(Rect dirty) {
        int count = dirty.width() * dirty.height();
        if (renderPixels.length < count) renderPixels = new int[count];
        if (viewportBitmap == null) document.render(renderPixels, dirty.left, dirty.top, dirty.width(), dirty.height());
        else for (int y = dirty.top, i = 0; y < dirty.bottom; y++) for (int x = dirty.left; x < dirty.right; x++) {
            int hatch = document.maskHatch(x, y), tone = hatch >= 0 ? hatch : document.compositeTone(x, y);
            renderPixels[i++] = Color.rgb(tone, tone, tone);
        }
        display.setPixels(renderPixels, 0, dirty.width(), dirty.left, dirty.top, dirty.width(), dirty.height());
    }
    @Override protected void onDraw(Canvas canvas) {
        drawCount++;
        canvas.drawColor(Color.WHITE);
        if (viewportBitmap != null) canvas.drawBitmap(viewportBitmap.bitmap, 0, 0, null);
        else if (display != null) canvas.drawBitmap(display, pageToView, null);
    }
    private boolean showsGradientGuide() { return (fillGesture && fillGradient != ToolSettings.Gradient.FLAT) || gradientWaiting; }
    /**
     * Draws the gradient guide into the presented raster, replacing the previous one, so it
     * reaches the panel like any canvas change. Without antialiasing its pixels stay
     * black/white for {@link #beginFastPreview}.
     */
    private void drawGradientGuide() {
        Bitmap target = presented();
        if (target == null) return;
        float[] axis = {fillStartX, fillStartY, fillEndX, fillEndY};
        // The unzoomed page raster is only rotated (never scaled) relative to the view.
        if (viewportBitmap != null) pageToView.mapPoints(axis);
        gradientGuideOverlay.show(target, new float[][]{axis}, this::queueView, canvas -> {
            gradientGuide.setStyle(Paint.Style.STROKE);
            gradientGuide.setColor(Color.WHITE); gradientGuide.setStrokeWidth(dp(5));
            canvas.drawLine(axis[0], axis[1], axis[2], axis[3], gradientGuide);
            gradientGuide.setColor(Color.BLACK); gradientGuide.setStrokeWidth(dp(2));
            canvas.drawLine(axis[0], axis[1], axis[2], axis[3], gradientGuide);
            gradientGuide.setStyle(Paint.Style.FILL);
            canvas.drawCircle(axis[0], axis[1], dp(4), gradientGuide);
            canvas.drawCircle(axis[2], axis[3], dp(4), gradientGuide);
        });
    }
    /** Restores the pixels under the gradient guide and queues them. */
    private void eraseGradientGuide() { gradientGuideOverlay.hide(presented(), this::queueView); }
    /** Draws the guide again after a re-render replaced the raster under it. */
    private void redrawGradientGuide() {
        gradientGuideOverlay.discard();
        if (showsGradientGuide()) drawGradientGuide();
    }
    /** Removes the gradient guide from the screen. */
    private void clearGradientGuide() { eraseGradientGuide(); flush(true); }

    // Selection border

    /** Draws the border around the page's selection over the whole presented raster. */
    private void drawSelectionBorder() {
        Selection selection = document == null ? null : document.selection();
        if (selection == null || selection.empty()) { hideSelectionBorder(); return; }
        // A full redraw must measure the tiles the border covers; only the incremental
        // touch below can reuse the measurement.
        showSelection(selectionBorder, toView(selection.border()), clipboard == null ? null : this::paintCopy);
    }
    /** Redraws only the part of the selection border inside a page area, over the artwork just rendered there. */
    private void touchSelectionBorder(Rect pageArea) {
        selectionBorder.rearm(presented(), viewRect(pageArea), this::queueView, this::selectionInkNow);
    }
    /**
     * The border and, while a copy is held, the copy's own pixels: the ghost shows what
     * will be placed, not just an empty outline. Nothing is drawn when the page has no
     * selection, which leaves the tiles the overlay saved already restored.
     */
    private void selectionInkNow(Canvas canvas) {
        Selection selection = document == null ? null : document.selection();
        if (selection == null || selection.empty()) return;
        if (clipboard != null) paintCopy(canvas);
        selectionInk(canvas, toView(selection.border()));
    }
    /** A page rectangle in the coordinates of the presented raster. */
    private Rect viewRect(Rect pageArea) {
        if (viewportBitmap == null) return new Rect(pageArea);
        float[] corners = { pageArea.left, pageArea.top, pageArea.right, pageArea.bottom };
        pageToView.mapPoints(corners);
        return new Rect((int)Math.floor(Math.min(corners[0], corners[2])), (int)Math.floor(Math.min(corners[1], corners[3])),
                (int)Math.ceil(Math.max(corners[0], corners[2])), (int)Math.ceil(Math.max(corners[1], corners[3])));
    }
    /** Page-coordinate polylines as view-coordinate ones, for when the page is zoomed.
     * Cached per selection and viewport: a lasso's outline is thousands of points and
     * the border is redrawn on every paint frame. */
    private float[][] viewLines;
    private float[][] viewLinesSource;
    private android.graphics.Matrix viewLinesMatrix;
    private float[][] toView(float[][] lines) {
        if (viewportBitmap == null) return lines;
        if (viewLines != null && viewLinesSource == lines && viewLinesMatrix != null && viewLinesMatrix.equals(pageToView))
            return viewLines;
        float[][] at = new float[lines.length][];
        for (int i = 0; i < lines.length; i++) {
            at[i] = lines[i].clone();
            pageToView.mapPoints(at[i]);
        }
        viewLines = at; viewLinesSource = lines; viewLinesMatrix = new android.graphics.Matrix(pageToView);
        return at;
    }
    /**
     * The selection's outline as a dashed black line over a solid white halo, so it
     * reads on any artwork.
     */
    private void showSelection(Overlay overlay, float[][] lines) { showSelection(overlay, lines, null); }
    /** {@code under} paints the held copy inside the border, so a cut's ghost is not blank. */
    private void showSelection(Overlay overlay, float[][] lines, Overlay.Ink under) {
        overlay.show(presented(), lines, under == null ? null : copyBox(), this::queueView, canvas -> {
            if (under != null) under.draw(canvas);
            selectionInk(canvas, lines);
        });
    }
    /**
     * The dashed selection line over a solid white halo, so it reads on any artwork.
     *
     * <p>Only the stretches of border that fall inside the canvas clip are stroked. Skia
     * charges for the path it is handed, not for the part of the canvas that survives the
     * clip, so stroking a whole lasso to repaint one brush stroke's corner pays for the
     * lasso's full length on every frame of that stroke. That is what made drawing inside
     * a big selection slower than drawing outside one. Culling keeps a stroke frame's
     * border cost flat however large the selection is.</p>
     *
     * <p>Each stretch restarts the dash pattern at its own distance along the border, so
     * the dashes stay pinned to the page instead of sliding along it as the clip follows
     * the pen. Halos all go down before any dash, as they did when the border was one
     * path, so overlapping stretches of border look the same as before.</p>
     */
    private void selectionInk(Canvas canvas, float[][] lines) {
        Rect clip = canvas.getClipBounds();
        // Far enough past the halo's widest join that no cut end or miter spike of a
        // dropped stretch can reach into the clip and leave a nick in the line.
        float reach = dp(12);
        java.util.List<BorderRun> runs = new java.util.ArrayList<>();
        for (float[] line : lines) {
            int i = 0;
            double arc = 0;
            while (i + 3 < line.length) {
                while (i + 3 < line.length && !meets(line, i, clip, reach)) { arc += span(line, i); i += 2; }
                if (i + 3 >= line.length) break;
                android.graphics.Path run = new android.graphics.Path();
                run.moveTo(line[i], line[i + 1]);
                double start = arc;
                while (i + 3 < line.length && meets(line, i, clip, reach)) {
                    run.lineTo(line[i + 2], line[i + 3]);
                    arc += span(line, i);
                    i += 2;
                }
                runs.add(new BorderRun(run, start));
            }
        }
        selectionHalo.setStyle(Paint.Style.STROKE);
        selectionHalo.setColor(Color.WHITE); selectionHalo.setStrokeWidth(dp(4));
        for (BorderRun run : runs) canvas.drawPath(run.path, selectionHalo);
        selectionDash.setStyle(Paint.Style.STROKE);
        selectionDash.setColor(Color.BLACK); selectionDash.setStrokeWidth(dp(2));
        for (BorderRun run : runs) {
            selectionDash.setPathEffect(dashAt(run.phase));
            canvas.drawPath(run.path, selectionDash);
        }
    }
    /** One stretch of the selection border inside the clip, and how far along the border it starts. */
    private static final class BorderRun {
        final android.graphics.Path path; final double phase;
        BorderRun(android.graphics.Path path, double phase) { this.path = path; this.phase = phase; }
    }
    /** Whether segment `i` of the polyline comes within `reach` of the clip. */
    private static boolean meets(float[] line, int i, Rect clip, float reach) {
        return Math.min(line[i], line[i + 2]) < clip.right + reach
            && Math.max(line[i], line[i + 2]) > clip.left - reach
            && Math.min(line[i + 1], line[i + 3]) < clip.bottom + reach
            && Math.max(line[i + 1], line[i + 3]) > clip.top - reach;
    }
    private static double span(float[] line, int i) {
        return Math.hypot(line[i + 2] - line[i], line[i + 3] - line[i + 1]);
    }
    private final Paint selectionHalo = new Paint();
    private final Paint selectionDash = new Paint();
    private float[] dashIntervals;
    /** The dash pattern shifted to where this stretch starts along the border, so dashes stay pinned to the page. */
    private DashPathEffect dashAt(double arc) {
        if (dashIntervals == null) dashIntervals = new float[]{ dp(6), dp(8) };
        float cycle = dashIntervals[0] + dashIntervals[1];
        return new DashPathEffect(dashIntervals, (float) (arc % cycle));
    }
    /** The view-space box the held copy would cover at its current offset, or null. */
    private Rect copyBox() {
        if (clipboard == null || clipboard.empty()) return null;
        float[] corners = { clipboard.left + moveDx, clipboard.top + moveDy,
                clipboard.left + moveDx + clipboard.width, clipboard.top + moveDy + clipboard.height };
        if (viewportBitmap != null) pageToView.mapPoints(corners);
        return new Rect((int)Math.floor(Math.min(corners[0], corners[2])), (int)Math.floor(Math.min(corners[1], corners[3])),
                (int)Math.ceil(Math.max(corners[0], corners[2])), (int)Math.ceil(Math.max(corners[1], corners[3])));
    }
    /**
     * Draw the held copy's pixels into the presented raster at the current drag offset.
     * The bitmap is built once per copy and reused: rebuilding it every frame allocates
     * the whole patch again on each stroke frame, which makes drawing crawl.
     */
    private Bitmap copyBitmap;
    private Clipboard copyBitmapSource;   // the clipboard this bitmap was built from
    private void paintCopy(Canvas canvas) {
        // Before the first tap places the copy it has no home on the page. Inking it at its
        // original spot would redraw the very pixels a cut just took off, leaving the page
        // looking untouched. The outline alone shows where the copy came from until then.
        if (clipboard == null || clipboard.empty() || !copyPlaced) return;
        if (copyBitmap == null || copyBitmapSource != clipboard) {
            int[] px = new int[clipboard.width * clipboard.height];
            for (int i = 0; i < px.length; i++) {
                int a = clipboard.pixels.get(i) ? clipboard.alpha[i] & 255 : 0;
                int t = clipboard.tones[i] & 255;
                // ARGB bitmaps are premultiplied: the tone must be scaled by its own alpha.
                int c = (t * a + 127) / 255;
                px[i] = (a << 24) | (c << 16) | (c << 8) | c;
            }
            if (copyBitmap != null) copyBitmap.recycle();
            copyBitmap = Bitmap.createBitmap(px, clipboard.width, clipboard.height, Bitmap.Config.ARGB_8888);
            copyBitmapSource = clipboard;
        }
        // The bitmap is patch-local: pixel 0,0 is the patch's own left,top on the page.
        // Translate by that origin plus the drag in page space, before the page-to-view
        // rotation. With no viewport bitmap the raster the canvas draws into IS the page,
        // so applying the rotation here would spin the copy off in the wrong direction.
        Matrix at = viewportBitmap == null ? new Matrix() : new Matrix(pageToView);
        at.preTranslate(clipboard.left + moveDx, clipboard.top + moveDy);
        if (copyPaint == null) {
            copyPaint = new Paint();
            copyPaint.setFilterBitmap(false); // keep the pixels as hard squares, like the page itself
        }
        canvas.drawBitmap(copyBitmap, at, copyPaint);
    }
    private Paint copyPaint;
    /** Restores the pixels under the border and queues them, so the dashes leave the panel. */
    private void hideSelectionBorder() { selectionBorder.hide(presented(), this::queueView); }
    /** Redraws the border over the current pixels, for example after a layer change. */
    void refreshSelectionBorder() {
        if (moveGesture) return;
        hideSelectionBorder();
        drawSelectionBorder();
        flush(true);
    }

    /** Re-renders what the last reference change dirtied, limited to its band. */
    void renderReferenceChange() {
        renderReferenceBand(document.dirty());
    }
    /**
     * Composite only the changed band. A reference drag dirties a band, not the page;
     * compositing the whole page through the per-pixel path costs ~600 ms per finger move
     * where the rect-limited native composite is ~50 ms on the Manta.
     */
    private void renderReferenceBand(int[] bounds) {
        if (document == null || display == null || bounds == null) return;
        // The gradient guide's saved background and the zoomed raster need the slow path.
        if (gradientGuideOverlay.showing() || viewportBitmap != null) { renderDirty(); return; }
        Rect area = new Rect(Math.max(0, bounds[0] - 2), Math.max(0, bounds[1] - 2),
                Math.min(document.width, bounds[2] + 2), Math.min(document.height, bounds[3] + 2));
        if (area.isEmpty()) { document.clearDirty(); return; }
        ViewportBitmap.compose(document, display, true, area);
        drawMask(area);
        document.clearDirty();
        queue(area);
    }

    /**
     * One finger drags the reference image around under the paint. Starts only when the
     * touch lands on the picture and nothing else owns the gesture.
     */
    private boolean referenceDrag(MotionEvent event) {
        if (referencePointer == -1) {
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN || event.getPointerCount() != 1) return false;
            if (!Ui.fingersOnly(event) || !document.hasReference() || document.referenceLocked()) return false;
            if (blocksFingerGesture()) return false;
            pagePoint(event.getX(0), event.getY(0));
            int[] box = document.referenceBounds();
            if (samplePoint[0] < box[0] || samplePoint[0] >= box[2]
                    || samplePoint[1] < box[1] || samplePoint[1] >= box[3]) return false;
            referencePointer = event.getPointerId(0);
            referenceLastX = samplePoint[0]; referenceLastY = samplePoint[1];
            referenceDragStarted = false;
            return true;
        }
        int action = event.getActionMasked();
        int index = event.findPointerIndex(referencePointer);
        if (action == MotionEvent.ACTION_CANCEL || index < 0 || event.getPointerCount() > 1) {
            endReferenceDrag();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            pagePoint(event.getX(index), event.getY(index));
            if (!referenceDragStarted
                    && Math.hypot(samplePoint[0] - referenceLastX, samplePoint[1] - referenceLastY) < dp(10))
                return true; // a resting hand does nothing
            if (!referenceDragStarted) {
                document.beginReferenceEdit();
                referenceDragStarted = true;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            // Map both ends of the step through the view→page matrix: dividing the raw
            // delta by the scale is only correct on an upright page.
            float dx = samplePoint[0] - referenceLastX, dy = samplePoint[1] - referenceLastY;
            referenceLastX = samplePoint[0]; referenceLastY = samplePoint[1];
            document.slideReference(dx, dy);
            renderReferenceBand(document.dirty());
            flush(false);
        } else if (action == MotionEvent.ACTION_UP) {
            endReferenceDrag();
        }
        return true;
    }

    private void endReferenceDrag() {
        int pointer = referencePointer;
        referencePointer = -1;
        if (referenceDragStarted) {
            referenceDragStarted = false;
            document.endReferenceEdit();
            // The release re-rasters the exact placement; draw that frame or the screen
            // keeps showing the last whole-pixel slide step.
            renderReferenceBand(document.dirty());
            flush(true);
            app.recovery();
        }
        if (pointer != -1) getParent().requestDisallowInterceptTouchEvent(false);
    }

    /** Draws a black outline of the reference at {@code transform}, over a white halo. */
    void showReferenceOutline(float[] transform) {
        outlineTransform = transform;
        drawReferenceOutline();
        // The outline queues its own tiles; nothing else presents them during a slider
        // drag, so without this the box only appears when the gesture commits.
        flush(true);
    }
    /** The transform the outline currently marks, or null when none is shown. */
    float[] markedOutline() { return outlineTransform == null ? null : outlineTransform.clone(); }
    void clearReferenceOutline() { clearReferenceOutline(true); }
    /** @param flush present now; false when a re-render presents in this same frame. */
    void clearReferenceOutline(boolean flush) {
        outlineTransform = null;
        eraseReferenceOutline();
        if (flush) flush(true);
    }
    /** Put the outline back after a re-render replaced the raster under it. */
    void redrawReferenceOutline() {
        if (outlineTransform == null) return;
        // The rendered pixels are fresh; drop the saved background instead of restoring it over them.
        outlineOverlay.discard();
        drawReferenceOutline();
    }
    private void drawReferenceOutline() {
        if (outlineTransform == null) return;
        Bitmap target = presented();
        if (target == null) return;
        float[] corners = outlineCorners();
        // Tile along the four edges so a full-page reference damages only a band per frame.
        float[] edges = {corners[0], corners[1], corners[2], corners[3],
                corners[2], corners[3], corners[4], corners[5],
                corners[4], corners[5], corners[6], corners[7],
                corners[6], corners[7], corners[0], corners[1]};
        outlineOverlay.show(target, new float[][]{edges}, this::queueView, canvas -> {
            Paint paint = new Paint();
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.WHITE); paint.setStrokeWidth(dp(5));
            drawOutlinePath(canvas, corners, paint);
            paint.setColor(Color.BLACK); paint.setStrokeWidth(dp(2));
            drawOutlinePath(canvas, corners, paint);
        });
    }
    /** The image's four corners mapped through the candidate transform, then to view space. */
    private float[] outlineCorners() {
        ReferenceImage ref = document.layerSnapshot().layers.get(0).reference;
        float[] corners = {0, 0, ref.width, 0, ref.width, ref.height, 0, ref.height};
        float[] m = outlineTransform;
        for (int i = 0; i < 4; i++) {
            float x = corners[i * 2], y = corners[i * 2 + 1];
            corners[i * 2] = m[0] * x + m[1] * y + m[2];
            corners[i * 2 + 1] = m[3] * x + m[4] * y + m[5];
        }
        if (viewportBitmap != null) pageToView.mapPoints(corners);
        return corners;
    }
    private static void drawOutlinePath(Canvas canvas, float[] corners, Paint paint) {
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(corners[0], corners[1]);
        path.lineTo(corners[2], corners[3]);
        path.lineTo(corners[4], corners[5]);
        path.lineTo(corners[6], corners[7]);
        path.close();
        canvas.drawPath(path, paint);
    }
    /** Restore the pixels under the outline and queue them. */
    private void eraseReferenceOutline() { outlineOverlay.hide(presented(), this::queueView); }

    // Direct e-ink presentation

    /** Starts direct presentation and native pen input once nothing covers the canvas. */
    void connectDisplay() {
        scheduleWet();
        if (app.canvasCovered() || !app.resumed || !hasWindowFocus() || app.loading || display == null || direct != null) return;
        // Standard Android drawing leaves the firmware pen and e-ink interfaces untouched.
        if (DirectEink.androidDrawing()) return;
        if (isLayoutRequested() || app.root.isLayoutRequested() || app.orientationFrame.isLayoutRequested()
                || app.previewFrame.isLayoutRequested()) return;
        try {
            if (!input.prepareDocumentCanvas()) throw new IllegalStateException(input.status);
            direct = viewportBitmap == null ? DirectEink.forView(this, display, pageToView, 0, 7)
                    : DirectEink.forView(this, viewportBitmap.bitmap, new Matrix(), 0, 7);
            refreshFastInk();
        } catch (RuntimeException | LinkageError error) {
            Log.w(ProbeActivity.TAG, "Using Android drawing presentation", error);
            if (!fallbackNotice) { app.message("Fast display unavailable; using standard drawing"); fallbackNotice = true; }
        }
    }
    void disconnectDisplay() {
        cancelWetCallback();
        removeCallbacks(retry);
        if (previewDirect != null) { previewDirect.close(); previewDirect = null; previewArea.setEmpty(); }
        if (direct != null) { direct.close(); direct = null; }
        input.disable();
    }
    void present() { flush(true); }
    /**
     * Removes fast ink a closed popup left over the canvas. The Nomad keeps pen-plane
     * (mode 9) pixels through Android redraws and panel refreshes, so blank that plane
     * here and show the canvas again through the gray session once it is connected.
     */
    void eraseFastInk(RectF panelArea) {
        if (panelArea.isEmpty()) return;
        Rect area = new Rect(); panelArea.roundOut(area);
        Bitmap white = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
        white.eraseColor(Color.WHITE);
        DirectEink blank = null;
        try {
            Matrix toPanel = new Matrix(); toPanel.setTranslate(area.left, area.top);
            blank = DirectEink.forPanel(white, toPanel, 1, 9);
            blank.refresh(white, new Rect(0, 0, area.width(), area.height()));
        } catch (RuntimeException | LinkageError error) {
            Log.w(ProbeActivity.TAG, "Fast ink stays until the canvas redraws", error);
        } finally {
            if (blank != null) blank.close();
            white.recycle();
        }
        fastInk.union(panelArea);
        refreshFastInk();
    }
    private void refreshFastInk() {
        if (fastInk.isEmpty() || direct == null) return;
        Matrix toPanel = new Matrix(viewportBitmap == null ? pageToView : new Matrix());
        toPanel.postConcat(PanelCoordinates.fromView(this));
        Matrix fromPanel = new Matrix();
        if (!toPanel.invert(fromPanel)) return;
        RectF mapped = new RectF(fastInk); fromPanel.mapRect(mapped);
        Rect area = new Rect(); mapped.roundOut(area);
        Bitmap source = presented();
        if (!area.intersect(0, 0, source.getWidth(), source.getHeight())) { fastInk.setEmpty(); return; }
        flush(true);
        // Forced: the software pixels are unchanged, only the panel's are stale.
        if (direct.refresh(source, area) < 0) { postDelayed(this::refreshFastInk, 8); return; }
        fastInk.setEmpty(); fastInkRefreshes++;
    }
    /**
     * Presents a moving preview (a shape or the gradient guide) through the Nomad's fast
     * black/white pen path, like the color bar. Gray-mode frames queue on the Nomad, so a
     * preview there trails the pen and leaves copies behind. The canvas raster is binary
     * dots throughout. Fast pixels do not survive a panel refresh, so
     * {@link #endFastPreview} presents the final pixels through {@link #direct}.
     */
    private void beginFastPreview() {
        if (direct == null || previewDirect != null || !DirectEink.fastBinaryControls()) return;
        flush(true);
        try { previewDirect = DirectEink.forView(this, presented(), viewportBitmap == null ? pageToView : new Matrix(), 1, 9); }
        catch (RuntimeException | LinkageError error) { Log.w(ProbeActivity.TAG, "Previews use gray display", error); }
    }
    private void endFastPreview() {
        if (previewDirect == null) return;
        flush(true); closeFastPreview(); flush(true);
    }
    private void queueView(Rect r) { pending.add(r.left, r.top, r.right, r.bottom); }
    /** Returns to the gray session, queueing everything the fast one showed. */
    private void closeFastPreview() {
        previewDirect.close(); previewDirect = null;
        queueView(previewArea); previewArea.setEmpty();
    }
    /** Finishes the gesture, sets wet paint and releases the panel, e.g. before a panel covers the canvas. */
    void suspend() { finishStroke(); dryWet(); disconnectDisplay(); }
    private void flush(boolean force) {
        if (pending.isEmpty()) return;
        long elapsed = SystemClock.uptimeMillis() - lastPresent;
        if (!force && elapsed < 8) {
            // A pressure-only event may be the last event for a while.
            // Flush its pixels when the coalescing window ends.
            removeCallbacks(retry);
            postDelayed(retry, 8 - elapsed);
            return;
        }
        removeCallbacks(retry);
        if (direct == null) { invalidate(); pending.clear(); return; }
        DirectEink target = previewDirect != null ? previewDirect : direct;
        try {
            while (!pending.isEmpty()) {
                int[] patch = pending.first();
                presenting.set(patch[0], patch[1], patch[2], patch[3]);
                int result = target.present(presented(), presenting);
                lastPresent = SystemClock.uptimeMillis();
                if (result < 0) {
                    if (++retries < 120) { postDelayed(retry, 8); return; }
                    throw new IllegalStateException("Display remained busy");
                }
                if (target == previewDirect) previewArea.union(presenting);
                pending.removeFirst(); retries = 0;
            }
        } catch (RuntimeException error) {
            if (target == previewDirect) {
                // Keep the canvas on the gray session; only the preview loses its speed.
                Log.w(ProbeActivity.TAG, "Fast preview failed", error);
                closeFastPreview(); retries = 0; flush(true); return;
            }
            Log.e(ProbeActivity.TAG, "Direct display failed", error);
            disconnectDisplay(); invalidate(); pending.clear();
            app.saveError = "Fast display stopped; painting is kept. Reopen the app to retry.";
        }
    }

    // Input

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (app.paletteEditor != null || app.loading || document == null || fill != null || gradientCommit
                || !app.resumed || !hasWindowFocus()) return true;
        if (historyTap(event)) return true;
        if (referenceDrag(event)) return true;
        if (navigationGesture(event)) return true;
        if (app.pickingShade || pickPointer != -1) { sampleShadeGesture(event); return true; }
        if (hasGradient()) return true;
        if (moveGesture) { continueMove(event); return true; }
        if (selectGesture) { continueSelectGesture(event); return true; }
        if (fillGesture) { continueFillGesture(event); return true; }
        if (shapeStroke != null) { continueShape(event); return true; }
        long inputStart = System.nanoTime();
        long eventAge = Math.max(0, SystemClock.uptimeMillis() - event.getEventTime());
        boolean drawingInput = false;
        int action = event.getActionMasked(), index = event.getActionIndex();
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                && pointer == -1 && isPen(event, index)) {
            if (!penDown(event, index)) return true;
            drawingInput = stroke != null;
            if (!drawingInput) return true;
        } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_UP) {
            int p = event.findPointerIndex(pointer);
            if (p >= 0 && stroke != null && (action == MotionEvent.ACTION_MOVE || event.getPointerId(index) == pointer)) {
                drawingInput = true;
                for (int h = 0; h < event.getHistorySize(); h++) sampleEvent(event, p, h);
                if (action == MotionEvent.ACTION_MOVE) sampleEvent(event, p, -1);
            }
        }
        renderDirty(); flush(false);
        if (drawingInput) wetBudget.input(System.nanoTime() - inputStart, eventAge, SystemClock.uptimeMillis());
        scheduleWet();
        if (action == MotionEvent.ACTION_CANCEL || pointerUp(event, pointer)) finishStroke();
        return true;
    }
    /**
     * Starts the selected tool's gesture at a pen-down inside the page.
     * @return false if the pen-down was refused
     */
    private boolean penDown(MotionEvent event, int index) {
        pagePoint(event.getX(index), event.getY(index));
        if (samplePoint[0] < 0 || samplePoint[1] < 0
                || samplePoint[0] >= document.width || samplePoint[1] >= document.height) return false;
        PaintState paint = app.paint;
        // Masking fluid lies over every layer, hidden ones too.
        boolean fluid = paint.maskingFluid;
        if (!fluid && !document.layerVisible(document.activeLayer())) {
            app.message("This layer is hidden. Open Layers to show it or select another layer."); return false;
        }
        // A stylus's eraser end, or a stroke begun with its side button held, erases with
        // the Eraser tool, whatever tool is selected.
        boolean penEraser = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
                || (event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0;
        ToolSettings settings = penEraser ? app.library.builtin(ToolSettings.Tool.ERASER) : app.library.current();
        boolean erasing = paint.eraseMode && settings.supportsEraseMode();
        // Brushes paint into a wet canvas; the brush pens, and brushes, shapes and fills in
        // watercolor mode, bring their own water, which on a dry canvas wets only where it lands.
        // Masking fluid is laid dry, opaque and in full.
        boolean watercolor = !erasing && !fluid && paint.transparentPaint && paint.watercolor;
        washing = watercolor && (settings.tool == ToolSettings.Tool.SHAPES || settings.tool == ToolSettings.Tool.FILL);
        boolean wets = !erasing && !fluid && (settings.tool.water || washing
                || settings.isBrush() && (paint.wetCanvas || watercolor));
        if (!wets || wet != null && wet.coversCanvas() != paint.wetCanvas) dryWet();
        if (wets) wetPaint();
        document.paintMaskingFluid(fluid);
        pointer = event.getPointerId(index);
        getParent().requestDisallowInterceptTouchEvent(true);
        int gray = fluid ? 0 : paint.gray, shade = erasing ? ToneDocument.ERASE : gray;
        if (settings.tool == ToolSettings.Tool.SHAPES) { beginShape(settings, shade); return true; }
        if (settings.tool == ToolSettings.Tool.FILL) { beginFill(settings, shade); return true; }
        if (settings.tool == ToolSettings.Tool.SELECT) { beginSelection(settings); return true; }
        cancelWetCallback();
        // With opaque paint the wet brush pen keeps the selected ink: each stroke starts dipped in it again.
        if (settings.holdsInk() && !paint.transparentPaint && !erasing) paint.brushInk.load(gray);
        stroke = ToolStrokes.create(document, settings, gray, wet, paint.transparentPaint && !fluid, erasing, app.prefs.smoothEdges(),
                settings.tool.flowing ? (settings.holdsInk() ? paint.brushInk : null) : watercolor ? paint.brushLoad : null);
        // Supernote encodes signed X degrees in ORIENTATION, Y in TILT.
        sampleEvent(event, index, -1);
        return true;
    }
    /** The pointer is a stylus tip or eraser end. */
    private static boolean isPen(MotionEvent event, int index) {
        int type = event.getToolType(index);
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER;
    }
    /** A pen stroke, shape or fill drag is in progress. */
    boolean penActive() { return pointer != -1; }
    private void pagePoint(float x, float y) {
        samplePoint[0] = x; samplePoint[1] = y;
        if (!unscaledPage) viewToPage.mapPoints(samplePoint);
    }
    @Override public boolean onHoverEvent(MotionEvent event) {
        if (isPen(event, 0)) {
            penGuardUntil = SystemClock.uptimeMillis()+250;
            if (navigating) { touchBlocked = true; endNavigation(); }
        }
        return true;
    }
    private void sampleEvent(MotionEvent event, int pointerIndex, int history) {
        MotionEvent physical = app.physicalPenEvent == null ? event : app.physicalPenEvent;
        int source = physical.findPointerIndex(event.getPointerId(pointerIndex));
        if (source < 0 || history >= physical.getHistorySize()) { physical = event; source = pointerIndex; }
        boolean current = history < 0;
        float x = current ? event.getX(pointerIndex) : event.getHistoricalX(pointerIndex, history);
        float y = current ? event.getY(pointerIndex) : event.getHistoricalY(pointerIndex, history);
        float pressure = current ? event.getPressure(pointerIndex) : event.getHistoricalPressure(pointerIndex, history);
        float tiltX = current ? physical.getOrientation(source) : physical.getHistoricalOrientation(source, history);
        float tiltY = current ? physical.getAxisValue(MotionEvent.AXIS_TILT, source) : physical.getHistoricalAxisValue(MotionEvent.AXIS_TILT, source, history);
        pagePoint(x, y);
        // The page stays device-relative, as do Supernote's signed tilt-degree axes.
        // Transform positions only; treating ORIENTATION as Android azimuth corrupts tilt.
        if (landscapeTilt) { float panelX = tiltX; tiltX = -tiltY; tiltY = panelX; }
        stroke.sample(samplePoint[0], samplePoint[1], pressure, tiltX, tiltY);
    }

    /** Ends or cancels whatever gesture is running, committing finished work. */
    void finishStroke() {
        if (navigating) touchBlocked = true;
        endNavigation();
        if (shapeStroke != null) {
            removeCallbacks(previewFrame); previewFrameScheduled = false;
            renderShape(shapeStroke.cancel(display, viewportBitmap != null)); shapeStroke = null; pointer = -1;
            endFastPreview(); getParent().requestDisallowInterceptTouchEvent(false);
        }
        if (pickPointer != -1) {
            pickPointer = -1; getParent().requestDisallowInterceptTouchEvent(false);
            int original = app.pickOriginalShade;
            app.updateColorBar(() -> app.paint.gray = original);
            app.setPickingShade(false);
        }
        // Pen-up already accepted this fill. Complete it before a lifecycle save.
        if (gradientCommit && gradientFill != null) {
            removeCallbacks(gradientStep);
            gradientFill.secondShade(gradientShade);
            while (!gradientFill.advance(8192)) { }
            renderDirty(); flush(true); gradientReady = true; applyGradient();
        }
        if (fillGesture) {
            removeCallbacks(previewFrame); previewFrameScheduled = false;
            fillGesture = false; pointer = -1; clearGradientGuide(); endFastPreview();
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        if (selectGesture) {
            removeCallbacks(previewFrame); previewFrameScheduled = false;
            finishSelectionTrace();
        }
        if (moveGesture) cancelMove();
        cancelGradient();
        if (fill != null) {
            removeCallbacks(fillStep); fill.cancel(); fill = null;
            renderDirty(); flush(true); app.toolbar.operationStatus.setText("");
        }
        if (stroke == null) return;
        boolean changed = stroke.finish(); stroke = null; pointer = -1;
        renderDirty(); flush(true); app.showBrushInk();
        getParent().requestDisallowInterceptTouchEvent(false);
        if (changed) app.recovery();
        scheduleWet();
        // No View invalidation or toolbar update at pen-up on the direct path.
    }

    // Shapes

    private void beginShape(ToolSettings settings, int shade) {
        renderDirty();
        shapeX = samplePoint[0]; shapeY = samplePoint[1];
        shapeStroke = new ShapePreview(document, settings, shade, shapeX, shapeY);
        beginFastPreview(); drawPreviewFrame();
    }
    private void continueShape(MotionEvent event) {
        int action = event.getActionMasked(), index = event.findPointerIndex(pointer);
        if (action == MotionEvent.ACTION_CANCEL || index < 0) { finishStroke(); return; }
        boolean up = pointerUp(event, pointer);
        if (action == MotionEvent.ACTION_MOVE || up) {
            pagePoint(event.getX(index), event.getY(index));
            shapeX = samplePoint[0]; shapeY = samplePoint[1];
            if (!up) schedulePreviewFrame();
        }
        if (!up) return;
        drawPreviewFrame(); endFastPreview();
        if (washing) {
            int[] spans = shapeStroke.spans();
            renderShape(shapeStroke.cancel(display, viewportBitmap != null)); shapeStroke = null; pointer = -1;
            getParent().requestDisallowInterceptTouchEvent(false);
            wash(() -> WatercolorWash.spans(wet, spans, document.width, document.height, app.paint.gray));
            return;
        }
        boolean changed = shapeStroke.finish(); shapeStroke = null; pointer = -1;
        // The committed geometry matches the preview exactly; keep its native raster,
        // unless masking fluid kept the shape from some of it, or the shape was masking fluid.
        if (document.hasMask()) { renderDirty(); flush(true); } else document.clearDirty();
        getParent().requestDisallowInterceptTouchEvent(false);
        if (changed) app.recovery();
    }
    /** Coalesces queued moves into one preview frame per display frame. */
    private void schedulePreviewFrame() {
        if (previewFrameScheduled) return;
        previewFrameScheduled = true;
        postDelayed(previewFrame, Math.max(0, 16-(SystemClock.uptimeMillis()-lastPreviewFrame)));
    }
    private void drawPreviewFrame() {
        removeCallbacks(previewFrame); previewFrameScheduled = false;
        // Budget from frame start; rendering time must not add another full-frame delay.
        lastPreviewFrame = SystemClock.uptimeMillis();
        if (shapeStroke != null) renderShape(shapeStroke.preview(shapeX, shapeY, display, viewportBitmap != null));
        else if (selectGesture) drawSelectionTrace();
        else if (moveGesture) drawMoveGhost();
        else if (showsGradientGuide()) drawGradientGuide();
        else return;
        flush(true); previewFrameCount++;
    }
    private void renderShape(Rect dirty) {
        if (dirty.isEmpty()) return;
        // Preview pixels are already rasterized natively. Submit one complete frame.
        dirty.inset(-2, -2);
        if (!dirty.intersect(0, 0, document.width, document.height)) return;
        queue(dirty);
    }

    // Fills and gradients

    private void beginFill(ToolSettings settings, int shade) {
        fillGesture = true;
        fillStartX = fillEndX = samplePoint[0]; fillStartY = fillEndY = samplePoint[1];
        // Masking fluid has one shade, so it fills flat.
        fillGradient = app.paint.maskingFluid ? ToolSettings.Gradient.FLAT : settings.gradient;
        fillOriginalGray = app.paint.gray; fillShade = shade;
        fillTolerance = fillGradient == ToolSettings.Gradient.FLAT ? 0 : settings.tolerance;
        if (!showsGradientGuide()) return;
        beginFastPreview(); drawPreviewFrame();
    }
    private void continueFillGesture(MotionEvent event) {
        int action = event.getActionMasked(), p = event.findPointerIndex(pointer);
        if (action == MotionEvent.ACTION_CANCEL) { finishStroke(); return; }
        if (p < 0) return;
        boolean up = pointerUp(event, pointer);
        if (action != MotionEvent.ACTION_MOVE && !up) return;
        pagePoint(event.getX(p), event.getY(p));
        fillEndX = samplePoint[0]; fillEndY = samplePoint[1];
        if (!up) { schedulePreviewFrame(); return; }
        drawPreviewFrame();
        fillGesture = false; pointer = -1; getParent().requestDisallowInterceptTouchEvent(false);
        float[] axis = {fillStartX, fillStartY, fillEndX, fillEndY}; pageToView.mapPoints(axis);
        if (fillGradient == ToolSettings.Gradient.FLAT || Math.hypot(axis[2]-axis[0], axis[3]-axis[1]) < dp(8)) {
            if (fillGradient != ToolSettings.Gradient.FLAT) clearGradientGuide();
            fill = washing ? FloodFill.region(document, (int)fillStartX, (int)fillStartY, fillTolerance)
                    : new FloodFill(document, (int)fillStartX, (int)fillStartY, fillShade, fillTolerance);
            app.toolbar.operationStatus.setText("Filling…"); post(fillStep);
        } else {
            // Keep only the direction guide until the second color gesture begins.
            gradientWaiting = true; gradientReady = false; gradientCommit = false;
            app.scheduleGradientHint();
        }
        // A waiting guide must outlast a panel refresh; a cleared one is already gone.
        endFastPreview();
    }
    private void advanceFill() {
        if (fill == null) return;
        if (!advanceFor5ms(fill)) { postDelayed(fillStep, 1); return; }
        boolean changed = fill.finish();
        java.util.BitSet region = fill.region(); fill = null;
        app.toolbar.operationStatus.setText("");
        if (selectingRegion) {
            selectingRegion = false;
            // A new selection replaces the old one; one that matched nothing clears it, so
            // an empty selection never blocks the whole page.
            Selection selection = Selection.of(region, document.width);
            document.setSelection(selection.empty() ? null : selection);
            refreshSelectionBorder();
            if (selection.empty()) app.message("Nothing matched that area");
            return;
        }
        if (region != null) { wash(() -> WatercolorWash.pixels(wet, region, document.width, document.height, fillShade)); return; }
        renderDirty(); flush(true);
        if (changed) app.recovery();
    }
    /**
     * Lays a shape's or flat fill's wash as one stroke of the wet paint, shown at once as a
     * glaze, as a watercolor brush's is, and run once there is time.
     */
    private void wash(Runnable queue) {
        wetPaint(); document.begin(); wet.beginStroke();
        queue.run();
        boolean changed = wet.finishStroke();
        renderDirty(); flush(true);
        if (changed) app.recovery();
        scheduleWet();
    }
    /** Runs a fill for one 5 ms slice; true when it has finished. */
    private static boolean advanceFor5ms(FloodFill fill) {
        long deadline = System.nanoTime()+5_000_000L;
        boolean done;
        do { done = fill.advance(1024); } while (!done && System.nanoTime() < deadline);
        return done;
    }
    /** A gradient axis is drawn and waiting for, or previewing, its second color. */
    boolean hasGradient() { return gradientWaiting || gradientFill != null; }
    /** Previews the pending gradient with {@code shade} as its second color. */
    void previewGradient(int shade) {
        if (!hasGradient()) return;
        app.hideGradientHint();
        if (gradientWaiting) {
            gradientSample = document.snapshot();
            gradientShade = gradientPassShade = shade;
            gradientFill = new FloodFill(document, (int)fillStartX, (int)fillStartY, fillShade, fillTolerance,
                    fillStartX, fillStartY, fillEndX, fillEndY, shade, fillGradient);
            gradientWaiting = false; gradientReady = false;
            clearGradientGuide(); post(gradientStep); return;
        }
        gradientShade = shade;
        // Finish the current preview pass before starting the latest shade. Rapid
        // moves must not continually restart a large fill and starve its display.
        if (gradientReady && gradientPassShade != shade) {
            gradientFill.secondShade(shade); gradientPassShade = shade; gradientReady = false;
            post(gradientStep);
        }
    }
    private void advanceGradient() {
        if (gradientFill == null) return;
        if (!advanceFor5ms(gradientFill)) { postDelayed(gradientStep, 1); return; }
        renderDirty(); flush(true); gradientReady = true;
        if (gradientPassShade != gradientShade) previewGradient(gradientShade);
        else if (gradientCommit) applyGradient();
    }
    /** Commits the previewed gradient, once its latest pass is complete. */
    void applyGradient() {
        if (gradientFill == null) return;
        gradientCommit = true;
        if (!gradientReady) return;
        boolean changed = gradientFill.finish(); gradientFill = null; gradientSample = null; gradientCommit = false;
        app.toolbar.operationStatus.setText("");
        if (changed) app.recovery();
    }
    /** Discards a pending gradient and restores the color chosen before it. */
    void cancelGradient() {
        app.hideGradientHint(); removeCallbacks(gradientStep);
        if (!hasGradient()) return;
        if (gradientFill != null) gradientFill.cancel();
        if (gradientWaiting) { gradientWaiting = false; clearGradientGuide(); }
        gradientFill = null; gradientSample = null; gradientReady = false; gradientCommit = false;
        app.toolbar.operationStatus.setText(""); renderDirty(); flush(true);
        int original = fillOriginalGray;
        app.updateColorBar(() -> app.paint.gray = original);
        app.setEraseMode(fillShade == ToneDocument.ERASE);
        app.saveToolState();
    }

    // Selection

    private void beginSelection(ToolSettings settings) {
        if (clipboard != null || settings.selectMode == ToolSettings.SelectMode.MOVE) { beginMove(); return; }
        if (settings.selectMode == ToolSettings.SelectMode.FILL) {
            // A tap picks the connected tones around it, like the fill tool picks its region.
            pickMode = ToolSettings.SelectMode.FILL;
            selectingRegion = true;
            fill = FloodFill.region(document, (int)samplePoint[0], (int)samplePoint[1], settings.tolerance);
            app.toolbar.operationStatus.setText("Selecting\u2026");
            post(fillStep);
            return;
        }
        selectGesture = true;
        pickMode = ToolSettings.SelectMode.FREEHAND;
        tracePoints = 0;
        addTracePoint(samplePoint[0], samplePoint[1]);
        beginFastPreview();
        drawSelectionTrace();
    }

    private void addTracePoint(float x, float y) {
        if (tracePoints > 0) {
            float dx = x - trace[(tracePoints-1)*2], dy = y - trace[(tracePoints-1)*2+1];
            if (dx*dx + dy*dy < 4) return; // a resting pen adds nothing
        }
        if (tracePoints * 2 + 2 > trace.length) {
            if (trace.length >= 1 << 17) return; // shortcut: 65k trace points cover any page's input rate
            trace = java.util.Arrays.copyOf(trace, trace.length * 2);
        }
        trace[tracePoints*2] = x; trace[tracePoints*2+1] = y;
        tracePoints++;
    }

    private void continueSelectGesture(MotionEvent event) {
        int action = event.getActionMasked(), p = event.findPointerIndex(pointer);
        if (action == MotionEvent.ACTION_CANCEL || p < 0) { finishStroke(); return; }
        boolean up = pointerUp(event, pointer);
        if (action == MotionEvent.ACTION_MOVE || up) {
            pagePoint(event.getX(p), event.getY(p));
            if (!up) { addTracePoint(samplePoint[0], samplePoint[1]); schedulePreviewFrame(); return; }
            finishSelectionTrace();
        }
    }

    /** The traced loop becomes the page's selection. */
    private void finishSelectionTrace() {
        selectGesture = false;
        pointer = -1;
        getParent().requestDisallowInterceptTouchEvent(false);
        endFastPreview();
        if (isTapTrace()) {
            // A tap, or a pen that barely moved, traces no real loop. Rather than leave a
            // speck of selection, treat it as the artist asking to let the selection go.
            clearSelection();
            return;
        }
        Selection lasso = Selection.lasso(trace, tracePoints, document.width, document.height);
        document.setSelection(lasso);
        refreshSelectionBorder();
    }

    /**
     * Whether the trace is too small to be a lasso the artist meant: a tap, or the slight
     * drag a deliberate tap always carries. Judged on how far the pen travelled rather than
     * on points recorded, because the trace's deadzone still records several points for a
     * few pixels of drift.
     */
    private boolean isTapTrace() {
        if (tracePoints < 3) return true;
        float left = trace[0], right = trace[0], top = trace[1], bottom = trace[1];
        for (int i = 2; i + 1 < tracePoints * 2; i += 2) {
            left = Math.min(left, trace[i]); right = Math.max(right, trace[i]);
            top = Math.min(top, trace[i + 1]); bottom = Math.max(bottom, trace[i + 1]);
        }
        // A loop inside this is a tap. Comfortably past the few pixels a resting or
        // hesitating pen drifts, and far short of any shape worth selecting.
        float slop = dp(14);
        return right - left < slop && bottom - top < slop;
    }

    /** Live preview of the lasso being traced: the traced line itself. */
    private void drawSelectionTrace() {
        float[] path = java.util.Arrays.copyOf(trace, tracePoints * 2);
        if (viewportBitmap != null) pageToView.mapPoints(path);
        showSelection(selectionBorder, new float[][]{path});
    }

    // Moving a copy

    /** How far the copy has been dragged from where it was copied, in whole page pixels. */
    private int moveDx, moveDy;
    /**
     * The undo depth just after a cut wiped its spot. If nothing else was recorded between
     * then and the drop, the paste joins that wipe so one undo reverses the whole cut.
     */
    private int cutUndoDepth = -1;

    /** The copy attached to the pen, where it is currently placed, or null. */
    private Selection placedCopy() {
        // The region, not just the painted pixels: the artist should be able to draw
        // anywhere in the area they copied, not only where paint already happened to be.
        return clipboard == null ? null : clipboard.selectionMovedBy(moveDx, moveDy, document.width, document.height);
    }

    private void beginMove() {
        // Pen-down already claimed the pointer; a move that never starts must give it back.
        if (document.selection() == null && clipboard == null) { releasePointer(); return; }
        if (clipboard != null) {
            // The first press after Cut or Copy centres the patch on the press. The pixels
            // are written when the pen lifts, so placing and dragging it there are one
            // gesture and one undo step.
            if (!copyPlaced) {
                moveDx = Math.round(samplePoint[0] - (clipboard.left + clipboard.width/2f));
                moveDy = Math.round(samplePoint[1] - (clipboard.top + clipboard.height/2f));
                copyPlaced = true;
            }
        } else if (!document.selection().contains((int)samplePoint[0], (int)samplePoint[1])) {
            // A press outside the selection lets it go. Anything the copy left on the layer
            // stays there: it was committed when the copy was placed.
            clearSelection();
            selectPickingMode(pickMode);
            releasePointer();
            return;
        } else {
            // A move of the page's own selection starts from zero each press. Without this
            // the offsets of every earlier press pile up and each hop moves by the sum of
            // all of them, which reads as moves registering late, short or wrong way.
            moveDx = 0; moveDy = 0;
        }
        moveGesture = true;
        moveBaseX = moveDx; moveBaseY = moveDy;
        moveAnchorX = samplePoint[0]; moveAnchorY = samplePoint[1];
        hideSelectionBorder();
        beginFastPreview();
        drawMoveGhost();
        flush(true);
    }

    private void releasePointer() {
        pointer = -1;
        getParent().requestDisallowInterceptTouchEvent(false);
    }

    /**
     * Lay the copy down where the pen left it and keep it selected, so the next press can
     * keep nudging it or cut it off the page again. This is the moment a cut leaves its
     * original spot. The document records what lay underneath as it writes, so cutting
     * the placed copy uncovers that drawing rather than a hole the paste made. A cut's
     * paste joins its own wipe so one undo reverses the whole cut.
     */
    private void writeCopy() {
        // Read the cut's undo depth before clearing: it joins the wipe only if nothing else
        // was recorded in between.
        boolean joinsCut = cutUndoDepth >= 0 && cutUndoDepth == document.undoDepth();
        Clipboard patch = clipboard;
        int dx = moveDx, dy = moveDy;
        clearClipboard();
        try { document.pastePlaced(patch, dx, dy, joinsCut); }
        catch (IllegalStateException error) { app.message(error.getMessage()); }
        renderDirty();
        drawSelectionBorder();
        flush(true);
        app.recovery();
    }

    private void continueMove(MotionEvent event) {
        int action = event.getActionMasked(), p = event.findPointerIndex(pointer);
        if (action == MotionEvent.ACTION_CANCEL || p < 0) { cancelMove(); return; }
        boolean up = pointerUp(event, pointer);
        if (action == MotionEvent.ACTION_MOVE || up) {
            pagePoint(event.getX(p), event.getY(p));
            if (up) { commitMove(); return; }
            moveDx = moveBaseX + Math.round(samplePoint[0] - moveAnchorX);
            moveDy = moveBaseY + Math.round(samplePoint[1] - moveAnchorY);
            schedulePreviewFrame();
        }
    }

    /** Draw the copy's outline at its current offset, replacing whatever the overlay showed. */
    private float[][] ghostViewBase; /** The border mapped to view space once per gesture. */
    private float[][] ghostAt; /** The reused frame buffer: the base shifted by the drag vector. */
    private void drawMoveGhost() {
        if (ghostViewBase == null) {
            float[][] base = (clipboard != null ? clipboard.selectionMovedBy(0, 0, document.width, document.height)
                    : document.selection()).border();
            ghostViewBase = toView(base);
            ghostAt = new float[ghostViewBase.length][];
            for (int i = 0; i < ghostViewBase.length; i++) ghostAt[i] = ghostViewBase[i].clone();
        }
        // view = M*(page+d) = M*page + M*d, so the mapped base shifts by the rotated
        // offset. With no viewport bitmap the overlay works in page space and the page
        // rotation is applied later on the panel, so the offset must not be rotated here.
        float[] v = { moveDx, moveDy };
        if (viewportBitmap != null) pageToView.mapVectors(v);
        for (int i = 0; i < ghostAt.length; i++) for (int j = 0; j < ghostAt[i].length; j += 2) {
            ghostAt[i][j] = ghostViewBase[i][j] + v[0];
            ghostAt[i][j+1] = ghostViewBase[i][j+1] + v[1];
        }
        showSelection(selectionBorder, ghostAt, clipboard != null ? this::paintCopy : null);
    }

    /**
     * End the drag. A copy stays unwritten: the selection moves over it so the ghost keeps
     * its place and the next press picks it up from there, or lets it go and commits it.
     * Moving the page's own selection is the operation itself, so that one commits now.
     */
    private void commitMove() {
        moveGesture = false;
        releasePointer();
        endFastPreview();
        ghostViewBase = null; ghostAt = null;
        // A copy riding the pen is written down now; a move of the page's own selection is
        // the operation itself, so that one moves the pixels instead.
        if (clipboard != null) { writeCopy(); return; }
        try { document.moveSelection(moveDx, moveDy); }
        catch (IllegalStateException error) { app.message(error.getMessage()); }
        renderDirty();
        drawSelectionBorder();
        flush(true);
        app.recovery();
    }

    private void cancelMove() {
        moveGesture = false;
        releasePointer();
        ghostViewBase = null; ghostAt = null;
        // An abandoned placement leaves the copy still on the pen, ready to be centred on
        // the next tap rather than stuck where this one started.
        if (clipboard != null) copyPlaced = false;
        endFastPreview();
        drawSelectionBorder();
        flush(true);
    }

    /** Put the Selection tool back in a picking mode and tell the toolbar. */
    private void selectPickingMode(ToolSettings.SelectMode mode) {
        if (app.library.current().selectMode == mode) return;
        app.library.edit(app.library.current().selectMode(mode));
        app.saveToolState();
        app.toolbar.refreshToolSelection();
    }

    /**
     * Copy the selection out of the current layer; the tool moves to Move so the pen places
     * it. The copy is not written to the layer until the artist deselects it.
     */
    void copySelection() {
        // As with Cut: a copy already on the pen is not on the page, so reading the page
        // would find nothing and lose the copy being carried.
        if (clipboard != null) { stillCarrying(); return; }
        try { clipboard = document.copySelection(); } catch (IllegalStateException error) { app.message(error.getMessage()); return; }
        if (clipboard.empty()) { clipboard = null; return; }
        carryCopy();
    }

    /**
     * Copy the selection and take it off the page at once. The copy rides the pen like a
     * copy does, so the artist taps where it should land and can keep moving it; the spot
     * it came from is already bare. Undo puts the pixels back.
     */
    void cutSelection() {
        // A copy already riding the pen is the thing to cut. None of it has been written to
        // the page yet, so there is no spot to wipe, and re-reading the layer would find
        // the selection bare and report "nothing is selected".
        if (clipboard != null) { stillCarrying(); return; }
        try {
            clipboard = document.copySelection();
            // Only join the wipe on the drop if the wipe actually recorded an edit;
            // otherwise the paste would merge into an unrelated stroke instead.
            if (document.wipeSelection()) cutUndoDepth = document.undoDepth();
        } catch (IllegalStateException error) { clipboard = null; app.message(error.getMessage()); return; }
        if (clipboard.empty()) { clipboard = null; return; }
        carryCopy();
        renderDirty();
        app.recovery();
    }

    /** A fresh copy is on the pen at its own spot: select it and hand the pen to Move. */
    private void carryCopy() {
        moveDx = moveDy = 0;
        document.setSelection(placedCopy());
        selectPickingMode(ToolSettings.SelectMode.MOVE);
        refreshSelectionBorder();
    }

    /** Cut and Copy both mean the copy in hand while one is still on the pen. */
    private void stillCarrying() {
        selectPickingMode(ToolSettings.SelectMode.MOVE);
        app.message("Still carrying your copy. Drag it to move it, or tap outside to let it go.");
    }

    /** Forget the copy without writing it. */
    void clearClipboard() {
        clipboard = null; moveDx = moveDy = 0; copyPlaced = false;
        cutUndoDepth = -1;
        if (copyBitmap != null) { copyBitmap.recycle(); copyBitmap = null; copyBitmapSource = null; }
    }
    /** True until a held copy has had its first press place it on the canvas. */
    private boolean copyPlaced;
    boolean copyPending() { return clipboard != null; }

    /** Clear the page's selection. */
    void clearSelection() {
        if (document.selection() == null) return;
        document.setSelection(null);
        refreshSelectionBorder();
    }

    // Eyedropper

    private void sampleShadeGesture(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_CANCEL) { finishStroke(); return; }
        boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
        if (down && pickPointer == -1 && event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS) {
            pickPointer = event.getPointerId(index);
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        int p = event.findPointerIndex(pickPointer);
        if (p < 0) return;
        boolean up = pointerUp(event, pickPointer);
        if (down || action == MotionEvent.ACTION_MOVE || up) {
            pagePoint(event.getX(p), event.getY(p));
            if (samplePoint[0] >= 0 && samplePoint[1] >= 0 && samplePoint[0] < document.width && samplePoint[1] < document.height) {
                int x = (int)samplePoint[0], y = (int)samplePoint[1];
                // The unmodified composite prevents sampling the gradient's own preview.
                int shade = gradientSample == null ? document.compositeTone(x, y) : gradientSample[y*document.width+x]&255;
                if (app.paint.gray != shade || !app.pickedShade)
                    app.updateColorBar(() -> { app.paint.gray = shade; app.pickedShade = true; });
                previewGradient(shade);
            }
        }
        if (up) {
            pickPointer = -1; getParent().requestDisallowInterceptTouchEvent(false);
            if (app.pickedShade) {
                app.setEraseMode(false); app.setMaskingFluid(false); app.setPickingShade(false); applyGradient();
                app.loadInk(app.paint.gray); app.saveToolState();
            }
        }
    }

    // Finger taps

    /** A two-finger tap undoes and a three-finger tap redoes, like the header buttons. */
    private boolean historyTap(MotionEvent event) {
        if (blocksFingerGesture() || !Ui.fingersOnly(event) || app.busy()) { fingerTaps.reset(); return false; }
        int count = event.getPointerCount();
        float[] x = new float[count], y = new float[count]; int[] ids = new int[count];
        for (int i = 0; i < count; i++) { x[i] = event.getX(i); y[i] = event.getY(i); ids[i] = event.getPointerId(i); }
        int fingers = fingerTaps.event(event.getActionMasked(), event.getEventTime(), ids, x, y, dp(18));
        if (fingers == 0) return false;
        endNavigation();
        app.history(fingers == 2 ? document::undo : document::redo);
        return true;
    }

    // Pinch and pan

    /** Handles finger navigation; returns true if the event was consumed as navigation. */
    private boolean navigationGesture(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) { touchBlocked = false; endNavigation(); }
        boolean pen = false;
        for (int i = 0; i < event.getPointerCount(); i++)
            if (isPen(event, i)) pen = true;
        if (pen || pointer != -1 || pickPointer != -1 || fillGesture || selectGesture || moveGesture || hasGradient()
                || SystemClock.uptimeMillis() < penGuardUntil) {
            touchBlocked = true; endNavigation();
            if (pen) penGuardUntil = SystemClock.uptimeMillis()+250;
            return false;
        }
        if (app.navigationLocked) { touchBlocked = true; endNavigation(); return true; }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) { endNavigation(); return true; }
        if (touchBlocked) return true;
        if (action == MotionEvent.ACTION_POINTER_UP) {
            // Require a fresh two-finger gesture after either tracked finger lifts.
            if (event.getPointerId(index) == fingerA || event.getPointerId(index) == fingerB) {
                touchBlocked = true; endNavigation();
            }
            return true;
        }
        if (!navigating) {
            if (action != MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() != 2
                    || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER
                    || event.getToolType(1) != MotionEvent.TOOL_TYPE_FINGER) return true;
            fingerA = event.getPointerId(0); fingerB = event.getPointerId(1);
            fingerX = (event.getX(0)+event.getX(1))/2; fingerY = (event.getY(0)+event.getY(1))/2;
            fingerSpan = (float)Math.hypot(event.getX(1)-event.getX(0), event.getY(1)-event.getY(0));
            if (fingerSpan < dp(24)) { touchBlocked = true; fingerA = fingerB = -1; return true; }
            disconnectDisplay(); navigating = true;
            updateViewport(); connectDisplay();
            getParent().requestDisallowInterceptTouchEvent(true); return true;
        }
        int a = event.findPointerIndex(fingerA), b = event.findPointerIndex(fingerB);
        if (a < 0 || b < 0) { touchBlocked = true; endNavigation(); return true; }
        if (action == MotionEvent.ACTION_MOVE) {
            float x = (event.getX(a)+event.getX(b))/2, y = (event.getY(a)+event.getY(b))/2;
            float span = (float)Math.hypot(event.getX(b)-event.getX(a), event.getY(b)-event.getY(a));
            if (span < dp(24)) return true;
            viewport.gesture(span/fingerSpan, fingerX, fingerY, x, y);
            fingerX = x; fingerY = y; fingerSpan = span;
            if (!navigationFrameScheduled) {
                navigationFrameScheduled = true;
                postDelayed(navigationFrame, Math.max(0, 16-(SystemClock.uptimeMillis()-lastNavigationFrame)));
            }
        }
        return true;
    }
    void endNavigation() {
        fingerA = fingerB = -1;
        if (!navigating) return;
        if (navigationFrameScheduled) drawNavigationFrame();
        navigating = false; getParent().requestDisallowInterceptTouchEvent(false);
        if (viewport.zoom == 1 && viewport.scale() == 1) {
            disconnectDisplay(); updateViewport(); invalidate();
        }
        if (direct == null) post(this::connectDisplay); else scheduleWet();
    }
    private void drawNavigationFrame() {
        removeCallbacks(navigationFrame); navigationFrameScheduled = false;
        if (!navigating) return;
        lastNavigationFrame = SystemClock.uptimeMillis();
        long began = System.nanoTime();
        updateViewport();
        long rendered = System.nanoTime();
        pending.clear(); pending.add(0, 0, getWidth(), getHeight()); flush(true);
        // Keep Android's retained drawing commands current without requesting a
        // compositor frame for every finger movement on the direct display path.
        if (direct != null) app.selectionFeedback.retainForNextDraw(this, new Rect(0, 0, getWidth(), getHeight()));
        lastNavigationRasterNanos = rendered-began;
        lastNavigationPresentNanos = System.nanoTime()-rendered;
        navigationFrameCount++;
    }

    // Wet paint

    private void scheduleWet() {
        // Yield between slices; input queued during the last slice runs
        // before the next idle callback. Only completed sweeps wait a frame;
        // flowing water runs ahead, except a small wash's slowing tail.
        if (wet != null && wet.framePending()) scheduleWet(1);
        else if (wet != null && wet.flowing())
            scheduleWet((int)Math.max(1, wet.flowFrameMillis() - (SystemClock.uptimeMillis() - wetFrameStarted)));
        else scheduleWet(WetWatercolor.FRAME_MS);
    }
    private void scheduleWet(int delayMillis) {
        if (wet != null && wet.isAnimating() && !wetHeld() && app.resumed && hasWindowFocus() && !app.loading && !navigating) {
            // Start reading the tilt now, so it is ready when the paint first moves.
            app.canvasGravity.listen(true);
            if (!wetScheduled) { wetScheduled = true; postDelayed(wetStep, delayMillis); }
            if (wet.running()) app.wetDroplet.start();
        }
    }
    /** The wet paint, started on the canvas the wet canvas setting asks for if there is none. */
    private void wetPaint() {
        if (wet == null) wet = app.paint.wetCanvas ? new WetWatercolor(document, app.paint.wetness) : new WetWatercolor(document);
    }
    /** The stroke being drawn keeps wet paint still until it is finished. */
    private boolean wetHeld() { return stroke != null && stroke.holdsWetPaint(); }
    /** About how much wet paint is left to move, or 0 when it isn't moving. */
    int wetWork() { return wetScheduled && wet != null ? Math.max(1, wet.remainingWork()) : 0; }
    private void cancelWetCallback() {
        removeCallbacks(wetStep); wetQueue.removeIdleHandler(wetIdle); wetScheduled = false;
    }
    private void advanceWet() {
        wetScheduled = false;
        if (wet == null || wetHeld() || !app.resumed || !hasWindowFocus() || app.loading || navigating) {
            app.canvasGravity.listen(false); return;
        }
        boolean drawing = stroke != null;
        long budget = wetBudget.nanos(drawing, wet.strokePixels(), wet.activePixels(), SystemClock.uptimeMillis());
        // Submit the pen's outstanding pixels before adding more display work.
        if (budget == 0 || !pending.isEmpty()) { scheduleWet(16); return; }
        long start = System.nanoTime();
        app.canvasGravity.apply(wet);
        int frame = wet.frame();
        long sliceStarted = SystemClock.uptimeMillis();
        wetChanged |= wet.advance(drawing, wetBudget.tiles(budget, drawing), budget / 2, drawing ? 96 : 192);
        long computed = System.nanoTime();
        if (wet.frame() != frame) wetFrameStarted = sliceStarted;
        // Presenting every slice of fast-running water would cost more than working it out.
        // While drawing, each slice still shows its own changes, or the pen's next event
        // would have to render all the water's held-back ones.
        long now = SystemClock.uptimeMillis();
        boolean show = drawing || !wet.flowing() || now - lastWetShown >= WET_SHOW_MS;
        if (show) { renderDirty(); lastWetShown = now; }
        long rendered = System.nanoTime();
        if (show) flush(true);
        long finished = System.nanoTime();
        lastWetComputeNanos = computed - start;
        lastWetRenderNanos = rendered - computed;
        lastWetPresentNanos = finished - rendered;
        wetMaxSliceNanos = Math.max(wetMaxSliceNanos, finished - start);
        wetSliceCount++;
        wetBudget.completed(finished - start, wet.advancedTiles(), drawing, SystemClock.uptimeMillis());
        if (wet.isAnimating()) scheduleWet();
        else {
            app.canvasGravity.listen(false);
            if (wetChanged && stroke == null) { wetChanged = false; app.recovery(); }
        }
    }
    boolean stroking() { return stroke != null; }
    /**
     * Lays in full the brush dabs so far shown only as a glaze, e.g. before the brush's ink
     * changes outside a stroke: they trade ink with it in order. False if there were none.
     */
    boolean layQueuedPaint() {
        if (wet == null || wet.queuedDabs() == 0) return false;
        wetChanged |= wet.layQueued(stroke != null);
        renderDirty(); flush(true);
        return true;
    }
    /** Stops wet blending, leaving the paint as it is. */
    void dryWet() {
        cancelWetCallback();
        boolean laid = layQueuedPaint();
        // Running water's last changes may not have reached the panel yet.
        if (!laid && wet != null && wet.flowing()) { renderDirty(); flush(true); }
        app.canvasGravity.listen(false);
        wet = null; if (app.wetDroplet != null) app.wetDroplet.dried();
        if (wetChanged) { wetChanged = false; app.recovery(); }
    }

    void close() {
        finishStroke(); dryWet(); disconnectDisplay(); input.close();
        if (display != null) display.recycle();
        if (viewportBitmap != null) { viewportBitmap.close(); viewportBitmap = null; }
        display = null; renderPixels = new int[0]; document = null;
    }
}
