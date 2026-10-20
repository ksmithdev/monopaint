package io.github.mpdairy.monopaint;

import java.util.Arrays;

/** The selection's geometry, and how the document enforces it while painting, copying and pasting. */
public final class SelectionChecks {
    public static void main(String[] args) throws Exception {
        band();
        border();
        paintingStaysInside();
        copyAndPaste();
        cutSelection();
        moveSelection();
        pasteInsideSelection();
        secondMoveKeepsArtworkUnderneath();
        layerChangeKeepsSelection();
        resizeDropsSelection();
        settingsRoundTrip();
        System.out.println("PASS: selection band, border, painting clipped to it, copy, move, paste and settings round trip");
    }

    private static void band() {
        // A closed loop selects everything inside it; the traced line itself is the outline.
        // A 10x10 square loop from (2,2) to (12,12) on a 32x16 page.
        float[] loop = {2, 2, 12, 2, 12, 12, 2, 12};
        Selection lasso = Selection.lasso(loop, 4, 32, 16);
        check(lasso.contains(6, 6) && lasso.contains(11, 11), "Inside the loop is selected");
        check(!lasso.contains(0, 0) && !lasso.contains(14, 8), "Outside the loop is not selected");
        check(lasso.left == 2 && lasso.right == 12 && lasso.top == 2 && lasso.bottom == 12,
                "Lasso bounds: " + lasso.left + " " + lasso.right + " " + lasso.top + " " + lasso.bottom);
        // The outline is the traced line, closed back to its start - no band around it.
        float[][] outline = lasso.border();
        check(outline.length == 1, "The outline is the single traced line, got " + outline.length);
        check(outline[0].length == 10, "The closed line returns to its start: " + outline[0].length);
        check(outline[0][8] == 2 && outline[0][9] == 2, "The line closes on the first point");
        // A self-crossing trace (figure eight) selects both lobes by even-odd crossing.
        float[] eight = {2, 2, 12, 12, 12, 2, 2, 12};
        Selection figure = Selection.lasso(eight, 4, 32, 16);
        check(figure.contains(3, 4) && figure.contains(11, 10), "Both lobes of a figure eight are selected");
        check(!figure.contains(7, 4), "The middle of a figure eight is not selected");
        // An open trace that never encloses an area selects nothing.
        check(Selection.lasso(new float[]{2, 2, 20, 8}, 2, 32, 16).empty(), "A line encloses nothing");
        check(Selection.lasso(new float[]{40, 40, 40, 40}, 1, 32, 16).empty(), "Off-page trace selects nothing");
    }

    private static void border() {
        java.util.BitSet pixels = new java.util.BitSet(100);
        for (int y = 2; y < 5; y++) for (int x = 2; x < 5; x++) pixels.set(y * 10 + x);
        Selection square = Selection.of(pixels, 10);
        float[][] edges = square.border();
        check(count(edges, 2, 2, 2, 5) && count(edges, 5, 2, 5, 5), "Square has its left and right edges");
        check(count(edges, 2, 2, 5, 2) && count(edges, 2, 5, 5, 5), "Square has its top and bottom edges");
        check(edges.length == 4, "A solid square's border is four merged runs, got " + edges.length);
        // A hole inside the selection keeps its own border.
        pixels.clear(3 * 10 + 3);
        Selection holed = Selection.of(pixels, 10);
        float[][] hole = holed.border();
        check(count(hole, 3, 3, 4, 3) && count(hole, 3, 4, 4, 4)
                && count(hole, 3, 3, 3, 4) && count(hole, 4, 3, 4, 4), "A hole is outlined on all four sides");
        check(Selection.of(new java.util.BitSet(100), 10).border().length == 0, "Empty selection has no border");
    }

    private static boolean count(float[][] edges, float x0, float y0, float x1, float y1) {
        for (float[] line : edges) for (int i = 0; i + 3 < line.length; i += 2)
            if (line[i] == x0 && line[i+1] == y0 && line[i+2] == x1 && line[i+3] == y1) return true;
        return false;
    }

    private static void paintingStaysInside() {
        ToneDocument doc = new ToneDocument(16, 16);
        doc.setSelection(square(16, 4, 4, 8, 8));
        doc.begin();
        for (int y = 2; y < 12; y++) for (int x = 2; x < 12; x++) doc.setTone(x, y, 0);
        doc.finish();
        check(doc.tone(6, 6) == 0, "Paint inside the selection lands");
        check(doc.tone(2, 2) == 255 && doc.tone(10, 10) == 255, "Paint outside the selection is blocked");
        check(doc.tone(2, 6) == 255 && doc.tone(9, 6) == 255, "Paint just outside each edge is blocked");
        doc.setSelection(null);
        doc.begin(); for (int y = 2; y < 12; y++) doc.paintSpan(2, 12, y, 0); doc.finish();
        check(doc.tone(2, 2) == 0, "Without a selection everything paints again");
    }

    private static void copyAndPaste() {
        ToneDocument doc = new ToneDocument(24, 24);
        doc.begin();
        for (int y = 2; y < 6; y++) for (int x = 2; x < 6; x++) doc.paintTone(x, y, 40);
        doc.finish();
        doc.setSelection(square(24, 2, 2, 6, 6));
        Clipboard patch = doc.copySelection();
        check(patch.width == 4 && patch.height == 4 && patch.pixels.cardinality() == 16, "Copy holds the selection");
        // Untouched paper inside the selection must not travel: it would lay white over
        // whatever the copy lands on.
        doc.setSelection(square(24, 2, 2, 10, 10));
        Clipboard sparse = doc.copySelection();
        check(sparse.pixels.cardinality() == 16, "Copy skips the empty paper in the box, got " + sparse.pixels.cardinality());
        doc.setSelection(null);
        doc.begin(); for (int y = 10; y < 18; y++) for (int x = 10; x < 18; x++) doc.paintTone(x, y, 200); doc.finish();
        doc.setSelection(sparse.movedBy(8, 8, 24, 24));
        doc.paste(sparse, 8, 8);
        check(doc.tone(12, 12) == 40, "The sparse copy paints where it holds pixels");
        check(doc.tone(17, 17) == 200, "The sparse copy leaves the paint underneath alone");
        doc.addLayer();
        // The pad moves the selection along with the copy before pasting, so the paste lands.
        doc.setSelection(patch.movedBy(10, 10, 24, 24));
        doc.paste(patch, 10, 10);
        check(doc.tone(12, 12) == 40 && doc.tone(15, 15) == 40, "Paste draws the copy at its new place");
        check(doc.compositeTone(2, 2) == 40, "Paste leaves the original pixels alone");
        doc.undo();
        check(doc.tone(12, 12) == 255, "One undo removes the whole paste");
        doc.redo();
        check(doc.tone(12, 12) == 40, "Redo brings it back");
    }

    private static void cutSelection() {
        ToneDocument doc = new ToneDocument(24, 24);
        doc.begin();
        for (int y = 2; y < 6; y++) for (int x = 2; x < 6; x++) doc.paintTone(x, y, 40);
        doc.finish();
        doc.setSelection(square(24, 2, 2, 6, 6));
        Clipboard patch = doc.copySelection();
        // Cutting and dropping elsewhere moves the pixels in one undoable step.
        doc.moveCut(patch, 10, 0);
        check(doc.tone(2, 2) == 255 && doc.tone(5, 5) == 255, "A cut drop empties the original spot");
        check(doc.tone(12, 2) == 40 && doc.tone(15, 5) == 40, "A cut drop paints at the new place");
        check(doc.selection().contains(12, 2) && !doc.selection().contains(2, 2), "The selection follows the cut");
        doc.undo();
        check(doc.tone(2, 2) == 40 && doc.tone(12, 2) == 255, "One undo reverses the whole cut");
        check(doc.selection().contains(2, 2), "Undo puts the selection back on the original pixels");
    }

    private static void moveSelection() {
        ToneDocument doc = new ToneDocument(24, 24);
        doc.begin();
        for (int y = 2; y < 6; y++) for (int x = 2; x < 6; x++) doc.paintTone(x, y, 40);
        doc.finish();
        doc.setSelection(square(24, 2, 2, 6, 6));
        doc.moveSelection(8, 4);
        check(doc.tone(10, 6) == 40 && doc.tone(13, 9) == 40, "Move redraws the pixels at the offset");
        check(doc.tone(2, 2) == 255 && doc.tone(5, 5) == 255, "Move empties where the pixels came from");
        check(doc.selection().contains(10, 6) && !doc.selection().contains(2, 2), "The selection follows the pixels");
        doc.undo();
        check(doc.tone(2, 2) == 40 && doc.tone(10, 6) == 255, "One undo reverses the whole move");
        check(doc.selection() != null && doc.selection().contains(2, 2) && !doc.selection().contains(10, 6),
                "Undo puts the selection back where the move started");
        doc.redo();
        check(doc.tone(10, 6) == 40, "Redo brings the move back");
        check(doc.selection().contains(10, 6) && !doc.selection().contains(2, 2),
                "Redo moves the selection forward again");
    }

    /**
     * A placed copy covers blank paper and other artwork alike. Moving it a second time
     * must carry only its own pixels: reading the whole selected area from the layer
     * would drag the artwork underneath along and wipe it from where it was.
     */
    private static void secondMoveKeepsArtworkUnderneath() {
        ToneDocument doc = new ToneDocument(32, 32);
        doc.begin();
        for (int y = 2; y < 6; y++) for (int x = 2; x < 6; x++) doc.paintTone(x, y, 40);
        doc.finish();
        // Some artwork the copy will be dropped on top of.
        doc.begin(); for (int y = 10; y < 20; y++) for (int x = 10; x < 20; x++) doc.paintTone(x, y, 200); doc.finish();
        doc.setSelection(square(32, 2, 2, 6, 6));
        Clipboard patch = doc.copySelection();
        doc.wipeSelection();
        // The pad selects the copy's placed area during the drag, then drops it.
        doc.setSelection(patch.selectionMovedBy(8, 8, 32, 32));
        doc.pastePlaced(patch, 8, 8, false);
        check(doc.tone(10, 10) == 40, "The copy lands over the artwork");
        check(doc.tone(15, 15) == 200, "The artwork under the copy is intact");
        // Nudge the copy again. It overlaps the artwork on the way.
        doc.moveSelection(2, 2);
        check(doc.tone(12, 12) == 40, "The second move carries the copy");
        check(doc.tone(10, 10) == 200, "The second move uncovers the artwork the copy was sitting on, got "
            + doc.tone(10, 10));
        check(doc.tone(17, 17) == 200, "The second move leaves the artwork it crossed alone, got " + doc.tone(17, 17));
        check(doc.tone(19, 19) == 200, "Artwork beyond the copy's new footprint survives");
        // One undo reverses the whole second move, artwork and copy back as they were.
        doc.undo();
        check(doc.tone(10, 10) == 40 && doc.tone(12, 12) == 40,
                "Undo puts the copy back over the artwork it was covering");
        check(doc.tone(15, 15) == 200, "Undo leaves the untouched artwork alone");
        // Cutting a copy that is already placed must uncover the artwork too, not a hole.
        Clipboard again = doc.copySelection();
        check(doc.wipeSelection(), "A placed copy can be cut again");
        check(doc.tone(12, 12) == 200,
            "Cutting a placed copy uncovers the artwork under it, got " + doc.tone(12, 12));
        check(again.pixels.cardinality() == 16, "The recut copy still holds all its pixels");
    }

    private static void pasteInsideSelection() {
        ToneDocument doc = new ToneDocument(24, 24);
        doc.begin();
        for (int y = 2; y < 6; y++) for (int x = 2; x < 6; x++) doc.paintTone(x, y, 40);
        doc.finish();
        doc.setSelection(square(24, 2, 2, 6, 6));
        Clipboard patch = doc.copySelection();
        doc.addLayer();
        // The patch came from 2,2, so a dx of 10 lands its first pixel at 12,12. Only the
        // selection's 2x2 there accepts the paste; the rest of the 4x4 is blocked.
        doc.setSelection(square(24, 12, 12, 14, 14));
        doc.paste(patch, 10, 10);
        check(doc.tone(12, 12) == 40 && doc.tone(13, 13) == 40, "Paste lands inside the selection");
        check(doc.tone(15, 15) == 255, "Paste outside the selection is blocked");
    }

    private static void layerChangeKeepsSelection() {
        ToneDocument doc = new ToneDocument(24, 24);
        Selection selection = square(24, 2, 2, 8, 8);
        doc.setSelection(selection);
        doc.addLayer();
        check(doc.selection() == selection, "Adding a layer keeps the selection");
        doc.selectLayer(0);
        check(doc.selection() == selection, "Switching layers keeps the selection");
        doc.removeLayer();
        check(doc.selection() == selection, "Removing a layer keeps the selection");
    }

    private static void resizeDropsSelection() {
        ToneDocument doc = new ToneDocument(24, 24);
        doc.setSelection(square(24, 2, 2, 8, 8));
        doc.expand(4, 4, 0, 0);
        check(doc.selection() == null, "A resized page drops its selection");
    }

    private static void settingsRoundTrip() throws Exception {
        ToolLibrary library = new ToolLibrary();
        library.select(ToolSettings.Tool.SELECT);
        check(library.current().selectMode == ToolSettings.SelectMode.FREEHAND, "Selection starts in freehand mode");
        library.edit(library.current().selectMode(ToolSettings.SelectMode.FILL).tolerance(33).size(64));
        ToolSettings edited = library.current();
        ToolLibrary restored = ToolLibrary.decode(library.encode());
        check(restored.current().equals(edited) && restored.current().selectMode == ToolSettings.SelectMode.FILL
                && restored.current().tolerance == 33 && restored.current().maximum == 64,
                "Selection settings round trip through the preset format");
        check(edited.description().equals("Fill selection"), "Description: " + edited.description());
        check(ToolSettings.SelectMode.MOVE.label.equals("Move"), "Move mode is the third mode");

        // Move is a placement state, not a mode to come back to: re-selecting the tool
        // must land on the lasso, not a stuck Move with nothing pending.
        library.edit(library.current().selectMode(ToolSettings.SelectMode.MOVE));
        check(library.current().selectMode == ToolSettings.SelectMode.MOVE, "Move mode can be entered");
        library.select(ToolSettings.Tool.SELECT);
        check(library.current().selectMode == ToolSettings.SelectMode.FREEHAND,
                "Re-selecting Selection returns to freehand, not a stuck Move");
        library.select(ToolSettings.Tool.BRUSH);
        library.select(ToolSettings.Tool.SELECT);
        check(library.current().selectMode == ToolSettings.SelectMode.FREEHAND,
                "Freehand survives a trip through another tool");
        // A restart reads saved settings rather than going through select(), so it needs
        // the same rule: coming back to the app must not land in a stuck Move.
        library.edit(library.current().selectMode(ToolSettings.SelectMode.MOVE));
        check(ToolLibrary.decode(library.encode()).current().selectMode == ToolSettings.SelectMode.FREEHAND,
                "Restarting never restores a stuck Move mode");
    }

    private static Selection square(int stride, int left, int top, int right, int bottom) {
        java.util.BitSet pixels = new java.util.BitSet(stride * stride);
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) pixels.set(y * stride + x);
        return Selection.of(pixels, stride);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
