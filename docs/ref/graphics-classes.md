# Graphics Classes

## `Window`

### Construction

```nex
create Window
create Window(width, height)
create Window.with_title(title)
create Window.with_title(title, width, height)
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `show` | none | `Void` | Display window. |
| `close` | none | `Void` | Close window. |
| `clear` | none | `Void` | Clear drawing surface. |
| `vw` | none | `Integer` | Window width. |
| `vh` | none | `Integer` | Window height. |
| `bgcolor` | `color: String` | `Void` | Set background color. |
| `refresh` | none | `Void` | Repaint window contents. |
| `set_color` | `color: String` | `Void` | Set drawing color. |
| `set_font_size` | `size: Integer` | `Void` | Set text size. |
| `draw_line` | `x1, y1, x2, y2: Real` | `Void` | Draw line segment. |
| `draw_rect` | `x, y, width, height: Real` | `Void` | Draw rectangle outline. |
| `fill_rect` | `x, y, width, height: Real` | `Void` | Draw filled rectangle. |
| `draw_circle` | `x, y, r: Real` | `Void` | Draw circle outline. |
| `fill_circle` | `x, y, r: Real` | `Void` | Draw filled circle. |
| `draw_text` | `text: String, x, y: Real` | `Void` | Draw text at position. |
| `draw_image` | `img: Image, x, y: Real` | `Void` | Draw image at position. |
| `draw_image_scaled` | `img: Image, x, y, width, height: Real` | `Void` | Draw scaled image. |
| `draw_image_rotated` | `img: Image, x, y, angle: Real` | `Void` | Draw rotated image. |
| `sleep` | `ms: Integer` | `Void` | Pause current thread/event flow. |

## `Turtle`

### Construction

```nex
create Turtle.on_window(window)
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `forward` | `dist: Real` | `Void` | Move forward by distance. |
| `backward` | `dist: Real` | `Void` | Move backward by distance. |
| `right` | `angle: Real` | `Void` | Turn clockwise. |
| `left` | `angle: Real` | `Void` | Turn counterclockwise. |
| `penup` | none | `Void` | Lift pen (no drawing). |
| `pendown` | none | `Void` | Lower pen (draw while moving). |
| `color` | `c: String` | `Void` | Set pen color. |
| `pensize` | `size: Real` | `Void` | Set pen width. |
| `speed` | `s: Integer` | `Void` | Set turtle speed. |
| `shape` | `s: String` | `Void` | Set turtle shape. |
| `goto` | `x, y: Real` | `Void` | Move to coordinates. |
| `circle` | `r: Real` | `Void` | Draw circle. |
| `begin_fill` | none | `Void` | Start fill region. |
| `end_fill` | none | `Void` | End and apply fill. |
| `surface` | none | `Window` | Return owning window. |
| `hide` | none | `Void` | Hide turtle icon. |
| `xpos` | none | `Real` | Current x coordinate. |
| `ypos` | none | `Real` | Current y coordinate. |
| `show` | none | `Void` | Show turtle icon. |

## `Image`

### Construction

```nex
create Image.from_file(path)
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `width` | none | `Integer` | Image width. |
| `height` | none | `Integer` | Image height. |

## Examples

```nex
let w := create Window.with_title("Demo", 640, 360)
w.show()
w.bgcolor("white")
w.set_color("blue")
w.draw_rect(30, 30, 120, 80)
w.draw_text("Nex", 40, 70)
w.refresh()

let t := create Turtle.on_window(w)
t.color("red")
t.forward(80)
t.right(120)
t.forward(80)
t.right(120)
t.forward(80)

let img := create Image.from_file("sprite.png")
w.draw_image(img, 220, 100)
```
