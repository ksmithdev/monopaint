package io.github.mpdairy.monopaint;

import java.io.*;
import java.util.Arrays;

/** Host checks for the reference image: placement, rasterizer, stack rules, drag undo, sliding, persistence. */
final class ReferenceChecks {
    public static void main(String[] args) throws Exception {
        placement(); rasterizer(); stackRules(); dragUndo(); expansion(); persistence(); sliding();
        System.out.println("PASS: reference placement round trip, rasterizer semantics, stack rules, drag undo/redo, "
                + "canvas expansion, persistence with lock and masking fluid, whole-pixel sliding");
    }

    private static int u(byte value) { return value & 255; }
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
    private static void near(float a, float b, String what) {
        if (Math.abs(a - b) > 1e-3f) throw new AssertionError(what + " (" + a + " vs " + b + ")");
    }

    private static ReferenceImage checkerboard(int w, int h) {
        byte[] lum = new byte[w * h], alpha = new byte[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x;
            boolean dark = ((x + y) & 1) == 0;
            lum[i] = (byte)(dark ? 0 : 255);
            alpha[i] = (byte)((x == 0 && y == 0) ? 0 : 255);
        }
        return new ReferenceImage(lum, alpha, w, h, new float[]{1, 0, 0, 0, 1, 0});
    }

    private static void placement() {
        float[] plain = ReferenceImage.place(100, 50, 10, 20, 1, 0);
        float[] back = ReferenceImage.decompose(plain, 10, 20);
        near(back[0], 100, "plain center x"); near(back[1], 50, "plain center y");
        near(back[2], 1, "plain scale"); near(back[3], 0, "plain angle");
        float[] turned = ReferenceImage.place(64, 32, 12, 8, 2.5, 37);
        float[] backTurned = ReferenceImage.decompose(turned, 12, 8);
        near(backTurned[0], 64, "turned center x"); near(backTurned[1], 32, "turned center y");
        near(backTurned[2], 2.5f, "turned scale"); near(backTurned[3], 37, "turned angle");
        float[] slid = ReferenceImage.translated(turned, 10.25, -7.5);
        for (int i = 0; i < 6; i++)
            check(i == 2 || i == 5 || slid[i] == turned[i], "translated touches only offsets at " + i);
        near(slid[2], turned[2] + 10.25f, "translated x"); near(slid[5], turned[5] - 7.5f, "translated y");
        // A placement fully inside the page covers exactly its box.
        ReferenceImage ref = checkerboard(4, 4);
        ref = new ReferenceImage(ref.lum, ref.alpha, 4, 4, ReferenceImage.place(10, 10, 4, 4, 1, 0));
        int[] box = ReferenceImage.bounds(ref, 32, 32);
        check(box[0] == 8 && box[1] == 8 && box[2] == 12 && box[3] == 12, "inside bounds exact");
        // A placement hanging off both edges clips to the page.
        ReferenceImage off = new ReferenceImage(ref.lum, ref.alpha, 4, 4, ReferenceImage.place(1, 2, 4, 4, 4, 0));
        int[] clipped = ReferenceImage.bounds(off, 32, 32);
        check(clipped[0] == 0 && clipped[1] == 0, "clipped at the near edge");
        check(clipped[2] <= 32 && clipped[3] <= 32, "clipped at the far edge");
        check(clipped[2] == 9 && clipped[3] == 10, "clips to the covered area at the far edge");
        try { new ReferenceImage(new byte[3], new byte[4], 2, 2, new float[6]);
            throw new AssertionError("bad pixel count accepted"); } catch (IllegalArgumentException expected) {}
        try { new ReferenceImage(new byte[4], new byte[4], 2, 2, new float[5]);
            throw new AssertionError("bad transform accepted"); } catch (IllegalArgumentException expected) {}
        System.out.println("PASS: reference placement round trip, translated, bounds cover and clip");
    }

    private static byte[] rasterInto(ReferenceImage ref, int pw, int ph, byte[] coverageOut) {
        byte[] tones = new byte[pw * ph];
        byte[] coverage = new byte[pw * ph];
        ReferenceImage.raster(ref.lum, ref.alpha, ref.width, ref.height, ref.transform,
                tones, coverage, pw, ph, 0, 0, pw, ph);
        System.arraycopy(coverage, 0, coverageOut, 0, coverage.length);
        return tones;
    }

    private static void rasterizer() {
        // 2x2: top-left transparent, others opaque (black, white, black).
        byte[] lum = {0, (byte)255, (byte)255, 0}, alpha = {0, (byte)255, (byte)255, (byte)255};
        ReferenceImage ref = new ReferenceImage(lum, alpha, 2, 2, new float[]{1, 0, 0, 0, 1, 0});
        byte[] coverage = new byte[4];
        byte[] tones = rasterInto(ref, 2, 2, coverage);
        check(u(tones[0]) == 255 && u(coverage[0]) == 0, "transparent corner stays paper");
        check(u(tones[1]) == 255 && u(tones[2]) == 255 && u(tones[3]) == 0, "opaque pixels keep their tone");
        check(u(coverage[1]) == 255 && u(coverage[3]) == 255, "opaque pixels cover fully");
        // Exact 180° mirror: samples land on pixel centers, so no blending happens.
        ReferenceImage spun = new ReferenceImage(lum, alpha, 2, 2, ReferenceImage.place(1, 1, 2, 2, 1, 180));
        byte[] spunCoverage = new byte[4];
        byte[] spunTones = rasterInto(spun, 2, 2, spunCoverage);
        check(u(spunTones[0]) == 0 && u(spunCoverage[0]) == 255, "180° mirror brings the far black corner to the origin");
        check(u(spunCoverage[3]) == 0, "the transparent corner mirrors to the far end");
        check(u(spunTones[1]) == 255 && u(spunTones[2]) == 255, "mirror keeps the white edge");
        // Half scale of a 4x4 checkerboard lands in the middle 2x2 of a 4x4 page.
        ReferenceImage board = checkerboard(4, 4);
        ReferenceImage half = new ReferenceImage(board.lum, board.alpha, 4, 4,
                ReferenceImage.place(2, 2, 4, 4, 0.5, 0));
        byte[] halfCoverage = new byte[16];
        byte[] halfTones = rasterInto(half, 4, 4, halfCoverage);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
            boolean inside = x >= 1 && x < 3 && y >= 1 && y < 3;
            check((u(halfCoverage[y * 4 + x]) > 0) == inside, "half scale covers the middle 2x2 only at " + x + "," + y);
        }
        // Degenerate transform clears to paper.
        ReferenceImage flat = new ReferenceImage(board.lum, board.alpha, 4, 4, new float[]{0, 0, 0, 0, 0, 0});
        byte[] flatCoverage = new byte[16];
        byte[] flatTones = rasterInto(flat, 4, 4, flatCoverage);
        for (int i = 0; i < 16; i++) check(u(flatTones[i]) == 255 && u(flatCoverage[i]) == 0, "degenerate clears to paper");
        System.out.println("PASS: rasterizer transparent/opaque/white, 180° mirror, half scale, degenerate clears");
    }

    private static void paint(ToneDocument doc, int x, int y, int tone) {
        doc.begin(); doc.paintTone(x, y, tone); doc.finish();
    }

    private static void stackRules() {
        ToneDocument doc = new ToneDocument(16, 16);
        paint(doc, 4, 4, 20);
        ReferenceImage ref = checkerboard(16, 16);
        ReferenceImage placed = new ReferenceImage(ref.lum, ref.alpha, 16, 16, ReferenceImage.place(8, 8, 16, 16, 1, 0));
        doc.setReference(placed);
        check(doc.hasReference() && doc.isReference(0), "reference sits at index 0");
        check(doc.layerCount() == 2 && doc.paintLayers() == 1, "reference does not count as a paint layer");
        check(doc.activeLayer() == 1, "the artist never lands on the reference");
        check(doc.compositeTone(1, 1) == 0, "reference shows through empty paper");
        check(doc.compositeTone(4, 4) == 20, "paint stays above the reference");
        try { doc.selectLayer(0); throw new AssertionError("selected the reference"); }
        catch (IllegalStateException expected) {}
        doc.setReference(placed);
        check(doc.layerCount() == 2 && doc.hasReference(), "replace keeps one reference");
        check(doc.undo() && doc.referenceImageWidth() == 16
                && Math.abs(ReferenceImage.decompose(doc.referencePlacement(), 16, 16)[2] - 1f) < 1e-3,
                "undo restores the previous placement");
        check(doc.redo(), "redo reapplies the replacement");
        doc.setLayerVisible(0, false);
        check(doc.compositeTone(1, 1) == 255, "hidden reference shows nothing");
        check(doc.undo() && doc.compositeTone(1, 1) == 0, "hide undo restores the picture");
        doc.addLayer();
        check(!doc.isReference(doc.activeLayer()) && doc.activeLayer() == 2 && doc.layerCount() == 3,
                "a new layer lands above the reference");
        doc.selectLayer(1);
        doc.moveLayer(0);
        check(doc.isReference(0), "a layer cannot be moved under the reference");
        doc.clearAllLayers();
        check(doc.hasReference() && doc.compositeTone(1, 1) == 0, "clearing the page leaves the reference alone");
        check(doc.undo() && doc.hasReference(), "clear-all undo keeps the reference too");
        // 8 paint layers + the reference fill the stack; one paint layer always stays.
        ToneDocument full = new ToneDocument(8, 8);
        full.setReference(placed);
        while (full.paintLayers() < ToneDocument.MAX_LAYERS) full.addLayer();
        check(full.layerCount() == ToneDocument.MAX_STACK, "8 paint layers + reference = MAX_STACK");
        try { full.addLayer(); throw new AssertionError("exceeded the stack"); }
        catch (IllegalStateException expected) {}
        while (full.paintLayers() > 1) full.removeLayer();
        check(full.paintLayers() == 1 && full.hasReference(), "removing stops at one paint layer");
        try { full.removeLayer(); throw new AssertionError("removed the last paint layer"); }
        catch (IllegalStateException expected) {}
        check(full.undo() && full.paintLayers() == 2, "undo brings the layer back");
        doc.removeReference();
        check(!doc.hasReference() && doc.layerCount() == 2, "remove drops only the reference");
        check(doc.undo() && doc.hasReference(), "remove undo restores the reference");
        System.out.println("PASS: reference stack rules, selection refusal, replace, hide, clear, stack limit");
    }

    private static void dragUndo() {
        ToneDocument doc = new ToneDocument(16, 16);
        ReferenceImage ref = checkerboard(8, 8);
        doc.setReference(new ReferenceImage(ref.lum, ref.alpha, 8, 8, ReferenceImage.place(8, 8, 8, 8, 1, 0)));
        byte[] before = doc.layerSnapshot().layers.get(0).tones.clone();
        doc.beginReferenceEdit();
        for (int i = 1; i <= 5; i++)
            doc.placeReference(ReferenceImage.place(8 + i, 8 + i * 2, 8, 8, 1, i * 5));
        doc.endReferenceEdit();
        byte[] after = doc.layerSnapshot().layers.get(0).tones.clone();
        check(!Arrays.equals(before, after), "the drag changed the pixels");
        doc.undo();
        check(Arrays.equals(before, doc.layerSnapshot().layers.get(0).tones),
                "one undo restores pre-drag pixels, not the last step's");
        doc.undo();
        check(!doc.hasReference(), "the next undo is the import, so the drag was one step, not five");
        doc.redo(); // import back
        doc.redo();
        check(Arrays.equals(after, doc.layerSnapshot().layers.get(0).tones), "redo reapplies the whole drag");
        // A drag that went nowhere must not consume an undo slot.
        int depth = unwind(doc);
        doc.beginReferenceEdit();
        doc.placeReference(doc.referencePlacement());
        doc.endReferenceEdit();
        check(unwind(doc) == depth, "a drag that went nowhere adds no undo step");
        check(Arrays.equals(after, doc.layerSnapshot().layers.get(0).tones), "a no-op drag changed nothing");
        try { doc.placeReference(doc.referencePlacement()); throw new AssertionError("placed without an open step"); }
        catch (IllegalStateException expected) {}
        System.out.println("PASS: many placements inside one begin/end = one undo step; no-op drags record nothing");
    }

    /** Undo everything, count the steps, and redo them all back to where we started. */
    private static int unwind(ToneDocument doc) {
        int depth = 0;
        while (doc.undo()) depth++;
        while (doc.redo()) { }
        return depth;
    }

    private static void expansion() {
        ToneDocument doc = new ToneDocument(16, 16);
        ReferenceImage ref = checkerboard(8, 8);
        doc.setReference(new ReferenceImage(ref.lum, ref.alpha, 8, 8, ReferenceImage.place(4, 4, 8, 8, 1, 0)));
        byte[] before = doc.layerSnapshot().layers.get(0).tones.clone();
        doc.expand(4, 4, 0, 0);
        check(doc.width == 20 && doc.height == 20, "page grew");
        check(doc.hasReference(), "reference survives expansion");
        float[] placement = ReferenceImage.decompose(doc.referencePlacement(), 8, 8);
        near(placement[0], 8, "reference center moved right with the new paper");
        near(placement[1], 8, "reference center moved down with the new paper");
        check(u(doc.layerSnapshot().layers.get(0).tones[0]) == 255, "new paper starts blank");
        doc.undo();
        check(doc.width == 16 && doc.height == 16
                && Arrays.equals(before, doc.layerSnapshot().layers.get(0).tones),
                "undo restores the page and the reference together");
        System.out.println("PASS: canvas expansion moves the reference; undo restores both");
    }

    private static void persistence() throws Exception {
        ToneDocument doc = new ToneDocument(16, 16);
        paint(doc, 12, 12, 30);
        ReferenceImage ref = checkerboard(8, 8);
        float[] placement = ReferenceImage.place(5, 6, 8, 8, 1.5, 20);
        doc.setReference(new ReferenceImage(ref.lum, ref.alpha, 8, 8, placement));
        byte[] tones = doc.layerSnapshot().layers.get(0).tones.clone();
        byte[] packed = DrawingBook.encode(doc);
        // The validate-only path (other book pages) must consume the reference planes too.
        DocumentCodec.validate(new ByteArrayInputStream(packed), 16, 16);
        ToneDocument read = DocumentCodec.read(new ByteArrayInputStream(packed));
        check(read.hasReference() && read.isReference(0), "reference survives the round trip");
        check(Arrays.equals(tones, read.layerSnapshot().layers.get(0).tones), "reference pixels round trip exactly");
        float[] placed = read.referencePlacement();
        for (int i = 0; i < 6; i++) near(placed[i], placement[i], "placement float " + i);
        check(read.compositeTone(12, 12) == 30, "paint round trips above the reference");
        // The lock survives a save and refuses move/replace/remove.
        doc.setReferenceLocked(true);
        ToneDocument locked = DocumentCodec.read(new ByteArrayInputStream(DrawingBook.encode(doc)));
        check(locked.referenceLocked(), "lock survives the save");
        try { locked.placeReference(placement); throw new AssertionError("moved a locked reference"); }
        catch (IllegalStateException expected) {}
        try { locked.setReference(new ReferenceImage(ref.lum, ref.alpha, 8, 8, placement));
            throw new AssertionError("replaced a locked reference"); } catch (IllegalStateException expected) {}
        try { locked.removeReference(); throw new AssertionError("removed a locked reference"); }
        catch (IllegalStateException expected) {}
        locked.setReferenceLocked(false);
        check(!locked.referenceLocked(), "unlock works and is not an undo step");
        locked.beginReferenceEdit(); locked.placeReference(ReferenceImage.place(6, 6, 8, 8, 1.5, 20));
        locked.endReferenceEdit();
        check(locked.canUndo(), "unlocked reference moves again");
        // Reference + masking fluid round trip together, and neither alone writes the other's data.
        doc.paintMaskingFluid(true); doc.begin(); doc.paintTone(1, 1, 0); doc.finish(); doc.paintMaskingFluid(false);
        ToneDocument both = DocumentCodec.read(new ByteArrayInputStream(DrawingBook.encode(doc)));
        check(both.hasReference() && both.hasMask(), "reference and masking fluid round trip together");
        ToneDocument maskOnly = new ToneDocument(8, 8);
        maskOnly.paintMaskingFluid(true); maskOnly.begin(); maskOnly.paintTone(2, 2, 0); maskOnly.finish(); maskOnly.paintMaskingFluid(false);
        ToneDocument maskRead = DocumentCodec.read(new ByteArrayInputStream(DrawingBook.encode(maskOnly)));
        check(maskRead.hasMask() && !maskRead.hasReference(), "masking fluid alone writes no reference");
        // A flipped byte in the compressed stream is rejected.
        byte[] damaged = DrawingBook.encode(doc).clone();
        damaged[damaged.length / 2] ^= 0x01;
        try { DocumentCodec.read(new ByteArrayInputStream(damaged)); throw new AssertionError("damaged file accepted"); }
        catch (IOException expected) {}
        // A multi-page book keeps its reference pages.
        DrawingBook book = new DrawingBook(doc);
        book.addPage();
        book.select(1);
        check(!book.current().hasReference(), "a fresh page has no reference");
        book.select(0);
        check(book.current().hasReference(), "the reference page survives page switching");
        byte[] bookBytes;
        { ByteArrayOutputStream out = new ByteArrayOutputStream();
          BookCodec.write(out, book.snapshot()); bookBytes = out.toByteArray(); }
        DrawingBook reopened = BookCodec.read(new ByteArrayInputStream(bookBytes));
        reopened.select(0);
        check(reopened.current().hasReference(), "the reference survives the book file");
        System.out.println("PASS: reference persistence, lock, masking fluid together and alone, damage rejection, book pages");
    }

    private static void sliding() {
        int[][] vectors = {{31, -12}, {-20, 27}, {13, 13}};
        for (int[] vector : vectors) {
            ToneDocument doc = new ToneDocument(32, 32);
            ReferenceImage ref = checkerboard(12, 12);
            float[] start = ReferenceImage.place(16, 16, 12, 12, 1.25, 15);
            doc.setReference(new ReferenceImage(ref.lum, ref.alpha, 12, 12, start));
            doc.beginReferenceEdit();
            // Five uneven steps with a deliberate sub-pixel remainder each.
            double stepX = vector[0] / 5.0 + 0.37, stepY = vector[1] / 5.0 - 0.41;
            double movedX = 0, movedY = 0;
            int previousShiftX = 0, previousShiftY = 0;
            for (int i = 0; i < 5; i++) {
                movedX += stepX; movedY += stepY;
                doc.slideReference(stepX, stepY);
                float[] shown = doc.referencePlacement();
                int shiftX = Math.round(shown[2] - start[2]), shiftY = Math.round(shown[5] - start[5]);
                check(shiftX != previousShiftX || shiftY != previousShiftY,
                        "every step moves the drawn layer for vector " + Arrays.toString(vector));
                previousShiftX = shiftX; previousShiftY = shiftY;
                // The drawn pixels equal a straight raster of the transform now shown.
                byte[] expectedTones = new byte[32 * 32], expectedCoverage = new byte[32 * 32];
                ReferenceImage.raster(ref.lum, ref.alpha, 12, 12, shown,
                        expectedTones, expectedCoverage, 32, 32, 0, 0, 32, 32);
                ToneDocument.Layer layer = doc.layerSnapshot().layers.get(0);
                check(Arrays.equals(expectedTones, layer.tones),
                        "drawn pixels match a straight raster mid-drag for vector " + Arrays.toString(vector));
                check(Arrays.equals(expectedCoverage, layer.alpha),
                        "drawn coverage matches a straight raster mid-drag for vector " + Arrays.toString(vector));
            }
            doc.endReferenceEdit();
            byte[] landed = doc.layerSnapshot().layers.get(0).tones.clone();
            // Landing equals a direct placeReference of the total delta.
            ToneDocument direct = new ToneDocument(32, 32);
            direct.setReference(new ReferenceImage(ref.lum, ref.alpha, 12, 12, start));
            direct.beginReferenceEdit();
            direct.placeReference(ReferenceImage.translated(start, stepX * 5, stepY * 5));
            direct.endReferenceEdit();
            check(Arrays.equals(landed, direct.layerSnapshot().layers.get(0).tones),
                    "the slide lands exactly where a direct placement of the total delta lands");
            check(doc.undo() && Arrays.equals(doc.layerSnapshot().layers.get(0).tones,
                    preDrag(ref, start)), "slide undo restores the pre-drag picture");
            check(doc.redo() && Arrays.equals(doc.layerSnapshot().layers.get(0).tones, landed),
                    "slide redo reapplies it");
        }
        System.out.println("PASS: whole-pixel sliding follows the finger, matches straight rasters, lands exact, undoes");
    }

    private static byte[] preDrag(ReferenceImage ref, float[] start) {
        byte[] tones = new byte[32 * 32], coverage = new byte[32 * 32];
        ReferenceImage.raster(ref.lum, ref.alpha, 12, 12, start, tones, coverage, 32, 32, 0, 0, 32, 32);
        return tones;
    }
}
