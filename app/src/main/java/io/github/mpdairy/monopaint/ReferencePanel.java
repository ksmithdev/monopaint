package io.github.mpdairy.monopaint;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The Reference popup: import a picture to trace, then rotate, scale, fade, hide,
 * lock or remove it. Rotate and scale preview as an outline while the finger moves and
 * resample the picture once, when it lifts.
 */
final class ReferencePanel {
    private final PaintActivity app;
    private final ToolButton trigger;
    private final ToneDocument document;
    final PopupWindow popup;
    /** The scale the picture had when the panel opened; the slider is relative to it. */
    private final float baseScale;
    /** The placement and angle/scale the sliders last asked for. */
    private float[] candidate;
    private float centerX, centerY, angle;
    private float scale;

    ReferencePanel(PaintActivity app, ToolButton trigger) {
        this.app = app; this.trigger = trigger; this.document = app.pad.document;
        if (document.hasReference()) {
            float[] placed = ReferenceImage.decompose(document.referencePlacement(),
                    document.referenceImageWidth(), document.referenceImageHeight());
            centerX = placed[0]; centerY = placed[1]; scale = placed[2]; angle = placed[3];
            baseScale = scale;
        } else { baseScale = 1; }
        LinearLayout rows = new LinearLayout(app); rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(8), dp(8), dp(8), dp(8));
        rows.setBackground(Ui.outline(dp(2), Color.BLACK, 0));
        TextView title = new TextView(app);
        title.setText(document.hasReference() ? "Reference image" : "Reference image · none yet");
        title.setTextColor(Color.BLACK); title.setTextSize(18); title.setPadding(dp(8), dp(4), 0, dp(4));
        title.setTypeface(null, Typeface.BOLD); rows.addView(title);
        TextView hint = SettingsForm.hintText(app, hint());
        hint.setPadding(dp(8), 0, dp(8), dp(8)); rows.addView(hint);
        boolean reference = document.hasReference(), locked = document.referenceLocked();
        LinearLayout actions = new LinearLayout(app); rows.addView(actions);
        action(actions, reference ? "Replace image" : "Import image",
                () -> app.pickReference(this::dismiss), !locked);
        action(actions, "Done", this::dismiss, true);
        if (reference) {
            rows.addView(controlRow(), new LinearLayout.LayoutParams(-1, dp(52)));
            addRotate(rows);
            addScale(rows);
            addOpacity(rows);
            LinearLayout remove = new LinearLayout(app); rows.addView(remove);
            action(remove, "Remove image", () -> {
                app.referenceChange(document::removeReference);
                reopen();
            }, !locked);
        }
        SettingsForm.style(rows, app.prefs.largeSettingsText());

        ScrollView scroll = new ScrollView(app); scroll.addView(rows);
        popup = Popups.create(scroll, app.appTurn());
        android.graphics.RectF anchor = Popups.bounds(app.root, trigger);
        int rootWidth = app.root.getWidth(), rootHeight = app.root.getHeight();
        int width = Math.min(dp(app.prefs.largeSettingsText() ? 420 : 380), rootWidth);
        int height = Math.min(Popups.measuredHeight(scroll, width, 0), rootHeight);
        float left = app.toolboxRight && !app.landscape ? anchor.left - width : anchor.right;
        float top = app.landscape ? anchor.bottom : anchor.top;
        android.graphics.RectF bounds = Popups.rect(Popups.clamp(left, 0, rootWidth - width),
                Popups.clamp(top, 0, rootHeight - height), width, height);
        popup.setOnDismissListener(() -> {
            if (app.referencePopup == popup) app.referencePopup = null;
            // A slider drag cut short must still commit, or the document keeps an open
            // step that blocks painting — and must not leave a box stranded on the canvas.
            float[] pending = candidate;
            candidate = null;
            // Restore the pixels under the outline before the final composite, so the
            // saved background never covers freshly rendered artwork.
            app.pad.clearReferenceOutline();
            if (document.referenceEditing()) {
                if (pending != null) app.referenceChange(() -> { document.placeReference(pending); document.endReferenceEdit(); });
                else app.referenceChange(document::endReferenceEdit);
            }
            app.selectionFeedback.update(trigger, trigger.markerArea(), () -> trigger.marked = false);
        });
        app.referencePopup = popup;
        app.selectionFeedback.update(trigger, trigger.markerArea(), () -> trigger.marked = true);
        Popups.show(popup, app.root, bounds);
    }
    private String hint() {
        if (!document.hasReference()) return "Import a picture to lay under your layers for tracing.";
        if (document.referenceLocked()) return "Locked. Drag the lock open to move or change the picture.";
        return "Drag the picture on the canvas with one finger. Rotate and scale preview as an outline.";
    }
    private int dp(float value) { return app.dp(value); }
    private void dismiss() { if (app.referencePopup != null) app.referencePopup.dismiss(); }
    /** Rebuilds the panel after the reference changed. */
    private void reopen() { dismiss(); new ReferencePanel(app, trigger); }

    /** The reference's name with its lock and eye beside it. */
    private LinearLayout controlRow() {
        LinearLayout row = new LinearLayout(app); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(app); label.setText("Reference image");
        label.setTextColor(Color.BLACK); label.setGravity(Gravity.CENTER);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(52), 1));
        ToolButton lock = iconButton("Lock reference");
        Runnable describeLock = () -> {
            boolean locked = document.referenceLocked();
            lock.iconOnly(locked ? R.drawable.ic_locked : R.drawable.ic_unlocked);
            lock.setContentDescription(locked ? "Unlock the reference image" : "Lock the reference image in place");
        };
        describeLock.run();
        lock.setOnClickListener(v -> {
            document.setReferenceLocked(!document.referenceLocked());
            app.pad.clearReferenceOutline();
            reopen();
        });
        row.addView(lock, new LinearLayout.LayoutParams(dp(52), dp(52)));
        ToolButton eye = iconButton("Show reference");
        Runnable describeEye = () -> {
            boolean shown = document.layerVisible(0);
            eye.iconOnly(shown ? R.drawable.ic_layer_visible : R.drawable.ic_layer_hidden);
            eye.setContentDescription((shown ? "Hide " : "Show ") + "the reference image");
        };
        describeEye.run();
        eye.setOnClickListener(v -> {
            app.referenceChange(() -> document.setLayerVisible(0, !document.layerVisible(0)));
            app.layerFeedback.update(eye, describeEye);
        });
        row.addView(eye, new LinearLayout.LayoutParams(dp(52), dp(52)));
        return row;
    }
    private ToolButton iconButton(String name) {
        ToolButton button = new ToolButton(app, app);
        button.press.feedback = app.layerFeedback;
        button.setBackgroundColor(Color.WHITE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.iconHalf = 16;
        button.setContentDescription(name);
        return button;
    }
    private void action(LinearLayout row, String label, Runnable action, boolean enabled) {
        ToolButton button = new ToolButton(app, app);
        button.press.feedback = app.layerFeedback;
        button.setText(label); button.setAllCaps(false);
        button.setTextSize(14); button.setPadding(dp(4), 0, dp(4), 0);
        button.setContentDescription(label); button.setEnabled(enabled);
        button.setOnClickListener(v -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
    }

    /** −180…180 with 0 in the middle, so a short push either way is a short turn. */
    private void addRotate(LinearLayout rows) {
        TextView caption = new TextView(app);
        SeekBar bar = new SeekBar(app);
        bar.setContentDescription("Rotate the reference image");
        bar.setMax(360);
        bar.setProgress(180 + Math.round(angle));
        bar.setEnabled(!document.referenceLocked());
        SettingsForm.sliderRow(rows, caption, bar, app.prefs.largeSettingsText());
        Runnable captioned = () -> caption.setText("Rotate\n" + Math.round(angle) + "°");
        captioned.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                if (document.referenceLocked()) return;
                app.referenceChange(document::beginReferenceEdit);
            }
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                angle = progress - 180;
                captioned.run();
                if (fromUser && !document.referenceLocked()) preview();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { commit(); }
        });
    }

    /** 1…200% of the size the picture was imported at, 100% in the middle. */
    private void addScale(LinearLayout rows) {
        TextView caption = new TextView(app);
        SeekBar bar = new SeekBar(app);
        bar.setContentDescription("Scale the reference image");
        bar.setMax(199);
        bar.setProgress(Math.round(scale / baseScale * 100) - 1);
        bar.setEnabled(!document.referenceLocked());
        SettingsForm.sliderRow(rows, caption, bar, app.prefs.largeSettingsText());
        Runnable captioned = () -> caption.setText("Scale\n" + Math.round(scale / baseScale * 100) + "%");
        captioned.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                if (document.referenceLocked()) return;
                app.referenceChange(document::beginReferenceEdit);
            }
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                scale = baseScale * (progress + 1) / 100f;
                captioned.run();
                if (fromUser && !document.referenceLocked()) preview();
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { commit(); }
        });
    }

    /** Opacity needs no resample: it rides the layer's own persisted opacity. */
    private void addOpacity(LinearLayout rows) {
        TextView caption = new TextView(app);
        SeekBar bar = new SeekBar(app);
        bar.setContentDescription("Reference image opacity");
        bar.setMax(100);
        bar.setProgress(document.layerOpacity(0));
        bar.setEnabled(!document.referenceLocked());
        SettingsForm.sliderRow(rows, caption, bar, app.prefs.largeSettingsText());
        caption.setText("Opacity\n" + document.layerOpacity(0) + "%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                caption.setText("Opacity\n" + value + "%");
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                app.referenceChange(() -> document.setLayerOpacity(0, seekBar.getProgress()));
            }
        });
    }

    private void preview() {
        candidate = ReferenceImage.place(centerX, centerY,
                document.referenceImageWidth(), document.referenceImageHeight(), scale, angle);
        app.pad.showReferenceOutline(candidate);
    }

    /** Resample once, at the transform the slider reached. */
    private void commit() {
        float[] pending = candidate;
        candidate = null;
        // Restore the pixels under the outline before the final composite.
        app.pad.clearReferenceOutline();
        if (pending == null) {
            // A touch that never moved still opened a step; close it so painting works.
            if (document.referenceEditing()) app.referenceChange(document::endReferenceEdit);
            return;
        }
        app.referenceChange(() -> {
            document.placeReference(pending);
            document.endReferenceEdit();
        });
    }
}
