package io.github.mpdairy.monopaint;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import java.lang.reflect.*;
import java.util.Arrays;

/** Reference image on a temporary book; always restore the original session. */
final class ReferenceUiChecks {
    static void run(Instrumentation test, StringBuilder report) throws Exception {
        PaintActivity activity = (PaintActivity)test.startActivitySync(
                new Intent(test.getTargetContext(), PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(test, () -> !(Boolean)get(activity,"loading") && get(activity,"book")!=null, "Recovery loaded");
        Object pad = get(activity,"pad");
        DrawingBook original = (DrawingBook)get(activity,"book");
        String originalName = (String)get(activity,"drawingName");
        int originalQuarter = (4-(Integer)get(activity,"appRotation"))%4;
        ToneDocument doc = new ToneDocument(original.width, original.height);
        try {
            main(test, () -> {
                ((android.view.OrientationEventListener)get(get(activity,"rotationPrompt"),"orientationSensor")).disable();
                set(activity,"drawingName","");
                call(activity,"replaceBook", new Class<?>[]{DrawingBook.class}, new DrawingBook(doc));
                doc.begin(); doc.paintTone(doc.width/2, doc.height/2, 30); doc.finish();
                call(pad,"renderAll");
            });
            // Import a large dark square reference directly through the document API.
            final int iw = 600, ih = 600;
            main(test, () -> {
                byte[] lum = new byte[iw*ih], alpha = new byte[iw*ih];
                Arrays.fill(lum, (byte)0); Arrays.fill(alpha, (byte)255);
                doc.setReference(new ReferenceImage(lum, alpha, iw, ih,
                        ReferenceImage.place(doc.width/2f, doc.height/2f, iw, ih, 1, 0)));
                call(pad,"renderAll");
            });
            check(doc.hasReference() && doc.paintLayers()==1, "reference under one paint layer");
            int[] box = doc.referenceBounds();
            check(doc.compositeTone(box[0]+5, box[1]+5)==0, "reference shows through empty paper");
            check(doc.compositeTone(doc.width/2, doc.height/2)==30, "paint stays above the reference");
            try { doc.selectLayer(0); throw new Exception("pen selected the reference"); }
            catch (IllegalStateException expected) {}
            report.append("PASS: reference under the paint layer, shows through empty paper, cannot be selected\n");

            dragFollowsFinger(test, activity, pad, doc, 0, report);
            rotatedDrag(test, activity, pad, doc, report);
            panelChecks(test, activity, pad, doc, report);
            outlinePreview(test, activity, pad, doc, report);
            report.append("PASS: reference drag, rotated drag, panel controls, outline-only preview\n");
        } finally {
            main(test, () -> {
                PopupWindow popup = (PopupWindow)get(activity,"referencePopup");
                if (popup!=null) popup.dismiss();
                popup = (PopupWindow)get(activity,"layersPopup");
                if (popup!=null) popup.dismiss();
                set(activity,"drawingName",originalName);
                call(activity,"replaceBook", new Class<?>[]{DrawingBook.class}, original);
                call(activity,"requestQuarter", new Class<?>[]{int.class}, originalQuarter);
                call(activity,"applyToolboxSide");
                call(activity,"recovery");
            });
            TestSessionSave.await(activity);
        }
    }

    /** One-finger drag follows the finger by the requested page delta as one undo step. */
    private static void dragFollowsFinger(Instrumentation test, PaintActivity activity, Object pad,
                                        ToneDocument doc, int quarter, StringBuilder report) throws Exception {
        if (quarter != 0) {
            main(test, () -> call(activity,"requestQuarter", new Class<?>[]{int.class}, quarter));
            test.waitForIdleSync(); SystemClock.sleep(300);
        }
        int[] box = doc.referenceBounds();
        float startX = (box[0]+box[2])/2f, startY = (box[1]+box[3])/2f;
        float deltaX = 300, deltaY = 200;
        byte[] before = doc.layerSnapshot().layers.get(0).tones.clone();
        float[] startPlacement = doc.referencePlacement().clone();
        boolean hadReference = doc.hasReference();
        fingerDrag(test, pad, startX, startY, startX+deltaX, startY+deltaY, (midTones) -> {
            check(!Arrays.equals(before, midTones), "the drag moved the drawn reference at every step");
        });
        test.waitForIdleSync(); SystemClock.sleep(150);
        float[] placed = ReferenceImage.decompose(doc.referencePlacement(),
                doc.referenceImageWidth(), doc.referenceImageHeight());
        float[] start = ReferenceImage.decompose(startPlacement,
                doc.referenceImageWidth(), doc.referenceImageHeight());
        check(Math.abs(placed[0]-start[0]-deltaX) < 1.5f && Math.abs(placed[1]-start[1]-deltaY) < 1.5f,
                "the reference followed the finger by the page delta (turned=" + (quarter!=0) + ")");
        // Band composite == full render.
        main(test, () -> {
            Bitmap full = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888);
            ViewportBitmap.compose(doc, full, true);
            Bitmap display = (Bitmap)get(pad,"display");
            for (int y=0; y<doc.height; y+=7) for (int x=0; x<doc.width; x+=7)
                check(full.getPixel(x,y)==display.getPixel(x,y),
                        "band composite matches the full render at "+x+","+y);
            full.recycle();
        });
        // One undo restores the pre-drag pixels; on a fresh import the next undo removes it.
        main(test, () -> {
            check(doc.undo(), "the drag undoes");
            check(Arrays.equals(before, doc.layerSnapshot().layers.get(0).tones),
                    "one undo restores the pre-drag picture");
            if (!hadReference) check(doc.undo() && !doc.hasReference(), "the drag was one undo step, not many");
            else check(java.util.Arrays.equals(before, doc.layerSnapshot().layers.get(0).tones),
                    "the drag was one undo step, not many");
            doc.redo(); if (!hadReference) doc.redo();
            call(pad,"renderAll");
        });
        report.append("PASS: one-finger reference drag follows the finger as one undo step; band composite == full render (turn "+quarter+")\n");
    }

    private static void rotatedDrag(Instrumentation test, PaintActivity activity, Object pad,
                                   ToneDocument doc, StringBuilder report) throws Exception {
        dragFollowsFinger(test, activity, pad, doc, 1, report);
        main(test, () -> call(activity,"requestQuarter", new Class<?>[]{int.class}, 0));
        test.waitForIdleSync(); SystemClock.sleep(200);
    }

    /** The panel's sliders, lock and eye behave; scale reaches 200% of the imported base. */
    private static void panelChecks(Instrumentation test, PaintActivity activity, Object pad,
                                  ToneDocument doc, StringBuilder report) throws Exception {
        openReference(test, activity);
        PopupWindow popup = referencePopup(activity);
        check(find((View)popup.getContentView(), "Reference image")!=null, "panel shows its title");
        SeekBar rotate = slider(popup, "Rotate the reference image");
        SeekBar scale = slider(popup, "Scale the reference image");
        SeekBar opacity = slider(popup, "Reference image opacity");
        check(rotate.getMax()==360 && rotate.getProgress()==180, "rotate slider -180..180 centered");
        check(opacity.getMax()==100 && opacity.getProgress()==100, "opacity slider 0..100 full");
        check(scale.getMax()==199 && scale.getProgress()==99, "scale slider 1..200% with 100% in the middle");
        float base = ReferenceImage.decompose(doc.referencePlacement(),
                doc.referenceImageWidth(), doc.referenceImageHeight())[2];
        dragSliderTo(test, scale, scale.getMax());
        await(test, () -> Math.abs(ReferenceImage.decompose(doc.referencePlacement(),
                doc.referenceImageWidth(), doc.referenceImageHeight())[2] - base*2) < base*0.02f,
                "scale slider at the end gives 200% of the base");
        // Lock disables the controls. Locking rebuilds the panel, so re-fetch the sliders.
        tap(test, description(popup.getContentView(), "Lock the reference image in place"));
        await(test, () -> doc.referenceLocked(), "lock responds");
        test.waitForIdleSync(); SystemClock.sleep(150);
        popup = referencePopup(activity);
        rotate = slider(popup, "Rotate the reference image");
        scale = slider(popup, "Scale the reference image");
        opacity = slider(popup, "Reference image opacity");
        check(!rotate.isEnabled() && !scale.isEnabled() && !opacity.isEnabled(),
                "locked reference disables the sliders");
        check(!find((View)popup.getContentView(), "Replace image").isEnabled(),
                "locked reference disables replacing");
        tap(test, description(popup.getContentView(), "Unlock the reference image"));
        await(test, () -> !doc.referenceLocked(), "unlock responds");
        test.waitForIdleSync(); SystemClock.sleep(150);
        popup = referencePopup(activity);
        // Hide and show.
        tap(test, description(popup.getContentView(), "Hide the reference image"));
        await(test, () -> !doc.layerVisible(0), "eye hides the reference");
        tap(test, description(popup.getContentView(), "Show the reference image"));
        await(test, () -> doc.layerVisible(0), "eye shows the reference again");
        dismissReference(test, activity);
        report.append("PASS: reference panel title, rotate/opacity/scale ranges, 200% endpoint, lock and eye\n");
    }

    /** Drag a slider from its middle to its far end, through the real touch path. */
    private static void dragSliderTo(Instrumentation test, SeekBar slider, int progress) throws Exception {
        int[] location = new int[2];
        slider.getLocationOnScreen(location);
        float y = location[1] + slider.getHeight()/2f;
        float mid = location[0] + slider.getWidth()/2f;
        float end = location[0] + slider.getWidth() - Ui.dp(slider.getContext(), 8);
        long down = SystemClock.uptimeMillis();
        inject(test, down, MotionEvent.ACTION_DOWN, mid, y);
        test.waitForIdleSync();
        inject(test, down, MotionEvent.ACTION_MOVE, end, y);
        test.waitForIdleSync(); SystemClock.sleep(60);
        inject(test, down, MotionEvent.ACTION_UP, end, y);
        test.waitForIdleSync(); SystemClock.sleep(120);
    }

    /** Rotate shows an outline only; the picture resamples once on release. */
    private static void outlinePreview(Instrumentation test, PaintActivity activity, Object pad,
                                     ToneDocument doc, StringBuilder report) throws Exception {
        openReference(test, activity);
        PopupWindow popup = referencePopup(activity);
        SeekBar rotate = slider(popup, "Rotate the reference image");
        byte[] before = doc.layerSnapshot().layers.get(0).tones.clone();
        // Drag the rotate slider partway without releasing.
        int[] where = new int[2];
        rotate.getLocationOnScreen(where);
        long down = SystemClock.uptimeMillis();
        inject(test, down, MotionEvent.ACTION_DOWN, where[0]+rotate.getWidth()/2f, where[1]+rotate.getHeight()/2f);
        test.waitForIdleSync();
        inject(test, down, MotionEvent.ACTION_MOVE, where[0]+rotate.getWidth()*0.8f, where[1]+rotate.getHeight()/2f);
        test.waitForIdleSync(); SystemClock.sleep(120);
        main(test, () -> check(Arrays.equals(before, doc.layerSnapshot().layers.get(0).tones),
                "mid-drag the reference layer's pixels are byte-identical: nothing was resampled"));
        // Dismiss the popup partway: it must commit the transform and leave no outline.
        main(test, () -> popup.dismiss());
        test.waitForIdleSync(); SystemClock.sleep(200);
        main(test, () -> {
            check(!doc.referenceEditing(), "dismissal closed the edit step");
            float[] placed = ReferenceImage.decompose(doc.referencePlacement(),
                    doc.referenceImageWidth(), doc.referenceImageHeight());
            check(Math.abs(placed[3]) > 5, "the transform reached at dismissal was committed");
            Bitmap full = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888);
            ViewportBitmap.compose(doc, full, true);
            Bitmap display = (Bitmap)get(pad,"display");
            for (int y=0; y<doc.height; y+=5) for (int x=0; x<doc.width; x+=5)
                check(full.getPixel(x,y)==display.getPixel(x,y),
                        "no outline pixels left anywhere at "+x+","+y);
            full.recycle();
            check(doc.undo(), "the committed rotate is one undo step");
            check(Arrays.equals(before, doc.layerSnapshot().layers.get(0).tones),
                    "undo restores the pre-rotate picture exactly");
            doc.redo();
            call(pad,"renderAll");
        });
        report.append("PASS: outline-only rotate preview, dismissal commits one step and leaves no outline\n");
    }

    private interface ToneSnapshot { void check(byte[] tones) throws Exception; }

    /** Inject a one-finger drag from a page point to another, checking the layer at each step. */
    private static void fingerDrag(Instrumentation test, Object pad,
                                  float fromX, float fromY, float toX, float toY,
                                  ToneSnapshot stepCheck) throws Exception {
        Object pageToView = get(pad, "pageToView");
        float[] from = {fromX, fromY}, to = {toX, toY};
        main(test, () -> { ((android.graphics.Matrix)pageToView).mapPoints(from); });
        main(test, () -> { ((android.graphics.Matrix)pageToView).mapPoints(to); });
        float[] fromScreen = viewToScreen(test, (View)pad, from[0], from[1]);
        float[] toScreen = viewToScreen(test, (View)pad, to[0], to[1]);
        long down = SystemClock.uptimeMillis();
        inject(test, down, MotionEvent.ACTION_DOWN, fromScreen[0], fromScreen[1]);
        test.waitForIdleSync();
        int steps = 6;
        for (int i=1; i<=steps; i++) {
            float t = (float)i/steps;
            inject(test, down, MotionEvent.ACTION_MOVE,
                    fromScreen[0]+(toScreen[0]-fromScreen[0])*t,
                    fromScreen[1]+(toScreen[1]-fromScreen[1])*t);
            test.waitForIdleSync(); SystemClock.sleep(40);
            main(test, () -> stepCheck.check(doc0(pad).layerSnapshot().layers.get(0).tones));
        }
        inject(test, down, MotionEvent.ACTION_UP, toScreen[0], toScreen[1]);
        test.waitForIdleSync();
    }
    private static ToneDocument doc0(Object pad) throws Exception { return (ToneDocument)get(pad,"document"); }

    private static float[] viewToScreen(Instrumentation test, View view, float x, float y) throws Exception {
        final float[] point = {x, y};
        main(test, () -> PanelCoordinates.fromView(view).mapPoints(point));
        int[] location = new int[2];
        main(test, () -> view.getLocationOnScreen(location));
        return new float[]{point[0], point[1]};
    }

    private static void inject(Instrumentation test, long down, int action, float x, float y) {
        MotionEvent.PointerProperties prop = new MotionEvent.PointerProperties();
        prop.id = 0; prop.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords point = new MotionEvent.PointerCoords();
        point.x = x; point.y = y; point.pressure = 1;
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{prop}, new MotionEvent.PointerCoords[]{point},
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        check(test.getUiAutomation().injectInputEvent(event, true), "finger event injected");
        event.recycle();
    }

    private static void openReference(Instrumentation test, PaintActivity activity) throws Exception {
        await(test, () -> activity.hasWindowFocus(), "window focus");
        main(test, () -> {
            View button = (View)get(get(activity,"toolbar"), "referenceButton");
            button.performClick();
        });
        await(test, () -> referencePopup(activity)!=null && referencePopup(activity).isShowing(), "Reference panel opened");
        test.waitForIdleSync(); SystemClock.sleep(120);
    }
    private static void dismissReference(Instrumentation test, PaintActivity activity) throws Exception {
        PopupWindow popup = referencePopup(activity);
        if (popup != null) main(test, popup::dismiss);
        await(test, () -> referencePopup(activity)==null, "Reference panel closed");
    }
    private static PopupWindow referencePopup(PaintActivity activity) throws Exception {
        return (PopupWindow)get(activity, "referencePopup");
    }
    private static SeekBar slider(PopupWindow popup, String description) {
        View found = description(popup.getContentView(), description);
        check(found instanceof SeekBar, "slider found: " + description);
        return (SeekBar)found;
    }
    private static View find(View view, String text) {
        if (view instanceof TextView && ((TextView)view).getText().toString().equals(text)) return view;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            View found = find(((ViewGroup)view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private static View description(View view, String text) {
        if (view.getContentDescription()!=null && view.getContentDescription().toString().equals(text)) return view;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            View found = description(((ViewGroup)view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private static void tap(Instrumentation test, View view) {
        check(view!=null, "control exists");
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent e = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                    location[0]+view.getWidth()/2f, location[1]+view.getHeight()/2f, 0);
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            check(test.getUiAutomation().injectInputEvent(e, true), "tap injected");
            e.recycle();
        }
        test.waitForIdleSync(); SystemClock.sleep(120);
    }
    private interface Work { void run() throws Exception; }
    private interface Condition { boolean ok() throws Exception; }
    private static void main(Instrumentation test, Work work) throws Exception {
        Throwable[] failure = {null};
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { failure[0]=e; } });
        if (failure[0]!=null) throw new Exception(failure[0]);
    }
    private static void await(Instrumentation test, Condition condition, String message) throws Exception {
        for (int i=0; i<100; i++) {
            boolean[] ready = {false};
            main(test, () -> ready[0]=condition.ok());
            if (ready[0]) return;
            SystemClock.sleep(100);
        }
        throw new IllegalStateException(message + " timed out");
    }
    private static Object get(Object owner, String name) throws Exception {
        Class<?> type = owner.getClass();
        while (type != null) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
            catch (NoSuchFieldException gone) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value);
    }
    private static void call(Object owner, String name) throws Exception {
        call(owner, name, new Class<?>[0]);
    }
    private static void call(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(owner, args);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
