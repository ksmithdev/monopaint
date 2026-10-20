package io.github.mpdairy.monopaint;

/** Immutable settings: a running stroke and a saved preset cannot change underneath the pen. */
final class ToolSettings {
    static final int DEFAULT_STRENGTH = 35;
    static final int DEFAULT_PRESSURE_RESPONSE = 50;
    static final int DEFAULT_HEAD_THICKNESS = 10;
    /** A brush paint load that never runs out. */
    static final int UNLIMITED_PAINT = 100;
    /** The paint an oil brush starts with before it is loaded in the palette. */
    static final int DEFAULT_PAINT_LOAD = 41;
    static final int DEFAULT_LOADING_SPEED = 50;
    /** A tap in the palette loads enough paint for about 50 px. */
    static final int DEFAULT_MINIMUM_LOAD = 5;
    /** How much of the paint beneath it a wet brush pen's opaque stroke covers at first. */
    static final int DEFAULT_COVERAGE = 50;
    static final int MAX_FLAT_WIDTH = 256;
    static final int MAX_FLAT_HEIGHT = 20;
    static int sizeLimit(Head head) { return head == Head.FLAT ? MAX_FLAT_WIDTH : 128; }

    /**
     * Every drawing tool. To add one:
     * <ol>
     * <li>Append a constant here. Ordinals are part of the saved preset format, so never
     *     reorder them, and add a new format version in {@link ToolLibrary}.</li>
     * <li>Give it an icon in {@code ToolIcons}, settings rows in {@code ToolControls}
     *     and a pen stroke in {@code ToolStrokes}.</li>
     * <li>List it in {@code Toolbar.TOOLS} to show it in the side toolbar.</li>
     * </ol>
     */
    enum Tool {
        BRUSH("Brush", Kind.BRUSH),
        PENCIL("Pencil", Kind.COLORED),
        FILL("Fill", Kind.COLORED),
        ERASER("Eraser", Kind.COLORLESS),
        SOFTEN("Blending stump", Kind.COLORLESS),
        WATERCOLOR("Watercolor", Kind.BRUSH),
        WET_WATERCOLOR("Wet watercolor (experimental)", Kind.BRUSH),
        FLAT_WASH("Flat wash", Kind.BRUSH),
        AIRBRUSH("Airbrush", Kind.COLORED),
        SHAPES("Shapes", Kind.COLORED),
        BRUSH_PEN("Brush pen", Kind.WATER),
        WET_BRUSH_PEN("Wet brush pen", Kind.FLOWING_WATER),
        SELECT("Selection", Kind.COLORLESS);

        final String label;
        /** Brushes share brush heads, wet canvas and transparent paint; they display as one Brush tool. */
        final boolean brush;
        /** Tools that apply the selected color, the wet brush pen's dip too, can erase with their own footprint instead. */
        final boolean erasable;
        /** Lays down its own clear water, wet canvas or not. Tilt sets its size; pressure its pull. */
        final boolean water;
        /** Its water stays wet and carries ink like ink in water, faster where the water is fresher. */
        final boolean flowing;
        Tool(String label, Kind kind) {
            this.label = label; brush = kind == Kind.BRUSH; erasable = kind == Kind.BRUSH || kind == Kind.COLORED || kind == Kind.FLOWING_WATER;
            flowing = kind == Kind.FLOWING_WATER; water = kind == Kind.WATER || flowing;
        }
        private enum Kind { BRUSH, COLORED, COLORLESS, WATER, FLOWING_WATER }
    }
    enum Head {
        ROUND("Round"), FLAT("Flat"), FILBERT("Filbert");
        final String label;
        Head(String label) { this.label=label; }
    }
    enum Gradient {
        LINEAR("Linear"), CIRCULAR("Circular"), FLAT("Flat fill");
        final String label;
        Gradient(String label) { this.label=label; }
    }
    enum Shape {
        LINE("Line"), RECTANGLE("Rectangle"), SQUARE("Square"), OVAL("Oval"), CIRCLE("Circle");
        final String label;
        Shape(String label) { this.label=label; }
    }
    /** How the Selection tool picks its region. */
    enum SelectMode {
        /** Trace a loop; the area you enclose becomes the selection. */
        FREEHAND("Freehand"),
        /** Tap an area; the connected tones around the tap become the selection. */
        FILL("Fill"),
        /** Drag the current selection, and any copy attached to the pen, to a new place. */
        MOVE("Move");
        final String label;
        SelectMode(String label) { this.label=label; }
    }
    final Shape shape;
    final boolean filled;
    final int outlineWidth;
    /** How far the brush pen pushes the paint it picks up along the stroke, 0–100. */
    final int carry;
    /**
     * How much paint a brush carries, 1–100: lower runs out sooner along a stroke, then
     * smudges what is underneath. {@link #UNLIMITED_PAINT} never runs out.
     */
    final int paintLoad;
    /** How much paint rubbing in the palette loads per pixel, 0–100; 50 lasts as far as the pen rubbed. */
    final int loadingSpeed;
    /** Paint a tap in the palette loads, 0–100, before any rubbing adds more; 0 is a tiny dab. */
    final int minimumLoad;
    /**
     * The wet brush pen lays only clear water and holds no ink: it lifts the paint it touches
     * and lets it run out through its water, as the first wet brush pen did.
     */
    final boolean clearWater;
    /** With opaque paint, the share of the paint under it, 0–100, that each wet brush pen stroke covers with its color. */
    final int coverage;
    final Gradient gradient;
    /** The Selection tool's mode. */
    final SelectMode selectMode;
    final Tool tool;
    final Head head;
    final int angle, bristles, headThickness;
    final int minimum, maximum, tip, hardness, softness, tolerance, strength, pressureResponse;
    private final double pressureExponent;
    final boolean soft, tilt;

    ToolSettings(Tool tool, int maximum, int tip, int softness, boolean tilt, int hardness) {
        this(tool, tool == Tool.PENCIL ? 1 : 2, maximum, tip, softness, tilt, hardness, 0, DEFAULT_STRENGTH,
                DEFAULT_PRESSURE_RESPONSE, Head.ROUND, 0, 0, DEFAULT_HEAD_THICKNESS, Gradient.LINEAR, Shape.LINE, false, 3, 0, UNLIMITED_PAINT, DEFAULT_LOADING_SPEED, DEFAULT_MINIMUM_LOAD);
    }
    ToolSettings(Tool tool, int minimum, int maximum, int tip, int softness, boolean tilt, int hardness, int tolerance, int strength, int pressureResponse, Head head, int angle, int bristles, int headThickness, Gradient gradient, Shape shape, boolean filled, int outlineWidth, int carry, int paintLoad, int loadingSpeed, int minimumLoad) {
        this(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, angle, bristles, headThickness, gradient, shape, filled, outlineWidth, carry, paintLoad, loadingSpeed, minimumLoad, false, DEFAULT_COVERAGE, SelectMode.FREEHAND);
    }
    private ToolSettings(Tool tool, int minimum, int maximum, int tip, int softness, boolean tilt, int hardness, int tolerance, int strength, int pressureResponse, Head head, int angle, int bristles, int headThickness, Gradient gradient, Shape shape, boolean filled, int outlineWidth, int carry, int paintLoad, int loadingSpeed, int minimumLoad, boolean clearWater, int coverage, SelectMode selectMode) {
        if(shape==null || outlineWidth<1 || outlineWidth>128) throw new IllegalArgumentException("Invalid shape settings");
        this.shape=shape; this.filled=filled; this.outlineWidth=outlineWidth; this.carry=carry; this.paintLoad=paintLoad; this.loadingSpeed=loadingSpeed; this.minimumLoad=minimumLoad;
        this.clearWater = clearWater && tool.flowing; this.coverage = coverage;
        if (coverage < 0 || coverage > 100) throw new IllegalArgumentException("Invalid coverage");
        if (gradient == null || headThickness < 0 || headThickness > 100 || bristles < 0 || bristles > 100 || tool == null || head == null || angle < 0 || angle > 180
                || (!tool.brush && head != Head.ROUND) || maximum < 2 || maximum > sizeLimit(head) || minimum < 1 || minimum > maximum || tip < 1 || tip > sizeLimit(head)
                || softness < 0 || softness > 100 || hardness < 0 || hardness > 100 || tolerance < 0 || tolerance > 100 || carry < 0 || carry > 100 || paintLoad < 1 || paintLoad > UNLIMITED_PAINT || loadingSpeed < 0 || loadingSpeed > 100 || minimumLoad < 0 || minimumLoad > 100 || strength < 0 || strength > 100 || pressureResponse < 0 || pressureResponse > 100)
            throw new IllegalArgumentException("Invalid tool settings");
        this.gradient=gradient; this.tool = tool; this.head = head; this.angle = angle; this.bristles = bristles; this.headThickness = headThickness; this.minimum = minimum; this.maximum = maximum; this.tip = Math.max(minimum,Math.min(tip,maximum));
        this.selectMode = selectMode == null ? SelectMode.FREEHAND : selectMode;
        this.softness = softness; this.soft = softness > 0; this.tilt = tilt; this.hardness = hardness; this.tolerance = tolerance; this.strength = strength;
        this.pressureResponse = pressureResponse;
        // Brushes span linear (0) to quartic (100). The brush pen's pull centers on
        // linear instead, so low settings respond to a light touch (square root at 25).
        pressureExponent = tool.water ? Math.pow(2, (pressureResponse - 50) / 25.0) : Math.pow(2, pressureResponse / 50.0);
    }
    private ToolSettings(Values v) {
        this(v.tool, v.minimum, v.maximum, v.tip, v.softness, v.tilt, v.hardness, v.tolerance, v.strength, v.pressureResponse,
                v.head, v.angle, v.bristles, v.headThickness, v.gradient, v.shape, v.filled, v.outlineWidth, v.carry, v.paintLoad, v.loadingSpeed, v.minimumLoad, v.clearWater, v.coverage, v.selectMode);
    }
    /** First-install settings; the toolbar's tools were tuned by hand on a Manta. */
    static ToolSettings defaults(Tool tool) {
        ToolSettings settings=new ToolSettings(tool, tool == Tool.SOFTEN ? 32 : tool.water ? 48 : 64, tool == Tool.PENCIL ? 1 : 3, 70, tool == Tool.PENCIL, 40);
        // A light touch should already move paint: the pen's curve starts gentle.
        // Wet brush pens give out the same water at any pressure; fully fresh water evens out
        // everything beneath it, so they start below that, leaving darks where they were.
        if (tool.water) return settings.minimum(8).pressureResponse(25).strength(tool.flowing ? 60 : 85);
        switch (tool) {
            case BRUSH: return settings.size(90).minimum(1);
            case PENCIL: return settings.size(81);
            case ERASER: return settings.minimum(35);
            case SOFTEN: return settings.size(57).minimum(19);
            case AIRBRUSH: return settings.size(86).minimum(36).softness(88).strength(18);
            case FILL: return settings.gradient(Gradient.FLAT);
            case SELECT: return settings.size(24).minimum(4).tolerance(12);
            default: return settings;
        }
    }
    /** First-install settings for one brush head of a brush tool. */
    static ToolSettings defaults(Tool tool, Head head) {
        ToolSettings settings = defaults(tool).head(head).automaticHead();
        if (tool != Tool.BRUSH) return settings;
        return head == Head.FLAT ? settings.size(41).minimum(32) : head == Head.FILBERT ? settings.size(64).minimum(53) : settings;
    }

    /** Mutable copy used to derive a changed, validated settings object. */
    private static final class Values {
        Tool tool; Head head; Gradient gradient; Shape shape; SelectMode selectMode;
        int minimum, maximum, tip, softness, hardness, tolerance, strength, pressureResponse, angle, bristles, headThickness, outlineWidth, carry, paintLoad, loadingSpeed, minimumLoad, coverage;
        boolean tilt, filled, clearWater;
    }
    private interface Change { void apply(Values values); }
    private ToolSettings with(Change change) {
        Values v = new Values();
        v.tool = tool; v.head = head; v.gradient = gradient; v.shape = shape;
        v.minimum = minimum; v.maximum = maximum; v.tip = tip; v.softness = softness; v.hardness = hardness;
        v.tolerance = tolerance; v.strength = strength; v.pressureResponse = pressureResponse; v.angle = angle;
        v.bristles = bristles; v.headThickness = headThickness; v.outlineWidth = outlineWidth;
        v.tilt = tilt; v.filled = filled; v.carry = carry; v.paintLoad = paintLoad; v.loadingSpeed = loadingSpeed; v.minimumLoad = minimumLoad;
        v.clearWater = clearWater; v.coverage = coverage; v.selectMode = selectMode;
        change.apply(v);
        return new ToolSettings(v);
    }

    ToolSettings asBrush() {
        if (!isBrush() || tool == Tool.BRUSH) return this;
        return with(v -> v.tool = Tool.BRUSH);
    }
    ToolSettings size(int value) { return with(v -> { v.maximum = value; v.minimum = Math.min(minimum, value); }); }
    ToolSettings minimum(int value) { return with(v -> v.minimum = value); }
    ToolSettings options(int tip, boolean soft, boolean tilt) {
        return with(v -> { v.tip = tip; v.softness = soft == this.soft ? softness : soft ? 85 : 0; v.tilt = tilt; });
    }
    ToolSettings hardness(int value) { return with(v -> v.hardness = value); }
    ToolSettings softness(int value) { return with(v -> v.softness = value); }
    ToolSettings tolerance(int value) { return with(v -> v.tolerance = value); }
    ToolSettings strength(int value) { return with(v -> v.strength = value); }
    ToolSettings pressureResponse(int value) { return with(v -> v.pressureResponse = value); }
    ToolSettings head(Head value) {
        int size = Math.min(maximum, sizeLimit(value));
        return with(v -> {
            v.head = value; v.maximum = size; v.minimum = Math.min(minimum, size); v.tip = Math.min(tip, size);
            if (value == Head.FLAT && head != Head.FLAT) v.headThickness = 0;
        });
    }
    ToolSettings angle(int value) { return with(v -> v.angle = value); }
    // Pencil uses tilt for broadening; shaped brushes use it for heading.
    ToolSettings tilt(boolean value) { return with(v -> v.tilt = value); }
    ToolSettings bristles(int value) { return with(v -> v.bristles = value); }
    ToolSettings headThickness(int value) { return with(v -> v.headThickness = value); }
    // Flat exposes height; the other legacy shape controls remain automatic.
    ToolSettings automaticHead() {
        int thickness=head==Head.FILBERT?55:Math.min(MAX_FLAT_HEIGHT,headThickness);
        if (!isBrush() || head == Head.ROUND || (tilt && angle == 0 && headThickness == thickness)) return this;
        return with(v -> { v.tilt = true; v.angle = 0; v.headThickness = thickness; });
    }
    ToolSettings gradient(Gradient value) { return with(v -> v.gradient = value); }
    ToolSettings shape(Shape value) { return with(v -> v.shape = value); }
    ToolSettings filled(boolean value) { return with(v -> v.filled = value); }
    ToolSettings outlineWidth(int value) { return with(v -> v.outlineWidth = value); }
    ToolSettings carry(int value) { return with(v -> v.carry = value); }
    ToolSettings paintLoad(int value) { return with(v -> v.paintLoad = value); }
    ToolSettings loadingSpeed(int value) { return with(v -> v.loadingSpeed = value); }
    ToolSettings minimumLoad(int value) { return with(v -> v.minimumLoad = value); }
    ToolSettings clearWater(boolean value) { return with(v -> v.clearWater = value); }
    ToolSettings coverage(int value) { return with(v -> v.coverage = value); }
    ToolSettings selectMode(SelectMode value) { return with(v -> v.selectMode = value); }
    /** The wet brush pen, unless it lays {@link #clearWater}: it holds the ink it is dipped in or soaks up between strokes. */
    boolean holdsInk() { return tool.flowing && !clearWater; }
    /** Turns limited paint on with a moderate load, or off. */
    ToolSettings oilPaint(boolean on) {
        return on == limitsPaint() ? this : paintLoad(on ? DEFAULT_PAINT_LOAD : UNLIMITED_PAINT);
    }
    /**
     * Full-width stroke length in pixels over which a brush's paint runs out, or infinity when
     * it never does. It grows with the square of the load, so small loads can be a sliver.
     */
    float paintLength() { return paintLoad >= UNLIMITED_PAINT ? Float.POSITIVE_INFINITY : paintLoad * paintLoad / 4f; }
    /** A brush whose paint runs out along the stroke. */
    boolean limitsPaint() { return isBrush() && Float.isFinite(paintLength()); }
    /**
     * Loads a brush with limited paint by rubbing the pen {@code travel} pixels around a shade:
     * a tap loads the minimum load, 10 px per point down to a sliver at 0, and at the middle loading speed rubbing
     * adds about as far as the pen rubbed. Each 25 points of speed doubles or halves that.
     */
    ToolSettings loadedBy(float travel) {
        float rate = (float)Math.pow(2, (loadingSpeed - DEFAULT_LOADING_SPEED) / 25.0);
        float length = minimumLoad * 10 + travel * rate;
        return paintLoad(Math.max(1, Math.min(UNLIMITED_PAINT - 1, Math.round(2 * (float)Math.sqrt(length)))));
    }

    float headAspectRatio() { return head == Head.ROUND ? 1f : headThickness / 100f; }
    float flatHeight(float width) { return Math.max(1, width*Math.min(MAX_FLAT_HEIGHT,headThickness)/100f); }
    String description() {
        if (tool == Tool.SHAPES) return shape.label + (shape == Shape.LINE ? "" : filled ? " filled" : " outline");
        if (isBrush()) return head.label + " " + label();
        if (tool == Tool.FILL) return gradient == Gradient.FLAT ? "Flat fill" : gradient.label + " gradient";
        if (tool == Tool.SELECT) return selectMode.label + " selection";
        return label();
    }
    /** The measured .05–.45 pen pressure range as 0–1. */
    private static float travel(float pressure) {
        if(!Float.isFinite(pressure)) pressure=0;
        return Math.max(0,Math.min(1,(pressure-.05f)/.40f));
    }
    float diameter(float pressure) {
        float p=travel(pressure);
        // Keep the measured .05–.45 pressure range reachable at every response.
        // Light touch is linear; 50 preserves the original square; firm is quartic.
        if (!isBrush() || pressureResponse == DEFAULT_PRESSURE_RESPONSE)
            return minimum+(maximum-minimum)*p*p;
        return minimum+(maximum-minimum)*(float)Math.pow(p, pressureExponent);
    }
    /** Whether the head turns with the pen's tilt: shaped brushes that follow tilt, and the brush pens. */
    boolean followsTilt() { return tool.water || tilt && head != Head.ROUND; }
    /**
     * A leaning tip stretches along its lean, like a tilted pencil lead: the footprint's
     * short side when its long side is {@code major} and the upright tip is {@code upright}.
     */
    float leanMinor(float major, float upright) { return Math.max(minimum, upright + (major - upright) * .28f); }
    /**
     * Stroke size for a pen sample; for the brush pens, the long side. The dry brush pen's
     * grows as the pen leans whatever the pressure; a wet brush pen's grows with pressure
     * through the response curve, like pressing a real one down, and tilt only points it.
     */
    float diameter(float pressure, float tiltX, float tiltY) {
        if (tool.flowing) return minimum + (maximum - minimum) * (float)Math.pow(travel(pressure), pressureExponent);
        return tool.water ? minimum + (maximum - minimum) * BrushDirection.lean(tiltX, tiltY) : diameter(pressure);
    }
    /**
     * How strongly brush pen water pulls paint along, 0–1, scaled by strength. The dry brush
     * pen pulls by pressure through the response curve; a wet brush pen gives out the same
     * water however hard it is pressed, only over more paper.
     */
    float pull(float pressure) {
        if (tool.flowing) return strength / 100f;
        return strength / 100f * (.15f + .85f * (float)Math.pow(travel(pressure), pressureExponent));
    }
    boolean supportsEraseMode() { return tool.erasable; }
    boolean isBrush() { return tool.brush; }
    String label() { return tool.label; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ToolSettings)) return false;
        ToolSettings s = (ToolSettings)other;
        return tool == s.tool && minimum == s.minimum && maximum == s.maximum && tip == s.tip && softness == s.softness && tilt == s.tilt && hardness == s.hardness && tolerance == s.tolerance && strength == s.strength && pressureResponse == s.pressureResponse && head == s.head && angle == s.angle && bristles == s.bristles && headThickness == s.headThickness && gradient == s.gradient && shape == s.shape && filled == s.filled && outlineWidth == s.outlineWidth && carry == s.carry && paintLoad == s.paintLoad && loadingSpeed == s.loadingSpeed && minimumLoad == s.minimumLoad && clearWater == s.clearWater && coverage == s.coverage && selectMode == s.selectMode;
    }
    @Override public int hashCode() { return java.util.Objects.hash(tool, minimum, maximum, tip, softness, tilt, hardness, tolerance, strength, pressureResponse, head, angle, bristles, headThickness, gradient, shape, filled, outlineWidth, carry, paintLoad, loadingSpeed, minimumLoad, clearWater, coverage, selectMode); }
}
