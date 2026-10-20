package io.github.mpdairy.monopaint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authoritative 8-bit tones, including black stipples deposited by watercolor.
 * Display dithering is derived from these tones. Confined to its owner thread. */
final class ToneDocument {
    static final int MAX_LAYERS = 8;
    /** The whole layer list including the reference image underneath. */
    static final int MAX_STACK = MAX_LAYERS + 1;
    static final int MAX_PIXELS = 1920 * 2560;
    private static final int TILE = 64;
    private static final int HISTORY_BYTES = 32 * 1024 * 1024;
    int width, height;
    private byte[] tones, alpha;
    /** The last tile {@link #baseTile} looked up, so a dab's pixels skip the map; reset with {@link #before}. */
    private byte[] lastBaseTile;
    private int lastBaseKey=-1;
    /**
     * Pixels this gesture's {@link #transparentMask} has fully glazed with {@link #glazedGray},
     * one bit each, a row of a tile per long. Overlapping dabs would only lay the same glaze again.
     */
    private final HashMap<Integer,long[]> glazed=new HashMap<>();
    private int glazedGray=-1;
    private final ArrayList<Layer> layers = new ArrayList<>();
    private int active;
    /**
     * Masking fluid over the whole page, or null: its darkness shields every layer from paint,
     * erasing and wet paint seeping in. It is never part of the artwork.
     */
    private Layer mask;
    /** Gestures paint the masking fluid instead of the active layer; see {@link #paintMaskingFluid}. */
    private boolean maskTarget;
    /**
     * The selected region of the page, or null. While it is set, every pixel change lands
     * only on selected pixels, whatever tool or layer made it. The artist switches layers
     * with the selection kept, so it belongs to the document rather than to a layer.
     */
    private Selection selection;
    /** The layer {@link #tones} and {@link #alpha} belong to: the active one, or the mask during a masking gesture. */
    private Layer bound;
    private long revision;
    long revision() { return revision; }
    static final class Layer {
        String name;
        boolean visible;
        int opacity=100;
        byte[] tones, alpha;
        boolean shared;
        /** The reference layer: never paintable, never leaves index 0. */
        boolean readOnly;
        /** The reference's own padlock; its move/replace/remove APIs refuse while set. */
        boolean locked;
        /** The source picture + placement these pixels came from. */
        ReferenceImage reference;
        Layer(String name, boolean visible, byte[] tones, byte[] alpha) {
            this.name=name; this.visible=visible; this.tones=tones; this.alpha=alpha;
        }
        // Saves own immutable array references. Only a subsequently edited layer is copied.
        Layer copy() { shared=true; Layer copy=new Layer(name,visible,tones,alpha); copy.opacity=opacity;
            copy.readOnly=readOnly; copy.locked=locked; copy.reference=reference; return copy; }
    }
    static final class Snapshot {
        final int width, height, active;
        final ArrayList<Layer> layers = new ArrayList<>();
        /** The masking fluid, or null when the page has none. */
        final Layer mask;
        Snapshot(ToneDocument document) {
            width=document.width; height=document.height; active=document.active;
            for(Layer layer:document.layers) layers.add(layer.copy());
            mask=document.hasMask()?document.mask.copy():null;
        }
    }
    Snapshot layerSnapshot() { return new Snapshot(this); }
    int layerCount() { return layers.size(); }
    /** How many layers the artist has: everything except the reference underneath. */
    int paintLayers() {
        int count=0;
        for(Layer layer:layers) if(!layer.readOnly) count++;
        return count;
    }
    boolean isReference(int index) { return layers.get(index).readOnly; }
    boolean hasReference() { return !layers.isEmpty() && layers.get(0).readOnly; }
    private Layer referenceLayer() { return layers.get(0); }
    int[] referenceBounds() { return ReferenceImage.bounds(referenceLayer().reference, width, height); }
    float[] referencePlacement() { return hasReference() ? referenceLayer().reference.transform.clone() : null; }
    int referenceImageWidth() { return referenceLayer().reference.width; }
    int referenceImageHeight() { return referenceLayer().reference.height; }
    boolean referenceLocked() { return hasReference() && referenceLayer().locked; }
    /** The lock is a wish about the panel, not artwork: never an undo step. */
    void setReferenceLocked(boolean value) { idle(); if(hasReference()) referenceLayer().locked=value; }
    /**
     * Live opacity while the panel's slider moves. No undo step and no resample: opacity
     * only changes the pixels the reference already covers, so just its band is redrawn.
     * {@link #commitReferenceOpacity} records the gesture as one step.
     */
    void previewReferenceOpacity(int value) {
        idle();
        if(value<0||value>100) throw new IllegalArgumentException("Invalid layer opacity");
        Layer layer=referenceLayer();
        if(layer.opacity==value) return;
        layer.opacity=value;
        revision++;
        int[] box=referenceBounds();
        markDirty(box[0],box[1],box[2],box[3]);
    }
    /** Record the previewed opacity as one undo step, coming from {@code from}. */
    void commitReferenceOpacity(int from) {
        idle();
        Layer layer=referenceLayer();
        int to=layer.opacity;
        if(from==to) return;
        layer.opacity=from;                      // rewind so the snapshot captures the old state
        StackState old=new StackState(this);
        layer.opacity=to;
        stackEdit(old);
    }
    /** Replace or insert the reference at the bottom of the stack as one undo step. */
    void setReference(ReferenceImage image) {
        idle();
        if(image==null) throw new IllegalArgumentException("A reference needs a picture");
        if(referenceLocked()) throw new IllegalStateException("Unlock the reference image first");
        StackState old=new StackState(this);
        if(hasReference()) {
            Layer prior=referenceLayer();
            Layer fresh=new Layer("Reference",prior.visible,paper(width,height),new byte[width*height]);
            fresh.readOnly=true; fresh.locked=prior.locked; fresh.reference=image; fresh.opacity=prior.opacity;
            ReferenceImage.rasterInto(image,fresh.tones,fresh.alpha,width,height,0,0,width,height);
            layers.set(0,fresh);
        } else {
            Layer layer=new Layer("Reference",true,paper(width,height),new byte[width*height]);
            layer.readOnly=true; layer.reference=image;
            ReferenceImage.rasterInto(image,layer.tones,layer.alpha,width,height,0,0,width,height);
            layers.add(0,layer);
            selectLayer(active+1); // the artist never ends up "on" the reference
        }
        stackEdit(old);
    }
    void removeReference() {
        idle();
        if(!hasReference()) return;
        if(referenceLocked()) throw new IllegalStateException("Unlock the reference image first");
        StackState old=new StackState(this);
        layers.remove(0);
        selectLayer(Math.max(0,active-1));
        stackEdit(old);
    }
    private float[] referenceEditFrom;
    private float[] referencePending;
    private StackState referenceEditOld;
    boolean referenceEditing() { return editing && referenceEditFrom!=null; }
    /** Opens one undoable step spanning many re-rasterizations of the reference. */
    void beginReferenceEdit() {
        idle();
        if(!hasReference()) throw new IllegalStateException("There is no reference image");
        if(referenceLocked()) throw new IllegalStateException("Unlock the reference image first");
        referenceEditFrom=referenceLayer().reference.transform.clone();
        referencePending=referenceEditFrom.clone();
        referenceEditOld=new StackState(this);
        editing=true;
    }
    /** Re-raster the picture at a new placement inside an open reference edit. */
    void placeReference(float[] placement) {
        if(!referenceEditing()) throw new IllegalStateException("Start a reference edit first");
        if(referenceLocked()) throw new IllegalStateException("Unlock the reference image first");
        referencePending=placement.clone();
        Layer layer=writableReference();
        ReferenceImage prior=layer.reference;
        int[] before=ReferenceImage.bounds(prior,width,height);
        ReferenceImage next=new ReferenceImage(prior.lum,prior.alpha,prior.width,prior.height,placement.clone());
        int[] now=ReferenceImage.bounds(next,width,height);
        int l=Math.min(before[0],now[0]),t=Math.min(before[1],now[1]),r=Math.max(before[2],now[2]),b=Math.max(before[3],now[3]);
        for(int y=t;y<b;y++) {
            java.util.Arrays.fill(layer.tones,y*width+l,y*width+r,(byte)255);
            java.util.Arrays.fill(layer.alpha,y*width+l,y*width+r,(byte)0);
        }
        ReferenceImage.rasterInto(next,layer.tones,layer.alpha,width,height,now[0],now[1],now[2],now[3]);
        layer.reference=next;
        markDirty(l,t,r,b);
    }
    /**
     * Move the already drawn pixels by whole pixels, no resample. Accumulate against the
     * REQUESTED placement, never the drawn (rounded) one, or the fractional remainder of
     * each step is lost and the picture lags the finger.
     */
    void slideReference(double dx,double dy) {
        if(!referenceEditing()) throw new IllegalStateException("Start a reference edit first");
        if(referenceLocked()) throw new IllegalStateException("Unlock the reference image first");
        float[] next=ReferenceImage.translated(referencePending,dx,dy);
        referencePending=next;
        float[] shown=referenceLayer().reference.transform;
        int sx=Math.round(next[2]-shown[2]),sy=Math.round(next[5]-shown[5]);
        if(sx!=0||sy!=0) shiftReference(sx,sy);
    }
    /**
     * Shift both planes of the reference layer by whole pixels, filling the vacated strip
     * with paper. Rows walk toward the direction of travel so every row is read before it
     * is overwritten; top-to-bottom while shifting down would copy already-cleared rows.
     */
    private void shiftReference(int dx,int dy) {
        Layer layer=writableReference();
        ReferenceImage prior=layer.reference;
        int[] before=ReferenceImage.bounds(prior,width,height);
        ReferenceImage next=new ReferenceImage(prior.lum,prior.alpha,prior.width,prior.height,
                ReferenceImage.translated(prior.transform,dx,dy));
        shiftPlane(layer.tones,width,height,dx,dy,(byte)255);
        shiftPlane(layer.alpha,width,height,dx,dy,(byte)0);
        layer.reference=next;
        int[] now=ReferenceImage.bounds(next,width,height);
        markDirty(Math.min(before[0],now[0]),Math.min(before[1],now[1]),
                Math.max(before[2],now[2]),Math.max(before[3],now[3]));
    }
    private static void shiftPlane(byte[] plane,int w,int h,int dx,int dy,byte paper) {
        int step=dy>=0?-1:1,start=dy>=0?h-1:0;
        for(int y=start;y>=0&&y<h;y+=step) {
            int sy=y-dy,row=y*w;
            if(sy<0||sy>=h) { java.util.Arrays.fill(plane,row,row+w,paper); continue; }
            int srow=sy*w;
            if(dx>=0) {
                java.util.Arrays.fill(plane,row,row+Math.min(dx,w),paper);
                int len=w-dx;
                if(len>0) System.arraycopy(plane,srow,plane,row+dx,len);
            } else {
                int len=w+dx;
                if(len>0) System.arraycopy(plane,srow-dx,plane,row,len);
                java.util.Arrays.fill(plane,row+len,row+w,paper);
            }
        }
    }
    /**
     * Close the reference edit: re-raster the exact pending placement and record one undo
     * step. A drag that went nowhere records nothing.
     */
    void endReferenceEdit() {
        if(!referenceEditing()) throw new IllegalStateException("No reference edit is open");
        float[] from=referenceEditFrom,pending=referencePending;
        referenceEditFrom=null; referencePending=null;
        StackState old=referenceEditOld; referenceEditOld=null;
        editing=false;
        if(java.util.Arrays.equals(from,pending)) return;
        Layer layer=writableReference();
        ReferenceImage prior=layer.reference;
        int[] before=ReferenceImage.bounds(prior,width,height);
        ReferenceImage next=new ReferenceImage(prior.lum,prior.alpha,prior.width,prior.height,pending);
        int[] now=ReferenceImage.bounds(next,width,height);
        // placeReference may already have drawn this exact transform; skip the second resample.
        if(!java.util.Arrays.equals(prior.transform,pending)) {
            int l=Math.min(before[0],now[0]),t=Math.min(before[1],now[1]),r=Math.max(before[2],now[2]),b=Math.max(before[3],now[3]);
            for(int y=t;y<b;y++) {
                java.util.Arrays.fill(layer.tones,y*width+l,y*width+r,(byte)255);
                java.util.Arrays.fill(layer.alpha,y*width+l,y*width+r,(byte)0);
            }
            ReferenceImage.rasterInto(next,layer.tones,layer.alpha,width,height,now[0],now[1],now[2],now[3]);
            layer.reference=next;
            revision++;
            markDirty(l,t,r,b);
        }
        addEdit(new Edit(old,new StackState(this)));
    }
    /** The reference layer, un-shared and ready to mutate in place. */
    private Layer writableReference() {
        revision++;
        Layer layer=referenceLayer();
        if(layer.shared) {
            layer.tones=layer.tones.clone(); layer.alpha=layer.alpha.clone(); layer.shared=false;
        }
        return layer;
    }
    /** Re-raster the whole reference layer from its picture; never trust reused arrays. */
    private void restoreReference(ReferenceImage image) {
        if(!hasReference()||image==null) return;
        Layer layer=referenceLayer();
        if(layer.shared) {
            layer.tones=layer.tones.clone(); layer.alpha=layer.alpha.clone(); layer.shared=false;
        }
        java.util.Arrays.fill(layer.tones,(byte)255); java.util.Arrays.fill(layer.alpha,(byte)0);
        ReferenceImage.rasterInto(image,layer.tones,layer.alpha,width,height,0,0,width,height);
        layer.reference=image;
    }
    int activeLayer() { return active; }
    String layerName(int index) { return layers.get(index).name; }
    boolean layerVisible(int index) { return layers.get(index).visible; }
    int layerOpacity(int index) { return layers.get(index).opacity; }
    void setLayerOpacity(int index,int value) {
        idle(); if(value<0||value>100) throw new IllegalArgumentException("Invalid layer opacity");
        if(layers.get(index).opacity==value)return;
        StackState old=new StackState(this); layers.get(index).opacity=value; stackEdit(old);
    }
    private void writable() {
        revision++;
        if(bound.shared) {
            bound.tones=bound.tones.clone(); bound.alpha=bound.alpha.clone(); bound.shared=false;
            bind(bound);
        }
    }
    private void bind(Layer layer) { bound=layer; tones=layer.tones; alpha=layer.alpha; }
    private void idle() { if(editing) throw new IllegalStateException("Finish the current gesture first"); }
    void selectLayer(int index) {
        idle(); if(isReference(index)) throw new IllegalStateException("The reference image cannot be drawn on.");
        Layer layer=layers.get(index); if(active!=index)revision++;
        active=index; bind(layer);
    }
    /** Gestures from now on lay or, erasing, rub off masking fluid instead of painting the active layer. */
    void paintMaskingFluid(boolean value) { idle(); maskTarget=value; }
    /** Whether any masking fluid is on the page. */
    boolean hasMask() {
        if(mask==null) return false;
        for(byte a:mask.alpha) if(a!=0) return true;
        return false;
    }
    /** How strongly masking fluid shields this pixel, 0 to 255: the fluid's darkness. */
    private int shield(int i) { return mask==null?0:255-onWhite(mask.tones[i]&255,mask.alpha[i]&255); }
    /** Whether this page pixel may be changed: inside the selection, or there is none. */
    private boolean allowed(int x,int y) { return selection==null||selection.contains(x,y); }
    /** The page's selection, or null. */
    Selection selection() { return selection; }
    void setSelection(Selection value) { selection=value; }
    /** The selection's bounding box, or null when the page has no selection. */
    int[] selectionBounds() { return selection==null?null:new int[]{selection.left,selection.top,selection.right,selection.bottom}; }
    /** Copy the selected pixels of the active layer into a patch ready to paste. */
    Clipboard copySelection() {
        idle();
        if (selection == null || selection.empty()) throw new IllegalStateException("Nothing is selected");
        int[] box = selectionBounds();
        int w = box[2]-box[0], h = box[3]-box[1];
        byte[] tones = new byte[w*h], alpha = new byte[w*h];
        for (int y = 0; y < h; y++) {
            System.arraycopy(bound.tones, (box[1]+y)*width+box[0], tones, y*w, w);
            System.arraycopy(bound.alpha, (box[1]+y)*width+box[0], alpha, y*w, w);
        }
        java.util.BitSet pixels = new java.util.BitSet(w*h);
        java.util.BitSet region = new java.util.BitSet(w*h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = (box[1]+y)*width + box[0]+x;
            if (!selection.contains(box[0]+x, box[1]+y)) continue;
            region.set(y*w+x);
            // Only pixels that hold paint travel with the copy; untouched paper would
            // lay white over whatever the copy lands on. A placed copy also only carries
            // the pixels it owns, never artwork that was already under its area.
            if (selection.owns(box[0]+x, box[1]+y) && (bound.alpha[i]&255) != 0) pixels.set(y*w+x);
        }
        if (pixels.isEmpty()) throw new IllegalStateException("Nothing is selected");
        // The outline is the source selection's own: a lasso keeps the smooth line the
        // artist traced. Rebuilding it from the travelling pixels instead draws a
        // one-pixel staircase of thousands of tiny segments, which a dash effect turns
        // into unreadable mush.
        return new Clipboard(box[0], box[1], w, h, tones, alpha, pixels, region, selection.outline);
    }
    /**
     * Paste {@code patch} onto the active layer at {@code dx},{@code dy} page pixels, as one
     * undoable change. Only the patch's selected pixels are drawn, and only where the page's
     * current selection allows, so a paste lands inside the selection like any other stroke.
     */
    void paste(Clipboard patch,int dx,int dy) { paste(patch,dx,dy,false); }
    /**
     * As {@link #paste}, and when {@code continues} is set the paste joins the undo step
     * already on top of the stack rather than adding its own. A cut wipes its spot when it
     * is made and pastes when it is dropped; that is one action, so one undo step should
     * reverse both.
     */
    void paste(Clipboard patch,int dx,int dy,boolean continues) {
        idle();
        if(patch==null||patch.empty()) throw new IllegalStateException("There is nothing to paste");
        begin();
        try { pasteInto(patch,dx,dy); } finally { if (continues) finishContinuation(); else finish(); }
    }
    private void pasteInto(Clipboard patch,int dx,int dy) {
        for(int row=0;row<patch.height;row++) for(int col=0;col<patch.width;col++) {
            if(!patch.pixels.get(row*patch.width+col)) continue;
            int x=patch.left+col+dx,y=patch.top+row+dy;
            if(x<0||y<0||x>=width||y>=height) continue;
            setPixel(x,y,patch.tones[row*patch.width+col]&255,patch.alpha[row*patch.width+col]&255);
        }
    }
    /**
     * Write {@code patch} down at {@code dx},{@code dy} and make the selection the copy's
     * new home, remembering what lay under it first. This is dropping a copy: the copy
     * overwrites the drawing beneath, so the record has to be taken before the paste or
     * the next move of the copy can only uncover a hole.
     */
    void pastePlaced(Clipboard patch, int dx, int dy, boolean continues) {
        idle();
        if (patch == null || patch.empty()) throw new IllegalStateException("There is nothing to paste");
        begin();
        try {
            // Move the selection over the landing spot first: the gate would otherwise
            // block every pixel that lands outside where the selection was.
            selection = patch.selectionMovedBy(dx, dy, width, height);
            // Read what is down before writing over it: begin() has just bound the layer
            // the copy lands on, so this is the first moment the covered artwork is right.
            selection.backing = snapshotUnder(patch, dx, dy);
            pasteInto(patch, dx, dy);
        } finally { if (continues) finishContinuation(); else finish(); }
    }
    /** How many undo steps exist now. A cut records this so its drop can join its own wipe. */
    int undoDepth() { return undo.size(); }

    /**
     * Take the selected pixels off the page as one undoable step, leaving the layer bare
     * where they were. {@link #copySelection} took their copy first, so a cut is this wipe
     * plus a copy riding the pen; undoing puts them back.
     */
    boolean wipeSelection() {
        idle();
        if (selection == null || selection.empty()) throw new IllegalStateException("Nothing is selected");
        int[] box = selectionBounds();
        java.util.BitSet inside = new java.util.BitSet(width * height);
        for (int y = box[1]; y < box[3]; y++) for (int x = box[0]; x < box[2]; x++)
            // Same rule as a copy: a placed copy wipes only its own pixels, so the bare
            // spot it leaves never takes artwork that was merely underneath it.
            if (selection.contains(x, y) && selection.owns(x, y)
                    && (bound.alpha[y * width + x] & 255) != 0) inside.set(y * width + x);
        if (inside.isEmpty()) return false;
        Selection was = selection;
        // A placed copy overwrote the artwork it was sitting on; that record is what the
        // bare spot should show, so cutting a placed copy uncovers the drawing instead of
        // leaving a hole shaped like its own pixels.
        Clipboard under = was.backing;
        begin();
        try {
            // The gate would block the wipe: the pixels being cleared are the ones the
            // selection currently allows, and clearing them is not drawing inside it.
            selection = null;
            for (int i = inside.nextSetBit(0); i >= 0; i = inside.nextSetBit(i + 1))
                uncover(under, i % width, i / width);
        } finally { finish(); }
        selection = was;
        return true;
    }

    /**
     * Cut the patch out of the page and paste it {@code dx},{@code dy} page pixels away
     * as one undoable step, the selection following the pixels. This is what dropping a
     * cut copy does, and what moving the current selection does.
     */
    void moveCut(Clipboard patch, int dx, int dy) {
        idle();
        if (patch == null || patch.empty()) throw new IllegalStateException("Nothing is selected");
        Selection was = selection;
        // Whatever the copy was sitting on. A paste overwrote it, so uncovering the copy
        // can only restore it from this record; without one the copy leaves a blank hole
        // exactly the shape of its own pixels.
        Clipboard backing = was == null ? null : was.backing;
        int edits = undo.size();
        begin();
        try {
            // The page's selection may already sit over the drop point, so clear it while
            // wiping the source: the gate would otherwise block every one of these pixels.
            selection = null;
            for (int row = 0; row < patch.height; row++) for (int col = 0; col < patch.width; col++) {
                if (!patch.pixels.get(row * patch.width + col)) continue;
                uncover(backing, patch.left + col, patch.top + row);
            }
            // The pixels land where the selection is going, so the gate lets them through.
            selection = patch.selectionMovedBy(dx, dy, width, height);
            // Remember what this copy covers at its new resting place, so the next move
            // uncovers the drawing rather than a hole.
            selection.backing = snapshotUnder(patch, dx, dy);
            pasteInto(patch, dx, dy);
        } finally { finish(); }
        // Undo puts the selection back where the move started; redo brings it forward.
        if (undo.size() > edits) {
            Edit edit = undo.peekLast();
            edit.from = was; edit.to = selection;
        }
    }

    /**
     * Put one page pixel back to whatever a placed copy found underneath it, or to bare
     * paper when it has no such record. {@code under} is a patch of page content sharing
     * the copy's box, so the page point maps straight into it.
     */
    private void uncover(Clipboard under, int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        int at = under == null ? -1 : (y - under.top) * under.width + (x - under.left);
        boolean held = under != null && at >= 0 && at < under.pixels.size() && under.pixels.get(at);
        setPixel(x, y, held ? under.tones[at] & 255 : 255, held ? under.alpha[at] & 255 : 0);
    }

    /**
     * The page content under {@code patch} where it would land at {@code dx},{@code dy},
     * as a patch of the same box. A placed copy keeps this so moving it can put the
     * artwork it was covering back instead of leaving bare paper.
     */
    Clipboard snapshotUnder(Clipboard patch, int dx, int dy) {
        byte[] heldTones = new byte[patch.width * patch.height];
        byte[] heldAlpha = new byte[patch.width * patch.height];
        java.util.BitSet covered = new java.util.BitSet(patch.width * patch.height);
        for (int row = 0; row < patch.height; row++) for (int col = 0; col < patch.width; col++) {
            if (!patch.pixels.get(row * patch.width + col)) continue;
            int x = patch.left + col + dx, y = patch.top + row + dy;
            if (x < 0 || y < 0 || x >= width || y >= height) continue;
            int at = row * patch.width + col, page = y * width + x;
            heldTones[at] = tones[page]; heldAlpha[at] = alpha[page];
            covered.set(at);
        }
        return new Clipboard(patch.left + dx, patch.top + dy, patch.width, patch.height,
                heldTones, heldAlpha, covered);
    }
    /**
     * Move the selected pixels {@code dx},{@code dy} page pixels away as one undoable step:
     * the area they came from goes back to paper and the selection follows the pixels.
     */
    void moveSelection(int dx, int dy) {
        idle();
        if (selection == null || selection.empty()) throw new IllegalStateException("Nothing is selected");
        moveCut(copySelection(), dx, dy);
    }
    /** Peel all the masking fluid off the page as one undoable step; false when there is none. */
    boolean peelMask() {
        idle(); if(!hasMask()) { mask=null; return false; }
        StackState old=new StackState(this); mask=null; stackEdit(old); return true;
    }
    /** Spacing of {@link #maskHatch}'s diagonal lines, and how many pixels across each is, thick enough to read as lines at the panel's resolution. */
    static final int HATCH=12, HATCH_LINE=4;
    /**
     * Diagonal hatching that shows the masking fluid over the painting: black over light
     * paint and white over dark, or -1 where the pixel shows the painting.
     */
    int maskHatch(int x,int y) {
        if(mask==null||(x+y)%HATCH>=HATCH_LINE||shield(y*width+x)<32) return -1;
        return compositeTone(x,y)<128?255:0;
    }
    void addLayer() {
        idle(); if(paintLayers()>=MAX_LAYERS) throw new IllegalStateException("Each page can have up to "+MAX_LAYERS+" layers.");
        StackState old=new StackState(this);
        int number=1;
        while(hasName("Layer "+number)) number++;
        layers.add(active+1,new Layer("Layer "+number,true,paper(width,height),new byte[width*height]));
        selectLayer(active+1); stackEdit(old);
    }
    private boolean hasName(String name) { for(Layer layer:layers) if(layer.name.equals(name)) return true; return false; }
    void removeLayer() {
        idle(); if(paintLayers()==1) throw new IllegalStateException("Keep at least one layer.");
        StackState old=new StackState(this); layers.remove(active); selectLayer(Math.min(active,layers.size()-1)); stackEdit(old);
    }
    void moveLayer(int target) {
        idle();
        if(hasReference()&&target==0) target=1; // the reference always stays underneath
        if(target<0||target>=layers.size()||target==active) return;
        StackState old=new StackState(this); Layer layer=layers.remove(active); layers.add(target,layer); selectLayer(target); stackEdit(old);
    }
    void setLayerVisible(int index,boolean visible) {
        idle(); if(layers.get(index).visible==visible) return;
        StackState old=new StackState(this); layers.get(index).visible=visible; stackEdit(old);
    }
    void renameLayer(String name) {
        idle(); name=name.trim(); if(name.isEmpty()||name.length()>40) throw new IllegalArgumentException("Use a layer name from 1 to 40 characters.");
        if(layers.get(active).name.equals(name)) return;
        StackState old=new StackState(this); layers.get(active).name=name; stackEdit(old);
    }
    private void stackEdit(StackState old) {
        revision++;
        addEdit(new Edit(old,new StackState(this))); markAllDirty();
    }
    /** Add transparent paper around every layer without resampling existing pixels. */
    void expand(int left, int top, int right, int bottom) {
        idle();
        if (left<0 || top<0 || right<0 || bottom<0) throw new IllegalArgumentException("Canvas margins cannot be negative");
        long w=(long)width+left+right, h=(long)height+top+bottom;
        if(w>4096 || h>4096) throw new IllegalArgumentException("This canvas is too large to expand further.");
        validateSize((int)w,(int)h);
        if(w==width && h==height)return;
        // A resized page no longer matches its selection's indices; drop it rather than
        // leave it covering the wrong pixels.
        selection=null;
        StackState old=new StackState(this);
        ArrayList<Layer> expanded=new ArrayList<>();
        for(Layer layer:layers) expanded.add(expanded(layer,(int)w,(int)h,left,top));
        Layer fluid=mask==null?null:expanded(mask,(int)w,(int)h,left,top);
        width=(int)w; height=(int)h; layers.clear(); layers.addAll(expanded); mask=fluid;
        // The reference's drawn pixels moved with the paper; its placement must say so too.
        if(hasReference()) {
            Layer layer=referenceLayer();
            layer.reference=new ReferenceImage(layer.reference.lum,layer.reference.alpha,
                    layer.reference.width,layer.reference.height,
                    ReferenceImage.translated(layer.reference.transform,left,top));
        }
        captured=new boolean[columns()*((height+TILE-1)/TILE)];
        selectLayer(active); stackEdit(old);
    }
    /** A copy of {@code layer} on a larger transparent canvas, offset by {@code left} and {@code top}. */
    private Layer expanded(Layer layer,int w,int h,int left,int top) {
        byte[] ink=paper(w,h), coverage=new byte[w*h];
        for(int y=0;y<height;y++) {
            System.arraycopy(layer.tones,y*width,ink,(y+top)*w+left,width);
            System.arraycopy(layer.alpha,y*width,coverage,(y+top)*w+left,width);
        }
        Layer next=new Layer(layer.name,layer.visible,ink,coverage);
        next.opacity=layer.opacity; next.readOnly=layer.readOnly; next.locked=layer.locked; next.reference=layer.reference;
        return next;
    }
    private void markAllDirty() { left=0; top=0; right=width; bottom=height; }
    private void markDirty(int l,int t,int r,int b) {
        left=Math.min(left,l); top=Math.min(top,t); right=Math.max(right,r); bottom=Math.max(bottom,b);
    }
    private void addEdit(Edit edit) {
        for(Edit discarded:redo) historyBytes-=discarded.bytes;
        redo.clear(); undo.addLast(edit); historyBytes+=edit.bytes; trimHistory(HISTORY_BYTES);
    }
    /** Bottom-to-top source-over composition on white paper. Brushes read only the active layer. */
    int compositeTone(int x,int y) {
        int i=y*width+x, result=255;
        for(int n=0;n<layers.size();n++) {
            Layer layer=layers.get(n); if(!layer.visible) continue;
            int a=((layer.alpha[i]&255)*layer.opacity+50)/100;
            result=((layer.tones[i]&255)*a+result*(255-a)+127)/255;
        }
        return result;
    }
    int opacity(int x,int y) { return alpha[y*width+x]&255; }
    void eraseTone(int x,int y,float strength) {
        if(x<0||y<0||x>=width||y>=height) return;
        int a=opacity(x,y), removed=Math.max(1,Math.round(a*strength));
        if(strength>0) setPixel(x,y,tones[y*width+x]&255,Math.max(0,a-removed));
    }
    /** Anti-aliased edge pixels, with partial mask alpha, remove only that share of the gesture's original coverage. */
    void eraseMask(int[] mask,int stride,int x,int y,int w,int h) {
        for(int row=0;row<h;row++) for(int col=0;col<w;col++) {
            int c=mask[row*stride+col]>>>24, px=x+col, py=y+row;
            if(c==255) eraseTone(px,py,1);
            else if(c!=0 && px>=0 && py>=0 && px<width && py<height) eraseFromBase(px,py,c/255f);
        }
    }
    private boolean[] captured;
    private final LinkedHashMap<Integer, byte[]> before = new LinkedHashMap<>();
    private final ArrayDeque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private int historyBytes;
    private boolean editing;
    private int left, top, right, bottom;

    ToneDocument(int width, int height) {
        this(width, height, paper(width, height));
        Arrays.fill(alpha,(byte)0);
    }
    ToneDocument(int width, int height, byte[] source) {
        validateSize(width, height);
        if (source.length != width * height) throw new IllegalArgumentException("Wrong tone count");
        this.width = width; this.height = height;
        byte[] coverage=new byte[source.length]; Arrays.fill(coverage,(byte)255);
        layers.add(new Layer("Layer 1",true,source.clone(),coverage)); selectLayer(0);
        captured = new boolean[columns() * ((height + TILE - 1) / TILE)];
        clearDirty();
    }
    ToneDocument(int width,int height,ArrayList<Layer> source,int active) { this(width,height,source,active,null); }
    /** @param mask the page's masking fluid, or null */
    ToneDocument(int width,int height,ArrayList<Layer> source,int active,Layer mask) {
        validateSize(width,height); this.width=width; this.height=height;
        if(source.isEmpty()||source.size()>MAX_STACK) throw new IllegalArgumentException("Invalid layer count");
        ArrayList<Layer> all=new ArrayList<>(source); if(mask!=null) all.add(mask);
        for(Layer layer:all)
            if(layer.tones.length!=width*height||layer.alpha.length!=width*height) throw new IllegalArgumentException("Invalid layer pixels");
        layers.addAll(source); this.mask=mask;
        // A page always keeps one paintable layer; never land on the reference.
        if(isReference(active)) {
            int first=0;
            while(first<layers.size()&&isReference(first)) first++;
            if(first==layers.size()) throw new IllegalArgumentException("A page needs a paintable layer");
            active=first;
        }
        selectLayer(active); captured=new boolean[columns()*((height+TILE-1)/TILE)]; clearDirty();
    }
    static void validateSize(int width, int height) {
        if (!validSize(width, height)) throw new IllegalArgumentException("Invalid canvas size");
    }
    static boolean validSize(int width, int height) {
        return width > 0 && height > 0 && width <= 4096 && height <= 4096 && (long)width * height <= MAX_PIXELS;
    }
    private static byte[] paper(int width, int height) {
        validateSize(width, height);
        byte[] bytes = new byte[width * height]; Arrays.fill(bytes, (byte)255); return bytes;
    }
    int tone(int x, int y) { int i=y*width+x; return onWhite(tones[i]&255,alpha[i]&255); }
    /** The active layer's tone, even while a gesture lays masking fluid, so fills can mask a painted region. */
    int layerTone(int x, int y) { Layer layer=layers.get(active); int i=y*width+x; return onWhite(layer.tones[i]&255,layer.alpha[i]&255); }
    private static int onWhite(int tone,int alpha) { return (tone*alpha+255*(255-alpha)+127)/255; }
    int strokeBaseTone(int x, int y) {
        int key=(y/TILE)*columns()+x/TILE;
        if(!captured[key]) return tone(x,y);
        int tileWidth=Math.min(TILE,width-(x/TILE)*TILE);
        int offset=(y%TILE)*tileWidth+x%TILE;
        byte[] tile=baseTile(key);
        return onWhite(tile[offset]&255,tile[tile.length/2+offset]&255);
    }
    byte[] snapshot() {
        byte[] result=new byte[width*height];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) result[y*width+x]=(byte)compositeTone(x,y);
        return result;
    }
    /** Opaque RGB pixels of the composite, calibrated as in {@link DotPattern#exportGray} or as raw logical tones. */
    int[] exportPixels(boolean calibrated) {
        int[] pixels=new int[width*height];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int g=compositeTone(x,y); if(calibrated) g=DotPattern.exportGray(g);
            pixels[y*width+x]=0xff000000|(g<<16)|(g<<8)|g;
        }
        return pixels;
    }
    void begin() { begin(maskTarget); }
    private void begin(boolean fluid) {
        if (editing) throw new IllegalStateException("Finish the current gesture first");
        if(fluid) {
            if(mask==null) mask=new Layer("Masking fluid",true,paper(width,height),new byte[width*height]);
            bind(mask);
        } else if(!layerVisible(active)) throw new IllegalStateException("Show this layer before drawing on it.");
        writable(); editing = true; before.clear();lastBaseTile=null;lastBaseKey=-1;glazed.clear();
    }
    void setTone(int x,int y,int gray) {
        if (!editing) throw new IllegalStateException("No active gesture");
        if(gray<0||gray>255) throw new IllegalArgumentException("Invalid tone");
        if(x<0||y<0||x>=width||y>=height||tone(x,y)==gray) return;
        // Tonal tools carry pigment over transparent paper; opaque brushes use paintTone.
        int coverage=Math.max(opacity(x,y),255-gray);
        setPixel(x,y,rawTone(gray,coverage),coverage);
    }
    /**
     * {@link #setTone} for a block of pixels, {@code grays} row by row, {@code w} to a row; a
     * negative gray leaves its pixel, as do those off the page. Wet paint changes this many
     * at once each frame, and the checks for the whole block are made once.
     */
    void setTones(int x,int y,int w,int h,int[] grays) {
        if (!editing) throw new IllegalStateException("No active gesture");
        if(mask!=null&&bound!=mask) {
            // Masking fluid shields some of these pixels; each one takes its share.
            for(int row=0;row<h;row++) for(int col=0;col<w;col++) if(grays[row*w+col]>=0) setTone(x+col,y+row,grays[row*w+col]);
            return;
        }
        for(int row=Math.max(0,-y);row<Math.min(h,height-y);row++) for(int col=Math.max(0,-x);col<Math.min(w,width-x);col++) {
            int gray=grays[row*w+col],px=x+col,py=y+row,i=py*width+px;
            if(gray<0) continue;
            if(gray>255) throw new IllegalArgumentException("Invalid tone");
            if(onWhite(tones[i]&255,alpha[i]&255)==gray) continue;
            int coverage=Math.max(alpha[i]&255,255-gray);
            store(px,py,rawTone(gray,coverage),coverage);
        }
    }
    private static int rawTone(int onWhite,int coverage) {
        return coverage==0?255:Math.max(0,Math.min(255,(onWhite*255-255*(255-coverage)+coverage/2)/coverage));
    }
    void glazeTone(int x,int y,int gray) { glazeTone(x,y,gray,255); }
    /** @param edge anti-aliased share of the pixel the glaze covers, out of 255 */
    void glazeTone(int x,int y,int gray,int edge) {
        if(gray==255) return;
        int pixel=strokeBasePixel(x,y),coverage=pixel&255;
        int result=transparentTone(onWhite(pixel>>>8,coverage),gray);
        coverage=255-transparentTone(255-coverage,gray);
        cover(x,y,rawTone(result,coverage),coverage,edge);
    }
    /** Moves the gesture's base tone toward {@code gray} by {@code strength}, covering at least that much. */
    void mixTone(int x,int y,int gray,float strength) { mixTone(x,y,gray,strength,255); }
    void mixTone(int x,int y,int gray,float strength,int edge) {
        int base=strokeBaseTone(x,y),result=Math.round(base+(gray-base)*strength);
        int coverage=Math.max(edge==255?opacity(x,y):strokeBasePixel(x,y)&255,Math.max(Math.round(255*strength),255-result));
        cover(x,y,rawTone(result,coverage),coverage,edge);
    }
    /** The active layer's pixel as this gesture found it: raw tone in the high byte, coverage in the low byte. */
    private int strokeBasePixel(int x,int y) {
        int key=(y/TILE)*columns()+x/TILE,i=y*width+x;
        if(!captured[key]) return (tones[i]&255)<<8|(alpha[i]&255);
        byte[] tile=baseTile(key); int w=Math.min(TILE,width-(x/TILE)*TILE),offset=(y%TILE)*w+x%TILE;
        return (tile[offset]&255)<<8|(tile[tile.length/2+offset]&255);
    }
    /** A captured tile as this gesture found it. Captures never change mid-gesture, so the last one is kept at hand. */
    private byte[] baseTile(int key) {
        if(lastBaseKey!=key||lastBaseTile==null) { lastBaseKey=key; lastBaseTile=before.get(key); }
        return lastBaseTile;
    }
    /**
     * Lays what full contact leaves ({@code gray} at {@code coverage}) on {@code edge}/255 of the pixel,
     * mixing premultiplied with the gesture's base. A partial edge never weakens a stronger contact
     * from an overlapping dab, so smooth strokes do not darken where stamps overlap.
     */
    private void cover(int x,int y,int gray,int coverage,int edge) {
        if(edge>=255) { setPixel(x,y,gray,coverage); return; }
        if(edge<=0||x<0||y<0||x>=width||y>=height) return;
        int base=strokeBasePixel(x,y),baseTone=base>>>8,baseAlpha=base&255,i=y*width+x;
        float t=edge/255f,a=baseAlpha+(coverage-baseAlpha)*t;
        float pigment=baseTone*baseAlpha+(gray*coverage-baseTone*baseAlpha)*t;
        int resultAlpha=Math.round(a),result=resultAlpha==0?255:Math.max(0,Math.min(255,Math.round(pigment/a)));
        int from=onWhite(baseTone,baseAlpha);
        int reach=Math.abs(onWhite(result,resultAlpha)-from)+Math.abs(resultAlpha-baseAlpha);
        if(reach>Math.abs(tone(x,y)-from)+Math.abs(opacity(x,y)-baseAlpha)) setPixel(x,y,result,resultAlpha);
    }
    /** Strongest pencil contact in this gesture, carrying the chosen pigment and its coverage. */
    void pencilTone(int x,int y,int gray,float strength) { pencilTone(x,y,gray,strength,1); }
    /** @param depth how far from white toward {@code gray} this pixel's paper tooth can ever darken, across gestures */
    void pencilTone(int x,int y,int gray,float strength,float depth) {
        int base=strokeBaseTone(x,y),coverage=strokeBasePixel(x,y)&255;
        int target=Math.round(base+(gray-base)*strength),current=tone(x,y);
        if(gray<base) target=Math.max(target,Math.min(base,Math.round(255+(gray-255)*depth)));
        int resultAlpha=Math.round(coverage+(255-coverage)*strength);
        if((gray<base&&target>current)||(gray>base&&target<current)) return;
        if(target==current&&resultAlpha<=opacity(x,y)) return;
        setPixel(x,y,rawTone(target,resultAlpha),resultAlpha);
    }
    /** Source-over toward {@code gray} on the current pixel, always moving at least one step, like {@link #eraseTone}. */
    void sprayTone(int x,int y,int gray,float strength) {
        if(x<0||y<0||x>=width||y>=height||strength<=0) return;
        strength=Math.min(1,strength);
        int i=y*width+x,tone=tones[i]&255,coverage=alpha[i]&255;
        float a=strength*255+coverage*(1-strength);
        int result=Math.round((gray*strength*255+tone*coverage*(1-strength))/a);
        if(result==tone&&tone!=gray&&coverage>0) result+=gray>tone?1:-1;
        setPixel(x,y,result,Math.min(255,Math.max(coverage+1,Math.round(a))));
    }
    /** Remove coverage from the gesture's original layer, retaining its pigment. */
    void eraseFromBase(int x,int y,float strength) {
        int key=(y/TILE)*columns()+x/TILE,i=y*width+x,coverage=alpha[i]&255;
        if(captured[key]) coverage=strokeBasePixel(x,y)&255;
        int result=Math.round(coverage*(1-Math.max(0,Math.min(1,strength))));
        if(result<opacity(x,y)) setPixel(x,y,tones[i]&255,result);
    }
    /** Transparent color endpoint for fills and shapes; shades remain 0–255. */
    static final int ERASE = -1;
    void paintTone(int x,int y,int gray) { paintTone(x,y,gray,255); }
    /** Replace pigment and coverage; transparent fill endpoints must not paint white. */
    void paintTone(int x,int y,int gray,int coverage) { setPixel(x,y,gray,coverage); }
    /** Paint or erase a scanline for geometric shapes; capture undo tiles once, then fill contiguous pixels. */
    void paintSpan(int x, int end, int y, int gray) {
        if(!editing) throw new IllegalStateException("No active gesture");
        if(gray<ERASE || gray>255) throw new IllegalArgumentException("Invalid pixel");
        x=Math.max(0,x);end=Math.min(width,end);
        if(y<0 || y>=height || x>=end) return;
        if(mask!=null&&bound!=mask) {
            // Masking fluid shields some of these pixels; each one takes its share.
            for(int px=x;px<end;px++) if(gray==ERASE) setPixel(px,y,255,0); else setPixel(px,y,gray,255);
            return;
        }
        if(selection!=null) {
            // A selection keeps its own edges, so the span goes pixel by pixel like the mask path.
            for(int px=x;px<end;px++) if(selection.contains(px,y))
                setPixel(px,y,gray==ERASE?255:gray,gray==ERASE?0:255);
            return;
        }
        writable();
        int rowKey=(y/TILE)*columns();
        for(int column=x/TILE;column<=(end-1)/TILE;column++) {
            int key=rowKey+column;
            if(!captured[key]) { before.put(key,copyTile(key));captured[key]=true; }
        }
        Arrays.fill(tones,y*width+x,y*width+end,(byte)(gray==ERASE?255:gray));
        Arrays.fill(alpha,y*width+x,y*width+end,(byte)(gray==ERASE?0:255));
        left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,end);bottom=Math.max(bottom,y+1);
    }
    /** Restore only pixels removed by a resizing preview, keeping its original undo capture. */
    void restoreSpan(int x,int end,int y) {
        if(!editing) throw new IllegalStateException("No active gesture");
        x=Math.max(0,x);end=Math.min(width,end);
        if(y<0 || y>=height || x>=end)return;
        writable();
        for(int column=x/TILE;column<=(end-1)/TILE;column++) {
            int key=(y/TILE)*columns()+column;
            byte[] tile=before.get(key);
            if(tile==null)continue;
            int tileX=column*TILE,tileWidth=Math.min(TILE,width-tileX);
            int start=Math.max(x,tileX),stop=Math.min(end,tileX+tileWidth);
            int offset=(y%TILE)*tileWidth+start-tileX;
            System.arraycopy(tile,offset,tones,y*width+start,stop-start);
            System.arraycopy(tile,tile.length/2+offset,alpha,y*width+start,stop-start);
        }
        left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,end);bottom=Math.max(bottom,y+1);
    }
    private void setPixel(int x,int y,int gray,int coverage) {
        if (!editing) throw new IllegalStateException("No active gesture");
        if(gray<0||gray>255||coverage<0||coverage>255) throw new IllegalArgumentException("Invalid pixel");
        if(x<0||y<0||x>=width||y>=height) return;
        int i=y*width+x;
        int shield=bound==mask?0:shield(i);
        if(shield==255) return;
        if(shield>0) {
            // Only the unmasked share of the change reaches the paint the gesture found.
            int base=strokeBasePixel(x,y),baseTone=base>>>8,baseAlpha=base&255;
            float t=1-shield/255f,a=baseAlpha+(coverage-baseAlpha)*t;
            float pigment=baseTone*baseAlpha+(gray*coverage-baseTone*baseAlpha)*t;
            coverage=Math.round(a); gray=coverage==0?255:Math.max(0,Math.min(255,Math.round(pigment/a)));
        }
        if(coverage==0) gray=255;
        store(x,y,gray,coverage);
    }
    /** Writes a pixel on the page, unless it already holds this, capturing its tile for Undo first. */
    private void store(int x,int y,int gray,int coverage) {
        if(!allowed(x,y)) return;
        int i=y*width+x;
        if((tones[i]&255)==gray&&(alpha[i]&255)==coverage) return;
        int key=(y/TILE)*columns()+x/TILE;
        if(!captured[key]) { before.put(key,copyTile(key)); captured[key]=true; }
        writable(); tones[i]=(byte)gray; alpha[i]=(byte)coverage;
        left=Math.min(left,x); top=Math.min(top,y); right=Math.max(right,x+1); bottom=Math.max(bottom,y+1);
    }
    /** Apply opaque logical raster pixels, generated by the accepted Android circle rasterizer; anti-aliased edges cover partly. */
    void paintMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int edge = mask[row * stride + col] >>> 24;
            if (edge == 255) paintTone(x + col, y + row, gray);
            else if (edge != 0) cover(x + col, y + row, gray, 255, edge);
        }
    }
    /** Half-strength multiply keeps even black translucent; white is clear water. */
    static int transparentTone(int base, int gray) { return (base * (255 + gray) + 255) / 510; }
    /** One glaze per gesture, independent of overlapping interpolation stamps. */
    void transparentMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        if (gray != glazedGray) { glazed.clear(); glazedGray = gray; }
        long[] bits = null; int bitsKey = -1;
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int px = x + col, py = y + row;
            int edge = mask[row * stride + col] >>> 24;
            if (edge == 0 || px < 0 || py < 0 || px >= width || py >= height) continue;
            // A partly covered edge may yet be covered more; a fully glazed pixel is final.
            if (edge < 255) { glazeTone(px, py, gray, edge); continue; }
            int key = (py / TILE) * columns() + px / TILE;
            if (key != bitsKey) {
                bitsKey = key; bits = glazed.get(key);
                if (bits == null) glazed.put(key, bits = new long[TILE]);
            }
            long bit = 1L << (px % TILE);
            if ((bits[py % TILE] & bit) != 0) continue;
            bits[py % TILE] |= bit;
            glazeTone(px, py, gray, 255);
        }
    }
    /** Darken to the selected logical gray; repeated passes retain the same shade. */
    void flatWashMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int px = x + col, py = y + row;
            if ((mask[row * stride + col] >>> 24) != 0
                    && px >= 0 && py >= 0 && px < width && py < height)
                setTone(px, py, Math.min(gray, tone(px, py)));
        }
    }
    /** Deposit only the shade's black dots; gaps leave the underlying tones intact. */
    void washMask(int[] mask, int stride, int x, int y, int w, int h, int gray) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            if ((mask[row * stride + col] >>> 24) != 0
                    && DotPattern.pixel(gray, x + col, y + row) == 0xff000000)
                setTone(x + col, y + row, 0);
        }
    }
    boolean finish() { return finish(false); }
    /** Animation belongs to the preceding wet stroke, not a separate Undo step. */
    boolean finishContinuation() { return finish(true); }
    private boolean finish(boolean continuation) {
        if (!editing) return false;
        editing = false;lastBaseTile=null;lastBaseKey=-1;glazed.clear();
        ArrayList<TileEdit> tiles = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : before.entrySet()) {
            captured[entry.getKey()] = false;
            byte[] after = copyTile(entry.getKey());
            if (!Arrays.equals(entry.getValue(), after))
                tiles.add(new TileEdit(entry.getKey(), entry.getValue(), after));
        }
        before.clear();
        Layer edited=bound; bind(layers.get(active));
        if (tiles.isEmpty()) return false;
        if (continuation && !undo.isEmpty() && redo.isEmpty() && undo.peekLast().layer==edited && undo.peekLast().from == null) {
            Edit previous = undo.removeLast(); historyBytes -= previous.bytes;
            LinkedHashMap<Integer, TileEdit> merged = new LinkedHashMap<>();
            for (TileEdit tile : previous.tiles) merged.put(tile.key, tile);
            for (TileEdit tile : tiles) {
                TileEdit old = merged.get(tile.key);
                merged.put(tile.key, new TileEdit(tile.key, old == null ? tile.before : old.before, tile.after));
            }
            tiles = new ArrayList<>();
            for (TileEdit tile : merged.values()) if (!Arrays.equals(tile.before, tile.after)) tiles.add(tile);
        }
        for (Edit edit : redo) historyBytes -= edit.bytes;
        redo.clear();
        if (!tiles.isEmpty()) { Edit edit = new Edit(tiles,edited); undo.addLast(edit); historyBytes += edit.bytes; }
        while (historyBytes > HISTORY_BYTES && undo.size() > 1) historyBytes -= undo.removeFirst().bytes;
        return true;
    }
    boolean canUndo() { return !undo.isEmpty(); }
    void trimHistory(int budget) {
        while(historyBytes>budget&&undo.size()>1)historyBytes-=undo.removeFirst().bytes;
        while(historyBytes>budget&&!redo.isEmpty())historyBytes-=redo.removeFirst().bytes;
    }
    /** Clear all disconnected marks as one undoable edit, retaining canvas dimensions. */
    boolean clear() {
        begin(false);
        for(int key=0;key<captured.length;key++) {
            byte[] tile=copyTile(key); boolean marked=false;
            for(int i=tile.length/2;i<tile.length;i++) if(tile[i]!=0) { marked=true; break; }
            if(marked) { before.put(key,tile); captured[key]=true; }
        }
        if(!before.isEmpty()) {
            Arrays.fill(tones,(byte)255); Arrays.fill(alpha,(byte)0); markAllDirty();
        }
        return finish();
    }
    /** Clear the current page, including hidden layers, as one structural undo step. */
    boolean clearAllLayers() {
        idle(); boolean marked=hasMask();
        for(Layer layer:layers) {
            if(layer.readOnly) continue; // clearing the page leaves the reference picture alone
            for(byte a:layer.alpha)if(a!=0) {marked=true;break;}
        }
        if(!marked)return false;
        StackState old=new StackState(this); mask=null;
        for(int i=0;i<layers.size();i++) {
            Layer prior=layers.get(i);
            if(prior.readOnly) continue;
            Layer blank=new Layer(prior.name,prior.visible,paper(width,height),new byte[width*height]);
            blank.opacity=prior.opacity; layers.set(i,blank);
        }
        selectLayer(active); stackEdit(old); return true;
    }
    void cancel() {
        if (!editing) return;
        for (Map.Entry<Integer, byte[]> tile : before.entrySet()) {
            restoreTile(tile.getKey(), tile.getValue()); captured[tile.getKey()] = false;
        }
        before.clear(); lastBaseTile=null; lastBaseKey=-1; glazed.clear(); editing = false;
        bind(layers.get(active));
    }
    boolean canRedo() { return !redo.isEmpty(); }
    boolean undo() { return travel(undo, redo, false); }
    boolean redo() { return travel(redo, undo, true); }
    private boolean travel(ArrayDeque<Edit> source, ArrayDeque<Edit> target, boolean forward) {
        if (editing) throw new IllegalStateException("Finish the current gesture first");
        if (source.isEmpty()) return false;
        Edit edit = source.removeLast();
        if(edit.layer==null) { (forward?edit.next:edit.previous).restore(this); revision++; markAllDirty(); }
        else {
            int index=layers.indexOf(edit.layer);
            // Any other layer is masking fluid, laid since the page last had none.
            if(index<0) { mask=edit.layer; bind(mask); } else selectLayer(index);
            for (TileEdit tile : edit.tiles) restoreTile(tile.key, forward ? tile.after : tile.before);
            if (edit.from != null || edit.to != null) selection = forward ? edit.to : edit.from;
            if(index<0) bind(layers.get(active));
        }
        target.addLast(edit); return true;
    }
    /** Returns [left, top, right, bottom], without clearing it. */
    int[] dirty() { return right > left && bottom > top ? new int[]{left, top, right, bottom} : null; }
    void clearDirty() { left = width; top = height; right = bottom = 0; }
    void render(int[] pixels, int x, int y, int w, int h) {
        for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
            int hatch = maskHatch(x + col, y + row);
            pixels[row * w + col] = hatch >= 0 ? (hatch == 0 ? 0xff000000 : 0xffffffff)
                    : DotPattern.pixel(compositeTone(x + col, y + row), x + col, y + row);
        }
    }
    private int columns() { return (width + TILE - 1) / TILE; }
    private byte[] copyTile(int key) {
        int x = key % columns() * TILE, y = key / columns() * TILE;
        int w = Math.min(TILE, width - x), h = Math.min(TILE, height - y);
        byte[] copy = new byte[w * h * 2];
        for (int row = 0; row < h; row++) {
            System.arraycopy(tones, (y + row) * width + x, copy, row * w, w);
            System.arraycopy(alpha, (y + row) * width + x, copy, w*h+row*w, w);
        }
        return copy;
    }
    private void restoreTile(int key, byte[] data) {
        writable();
        int x = key % columns() * TILE, y = key / columns() * TILE;
        int w = Math.min(TILE, width - x), h = Math.min(TILE, height - y);
        for (int row = 0; row < h; row++) {
            System.arraycopy(data, row * w, tones, (y + row) * width + x, w);
            System.arraycopy(data,w*h+row*w,alpha,(y+row)*width+x,w);
        }
        left = Math.min(left, x); top = Math.min(top, y);
        right = Math.max(right, x + w); bottom = Math.max(bottom, y + h);
    }
    private static final class TileEdit {
        final int key; final byte[] before, after;
        TileEdit(int key, byte[] before, byte[] after) { this.key = key; this.before = before; this.after = after; }
    }
    private static final class StackState {
        final ArrayList<Layer> layers;
        final Layer mask;
        /** The reference picture and placement captured at snapshot time. */
        final ReferenceImage reference;
        final String[] names;
        final boolean[] visible;
        final int[] opacity;
        final int active, width, height;
        StackState(ToneDocument doc) {
            layers=new ArrayList<>(doc.layers); mask=doc.mask; active=doc.active; width=doc.width; height=doc.height;
            reference=doc.hasReference()?doc.referenceLayer().reference:null;
            names=new String[layers.size()]; visible=new boolean[layers.size()]; opacity=new int[layers.size()];
            for(int i=0;i<layers.size();i++) { names[i]=layers.get(i).name; visible[i]=layers.get(i).visible; opacity[i]=layers.get(i).opacity; }
        }
        void restore(ToneDocument doc) {
            doc.width=width; doc.height=height;
            doc.captured=new boolean[doc.columns()*((height+TILE-1)/TILE)];
            doc.layers.clear(); doc.layers.addAll(layers); doc.mask=mask;
            for(int i=0;i<layers.size();i++) { layers.get(i).name=names[i]; layers.get(i).visible=visible[i]; layers.get(i).opacity=opacity[i]; }
            doc.selectLayer(active);
            // Re-raster the whole reference layer from the original picture rather than
            // trusting arrays a drag may have reused or shared.
            doc.restoreReference(reference);
        }
    }
    private static final class Edit {
        final ArrayList<TileEdit> tiles; final int bytes;
        final Layer layer;
        final StackState previous,next;
        /** The selection a move began with and ended with; null on every other edit. */
        Selection from, to;
        Edit(StackState previous,StackState next) {
            this.previous=previous; this.next=next; layer=null; tiles=null;
            int count=256;
            for(Layer layer:previous.layers) if(!next.layers.contains(layer)) count+=layer.tones.length*2;
            for(Layer layer:next.layers) if(!previous.layers.contains(layer)) count+=layer.tones.length*2;
            if(previous.mask!=next.mask) count+=(previous.mask!=null?previous.mask.tones.length*2:0)+(next.mask!=null?next.mask.tones.length*2:0);
            bytes=count;
        }
        Edit(ArrayList<TileEdit> tiles,Layer layer) {
            this.layer=layer; previous=next=null;
            this.tiles = tiles; int count = 0;
            for (TileEdit tile : tiles) count += tile.before.length + tile.after.length;
            bytes = count;
        }
    }
}
