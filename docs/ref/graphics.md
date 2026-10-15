# Graphics Libraries

This page documents the 2D drawing library shipped under `lib/graphics`.

These are library classes, not core built-in classes. Load them with `intern`.
They are written in Nex on top of Java2D and Swing (`with "java"`), with no
runtime, typechecker or compiler support of their own.

## Platform Scope

- `graphics/Canvas`: JVM interpreter, generated JVM
- `graphics/Color`: JVM interpreter, generated JVM
- `graphics/Font`: JVM interpreter, generated JVM
- `graphics/Key`: JVM interpreter, generated JVM
- `graphics/Canvas_Input`: JVM interpreter, generated JVM

Opening a window needs a display. Everything else (drawing, reading pixels,
saving images) also works headless, for example in tests or on a server.

## Load

```nex
intern graphics/Canvas   -- also interns Color, Font, Key and Canvas_Input
```

## Example

```nex
intern graphics/Canvas

let c := create Canvas.make(400, 400)
c.set_pen_color(Color.BLUE)
c.filled_circle(0.5, 0.5, 0.25)
c.set_pen_color(Color.BLACK)
c.text(0.5, 0.9, "hello")
c.save("circle.png")
c.show()                 -- opens a window
c.wait_until_closed()    -- keep it on screen until the user closes it
```

`examples/graphics_example.nex` draws a scene and runs a short animation.
`examples/graphics_input_example.nex` is a small sketchpad driven by the mouse
and keyboard.

## `Canvas`

A `Canvas` draws in **user coordinates** onto an offscreen image, and shows
that image in a window on request.

- **Coordinates.** (0, 0) is the bottom left and (1, 1) the top right until
  `set_x_scale`/`set_y_scale` change them. y grows upward.
- **Centre-based shapes.** `circle(x, y, radius)`, `square(x, y, half_side)`
  and `rectangle(x, y, half_width, half_height)` are all centred on (x, y).
  Lengths are scaled per axis, so with unequal x and y scales a circle comes
  out as an ellipse.
- **Pen.** Lines and outlines are drawn with the pen color, `2 * pen_radius`
  pixels wide. The pen radius is in pixels, not user units. Colors with an
  alpha below 255 blend with what is underneath.
- **Window.** `show` copies the offscreen image to the window, opening the
  window the first time. A canvas that never calls `show` never opens one.
  Without a display, `show` only updates the onscreen image and
  `wait_until_closed` returns at once, so the same program runs headless.
- **Buffering.** With double buffering off (the default), every drawing command
  also refreshes an already open window. With it on, the window changes only
  on `show`. Turn it on for flicker-free animation.
- **Program exit.** The window closes when the program ends. A script that
  should stay on screen ends with `wait_until_closed()`.

### Construction

| Constructor | Arguments | Description |
|---|---|---|
| `make` | `w, h: Integer` | A `w` by `h` pixel canvas, white, scale 0..1 on both axes, black pen of radius 1, `Font.SANS`, no double buffering. Requires `w > 0`, `h > 0`. |

### Queries

| Feature | Type | Description |
|---|---|---|
| `width`, `height` | `Integer` | Size in pixels. |
| `x_min`, `x_max`, `y_min`, `y_max` | `Real` | The visible user-coordinate rectangle. |
| `pen_radius` | `Real` | Pen radius in pixels. |
| `pen_color` | `Color` | Current drawing color. |
| `font` | `Font` | Current text font. |
| `is_double_buffered` | `Boolean` | Whether drawing waits for `show`. |
| `has_display()` | `Boolean` | Whether a window can be opened at all. |
| `is_window_open()` | `Boolean` | Whether this canvas has a window on screen. |
| `color_at(x, y: Real)` | `Color` | The color of the pixel at user position (x, y). Requires the point to be inside the scale. |
| `is_image_file_name(filename: String)` | `Boolean` | Whether `save` understands the file's extension. |
| `input` | `Canvas_Input` | The underlying mouse and keyboard state. See *Input*. |

### Drawing commands

| Method | Arguments | Description |
|---|---|---|
| `line` | `x0, y0, x1, y1: Real` | A line from (x0, y0) to (x1, y1). |
| `point` | `x, y: Real` | A dot of the pen's radius. |
| `circle`, `filled_circle` | `x, y, radius: Real` | A circle centred on (x, y). |
| `square`, `filled_square` | `x, y, half_side: Real` | An axis-aligned square centred on (x, y). |
| `rectangle`, `filled_rectangle` | `x, y, half_width, half_height: Real` | An axis-aligned rectangle centred on (x, y). |
| `polygon`, `filled_polygon` | `x, y: Array[Real]` | The closed polygon through (x[i], y[i]). Requires equal lengths and at least 3 points. |
| `text` | `x, y: Real, s: String` | `s` centred on (x, y). |
| `text_left` | `x, y: Real, s: String` | `s` starting at (x, y), vertically centred. |
| `text_right` | `x, y: Real, s: String` | `s` ending at (x, y), vertically centred. |

Radii and half-lengths must be non-negative.

### Control commands

| Method | Arguments | Description |
|---|---|---|
| `set_x_scale` | `x0, x1: Real` | Make x0..x1 the visible x range. Requires `x0 < x1`. |
| `set_y_scale` | `y0, y1: Real` | Make y0..y1 the visible y range. Requires `y0 < y1`. |
| `set_pen_radius` | `radius: Real` | Requires `radius >= 0`. |
| `set_pen_color` | `color: Color` | |
| `set_font` | `f: Font` | |
| `set_canvas_size` | `w, h: Integer` | Resize and clear to white. Scale, pen and font are kept. |
| `enable_double_buffering`, `disable_double_buffering` | none | See *Buffering* above. |
| `show` | none | Copy the offscreen image to the window, opening it if needed. |
| `clear` | none, or `color: Color` | Fill the whole canvas with white, or with `color`. |
| `pause` | `ms: Integer` | Sleep for `ms` milliseconds. For pacing an animation. |
| `wait_until_closed` | none | Block until the user closes the window. Returns at once if none is open. |
| `save` | `filename: String` | Write the image. The format comes from the extension: `.png`, `.jpg`/`.jpeg`, `.gif` or `.bmp`. |

### Input

While the window is open, the mouse and keyboard are polled, as in StdDraw. A
program reads them once per pass through its loop:

```nex
from until c.is_key_pressed(Key.ESCAPE) or not c.is_window_open() do
  if c.is_mouse_pressed() then
    c.filled_circle(c.mouse_x(), c.mouse_y(), 0.01)
  end
  c.pause(16)
end
```

| Feature | Returns | Description |
|---|---|---|
| `has_mouse_position()` | `Boolean` | Whether the mouse has been over the window yet. |
| `mouse_x()`, `mouse_y()` | `Real` | Where the mouse was last seen over the window, in user coordinates. Require `has_mouse_position()`. |
| `is_mouse_pressed()` | `Boolean` | Whether a mouse button is held down. |
| `has_next_key_typed()` | `Boolean` | Whether a typed character is waiting. |
| `next_key_typed()` | `String` | The oldest typed character not read yet, as a one-character string, removed from the queue. Requires `has_next_key_typed()`. |
| `is_key_pressed(code: Integer)` | `Boolean` | Whether the key with this code is held down. Use the `Key` constants. |

Typed characters are what the keyboard produced (`"A"` with Shift held, `"a"`
without). Held keys are physical keys (`Key.A` either way, `Key.LEFT`,
`Key.SHIFT`). Keys and buttons held when the window loses focus count as
released, since their release would never be heard.

Keyboard input goes to the window that has focus. A new window normally gets
it. If it doesn't, click it.

## `Canvas_Input`

The state behind a canvas's input queries, in pixels (0, 0 at the top left).
The window feeds it from Swing's event thread, so it is safe to read while
events arrive. It can also be fed directly, to test input handling without a
window or to replay a scripted session:

```nex
c.input.record_mouse_button(40, 60, true)    -- pixel (40, 60), button down
c.input.record_key_typed("q")
c.input.record_key(Key.LEFT, true)           -- LEFT held
```

| Feature | Description |
|---|---|
| `record_mouse_moved(px, py: Integer)` | The mouse is at pixel (px, py). |
| `record_mouse_button(px, py: Integer, is_down: Boolean)` | A button went down or up at (px, py). |
| `record_key_typed(s: String)` | A character was typed. Requires a one-character `s`. |
| `record_key(code: Integer, is_down: Boolean)` | A key went down or up. |
| `release_all()` | Forget held keys and buttons. |
| `has_mouse_position()`, `mouse_px()`, `mouse_py()`, `is_mouse_pressed()`, `has_next_key_typed()`, `next_key_typed()`, `is_key_pressed(code)` | As on `Canvas`, with positions in pixels. |

It also implements `java.awt.event.MouseListener`, `MouseMotionListener`,
`KeyListener` and `WindowFocusListener`, which is how the window feeds it.

## `Key`

Key codes for `is_key_pressed`. They are `java.awt.event.KeyEvent`'s virtual
key codes.

- Letters `A` to `Z` and digits `DIGIT_0` to `DIGIT_9`.
- Arrows `LEFT`, `RIGHT`, `UP`, `DOWN`.
- `SPACE`, `ENTER`, `ESCAPE`, `TAB`, `BACKSPACE`, `DELETE`.
- `SHIFT`, `CONTROL`, `ALT`.
- `HOME`, `END_KEY`, `PAGE_UP`, `PAGE_DOWN`. (`END_KEY` because `end` is a
  keyword.)
- `F1` to `F12`.

## `Color`

An immutable RGBA value. Each channel is `0..255`, and the constructor checks
it. `=` compares colors by value.

| Constructor | Arguments | Description |
|---|---|---|
| `make` | `r, g, b: Integer` | An opaque color. |
| `make_rgba` | `r, g, b, a: Integer` | Alpha 0 is transparent, 255 opaque. |

| Feature | Returns | Description |
|---|---|---|
| `red`, `green`, `blue`, `alpha` | `Integer` | The channels. |
| `with_alpha(a: Integer)` | `Color` | This color with a different alpha. |
| `is_opaque()` | `Boolean` | `alpha = 255`. |

Constants: `BLACK`, `WHITE`, `RED`, `GREEN`, `BLUE`, `YELLOW`, `CYAN`,
`MAGENTA`, `ORANGE`, `PINK`, `GRAY`, `LIGHT_GRAY`, `DARK_GRAY`.

## `Font`

An immutable font description. `"Serif"`, `"SansSerif"` and `"Monospaced"`
are always available. Other family names depend on the machine and fall back to
a default when missing.

| Constructor | Arguments | Description |
|---|---|---|
| `make` | `family_name: String, point_size: Integer` | A plain font. |
| `make_styled` | `family_name: String, point_size: Integer, bold_flag, italic_flag: Boolean` | |

| Feature | Returns | Description |
|---|---|---|
| `family` | `String` | Family name. |
| `size` | `Integer` | Point size. |
| `is_bold`, `is_italic` | `Boolean` | Style. |
| `with_size(n: Integer)` | `Font` | This font at size `n`. |
| `bold()`, `italic()` | `Font` | A bold or italic version. |

Constants: `SANS`, `SERIF`, `MONO` (all 16 point).
