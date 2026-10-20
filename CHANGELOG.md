# Changelog

User-facing changes since the last release. Add an entry under **Unreleased**
with each change; when cutting a release, turn that section into
`dist/RELEASE_NOTES.md` and retitle it with the new version.

## Unreleased

- A new **Selection** button in the toolbar lets you fence off part of the page so
  your pen only changes what's inside. In **Freehand** mode, trace a loop around
  an area; in **Fill** mode, tap and the matching tones around your tap become the
  selection, wider or narrower with the tolerance. While a selection is on, every
  tool only paints inside it, and it stays put when you switch layers, so you can
  colour one shape across several layers at once. The panel's buttons work on
  whatever is selected: **Cut** blanks it off the page and **Copy** keeps a
  duplicate — either way the pixels ride your pen as a ghost until you tap to
  place them, and you can keep nudging, redrawing, re-cutting or re-copying them
  from there. Tapping outside commits the pixels and lets the selection go;
  **Clear** frees the whole page, and in Freehand mode a plain tap does too. A
  copy carries only the pixels that hold paint and only its own artwork, so it
  never lays white over your drawing or drags it along — and moving it off again
  uncovers what it was covering. Cuts, moves and pastes are each a single undoable
  step that takes the selection back with it. Painting inside a selection stays
  fast however large it is, and long drags keep the copy glued to your pen. Every
  tap hands over cleanly, so you can select, clear and select again as many times
  as you like. The selection is working state: it isn't saved with your drawing.

- A new **Reference** button in the toolbar (under Layers) lays a picture from your
  tablet **under every layer** for tracing or copying. Import it from the panel,
  then drag it into place on the canvas with one finger. The panel turns it,
  scales it from 1% to 200%, fades it, hides it, and removes it. While you
  rotate or scale, a simple outline shows where the picture will land; hold still
  for a moment and the sharp picture is drawn so you can check it, or just lift
  your finger. Fading is shown live as you drag. Lock it so a stray
  finger can't nudge it. The reference saves with the page, shows in your
  exports, and never gets paint on it — your pen only touches your own layers.
  Drawings saved before this feature still open exactly as they were; adding a
  reference to one never moves or alters the paint you already laid down.

- The **wet brush pen** has a **Hold ink between strokes** setting, on at
  first. Turn it off for the first wet brush pen: it lays only clear water and
  holds no ink, lifting the paint it touches and letting it thin out through
  the wet area, so it looks really watery. Choosing a color with it goes back
  to your brush.
- The wet brush pen now sizes like a real one: press harder for a bigger
  footprint, and tilt only points which way it spreads from the tip. It gives
  out the same water however hard you press, set by **Strength**, which now
  starts at 60%. Full strength evens out everything under the stroke; lower
  keeps darks where they were. The dry brush pen works as before.
- With **opaque** paint chosen in the top bar, the wet brush pen paints like
  gouache: each stroke covers half of what's under it with its color, so white
  pushes dark back while the paint stays wet and blends at the edges. Go over a
  spot again to cover more. The pen's **Coverage** setting, 50% at first, sets
  how much each stroke covers. With Hold ink on, the brush starts every stroke
  dipped in the selected color again and the color bar's marker stays on it.

## 0.98.3

- With the **wet brush pen**, the Eraser color now erases with the pen's
  footprint, like other tools. To clean the brush, tap white.

## 0.98.2

- In landscape, the dots for the Eraser color, the Eyedropper and Masking fluid
  sit beside their icons, on the same side as the color bar's marker, so
  picking one looks like the color marker moved onto it.
- Turning the tablet to landscape for the first time no longer shrinks the
  painting: an expanded page still opens at 100%.
- The blow dryer shakes and blows air as it dries the paint, with no box
  around it.

## 0.98.1

- Supernote firmware 3.30.45 is now tested on both the Manta and the Nomad, so
  MonoPaint no longer says it's untested at startup, and Settings shows it as
  tested.
- In landscape, the Masking fluid button shows its dot again while it's
  selected.

## 0.98

- **Watercolor mode** is now the droplet in the color bar, for every tool
  instead of an option on each brush. The droplet is solid while it's on and
  dashed while it's off. With transparent paint, brushes paint
  watercolor as before, and shapes and flat fills now lay their whole area as a
  watercolor wash that blends and runs downhill too. Pencils, the airbrush and
  gradient fills paint as usual. The wetness slider beside the droplet now
  wets the canvas all by itself: slide it up for a wet canvas, or all the way
  down to dry it. Turn Watercolor mode on again with the droplet if your brushes
  had it on.
- On a wet canvas, watercolor glazes over the paint beneath it: most of that
  paint stays put and shows through, and some comes loose to bleed and run with
  the new wash, more the higher the wetness slider.
- Tap a color with the **wet brush pen** to dip it in that ink, instead of
  switching back to your brush; the Eyedropper dips it too. It gives the ink
  out as you paint and picks up what it passes over, and each time you lift it
  the color bar's marker slides to the ink it has left; tap a color to dip it
  again. The Eraser color still cleans it, and the marker slides to white.

- While wet paint is still moving, the **Watercolor mode** droplet's outline
  ripples: quickly while lots of paint is blending or running, slower and
  slower as it settles, until the droplet comes to rest. It waits while you draw. The dry brush pen's water settling
  for a moment after you lift it doesn't count.
- The brush in **Watercolor mode** paints a flat glaze while the pen is down,
  and the paint blends and runs only once you lift it, so a stroke goes where
  you put it. Rubbing no longer lifts dried paint under its wash; use the wet
  brush pen for that.
- **Masking fluid**, in the color bar beside the Watercolor mode droplet.
  Tap it and paint with any brush, pencil, fill or shape to lay masking fluid;
  it shows as bold diagonal hatching and isn't part of your painting. Then paint
  right over it: opaque paint, transparent glazes, erasing, and wet paint
  seeping across the canvas all stop at its edge, on every layer. Thin fluid,
  like the faded edge of a soft brush, lets some paint through. Turn on the
  Eraser color while Masking fluid is selected to rub fluid off with your tool.
  To peel it all off, hold the Masking fluid button until a Peel button pops
  out below it, then slide the pen onto Peel and lift (or lift, then tap Peel).
  Peeling can be undone, and Clear page peels the fluid too. Pick any shade
  to go back to painting.

- The Wet canvas water drop is a little taller and skinnier, and the drop and
  Masking fluid buttons take only the room they need, so the shade strip is
  wider.

- Supernote firmware 3.30.45 works with MonoPaint. On the Manta, the marker
  under the color bar follows the pen again instead of trailing behind it like
  a snake, and buttons are now drawn in black and white dots, as on the Nomad,
  so their press feedback is fast too. On the Nomad, closing the Layers panel
  or the Pages box no longer leaves a black box over the painting. Older
  firmware behaves as before.

- Small watercolor splotches keep running a little longer. They still take on
  most of their watercolor look right away, but then slow down instead of
  stopping, creeping for about two seconds, so you have time to tilt the
  tablet and let them run where you want. Bigger washes run at full speed as
  before. Going back over a wash with the watercolor brush glazes over it once it
  has slowed, instead of lifting it: starting the new stroke stops the older
  water, so it can't bleed into the new wash through gaps you missed. Paint you
  just laid is still wet enough to bleed into.

- Big watercolor washes run much faster. Every frame of wet paint now shares
  more of its work across the tablet's cores, so a wash covering the whole page
  moves about twice as fast and looks exactly the same as before. A wash that
  big (about a fifth of the page or more) also takes bigger steps: it dries and
  runs downhill in half as many frames, with a little less fine bleeding. A
  page full of watercolor you tilt now runs and settles in about 20 seconds
  instead of a minute and a half on the Nomad.

- On the Manta, the page button at the top right drops down a column of quick
  page action icons: add a page, **duplicate the page** (a copy goes right after
  it and becomes the current page: on page 5 of 8, the copy becomes page 6 of
  9), **delete the page**, **clear the page** and **clear the current layer**.
  Deleting a page asks first; clearing can be undone. On the Nomad, the Pages
  box shows them too: the page arrows and add page on top, the rest below.
  These replace the Clear button.

- The flat brush keeps up with the pen in transparent watercolor paint, as fast
  as the round and filbert brushes, and lays its watercolor the same as before.

- Tap the canvas with two fingers to undo, or with three fingers to redo, like
  Procreate. Each tap acts as soon as your fingers lift, so tap again to undo
  more. A pinch or pan never counts as a tap. The two-finger double tap that hid
  both bars is gone; swipe the bars away instead, or use Menu → Fullscreen.

- Paintings save themselves. Once a painting has a name, MonoPaint keeps its
  file up to date as you work: within a minute of drawing, whenever you leave
  the app, and before you open or start another painting, so switching
  paintings no longer asks "Save changes?". The **Save painting** button is
  gone. A **New painting** is kept inside the app until you give it a name with
  **Save painting as…**, and starting or opening another painting first asks
  whether to name it. On a named painting, Save painting as… makes a copy under
  a new name.

- Transparent paint keeps up with the pen: a transparent brush now lays its
  paint as fast as an opaque one, so big strokes no longer lag behind.
- The **Blow dryer** is back, inside the Watercolor mode droplet, so it stays
  at hand even with the toolbar hidden. While the droplet ripples, tap it to
  dry all wet paint at once: watercolor, wet canvas paint and wet brush pen ink
  stop running and stay as they are. It turns into a blow dryer that
  blows a little air for a moment, then the droplet comes back. Watercolor mode and
  the wetness slider stay as they were, and the tool you were using stays
  selected.
- Watercolor keeps up with the pen too: a big watercolor stroke shows right
  away as its color glazed over the page, then turns into watercolor (lifting,
  soaking and flowing) a moment later, catching up as soon as the tablet has
  time, mostly once you lift the pen. The finished painting is the same as
  before.

- The **Wet brush pen** is back, as a choice on the Brush pen: open the Brush
  pen's settings and pick Dry or Wet, and the toolbar button keeps your
  choice (Dry at first). The wet one's water stays wet and lets ink run
  through it like ink in water, faster where the water is fresher, and it
  runs smoothly instead of in square chunks when you move fast.
- Wet brush pen ink settles much faster: a big stroke now looks finished a
  few seconds after you lift the pen instead of around 20, and the ink keeps
  running while you're still drawing. Wet paint uses all of the tablet's
  processor cores.
- The Wet brush pen now holds ink between strokes. A clean brush soaks up ink
  it rubs over, lightening that spot a little, since dried paint holds more
  ink than shows: touch a black line and the brush fills with gray while the
  line stays dark. It soaks up less as it fills, until it
  only smears. Put it down somewhere else and the ink it collected comes out
  over lighter paint. With the Wet brush pen selected, the eraser in the color
  bar cleans the brush; its dot shows for a moment when you tap it.
- The **Wet canvas uses gravity** setting is now **Watercolor uses gravity**,
  and it's on by default.
- With **Watercolor uses gravity** on, Wet brush pen ink now runs downhill
  through its water and pools at the low edge of the wet area. Tilt the
  tablet a little for a soft gradient, more to pool it hard.
- New brush option, **Watercolor mode when transparent**: with transparent
  paint the brush paints like the Wet brush pen, on a dry or wet canvas. It lifts the
  paint it touches, soaks up ink and carries it between strokes, and its ink
  runs and pools with gravity. The selected color is the lightest shade the
  brush lays; it never runs out of it, and darker colors soak up less. White
  makes it work just like the Wet brush pen. Tap a color to reset the ink the
  brush has picked up. On a dry canvas, dried paint stays put under a wash:
  each brush dab over it soaks it a little, so a couple of passes over line
  art leave it alone, but keep scrubbing a spot and the brush lifts it. It never
  drains into the wash by itself. Paint that's still wet mixes
  freely. With the option off,
  transparent paint works as before.
- The brush's **Oil paint** option is now **Oil paint mode when opaque**: it
  only applies to opaque paint, so transparent paint never runs out.
- Choosing a color while a brush pen is selected switches back to the brush
  you were using before, built-in or favorite, in that color.

## 0.97

- Layer names show again in the Layers panel on the Nomad.
- On the Nomad, tapping a button in the Layers panel no longer leaves it stuck
  on screen after the panel closes, even through a screen refresh. The whole
  panel now disappears at once.
- Autosave no longer breaks when Supernote Cloud sync is on. The autosave copy
  of the painting you're working on now stays inside MonoPaint instead of in
  `Document/MonoPaint`, where syncing it left hundreds of `_recovery` copies and
  eventually made autosave fail. MonoPaint removes those leftovers from the
  folder the first time it opens. Paintings you save are still kept in
  `Document/MonoPaint`.
- Settings has a new **Screen drawing** choice. **Fast e-ink** is the normal
  mode. **Standard Android** is slower but avoids MonoPaint's direct screen
  updates, for firmware (such as a beta) that shows black boxes or other screen
  glitches. Settings also shows your firmware build and whether MonoPaint has
  been tested on it, and MonoPaint tells you once when it starts on firmware
  it hasn't been tested on.
- The Settings window is wider, and choices with long names (such as
  "Standard Android") are no longer cut off.
- Exporting one page of a painting with several pages now names the file for
  its page, such as `statues004.png`, instead of `statues.png`.
- Export PNG has a **Dithered, as on screen** option. It saves the same black
  and white dots MonoPaint shows on the tablet instead of smooth grays. Dots
  only look right at one size, so you also pick the size: the page's own, as
  large as fits a Manta or Nomad screen (for example, exporting a Manta
  painting as the Nomad would show it), or a custom width or height that keeps
  the page's proportions. The app remembers these choices for later exports.
- A page you expanded to fill the screen now opens at 100%, where you painted
  it, instead of shrunk (to about 91%) to fit beside the toolbars.
- MonoPaint no longer keeps the tablet awake. The screen now sleeps and locks
  after the tablet's normal idle time, so leaving MonoPaint open won't drain
  the battery.

## 0.96

- The color selector is faster on the Nomad: only the small marker below the
  shade strip moves, leaving the strip's dots untouched.
- Rapid color taps leave fewer stray marker copies on the Nomad. The marker
  follows each tap immediately, with a cleanup a quarter-second after you pause
  instead of extra refreshes after every tap.
- Paintings are now kept in `Document/MonoPaint`, where the Files app shows them
  and they survive uninstalling or reinstalling MonoPaint. Each launch asks for
  file access until you allow it, saying how many paintings are still stored
  inside the app; then they and the painting you're working on move there
  automatically. After a reinstall, paintings already in that folder open again.
  On tablets other than Supernotes, paintings go to the standard
  `Documents/MonoPaint` instead.
- Export PNG now saves to `EXPORT/MonoPaint` on a Supernote, where the Files
  app shows it, once MonoPaint has file access. Other tablets still use
  `Pictures/MonoPaint`, and a folder chosen with Change folder… still wins.
- The menu no longer has a Canvas size item. Each page still offers Keep
  canvas size or Expand canvas the first time its bars are hidden.
- Paintings are now `.mpaint` files. Existing `.tsm` paintings are renamed
  automatically the first time this version starts.
- New painting and Open painting now ask whether to save unsaved changes first:
  Save, Don't save, or Cancel. If nothing changed since your last save, they go
  straight ahead.
- On the Nomad, shape previews and the gradient direction line now follow the
  pen without leaving a trail of copies or catching up after you stop.
- The app now calls your artwork paintings: New painting, Open painting, Save
  painting as…, and so on.

## 0.95

- MonoPaint now supports drawing on the Supernote Nomad, with a compact Pages
  menu that leaves more room for the color bar.
- Hide the bars for more drawing room. Swipe one finger from the canvas off a
  bar's edge of the screen to hide or show just that bar, starting from any
  layout. The artwork stays in place. Swipes that stop inside the canvas do
  nothing, and Supernote's own gestures still work. On the Manta, upward swipes
  now work reliably even though its touch screen loses the finger just before
  the top edge.
- A two-finger double tap hides both bars when both are showing, or brings both
  back when neither is showing. With one bar showing, it undoes the last bar
  change. The three-finger gesture is gone, and Fullscreen in the menu offers
  all four bar layouts.
- The first time you hide the bars on a page, illustrated buttons let you keep
  the canvas size or expand it with blank margins that fill the screen, without
  stretching the artwork. Each page has its own choice; revisit it in Canvas
  size. Expansion can be undone, and showing the bars again never crops a page.
  Existing drawings still open normally.
- Quickly double-tap the Zoom tool to return to 100%, with the artwork back
  where the page began; an expanded page reaches under the bars. A single tap
  still locks or unlocks zoom and pan, and a double tap keeps your lock setting.
- Export PNG has a **Change folder…** button. It opens at Supernote's EXPORT
  folder, which the tablet's file manager shows (Pictures/MonoPaint doesn't).
  The app remembers the folder you choose for later exports.
- Nomad fixes: tilt-sensitive brushes like Flat follow the pen's tilt; color
  bar markers follow the pen smoothly without leaving a trail and stay on the
  chosen shade after a screen refresh; the wetness slider, swatches, buttons
  and page controls use fast pressed feedback with softer-edged icons.
- Page-navigation buttons clear before the page changes, avoiding repeated
  flashes while the page counter and arrows update.

## 0.91

See the [0.91 release notes](https://github.com/mpdairy/monopaint/releases/tag/v0.91).

## 0.9

See the [0.9 release notes](https://github.com/mpdairy/monopaint/releases/tag/v0.9).
