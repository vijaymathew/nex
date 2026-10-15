(ns nex.graphics-lib-test
  "graphics/Canvas, graphics/Color, graphics/Font (lib/graphics/): drawing in
   user coordinates onto an offscreen image, built entirely on Java interop.
   Nothing here calls `show`, so no window opens; pixels are read back with
   `color_at` or from the saved file.

   Behaviour is asserted on both backends."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

;; ---------------------------------------------------------------------------
;; Helpers (mirrors test/nex/mutex_test.clj's convention)
;; ---------------------------------------------------------------------------

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "graphics" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  "Printed output lines of CODE, asserted identical on both backends."
  [code]
  (let [compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- raises-on-both?
  "Whether CODE fails at runtime on both backends with a message containing MSG."
  [code msg]
  (every? (fn [interpret?]
            (let [f (java.io.File/createTempFile "graphics" ".nex")]
              (try
                (spit f code)
                (try (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))
                     false
                     (catch Throwable t
                       (loop [x t]
                         (cond
                           (str/includes? (str (ex-message x)) msg) true
                           (.getCause x) (recur (.getCause x))
                           :else false))))
                (finally (.delete f)))))
          [false true]))

(defn- program
  "CODE preceded by the Canvas intern."
  [& lines]
  (str/join "\n" (cons "intern graphics/Canvas" lines)))

;; ---------------------------------------------------------------------------
;; Color and Font
;; ---------------------------------------------------------------------------

(deftest color-is-a-value
  (is (= ["Color(255, 0, 0)" "true" "Color(255, 255, 255, 64)" "false" "true"]
         (both (program
                "print(Color.RED)"
                "print(create Color.make(255, 0, 0) = Color.RED)"
                "print(Color.WHITE.with_alpha(64))"
                "print(Color.WHITE.with_alpha(64).is_opaque())"
                "print(Color.BLACK == Color.BLACK)")))))

(deftest color-channels-are-checked
  (is (raises-on-both? (program "let c := create Color.make(256, 0, 0)") "red_in_range"))
  (is (raises-on-both? (program "let c := Color.RED.with_alpha(-1)") "alpha_in_range")))

(deftest font-derivations
  (is (= ["Font(SansSerif, 16)" "Font(Serif, 32, bold)" "Font(Monospaced, 16, italic)"]
         (both (program
                "print(Font.SANS)"
                "print(Font.SERIF.with_size(32).bold())"
                "print(Font.MONO.italic())"))))
  (is (raises-on-both? (program "let f := Font.SANS.with_size(0)") "positive_size")))

;; ---------------------------------------------------------------------------
;; Drawing
;; ---------------------------------------------------------------------------

(deftest new-canvas-is-white-with-unit-scale
  (is (= ["Color(255, 255, 255)" "\"0.0 1.0 0.0 1.0\"" "\"200 100\""]
         (both (program
                "let c := create Canvas.make(200, 100)"
                "print(c.color_at(0.5, 0.5))"
                "print(c.x_min.to_string + \" \" + c.x_max.to_string + \" \" + c.y_min.to_string + \" \" + c.y_max.to_string)"
                "print(c.width.to_string + \" \" + c.height.to_string)")))))

(deftest filled-shapes-are-centre-based
  (is (= ["Color(255, 0, 0)" "Color(255, 255, 255)"
          "Color(0, 0, 255)" "Color(0, 0, 255)" "Color(255, 255, 255)"
          "Color(0, 255, 0)" "Color(255, 255, 255)"]
         (both (program
                "let c := create Canvas.make(100, 100)"
                "c.set_pen_color(Color.RED)"
                "c.filled_circle(0.25, 0.25, 0.1)"
                "print(c.color_at(0.25, 0.25))"
                ;; just outside the radius along the diagonal
                "print(c.color_at(0.25 + 0.08, 0.25 + 0.08))"
                "c.set_pen_color(Color.BLUE)"
                "c.filled_rectangle(0.75, 0.25, 0.2, 0.1)"
                "print(c.color_at(0.75, 0.25))"
                "print(c.color_at(0.93, 0.25))"
                "print(c.color_at(0.75, 0.38))"
                "c.set_pen_color(Color.GREEN)"
                "c.filled_polygon([0.1, 0.4, 0.25], [0.6, 0.6, 0.9])"
                "print(c.color_at(0.25, 0.7))"
                "print(c.color_at(0.1, 0.9))")))))

(deftest outlines-leave-the-inside-alone
  (is (= ["Color(255, 255, 255)" "Color(0, 0, 0)" "Color(255, 255, 255)"]
         (both (program
                "let c := create Canvas.make(100, 100)"
                "c.set_pen_radius(2.0)"
                "c.square(0.5, 0.5, 0.3)"
                "print(c.color_at(0.5, 0.5))"
                "print(c.color_at(0.2, 0.5))"
                "c.circle(0.5, 0.5, 0.1)"
                "print(c.color_at(0.5, 0.5))")))))

(deftest lines-and-points
  (is (= ["Color(0, 0, 0)" "Color(255, 255, 255)" "Color(255, 0, 0)"]
         (both (program
                "let c := create Canvas.make(100, 100)"
                "c.set_pen_radius(2.0)"
                "c.line(0.0, 0.5, 1.0, 0.5)"
                "print(c.color_at(0.3, 0.5))"
                "print(c.color_at(0.3, 0.7))"
                "c.set_pen_color(Color.RED)"
                "c.set_pen_radius(4.0)"
                "c.point(0.8, 0.8)"
                "print(c.color_at(0.8, 0.8))")))))

(deftest user-scale-maps-coordinates
  ;; y grows upward: (0, 8) is near the top of a -10..10 by 0..10 canvas.
  (is (= ["Color(255, 0, 0)" "Color(255, 255, 255)"]
         (both (program
                "let c := create Canvas.make(100, 100)"
                "c.set_x_scale(-10.0, 10.0)"
                "c.set_y_scale(0.0, 10.0)"
                "c.set_pen_color(Color.RED)"
                "c.filled_square(0.0, 8.0, 1.0)"
                "print(c.color_at(0.0, 8.0))"
                "print(c.color_at(0.0, 2.0))")))))

(deftest clear-and-alpha
  (is (= ["Color(0, 0, 0)" "Color(255, 255, 255)" "true"]
         (both (program
                "let c := create Canvas.make(50, 50)"
                "c.clear(Color.BLACK)"
                "print(c.color_at(0.5, 0.5))"
                "c.clear()"
                "print(c.color_at(0.5, 0.5))"
                ;; half-transparent red over white is pink
                "c.set_pen_color(Color.RED.with_alpha(128))"
                "c.filled_square(0.5, 0.5, 0.4)"
                "let p := c.color_at(0.5, 0.5)"
                "print(p.red = 255 and p.green > 100 and p.green < 155 and p.green = p.blue)")))))

(deftest text-draws-something
  (is (= ["true"]
         (both (program
                "let c := create Canvas.make(200, 100)"
                "c.set_font(Font.SANS.with_size(48).bold())"
                "c.text(0.5, 0.5, \"███\")"
                "c.text_left(0.0, 0.1, \"left\")"
                "c.text_right(1.0, 0.9, \"right\")"
                "print(c.color_at(0.5, 0.5) /= Color.WHITE)")))))

(deftest set-canvas-size-resizes-and-clears
  (is (= ["\"300 120\"" "Color(255, 255, 255)" "Color(255, 0, 0)"]
         (both (program
                "let c := create Canvas.make(100, 100)"
                "c.set_pen_color(Color.BLUE)"
                "c.filled_square(0.5, 0.5, 0.5)"
                "c.set_canvas_size(300, 120)"
                "print(c.width.to_string + \" \" + c.height.to_string)"
                "print(c.color_at(0.5, 0.5))"
                "c.set_pen_color(Color.RED)"
                "c.filled_square(0.5, 0.5, 0.1)"
                "print(c.color_at(0.5, 0.5))")))))

(deftest double-buffering-flag
  (is (= ["false" "true" "false"]
         (both (program
                "let c := create Canvas.make(10, 10)"
                "print(c.is_double_buffered)"
                "c.enable_double_buffering()"
                "print(c.is_double_buffered)"
                "c.disable_double_buffering()"
                "print(c.is_double_buffered)")))))

;; ---------------------------------------------------------------------------
;; Saving
;; ---------------------------------------------------------------------------

(deftest save-writes-each-format
  (doseq [ext ["png" "jpg" "gif" "bmp"]
          interpret? [false true]]
    (let [out (java.io.File/createTempFile "canvas" (str "." ext))]
      (try
        (.delete out)
        (run-backend (program
                      "let c := create Canvas.make(64, 32)"
                      "c.set_pen_color(Color.RED)"
                      "c.filled_square(0.5, 0.5, 0.25)"
                      (str "c.save(\"" (.getPath out) "\")"))
                     interpret?)
        (testing (str ext (when interpret? " (interpreted)"))
          (let [img (javax.imageio.ImageIO/read out)]
            (is (some? img))
            (is (= [64 32] [(.getWidth img) (.getHeight img)]))
            ;; centre pixel is (close to, for lossy jpg) pure red
            (let [rgb (.getRGB img 32 16)
                  r (bit-and (bit-shift-right rgb 16) 0xFF)
                  g (bit-and (bit-shift-right rgb 8) 0xFF)]
              (is (> r 240))
              (is (< g 20)))))
        (finally (io/delete-file out true))))))

(deftest save-rejects-unknown-extension
  (is (raises-on-both? (program
                        "let c := create Canvas.make(10, 10)"
                        "c.save(\"picture.tiff\")")
                       "known_format")))

;; ---------------------------------------------------------------------------
;; Contracts
;; ---------------------------------------------------------------------------

(deftest preconditions
  (is (raises-on-both? (program "let c := create Canvas.make(0, 10)") "positive_width"))
  (is (raises-on-both? (program "let c := create Canvas.make(10, 10)"
                                "c.set_x_scale(1.0, 0.0)")
                       "increasing"))
  (is (raises-on-both? (program "let c := create Canvas.make(10, 10)"
                                "c.set_pen_radius(-1.0)")
                       "non_negative_radius"))
  (is (raises-on-both? (program "let c := create Canvas.make(10, 10)"
                                "c.polygon([0.1, 0.2, 0.3], [0.1, 0.2])")
                       "same_length"))
  (is (raises-on-both? (program "let c := create Canvas.make(10, 10)"
                                "c.filled_polygon([0.1, 0.2], [0.1, 0.2])")
                       "at_least_three_points"))
  (is (raises-on-both? (program "let c := create Canvas.make(10, 10)"
                                "c.circle(0.5, 0.5, -0.1)")
                       "non_negative_radius"))
  (is (raises-on-both? (program "let c := create Canvas.make(10, 10)"
                                "print(c.color_at(2.0, 0.5))")
                       "inside_x")))

;; ---------------------------------------------------------------------------
;; Input
;; ---------------------------------------------------------------------------

(deftest mouse-position-in-user-coordinates
  (is (= ["false" "2.5" "0.75" "true" "false"]
         (both (program
                "let c := create Canvas.make(200, 100)"
                "c.set_x_scale(0.0, 10.0)"
                "print(c.has_mouse_position())"
                ;; pixel (50, 25): a quarter across, a quarter down
                "c.input.record_mouse_button(50, 25, true)"
                "print(c.mouse_x())"
                "print(c.mouse_y())"
                "print(c.is_mouse_pressed())"
                "c.input.record_mouse_button(50, 25, false)"
                "print(c.is_mouse_pressed())")))))

(deftest typed-keys-queue-in-order
  (is (= ["true" "\"h\"" "\"i\"" "false"]
         (both (program
                "let c := create Canvas.make(10, 10)"
                "c.input.record_key_typed(\"h\")"
                "c.input.record_key_typed(\"i\")"
                "print(c.has_next_key_typed())"
                "print(c.next_key_typed())"
                "print(c.next_key_typed())"
                "print(c.has_next_key_typed())")))))

(deftest held-keys-and-release-all
  (is (= ["true" "false" "false" "true" "false" "false"]
         (both (program
                "let c := create Canvas.make(10, 10)"
                "c.input.record_key(Key.LEFT, true)"
                "print(c.is_key_pressed(Key.LEFT))"
                "print(c.is_key_pressed(Key.RIGHT))"
                "c.input.record_key(Key.LEFT, false)"
                "print(c.is_key_pressed(Key.LEFT))"
                "c.input.record_key(Key.A, true)"
                "c.input.record_mouse_button(1, 1, true)"
                "print(c.is_key_pressed(Key.A))"
                "c.input.release_all()"
                "print(c.is_key_pressed(Key.A))"
                "print(c.is_mouse_pressed())")))))

(deftest real-awt-events-reach-the-canvas
  ;; The listener path a window uses, without a window: mouse events
  ;; dispatched through a real component, key events handed to the listener
  ;; methods (AWT routes keys only to a focused window).
  (is (= ["0.25" "0.5" "true" "true" "\"z\"" "false"]
         (both (str/join "\n"
                         ["intern graphics/Canvas"
                          "import java.awt.event.KeyEvent"
                          "import java.awt.event.MouseEvent"
                          "import javax.swing.JLabel"
                          "let c := create Canvas.make(100, 100)"
                          "with \"java\" do"
                          "  let label := JLabel.new(\"x\")"
                          "  label.setSize(100, 100)"
                          "  label.addMouseListener(c.input)"
                          "  label.addMouseMotionListener(c.input)"
                          "  label.dispatchEvent(MouseEvent.new(label, MouseEvent.MOUSE_PRESSED, 0, 0, 25, 50, 1, false))"
                          "  c.input.keyPressed(KeyEvent.new(label, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED))"
                          "  c.input.keyTyped(KeyEvent.new(label, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, #z))"
                          "end"
                          "print(c.mouse_x())"
                          "print(c.mouse_y())"
                          "print(c.is_mouse_pressed())"
                          "print(c.is_key_pressed(Key.UP))"
                          "print(c.next_key_typed())"
                          "with \"java\" do"
                          "  let source := JLabel.new(\"y\")"
                          "  c.input.keyReleased(KeyEvent.new(source, KeyEvent.KEY_RELEASED, 0, 0, KeyEvent.VK_UP, KeyEvent.CHAR_UNDEFINED))"
                          "end"
                          "print(c.is_key_pressed(Key.UP))"])))))

(deftest input-preconditions
  (is (raises-on-both? (program "let c := create Canvas.make(10, 10)"
                                "print(c.mouse_x())")
                       "seen"))
  (is (raises-on-both? (program "let c := create Canvas.make(10, 10)"
                                "print(c.next_key_typed())")
                       "key_waiting"))
  (is (raises-on-both? (program "let c := create Canvas.make(10, 10)"
                                "c.input.record_key_typed(\"ab\")")
                       "one_character")))

;; ---------------------------------------------------------------------------
;; Pictures
;; ---------------------------------------------------------------------------

(defn- with-red-png
  "Run F with the absolute path of a fresh 20x10 all-red PNG, made by a
   Canvas and saved, so loading also checks the save -> load round trip."
  [f]
  (let [png (java.io.File/createTempFile "picture" ".png")]
    (try
      (run-backend (program "let c := create Canvas.make(20, 10)"
                            "c.clear(Color.RED)"
                            (str "c.save(\"" (.getPath png) "\")"))
                   false)
      (f (.getPath png))
      (finally (.delete png)))))

(deftest picture-loads-with-its-size
  (with-red-png
    (fn [path]
      (is (= ["20" "10" "true"]
             (both (program (str "let p := create Picture.load(\"" path "\")")
                            "print(p.width)"
                            "print(p.height)"
                            "print(p.to_string.starts_with(\"Picture(\"))")))))))

(deftest picture-natural-scaled-and-rotated
  ;; 100x100 canvas, unit scale: a pixel is 0.01. The picture is 20x10 px.
  (with-red-png
    (fn [path]
      (is (= [;; natural size: 0.1 either side of x, 0.05 of y
              "Color(255, 0, 0)" "Color(255, 255, 255)" "Color(255, 255, 255)"
              ;; scaled to half sizes 0.3 x 0.05
              "Color(255, 0, 0)" "Color(255, 255, 255)"
              ;; scaled the same and turned 90 degrees: now tall and thin
              "Color(255, 0, 0)" "Color(255, 255, 255)"]
             (both (program (str "let p := create Picture.load(\"" path "\")")
                            "let c := create Canvas.make(100, 100)"
                            "c.picture(0.5, 0.5, p)"
                            "print(c.color_at(0.58, 0.53))"
                            "print(c.color_at(0.63, 0.5))"
                            "print(c.color_at(0.5, 0.58))"
                            "c.clear()"
                            "c.picture(0.5, 0.5, p, 0.3, 0.05)"
                            "print(c.color_at(0.77, 0.5))"
                            "print(c.color_at(0.5, 0.58))"
                            "c.clear()"
                            "c.picture(0.5, 0.5, p, 0.3, 0.05, 90.0)"
                            "print(c.color_at(0.5, 0.77))"
                            "print(c.color_at(0.77, 0.5))")))))))

(deftest picture-keeps-png-transparency
  ;; The example sprite's corners are transparent: the black canvas shows
  ;; through there, and the sprite's body (light grey-white) at its centre.
  (let [ship (.getAbsolutePath (java.io.File. "examples/assets/ship.png"))]
    (is (= ["Color(0, 0, 0)" "true"]
           (both (program (str "let p := create Picture.load(\"" ship "\")")
                          "let c := create Canvas.make(64, 64)"
                          "c.clear(Color.BLACK)"
                          "c.picture(0.5, 0.5, p)"
                          "print(c.color_at(0.05, 0.95))"
                          "print(c.color_at(0.5, 0.5).red > 200)"))))))

(deftest picture-is-drawn-opaque-whatever-the-pen
  (with-red-png
    (fn [path]
      (is (= ["Color(255, 0, 0)"]
             (both (program (str "let p := create Picture.load(\"" path "\")")
                            "let c := create Canvas.make(100, 100)"
                            "c.set_pen_color(Color.BLUE.with_alpha(10))"
                            "c.picture(0.5, 0.5, p)"
                            "print(c.color_at(0.5, 0.5))")))))))

(deftest picture-load-errors
  (is (raises-on-both? (program "let p := create Picture.load(\"/no/such/dir/ship.png\")")
                       "Picture.load: no such file: /no/such/dir/ship.png"))
  (let [txt (java.io.File/createTempFile "not_an_image" ".png")]
    (try
      (spit txt "hello")
      (is (raises-on-both? (program (str "let p := create Picture.load(\"" (.getPath txt) "\")"))
                           "Picture.load: not a readable image"))
      (finally (.delete txt)))))
