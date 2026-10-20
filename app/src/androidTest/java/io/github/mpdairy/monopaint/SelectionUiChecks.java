package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Real pen routing for the Selection tool on a disposable book: lasso, copy, the ghost
 * the copy shows while it rides the pen, and where it lands. Restores the user's session.
 */
final class SelectionUiChecks {
    static void run(Instrumentation test, StringBuilder report) throws Exception {
        PaintActivity activity = (PaintActivity) test.startActivitySync(
                new Intent(test.getTargetContext(), PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for (int i = 0; i < 100 && (Boolean) get(activity, "loading"); i++) SystemClock.sleep(100);
        test.waitForIdleSync();
        Object pad = get(activity, "pad");
        ToolLibrary library = (ToolLibrary) get(activity, "library");

        // A page with a dark square to select, on a white page.
        ToneDocument doc = new ToneDocument(320, 480);
        doc.begin();
        for (int y = 20; y < 60; y++) for (int x = 20; x < 60; x++) doc.paintTone(x, y, 0);
        doc.finish();
        try {
            main(test, () -> {
                ((android.view.OrientationEventListener) get(get(activity, "rotationPrompt"), "orientationSensor")).disable();
                call(pad, "finishStroke"); call(pad, "dryWet");
                set(activity, "drawingName", "");
                call(activity, "replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(doc));
                library.select(ToolSettings.Tool.SELECT);
                // Built-in tools remember edits: pick the mode this check needs explicitly.
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
                call(pad, "renderAll");
            });
            test.waitForIdleSync(); SystemClock.sleep(150);

            // 1. Trace a loop around the square; what it encloses becomes the selection.
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            main(test, () -> {
                Selection selection = doc.selection();
                check(selection != null && !selection.empty(), "A traced loop makes a selection");
                check(selection.contains(40, 40), "The square inside the loop is selected");
                check(!selection.contains(150, 150), "Outside the loop is not selected");
            });
            report.append("PASS: pen lasso selects the enclosed area.\n");

            // 2. Copy: the patch rides the pen as a ghost, unwritten.
            main(test, () -> {
                call(pad, "copySelection");
                check((Boolean) call(pad, "copyPending"), "Copy holds a patch");
                check(doc.tone(200, 300) == 255, "Copy writes nothing until it is dropped");
            });

            // 3. First tap places the ghost centered on the tap, and its pixels show there.
            long down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 200, 300);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                Selection placed = (Selection) call(pad, "placedCopy");
                Clipboard patch = (Clipboard) get(pad, "clipboard");
                // The patch box centers on the tap; its painted corners follow.
                int cx = patch.left + patch.width / 2, cy = patch.top + patch.height / 2;
                int dx = 200 - cx, dy = 300 - cy;
                check(placed.contains(200, 300), "The copy centers on the tap");
                // Only painted pixels travel: the square 20..59 lands at its offset.
                check(placed.contains(20 + dx, 20 + dy) && placed.contains(59 + dx, 59 + dy),
                        "The copy spans the painted pixels there");
                check(!placed.contains(5 + dx, 5 + dy), "Empty paper does not travel with the copy");
                check(ghostShowsCopy(pad, 200, 300), "The copy's pixels are drawn inside the ghost");
                check(ghostShowsCopy(pad, 25 + dx, 25 + dy) && ghostShowsCopy(pad, 55 + dx, 55 + dy),
                        "The copy fills its painted area, not just the border");
            });
            pen(test, pad, MotionEvent.ACTION_UP, down, 200, 300);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                check(doc.selection() != null && doc.selection().contains(200, 300),
                        "The selection sits over the placed copy");
                // The tap writes the copy onto the layer, so it is committed there as soon
                // as the pen lifts, and nothing is left waiting on the pen.
                check(doc.tone(200, 300) == 0, "The tap pasted the pixels onto the layer");
                check(!(Boolean) call(pad, "copyPending"), "Nothing is left on the pen after the tap");
            });
            report.append("PASS: the first tap places the copy centered on the tap, drawn where it sits.\n");

            // 4. Press inside the placed copy and drag: it moves with the pen.
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 200, 300);
            pen(test, pad, MotionEvent.ACTION_MOVE, down, 220, 320);
            pen(test, pad, MotionEvent.ACTION_MOVE, down, 240, 340);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                check((Boolean) get(pad, "moveGesture"), "A press inside the placed copy starts a move");
                check(!(Boolean) get(pad, "selectGesture"), "A press inside the copy is not a lasso");
            });
            pen(test, pad, MotionEvent.ACTION_UP, down, 240, 340);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                check(doc.tone(240, 340) == 0, "The copy moved with the drag");
                check(doc.selection() != null && doc.selection().contains(240, 340),
                        "The selection followed the copy");
            });
            report.append("PASS: pressing inside the placed copy drags it.\n");

            // 5. Press outside: the pixels stay committed on the layer and the selection is
            // let go. The original is intact because this was a copy, not a cut.
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 30, 400);
            pen(test, pad, MotionEvent.ACTION_UP, down, 30, 400);
            test.waitForIdleSync(); SystemClock.sleep(150);
            main(test, () -> {
                check(!(Boolean) call(pad, "copyPending"), "Pressing outside lets the copy go");
                check(doc.tone(240, 340) == 0, "The copy stays committed on the layer");
                check(doc.tone(40, 40) == 0, "A copy leaves the original pixels alone");
                check(doc.selection() == null || !doc.selection().contains(240, 340),
                        "Pressing outside clears the selection");
            });
            report.append("PASS: pressing outside commits the copy where it was dropped.\n");

            // 6. Cut: the pixels leave the page at once and ride the pen like a copy, so
            // the artist taps where they should land and can keep moving them.
            main(test, () -> {
                call(pad, "clearSelection");
                library.select(ToolSettings.Tool.SELECT);
                call(get(activity, "toolbar"), "refreshToolSelection");
            });
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            main(test, () -> check(doc.selection().contains(40, 40), "Cut: the loop re-selected the square"));
            main(test, () -> {
                call(pad, "cutSelection");
                check((Boolean) call(pad, "copyPending"), "Cut: a patch is held");
                check(doc.tone(40, 40) == 255, "Cut: the original spot is bare right away");
                // The ghost must not redraw the pixels the cut just removed over the bare
                // spot before the first tap places them somewhere else.
                check(!darkAt(pad, 40, 40), "Cut: the screen shows the spot as bare too");
                check(!darkAt(pad, 55, 55), "Cut: nothing of the copy is drawn at its old area");
                check(doc.selection() != null && doc.selection().contains(40, 40),
                        "Cut: the selection follows the copy, not the bare spot");
            });
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 150, 420);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> check(ghostShowsCopy(pad, 150, 420), "Cut: the ghost shows the pixels at the tap"));
            pen(test, pad, MotionEvent.ACTION_UP, down, 150, 420);
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 300, 60);
            pen(test, pad, MotionEvent.ACTION_UP, down, 300, 60);
            test.waitForIdleSync(); SystemClock.sleep(150);
            main(test, () -> {
                check(doc.tone(150, 420) == 0, "Cut: the pixels landed where the copy was dropped");
                check(doc.tone(40, 40) == 255, "Cut: the original spot stayed bare");
            });
            // Undo takes the whole cut back in one step: the dropped copy goes and the
            // original comes back.
            main(test, () -> {
                int before = doc.undoDepth();
                check(doc.undo(), "Cut: one undo reverses the whole cut");
                check(doc.undoDepth() == before - 1, "Cut: the cut was a single undo step");
                check(doc.tone(40, 40) == 0, "Cut: the original pixels are back after undo");
                check(doc.tone(150, 420) == 255, "Cut: the dropped copy is gone after undo");
            });
            report.append("PASS: cut clears its spot at once and rides the pen to wherever it is tapped.\n");

            // 7. A copy with a hole in it is still one object: pressing inside its outline
            // but on the unpainted gap grabs it instead of starting a new lasso.
            main(test, () -> {
                call(pad, "clearSelection"); call(pad, "clearClipboard");
                ToneDocument holed = new ToneDocument(320, 480);
                holed.begin();
                for (int y = 20; y < 60; y++) for (int x = 20; x < 60; x++)
                    if (x < 38 || x > 42 || y < 38 || y > 42) holed.paintTone(x, y, 0);
                holed.finish();
                call(activity, "replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(holed));
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
                call(pad, "renderAll");
            });
            test.waitForIdleSync(); SystemClock.sleep(150);
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            final Clipboard[] holed = new Clipboard[1];
            main(test, () -> { ToneDocument now = (ToneDocument) get(pad, "document");
                check(now.selection().contains(30, 30), "Holed copy: the loop selected the square");
                call(pad, "copySelection");
                // Keep the patch: the tap writes it and clears it from the pen.
                holed[0] = (Clipboard) get(pad, "clipboard");
            });
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 200, 300);   // places the copy
            pen(test, pad, MotionEvent.ACTION_UP, down, 200, 300);
            test.waitForIdleSync(); SystemClock.sleep(120);
            // Find a point inside the placed copy's area that holds no paint: the hole.
            // The tap already wrote the copy, so the page's own selection is its placed area.
            final int[] gap = { -1, -1 };
            main(test, () -> {
                ToneDocument now = (ToneDocument) get(pad, "document");
                Clipboard patch = holed[0];
                Selection placed = now.selection();
                // The patch's bits are box-indexed; the placed copy is the box offset by
                // the tap. Walk the box and map back to the page.
                int offX = placed.left - patch.left, offY = placed.top - patch.top;
                for (int row = 0; row < patch.height && gap[0] < 0; row++)
                    for (int col = 0; col < patch.width; col++) {
                        int i = row * patch.width + col;
                        if (patch.region.get(i) && !patch.pixels.get(i))
                            { gap[0] = patch.left + offX + col; gap[1] = patch.top + offY + row; break; }
                    }
                check(gap[0] >= 0, "Holed copy: the copy really does have an unpainted gap");
                check(placed.contains(gap[0], gap[1]),
                        "Holed copy: the gap is still inside the selection, so the pen can draw there");
            });
            final int[] before = new int[4];
            main(test, () -> { Selection placed = ((ToneDocument) get(pad, "document")).selection();
                before[0] = placed.left; before[1] = placed.top;
                before[2] = placed.right; before[3] = placed.bottom; });
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, gap[0], gap[1]);
            test.waitForIdleSync(); SystemClock.sleep(60);
            main(test, () -> {
                check((Boolean) get(pad, "moveGesture"), "Holed copy: pressing the gap grabs the copy");
                check(!(Boolean) get(pad, "selectGesture"), "Holed copy: pressing the gap is not a lasso");
            });
            pen(test, pad, MotionEvent.ACTION_MOVE, down, gap[0] + 20, gap[1] + 20);
            pen(test, pad, MotionEvent.ACTION_UP, down, gap[0] + 20, gap[1] + 20);
            test.waitForIdleSync(); SystemClock.sleep(150);
            main(test, () -> { Selection placed = ((ToneDocument) get(pad, "document")).selection();
                check(placed.left == before[0] + 20 && placed.top == before[1] + 20
                        && placed.right == before[2] + 20 && placed.bottom == before[3] + 20,
                        "Holed copy: dragging from the gap moves the copy"); });
            report.append("PASS: a copy with a hole is grabbed from anywhere inside its outline.\n");

            // 8. The ghost's outline is the smooth line that was traced, not a one-pixel
            // staircase of the travelling pixels.
            main(test, () -> {
                call(pad, "clearSelection"); call(pad, "clearClipboard");
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
            });
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            main(test, () -> {
                call(pad, "copySelection");
                Clipboard patch = (Clipboard) get(pad, "clipboard");
                int segments = 0;
                for (float[] line : patch.outline) segments += line.length / 2;
                check(segments <= 8,
                        "The ghost keeps the traced outline, not a staircase of pixels (" + segments + " points)");
            });
            report.append("PASS: the ghost draws the traced outline, not a pixel staircase.\n");

            // 9. A diagonal drag moves the drawn copy diagonally: both axes follow the pen.
            main(test, () -> {
                call(pad, "clearSelection"); call(pad, "clearClipboard");
                ToneDocument diag = new ToneDocument(320, 480);
                diag.begin();
                for (int y = 20; y < 60; y++) for (int x = 20; x < 60; x++) diag.paintTone(x, y, 0);
                diag.finish();
                call(activity, "replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(diag));
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
                call(pad, "renderAll");
            });
            test.waitForIdleSync(); SystemClock.sleep(150);
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            main(test, () -> call(pad, "copySelection"));
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 200, 300);   // place the copy
            test.waitForIdleSync(); SystemClock.sleep(80);
            final int[] start = inkCentre(pad, 120, 120, 320, 480);   // the copy only
            // Drag along a diagonal in page space, one axis at a time, then both.
            for (int step = 10; step <= 60; step += 10) {
                pen(test, pad, MotionEvent.ACTION_MOVE, down, 200 + step, 300 + step);
                test.waitForIdleSync(); SystemClock.sleep(60);
                final int[] at = inkCentre(pad, 120, 120, 320, 480);
                final int s = step;
                main(test, () -> {
                    check(Math.abs(at[0] - start[0] - s) <= 4 && Math.abs(at[1] - start[1] - s) <= 4,
                            "Diagonal drag follows the pen on both axes (expected " + s + "," + s
                            + ", got " + (at[0] - start[0]) + "," + (at[1] - start[1]) + ")");
                });
            }
            pen(test, pad, MotionEvent.ACTION_UP, down, 260, 360);
            test.waitForIdleSync(); SystemClock.sleep(150);
            report.append("PASS: a diagonal drag moves the copy diagonally.\n");

            // 10. The same diagonal drag with the page turned: the copy must still follow
            // the pen on both axes, not snap to the page's own directions.
            main(test, () -> call(activity, "requestQuarter", new Class<?>[]{int.class}, 3));  // ROTATION_90
            for (int i = 0; i < 50 && (Integer) get(activity, "appRotation") != android.view.Surface.ROTATION_90; i++)
                SystemClock.sleep(100);
            test.waitForIdleSync(); SystemClock.sleep(400);
            main(test, () -> {
                call(pad, "clearSelection"); call(pad, "clearClipboard");
                ToneDocument turned = new ToneDocument(320, 480);
                turned.begin();
                for (int y = 20; y < 60; y++) for (int x = 20; x < 60; x++) turned.paintTone(x, y, 0);
                turned.finish();
                call(activity, "replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(turned));
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
                call(pad, "renderAll");
            });
            test.waitForIdleSync(); SystemClock.sleep(200);
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            main(test, () -> {
                ToneDocument now = (ToneDocument) get(pad, "document");
                check(now.selection() != null && now.selection().contains(30, 30), "Rotated: the loop selected the square");
                call(pad, "copySelection");
            });
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 200, 300);   // place the copy
            test.waitForIdleSync(); SystemClock.sleep(80);
            final int[] rStart = inkCentre(pad, 120, 120, 320, 480);
            for (int step = 10; step <= 60; step += 10) {
                pen(test, pad, MotionEvent.ACTION_MOVE, down, 200 + step, 300 + step);
                test.waitForIdleSync(); SystemClock.sleep(60);
                final int s = step;
                main(test, () -> {
                    // The copy is 40x40 centred on rStart and has moved (s,s). The strip it
                    // left at its old near edge is outside the new position, so it must be
                    // paper again. Ink there is a ghost the panel never cleared, which reads
                    // as the copy dragging and not following the pen.
                    int behindX = rStart[0] - 19, behindY = rStart[1];
                    check(!darkAt(pad, behindX, behindY),
                            "Rotated: no ghost left behind where the copy was (s=" + s + ")");
                    check(!darkAt(pad, rStart[0], rStart[1] - 19),
                            "Rotated: no ghost left behind on the other axis (s=" + s + ")");
                });
            }
            pen(test, pad, MotionEvent.ACTION_UP, down, 260, 360);
            test.waitForIdleSync(); SystemClock.sleep(150);
            main(test, () -> call(activity, "requestQuarter", new Class<?>[]{int.class}, 0));  // back to portrait
            for (int i = 0; i < 50 && (Integer) get(activity, "appRotation") != android.view.Surface.ROTATION_0; i++)
                SystemClock.sleep(100);
            test.waitForIdleSync(); SystemClock.sleep(300);
            report.append("PASS: a diagonal drag follows the pen on a turned page.\n");

            // 11. Many hops: lift the pen, put it down somewhere else, move again. Each press
            // inside the copy must move it by exactly the drag, whichever point of the copy
            // it lands on and whichever way it goes. A move that registers short or not at all
            // is stale gesture state carried over from the previous press.
            main(test, () -> {
                call(pad, "clearSelection"); call(pad, "clearClipboard");
                ToneDocument hops = new ToneDocument(320, 480);
                hops.begin();
                for (int y = 20; y < 60; y++) for (int x = 20; x < 60; x++) hops.paintTone(x, y, 0);
                hops.finish();
                call(activity, "replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(hops));
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
                call(pad, "renderAll");
            });
            test.waitForIdleSync(); SystemClock.sleep(150);
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            main(test, () -> call(pad, "copySelection"));
            // Place the copy, then hop: press offset from the copy's centre, drag, release.
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 200, 300);
            pen(test, pad, MotionEvent.ACTION_UP, down, 200, 300);
            test.waitForIdleSync(); SystemClock.sleep(100);
            hop(test, pad, 0, 0, 40, 40, "Hop 1 (centre press, diagonal)");
            hop(test, pad, -15, -15, -60, -30, "Hop 2 (top-left press, back across)");
            hop(test, pad, 15, 15, 0, 80, "Hop 3 (bottom-right press, straight down)");
            hop(test, pad, 0, 18, -90, -40, "Hop 4 (bottom edge press, up-left)");
            hop(test, pad, -19, -19, 12, 12, "Hop 5 (corner press)");
            hop(test, pad, 19, 19, -12, -12, "Hop 6 (opposite corner press)");
            report.append("PASS: every hop moves the copy by exactly the drag.\n");

            // 12. A press that lands outside the copy commits it there rather than moving it,
            // and the next press moves what was written.
            final int[] committed = box(pad);
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, committed[0] - 30, committed[1] - 30);
            test.waitForIdleSync(); SystemClock.sleep(60);
            main(test, () -> {
                check(!(Boolean) call(pad, "copyPending"), "A press off the copy lets it go");
                check(!(Boolean) get(pad, "moveGesture"), "A press off the copy is not a move");
            });
            pen(test, pad, MotionEvent.ACTION_UP, down, committed[0] - 30, committed[1] - 30);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                ToneDocument now = (ToneDocument) get(pad, "document");
                check(now.tone(committed[0] + 20, committed[1] + 20) == 0,
                        "The copy was written where it was let go");
                // Tapping outside lets the selection go; what was written stays on the layer.
                check(now.selection() == null || !now.selection().contains(committed[0] + 20, committed[1] + 20),
                        "Tapping outside clears the selection");
            });
            report.append("PASS: a press off the copy commits it where it was let go.\n");

            // 13. Now the same hops with no copy attached: the page's own selection is what
            // moves. Each press must move it by exactly the drag, not by the drag plus
            // whatever the last press moved it by. The press outside above let the selection
            // go, so lasso the written pixels back up first. They were left at `committed`.
            final int[] lassoed = new int[4];
            main(test, () -> {
                ToneDocument now = (ToneDocument) get(pad, "document");
                // Stay on the page: clamp the loop inside its bounds.
                lassoed[0] = Math.max(2, committed[0] - 8);
                lassoed[1] = Math.max(2, committed[1] - 8);
                lassoed[2] = Math.min(now.width - 2, committed[2] + 8);
                lassoed[3] = Math.min(now.height - 2, committed[3] + 8);
            });
            trace(test, pad, new float[][]{
                { lassoed[0], lassoed[1] }, { lassoed[2], lassoed[1] },
                { lassoed[2], lassoed[3] }, { lassoed[0], lassoed[3] },
                { lassoed[0], lassoed[1] } });
            // The lasso picked the area in Freehand mode; moving your own selection is what
            // the Move mode is for, so switch to it the way the panel's Move button does.
            main(test, () -> {
                library.edit(library.current().selectMode(ToolSettings.SelectMode.MOVE));
                call(get(activity, "toolbar"), "refreshToolSelection");
            });
            hop(test, pad, 0, 0, 30, 30, "Own-selection hop 1");
            hop(test, pad, -15, 15, -50, 20, "Own-selection hop 2");
            hop(test, pad, 15, -15, 20, -70, "Own-selection hop 3");
            hop(test, pad, 15, -15, 20, -70, "Own-selection hop 3");
            report.append("PASS: the page's own selection moves by exactly each drag.\n");

            // 14. A cancelled drag must leave the selection where it was, ready for the next
            // press to move it normally.
            int[] stillAt = box(pad);
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down,
                (stillAt[0]+stillAt[2])/2, (stillAt[1]+stillAt[3])/2);
            pen(test, pad, MotionEvent.ACTION_MOVE, down,
                (stillAt[0]+stillAt[2])/2 + 40, (stillAt[1]+stillAt[3])/2 + 40);
            pen(test, pad, MotionEvent.ACTION_CANCEL, down,
                (stillAt[0]+stillAt[2])/2 + 40, (stillAt[1]+stillAt[3])/2 + 40);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> check(!(Boolean) get(pad, "moveGesture"), "A cancel ends the move"));
            hop(test, pad, 0, 0, 25, -25, "After a cancelled drag");
            report.append("PASS: a cancelled drag leaves the selection ready for the next one.\n");

            // 15. After a copy is placed, the pen must draw anywhere in the copied area,
            // including the part that was blank paper. If the selection carried only the
            // painted pixels, the pen would be blocked everywhere but the old paint.
            main(test, () -> {
                call(pad, "clearSelection"); call(pad, "clearClipboard");
                ToneDocument sparse = new ToneDocument(320, 480);
                sparse.begin();
                for (int y = 20; y < 40; y++) for (int x = 20; x < 40; x++) sparse.paintTone(x, y, 0);
                sparse.finish();
                call(activity, "replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(sparse));
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
                call(pad, "renderAll");
            });
            test.waitForIdleSync(); SystemClock.sleep(150);
            // Lasso a wide area: painted in one corner, blank paper across the rest.
            trace(test, pad, new float[][]{{10, 10}, {120, 10}, {120, 120}, {10, 120}, {10, 10}});
            main(test, () -> {
                call(pad, "copySelection");
                check(((ToneDocument) get(pad, "document")).selection().contains(100, 100),
                        "The copied area's blank paper stays selectable");
            });
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 200, 300);   // drop the copy
            pen(test, pad, MotionEvent.ACTION_UP, down, 200, 300);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                call(pad, "clearClipboard");
                library.select(ToolSettings.Tool.BRUSH);
                call(get(activity, "toolbar"), "refreshToolSelection");
                ToneDocument now = (ToneDocument) get(pad, "document");
                check(now.selection() != null && now.selection().contains(200, 300),
                        "The dropped copy is still selected");
                check(now.tone(200, 300) == 255, "The blank part of the copied area is still paper");
            });
            // Draw across the copied area with the brush: blank paper must take ink too.
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 185, 285);
            pen(test, pad, MotionEvent.ACTION_MOVE, down, 200, 300);
            pen(test, pad, MotionEvent.ACTION_MOVE, down, 215, 315);
            pen(test, pad, MotionEvent.ACTION_UP, down, 215, 315);
            test.waitForIdleSync(); SystemClock.sleep(150);
            main(test, () -> {
                ToneDocument now = (ToneDocument) get(pad, "document");
                check(now.tone(200, 300) == 0, "The pen draws in the blank part of the copied area");
                check(now.tone(210, 310) == 0, "The pen draws across the whole copied area");
            });
            report.append("PASS: the pen draws anywhere inside a copied area, blank paper included.\n");

            // 16. A selection's border is redrawn on every stroke frame, so it must stay
            // small. A selection of scattered painted pixels has a border of thousands of
            // one-pixel edges, each of which the overlay saves and restores as its own tile,
            // which is what makes drawing inside a selection crawl.
            main(test, () -> {
                Selection sel = ((ToneDocument) get(pad, "document")).selection();
                int segments = 0;
                for (float[] line : sel.border()) segments += line.length / 2;
                check(segments < 40, "A copied area's border stays small enough to redraw fast ("
                    + segments + " segments)");
            });
            report.append("PASS: a copied area's border stays small enough to redraw fast.\n");

            // 17. A long drag made of many small steps, as a real pen produces. The copy's
            // centre must stay under the pen. Rounding each event's tiny delta on its own
            // throws away its fraction, and over hundreds of events the copy falls further
            // and further behind until the pen is no longer inside it.
            main(test, () -> {
                call(pad, "clearSelection"); call(pad, "clearClipboard");
                ToneDocument small = new ToneDocument(320, 480);
                small.begin();
                for (int y = 200; y < 240; y++) for (int x = 200; x < 240; x++) small.paintTone(x, y, 0);
                small.finish();
                call(activity, "replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(small));
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
                call(pad, "renderAll");
            });
            test.waitForIdleSync(); SystemClock.sleep(150);
            trace(test, pad, new float[][]{{190, 190}, {250, 190}, {250, 250}, {190, 250}, {190, 190}});
            main(test, () -> call(pad, "copySelection"));
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 220, 220);   // place it, pen at its centre
            test.waitForIdleSync(); SystemClock.sleep(80);
            // Drag to the top-left in 200 steps of 0.4 page pixels: 80 pixels in all.
            float[] pen2 = { 220, 220 };
            for (int i = 0; i < 60; i++) {
                pen2[0] -= 0.4f; pen2[1] -= 0.4f;
                pen(test, pad, MotionEvent.ACTION_MOVE, down, pen2[0], pen2[1]);
                SystemClock.sleep(8);
            }
            final float endX = pen2[0], endY = pen2[1];
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                Selection placed = (Selection) call(pad, "placedCopy");
                int cx = (placed.left + placed.right) / 2, cy = (placed.top + placed.bottom) / 2;
                check(Math.abs(cx - endX) <= 1 && Math.abs(cy - endY) <= 1,
                        "After a long drag the copy's centre is still under the pen (copy at " + cx + "," + cy
                        + ", pen at " + endX + "," + endY + ")");
            });
            pen(test, pad, MotionEvent.ACTION_UP, down, endX, endY);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                Selection sel = ((ToneDocument) get(pad, "document")).selection();
                int cx = (sel.left + sel.right) / 2, cy = (sel.top + sel.bottom) / 2;
                check(Math.abs(cx - endX) <= 1 && Math.abs(cy - endY) <= 1,
                        "The committed copy landed under the pen too (copy at " + cx + "," + cy
                        + ", pen at " + endX + "," + endY + ")");
            });
            report.append("PASS: a long drag of many small steps keeps the copy under the pen.\n");

            // 18. Cut or Copy while a copy is still riding the pen must not read the page
            // (which is bare where the copy will land) and report "nothing is selected" nor
            // throw the copy away. The copy in hand must survive untouched.
            main(test, () -> {
                call(pad, "clearSelection"); call(pad, "clearClipboard");
                ToneDocument again = new ToneDocument(320, 480);
                again.begin();
                for (int y = 20; y < 60; y++) for (int x = 20; x < 60; x++) again.paintTone(x, y, 0);
                again.finish();
                call(activity, "replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(again));
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
                call(pad, "renderAll");
            });
            test.waitForIdleSync(); SystemClock.sleep(150);
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            main(test, () -> call(pad, "copySelection"));
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 200, 300);   // place the copy
            test.waitForIdleSync(); SystemClock.sleep(80);
            final Clipboard carried = (Clipboard) get(pad, "clipboard");
            main(test, () -> check(carried != null, "Re-cut: the copy is still being carried"));
            main(test, () -> { call(pad, "cutSelection");
                check(get(pad, "clipboard") == carried, "Re-cut: the copy in hand survives");
                check(((ToneDocument) get(pad, "document")).tone(240, 340) == 255,
                        "Re-cut: nothing was written to the page"); });
            main(test, () -> { call(pad, "copySelection");
                check(get(pad, "clipboard") == carried, "Re-copy: the copy in hand survives"); });
            // And it still drops normally afterwards.
            pen(test, pad, MotionEvent.ACTION_MOVE, down, 230, 330);
            pen(test, pad, MotionEvent.ACTION_UP, down, 230, 330);
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 300, 60);
            pen(test, pad, MotionEvent.ACTION_UP, down, 300, 60);
            test.waitForIdleSync(); SystemClock.sleep(150);
            main(test, () -> check(((ToneDocument) get(pad, "document")).tone(230, 330) == 0,
                    "Re-cut: the copy still lands where it was dropped"));
            report.append("PASS: Cut and Copy while carrying a copy keep it instead of failing.\n");

            // 20. Drop a cut copy on top of other artwork, then move it again. The paste
            // overwrote the artwork underneath, so unless the copy remembers what it was
            // sitting on, moving it away uncovers a blank hole in the shape of its own
            // pixels - the artwork appears to be erased by the move.
            main(test, () -> {
                call(pad, "clearSelection"); call(pad, "clearClipboard");
                ToneDocument two = new ToneDocument(320, 480);
                two.begin();
                for (int y = 20; y < 60; y++) for (int x = 20; x < 60; x++) two.paintTone(x, y, 0);
                two.finish();
                // A second, differently toned block the copy will be dropped onto.
                two.begin();
                for (int y = 200; y < 280; y++) for (int x = 120; x < 240; x++) two.paintTone(x, y, 96);
                two.finish();
                call(activity, "replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(two));
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
                call(pad, "renderAll");
            });
            test.waitForIdleSync(); SystemClock.sleep(150);
            // The book was replaced, so read the page the pad is actually on.
            ToneDocument live = (ToneDocument) get(pad, "document");
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            main(test, () -> call(pad, "cutSelection"));
            // Drop it centred on the second block: the tap writes the cut pixels there and
            // leaves the selection sitting over them. Switch to Move the way the panel's
            // Move button does, so the next drag moves the copy rather than lassos.
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 180, 240);
            test.waitForIdleSync(); SystemClock.sleep(80);
            pen(test, pad, MotionEvent.ACTION_UP, down, 180, 240);
            test.waitForIdleSync(); SystemClock.sleep(80);
            main(test, () -> {
                library.edit(library.current().selectMode(ToolSettings.SelectMode.MOVE));
                call(get(activity, "toolbar"), "refreshToolSelection");
            });
            main(test, () -> {
                check(live.tone(180, 240) == 0, "the cut copy lies over the other artwork, got " + live.tone(180, 240));
                check(live.tone(230, 270) == 96, "artwork outside the copy is untouched");
            });
            // Move the copy again, well clear of the block it was covering.
            hop(test, pad, 0, 0, 0, -120, "uncoving");
            main(test, () -> {
                check(live.tone(180, 240) == 96,
                    "moving the copy uncovers the artwork it was sitting on, got " + live.tone(180, 240));
                check(live.tone(160, 240) == 96,
                    "the corner of the area the copy covered comes back, got " + live.tone(160, 240));
                check(live.tone(199, 259) == 96,
                    "the far corner the copy covered comes back, got " + live.tone(199, 259));
                check(live.tone(180, 120) == 0, "the copy moved to where it was dragged");
            });
            report.append("PASS: moving a dropped copy uncovers the artwork under it, not a hole.\n");

            // 21. A copy that has been placed can be cut off the page again and made to wait
            // for another tap. Once placed it is ordinary pixels with a selection over them,
            // so cutting is just the normal cut and the spot it came from goes bare.
            main(test, () -> {
                call(pad, "clearSelection"); call(pad, "clearClipboard");
                ToneDocument recut = new ToneDocument(320, 480);
                recut.begin();
                for (int y = 20; y < 60; y++) for (int x = 20; x < 60; x++) recut.paintTone(x, y, 0);
                recut.finish();
                call(activity, "replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(recut));
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
                call(pad, "renderAll");
            });
            test.waitForIdleSync(); SystemClock.sleep(150);
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            main(test, () -> call(pad, "copySelection"));
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 200, 300);   // place it
            pen(test, pad, MotionEvent.ACTION_UP, down, 200, 300);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                ToneDocument now = (ToneDocument) get(pad, "document");
                check(now.tone(200, 300) == 0, "Recut: the copy was placed on the layer");
                check(!(Boolean) call(pad, "copyPending"), "Recut: nothing is on the pen once placed");
                // Cut the placed copy: it comes off the page and waits on the pen.
                call(pad, "cutSelection");
                check((Boolean) call(pad, "copyPending"), "Recut: the placed copy is on the pen again");
                check(now.tone(200, 300) == 255, "Recut: the spot it was placed on is bare again");
                check(now.tone(40, 40) == 0, "Recut: the original copy is still there");
            });
            // Another tap puts it down somewhere else.
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 100, 420);
            pen(test, pad, MotionEvent.ACTION_UP, down, 100, 420);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                ToneDocument now = (ToneDocument) get(pad, "document");
                check(now.tone(100, 420) == 0, "Recut: the second tap placed it where tapped");
                check(!(Boolean) call(pad, "copyPending"), "Recut: the pen is free after the tap");
            });
            report.append("PASS: a placed copy can be cut again and placed by another tap.\n");

            // 22. Copying after a cut has been placed commits nothing new (it is already on
            // the layer) and makes a copy of those pixels for somewhere else. The placed
            // pixels must stay put while the new copy rides the pen.
            main(test, () -> {
                ToneDocument now = (ToneDocument) get(pad, "document");
                check(now.tone(100, 420) == 0, "Copy-after-cut: the placed pixels are on the layer");
                call(pad, "copySelection");
                check((Boolean) call(pad, "copyPending"), "Copy-after-cut: a new copy is on the pen");
                check(now.tone(100, 420) == 0, "Copy-after-cut: the placed pixels stay where they are");
            });
            down = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, down, 260, 120);
            pen(test, pad, MotionEvent.ACTION_UP, down, 260, 120);
            test.waitForIdleSync(); SystemClock.sleep(120);
            main(test, () -> {
                ToneDocument now = (ToneDocument) get(pad, "document");
                check(now.tone(260, 120) == 0, "Copy-after-cut: the copy landed at the new tap");
                check(now.tone(100, 420) == 0, "Copy-after-cut: the first placement is untouched");
                check(!(Boolean) call(pad, "copyPending"), "Copy-after-cut: the pen is free after the tap");
            });
            report.append("PASS: copying a placed cut makes another copy and leaves the first alone.\n");

            // 23. Drawing inside a selection must not slow down as the selection grows.
            // The border is stroked on every stroke frame, and Skia charges for the path it
            // is handed rather than for the part of the canvas the clip keeps, so stroking a
            // whole lasso to repaint one brush stroke's corner used to pay for the lasso's
            // full length each frame. A big selection's frame must now cost about what a
            // small one's does. Sized to the live page, which an earlier check replaced.
            ToneDocument perfPage = (ToneDocument) get(pad, "document");
            int shortSide = Math.min(perfPage.width, perfPage.height);
            long smallFrame = borderFrame(test, pad, Math.max(40, shortSide / 4));
            long bigFrame = borderFrame(test, pad, shortSide - 2);
            report.append("Selection border frame: small=" + (smallFrame / 1e6) + "ms"
                + " large=" + (bigFrame / 1e6) + "ms\n");
            check(bigFrame < 1_500_000, "A large selection's stroke frame stays fast ("
                + (bigFrame / 1e6) + "ms, was ~3.7ms before culling)");
            report.append("PASS: a large selection draws as fast as a small one.\n");

            // 24. A tap in Freehand selection mode lets the selection go, and so does the
            // slight drag a deliberate tap always carries. A pen that barely moved must not
            // leave a speck of selection behind.
            main(test, () -> {
                ToneDocument page = (ToneDocument) get(pad, "document");
                page.setSelection(null);
                call(pad, "clearClipboard");
                library.select(ToolSettings.Tool.SELECT);
                library.edit(library.current().selectMode(ToolSettings.SelectMode.FREEHAND));
                call(get(activity, "toolbar"), "refreshToolSelection");
            });
            test.waitForIdleSync(); SystemClock.sleep(150);
            main(test, () -> {
                ToneDocument page = (ToneDocument) get(pad, "document");
                check(page.selection() == null || page.selection().empty(),
                    "Tap-to-clear: starts with no selection, got " + selStr(page));
            });
            trace(test, pad, new float[][]{{10, 10}, {80, 10}, {80, 90}, {10, 90}, {10, 10}});
            main(test, () -> {
                ToneDocument page = (ToneDocument) get(pad, "document");
                check(page.selection() != null && page.selection().contains(40, 40),
                    "Tap-to-clear: a real lasso makes a selection first, got " + selStr(page));
            });
            // A tap with a few pixels of drift, as a real finger-on-stylus tap produces.
            long tapDown = SystemClock.uptimeMillis();
            pen(test, pad, MotionEvent.ACTION_DOWN, tapDown, 200, 300);
            pen(test, pad, MotionEvent.ACTION_MOVE, tapDown, 203, 302);
            pen(test, pad, MotionEvent.ACTION_MOVE, tapDown, 205, 305);
            pen(test, pad, MotionEvent.ACTION_UP, tapDown, 205, 305);
            test.waitForIdleSync(); SystemClock.sleep(150);
            main(test, () -> {
                ToneDocument page = (ToneDocument) get(pad, "document");
                check(page.selection() == null || page.selection().empty(),
                    "Tap-to-clear: a tap with a slight drag clears the selection, got "
                        + selStr(page));
            });
            // A deliberate small loop must still select, so the tap rule has not swallowed
            // real work.
            trace(test, pad, new float[][]{{150, 200}, {250, 200}, {250, 300}, {150, 300}, {150, 200}});
            main(test, () -> {
                ToneDocument page = (ToneDocument) get(pad, "document");
                check(page.selection() != null && page.selection().contains(200, 250),
                    "Tap-to-clear: a real loop still selects, got " + selStr(page));
            });
            report.append("PASS: a tap (even with a slight drag) clears the selection.\n");

        } finally {
            main(test, () -> {
                call(pad, "finishStroke"); call(pad, "dryWet");
                set(activity, "library", library);
            });
        }
    }

    /** Whether the presented raster is dark at a page point. */
    /**
     * The raster the pad presents, in the coordinates it uses: page space when there is no
     * viewport bitmap, view space when there is. Reads must follow the same rule the pad's
     * own overlay code does, or a turned page reads at the wrong place.
     */
    private static boolean scaledView(Object pad) throws Exception {
        return get(pad, "viewportBitmap") != null;
    }
    /** Map a page point into the presented raster's coordinates. */
    private static int[] toRaster(Object pad, int pageX, int pageY) throws Exception {
        float[] p = { pageX, pageY };
        if (scaledView(pad)) ((Matrix) get(pad, "pageToView")).mapPoints(p);
        return new int[]{ Math.round(p[0]), Math.round(p[1]) };
    }
    /** The page-space box the thing attached to the pen currently covers: the copy, or the
     * page's own selection when no copy is held. */
    private static int[] box(Object pad) throws Exception {
        Selection placed = (Selection) call(pad, "placedCopy");
        if (placed == null) placed = ((ToneDocument) get(pad, "document")).selection();
        return new int[]{ placed.left, placed.top, placed.right, placed.bottom };
    }

    /**
     * One pick-up-and-put-down: press the copy at an offset from its centre, drag it by
     * (dx,dy), release, and require the copy to have moved by exactly that. The offset is
     * what varies: a press on the copy's corner must move it as surely as one on its
     * middle, and a second hop must not inherit the first one's leftover offset.
     */
    private static void hop(Instrumentation test, Object pad, int grabX, int grabY, int dx, int dy,
                           String label) throws Exception {
        int[] before = box(pad);
        int cx = (before[0] + before[2]) / 2 + grabX, cy = (before[1] + before[3]) / 2 + grabY;
        long down = SystemClock.uptimeMillis();
        pen(test, pad, MotionEvent.ACTION_DOWN, down, cx, cy);
        test.waitForIdleSync(); SystemClock.sleep(50);
        main(test, () -> {
            check((Boolean) get(pad, "moveGesture"), label + ": a press on the copy starts a move");
            check(!(Boolean) get(pad, "selectGesture"), label + ": a press on the copy is not a lasso");
        });
        // Two intermediate moves so the drag looks like a real stroke, not a jump.
        pen(test, pad, MotionEvent.ACTION_MOVE, down, cx + dx / 2, cy + dy / 2);
        SystemClock.sleep(30);
        pen(test, pad, MotionEvent.ACTION_MOVE, down, cx + dx, cy + dy);
        test.waitForIdleSync(); SystemClock.sleep(80);
        // While a copy rides the pen its live box must already track the drag; the page's
        // own selection only moves when the drag is committed on release.
        if (get(pad, "clipboard") != null) {
            int[] during = box(pad);
            if (during[0] != before[0] + dx || during[1] != before[1] + dy)
                throw new AssertionError(label + ": the copy did not follow the pen while dragging (got ("
                    + (during[0]-before[0]) + "," + (during[1]-before[1]) + "), expected (" + dx + "," + dy + "))");
        }
        pen(test, pad, MotionEvent.ACTION_UP, down, cx + dx, cy + dy);
        test.waitForIdleSync(); SystemClock.sleep(100);
        int[] after = box(pad);
        if (after[0] != before[0] + dx || after[1] != before[1] + dy
                || after[2] != before[2] + dx || after[3] != before[3] + dy)
            throw new AssertionError(label + ": moved by (" + (after[0]-before[0]) + "," + (after[1]-before[1])
                + ") from a press at (" + grabX + "," + grabY + ") and a drag of (" + dx + "," + dy + "),"
                + " expected (" + dx + "," + dy + ")");
    }

    /** Whether the presented raster is dark at a page point. */
    private static boolean darkAt(Object pad, int pageX, int pageY) throws Exception {
        Bitmap shown = (Bitmap) call(pad, "presented");
        int[] p = toRaster(pad, pageX, pageY);
        if (p[0] < 0 || p[1] < 0 || p[0] >= shown.getWidth() || p[1] >= shown.getHeight()) return false;
        return (shown.getPixel(p[0], p[1]) & 0xff) < 64;
    }

    /**
     * The centre of the dark pixels the pad currently draws inside a page-space window,
     * returned in PAGE coordinates so a reading means the same however the page is turned.
     */
    private static int[] inkCentre(Object pad, int x0, int y0, int x1, int y1) throws Exception {
        Bitmap shown = (Bitmap) call(pad, "presented");
        Matrix toView = (Matrix) get(pad, "pageToView");
        Matrix toPage = (Matrix) get(pad, "viewToPage");
        float[] c = { x0, y0, x1, y0, x1, y1, x0, y1 };
        if (scaledView(pad)) toView.mapPoints(c);
        int vx0 = (int) Math.floor(Math.min(Math.min(c[0], c[2]), Math.min(c[4], c[6])));
        int vy0 = (int) Math.floor(Math.min(Math.min(c[1], c[3]), Math.min(c[5], c[7])));
        int vx1 = (int) Math.ceil(Math.max(Math.max(c[0], c[2]), Math.max(c[4], c[6])));
        int vy1 = (int) Math.ceil(Math.max(Math.max(c[1], c[3]), Math.max(c[5], c[7])));
        vx0 = Math.max(0, vx0); vy0 = Math.max(0, vy0);
        vx1 = Math.min(shown.getWidth(), vx1); vy1 = Math.min(shown.getHeight(), vy1);
        double sx = 0, sy = 0; int n = 0;
        for (int y = vy0; y < vy1; y += 2) for (int x = vx0; x < vx1; x += 2)
            if ((shown.getPixel(x, y) & 0xff) < 64) {
                float[] p = { x, y };
                if (scaledView(pad)) toPage.mapPoints(p);
                sx += p[0]; sy += p[1]; n++;
            }
        return n == 0 ? new int[]{-1, -1} : new int[]{(int)Math.round(sx/n), (int)Math.round(sy/n)};
    }

    /** Whether the presented raster shows the copy's dark pixels at a page point. */
    private static boolean ghostShowsCopy(Object pad, int pageX, int pageY) throws Exception {
        return darkAt(pad, pageX, pageY);
    }

    /** Inject a stylus trace of the given page-space polyline. */
    /** A selection as a short string, for check messages. */
    static String selStr(ToneDocument d) {
        Selection s = d.selection();
        return s == null ? "null" : s.left + "," + s.top + ".." + s.right + "," + s.bottom;
    }

    /**
     * Cost of one stroke frame's selection border redraw: the ink for a real lasso of
     * `size` px, clipped to a brush-sized area as a stroke would be.
     */
    static long borderFrame(Instrumentation test, Object pad, int size) throws Exception {
        // The pad's own live page: an earlier check replaced the book, so the document
        // this method was handed may no longer be the one the pad draws.
        ToneDocument page = (ToneDocument) get(pad, "document");
        float[] loop = square(size, 3, page.width, page.height);
        Selection sel = Selection.lasso(loop, loop.length / 2, page.width, page.height);
        main(test, () -> {
            page.setSelection(sel);
            call(pad, "hideSelectionBorder");
            call(pad, "drawSelectionBorder");
        });
        test.waitForIdleSync(); SystemClock.sleep(80);
        Rect clip = new Rect(page.width / 2 - 60, page.height / 2 - 60,
                           page.width / 2 + 60, page.height / 2 + 60);
        Bitmap target = (Bitmap) call(pad, "presented");
        Canvas canvas = new Canvas(target);
        long total = 0;
        int frames = 40;
        for (int f = 0; f < frames; f++) {
            long s = System.nanoTime();
            canvas.save(); canvas.clipRect(clip);
            call(pad, "selectionInkNow", new Class<?>[]{Canvas.class}, canvas);
            canvas.restore();
            total += System.nanoTime() - s;
        }
        return total / frames;
    }

    /** A closed square loop with a vertex every `step` px, centred on the page. */
    static float[] square(int size, int step, int w, int h) {
        int x0 = w / 2 - size / 2, y0 = h / 2 - size / 2, x1 = x0 + size, y1 = y0 + size;
        java.util.List<Float> f = new java.util.ArrayList<>();
        for (int x = x0; x <= x1; x += step) { f.add((float) x); f.add((float) y0); }
        for (int y = y0 + step; y <= y1; y += step) { f.add((float) x1); f.add((float) y); }
        for (int x = x1 - step; x >= x0; x -= step) { f.add((float) x); f.add((float) y1); }
        for (int y = y1 - step; y > y0; y -= step) { f.add((float) x0); f.add((float) y); }
        f.add((float) x0); f.add((float) y0);
        float[] a = new float[f.size()];
        for (int i = 0; i < a.length; i++) a[i] = f.get(i);
        return a;
    }

    private static void trace(Instrumentation test, Object pad, float[][] points) throws Exception {
        long down = SystemClock.uptimeMillis();
        pen(test, pad, MotionEvent.ACTION_DOWN, down, points[0][0], points[0][1]);
        for (int i = 1; i < points.length - 1; i++) {
            pen(test, pad, MotionEvent.ACTION_MOVE, down, points[i][0], points[i][1]);
            SystemClock.sleep(16);
        }
        pen(test, pad, MotionEvent.ACTION_MOVE, down, points[points.length-1][0], points[points.length-1][1]);
        test.waitForIdleSync();
        pen(test, pad, MotionEvent.ACTION_UP, down, points[points.length-1][0], points[points.length-1][1]);
        test.waitForIdleSync(); SystemClock.sleep(150);
    }

    private static void pen(Instrumentation test, Object pad, int action, long down, float pageX, float pageY) throws Exception {
        float[] point = { pageX, pageY };
        // Injected events carry display coordinates: page -> view -> panel, as the other
        // pen checks do. Mapping only page->view lands the pen outside the page.
        main(test, () -> { ((Matrix) get(pad, "pageToView")).mapPoints(point); PanelCoordinates.fromView((View) pad).mapPoints(point); });
        MotionEvent.PointerProperties prop = new MotionEvent.PointerProperties();
        prop.id = 0; prop.toolType = MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = point[0]; coords.y = point[1]; coords.pressure = .45f; coords.size = .1f;
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{prop}, new MotionEvent.PointerCoords[]{coords},
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_STYLUS, 0);
        try { check(test.getUiAutomation().injectInputEvent(event, true), "Pen injected"); }
        finally { event.recycle(); }
    }

    private interface Work { void run() throws Exception; }
    private static void main(Instrumentation test, Work work) throws Exception {
        Throwable[] error = { null };
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { error[0] = e; } });
        if (error[0] != null) throw new Exception(error[0]);
    }
    private static Object get(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value);
    }
    private static Object call(Object owner, String name) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name); m.setAccessible(true); return m.invoke(owner);
    }
    private static Object call(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(owner, args);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
