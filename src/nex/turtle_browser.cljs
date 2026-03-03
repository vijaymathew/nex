(ns nex.turtle-browser
  "Browser (ClojureScript) turtle graphics runtime for Nex Window/Turtle/Image built-ins."
  (:require [clojure.string :as str]))

(def named-colors
  {"black" "#000000"
   "white" "#ffffff"
   "red" "#ff0000"
   "green" "#008000"
   "blue" "#0000ff"
   "yellow" "#ffff00"
   "orange" "#ffa500"
   "purple" "#800080"
   "cyan" "#00ffff"
   "magenta" "#ff00ff"
   "brown" "#8b4513"
   "pink" "#ffc0cb"
   "gray" "#808080"
   "grey" "#808080"})

(defn- ensure-dom! []
  (when-not (exists? js/document)
    (throw (ex-info "Window/Turtle requires a browser DOM environment" {}))))

(defn parse-color
  "Parse a color string. Accepts named colors or CSS color strings."
  [s]
  (let [k (-> (str s) str/trim str/lower-case)]
    (or (get named-colors k) (str s))))

(defn speed-delay
  "Convert a speed value (0-10) to a delay in ms."
  [speed]
  (cond
    (<= speed 0) 0
    (>= speed 10) 5
    :else (long (/ 200 speed))))

(defn- now-ms []
  (if (exists? js/performance)
    (.now js/performance)
    (.now js/Date)))

(defn- draw-turtle-cursor
  [ctx x y heading color shape]
  (.save ctx)
  (.translate ctx x y)
  (.rotate ctx (* -1 (/ (* heading js/Math.PI) 180.0)))
  (set! (.-fillStyle ctx) color)
  (if (= shape :circle)
    (do
      (.beginPath ctx)
      (.arc ctx 0 0 6 0 (* 2 js/Math.PI))
      (.fill ctx))
    (do
      (.beginPath ctx)
      (.moveTo ctx 12 0)
      (.lineTo ctx -6 -7)
      (.lineTo ctx -6 7)
      (.closePath ctx)
      (.fill ctx)))
  (.restore ctx))

(defn- canvas-coords [win-state turtle-state]
  [(+ (/ (:width win-state) 2.0) (:x turtle-state))
   (- (/ (:height win-state) 2.0) (:y turtle-state))])

(defn- repaint-overlay! [win]
  (let [s @(:state win)
        ctx (:overlay-ctx s)
        turtles (:turtles s)]
    (.clearRect ctx 0 0 (:width s) (:height s))
    (doseq [t turtles]
      (let [ts @(:state t)]
        (when (:visible? ts)
          (let [[cx cy] (canvas-coords s ts)]
            (draw-turtle-cursor ctx cx cy (:heading ts) (parse-color (:color ts)) (:shape ts))))))))

(defn create-window
  "Create a browser window backed by HTML canvas."
  ([] (create-window "Nex Turtle Graphics" 800 600))
  ([title] (create-window title 800 600))
  ([title w h]
   (ensure-dom!)
   (let [draw-canvas (.createElement js/document "canvas")
         overlay-canvas (.createElement js/document "canvas")
         container (.createElement js/document "div")
         draw-ctx (.getContext draw-canvas "2d")
         overlay-ctx (.getContext overlay-canvas "2d")
         width (int w)
         height (int h)
         state (atom {:title (str title)
                      :width width
                      :height height
                      :bg-color "#ffffff"
                      :draw-color "#000000"
                      :font-size 14
                      :canvas draw-canvas
                      :ctx draw-ctx
                      :overlay overlay-canvas
                      :overlay-ctx overlay-ctx
                      :container container
                      :turtles []})]
     (set! (.-width draw-canvas) width)
     (set! (.-height draw-canvas) height)
     (set! (.-width overlay-canvas) width)
     (set! (.-height overlay-canvas) height)

     (set! (.. container -style -position) "relative")
     (set! (.. container -style -width) (str width "px"))
     (set! (.. container -style -height) (str height "px"))
     (set! (.. container -style -border) "1px solid #d0d0d0")
     (set! (.. container -style -margin) "8px 0")

     (set! (.. draw-canvas -style -position) "absolute")
     (set! (.. draw-canvas -style -left) "0")
     (set! (.. draw-canvas -style -top) "0")

     (set! (.. overlay-canvas -style -position) "absolute")
     (set! (.. overlay-canvas -style -left) "0")
     (set! (.. overlay-canvas -style -top) "0")
     (set! (.. overlay-canvas -style -pointerEvents) "none")

     (.appendChild container draw-canvas)
     (.appendChild container overlay-canvas)

     (set! (.-fillStyle draw-ctx) "#ffffff")
     (.fillRect draw-ctx 0 0 width height)

     {:nex-builtin-type :Window
      :state state})))

(defn- window-state [state-key win]
  (state-key @(:state win)))

(def window-height (partial window-state :height))
(def window-width (partial window-state :width))

(defn show-window [win]
  (ensure-dom!)
  (let [{:keys [container title]} @(:state win)]
    (when-not (.-isConnected container)
      (.appendChild (.-body js/document) container))
    (set! (.-title js/document) title)
    (repaint-overlay! win))
  nil)

(defn close-window [win]
  (let [{:keys [container]} @(:state win)]
    (when (.-isConnected container)
      (.remove container)))
  nil)

(defn repaint-window [win]
  (repaint-overlay! win)
  nil)

(defn clear-window [win]
  (let [s @(:state win)
        ctx (:ctx s)]
    (set! (.-fillStyle ctx) (:bg-color s))
    (.fillRect ctx 0 0 (:width s) (:height s))
    (repaint-window win))
  nil)

(defn set-bgcolor [win color-str]
  (swap! (:state win) assoc :bg-color (parse-color color-str))
  (clear-window win))

(defn set-draw-color [win color-str]
  (swap! (:state win) assoc :draw-color (parse-color color-str))
  nil)

(defn set-font-size [win size]
  (swap! (:state win) assoc :font-size (int size))
  nil)

(defn draw-line [win x1 y1 x2 y2]
  (let [s @(:state win)
        ctx (:ctx s)]
    (set! (.-strokeStyle ctx) (:draw-color s))
    (.beginPath ctx)
    (.moveTo ctx x1 y1)
    (.lineTo ctx x2 y2)
    (.stroke ctx))
  nil)

(defn draw-rect [win x y w h]
  (let [s @(:state win)
        ctx (:ctx s)]
    (set! (.-strokeStyle ctx) (:draw-color s))
    (.strokeRect ctx x y w h))
  nil)

(defn fill-rect [win x y w h]
  (let [s @(:state win)
        ctx (:ctx s)]
    (set! (.-fillStyle ctx) (:draw-color s))
    (.fillRect ctx x y w h))
  nil)

(defn draw-circle [win x y r]
  (let [s @(:state win)
        ctx (:ctx s)]
    (set! (.-strokeStyle ctx) (:draw-color s))
    (.beginPath ctx)
    (.arc ctx x y r 0 (* 2 js/Math.PI))
    (.stroke ctx))
  nil)

(defn fill-circle [win x y r]
  (let [s @(:state win)
        ctx (:ctx s)]
    (set! (.-fillStyle ctx) (:draw-color s))
    (.beginPath ctx)
    (.arc ctx x y r 0 (* 2 js/Math.PI))
    (.fill ctx))
  nil)

(defn draw-text [win text x y]
  (let [s @(:state win)
        ctx (:ctx s)]
    (set! (.-fillStyle ctx) (:draw-color s))
    (set! (.-font ctx) (str (:font-size s) "px sans-serif"))
    (.fillText ctx (str text) x y))
  nil)

(defn window-sleep [_win ms]
  ;; Blocking sleep to preserve interpreter semantics.
  (let [end (+ (now-ms) (double ms))]
    (while (< (now-ms) end) nil))
  nil)

(defn create-image [path]
  (ensure-dom!)
  (let [img (js/Image.)
        state (atom {:image img :width 0 :height 0 :loaded? false})]
    (set! (.-onload img)
          (fn []
            (swap! state assoc
                   :width (.-naturalWidth img)
                   :height (.-naturalHeight img)
                   :loaded? true)))
    (set! (.-src img) (str path))
    {:nex-builtin-type :Image
     :state state}))

(defn image-width [img]
  (:width @(:state img)))

(defn image-height [img]
  (:height @(:state img)))

(defn draw-image [win img x y]
  (let [s @(:state win)
        is @(:state img)
        ctx (:ctx s)]
    (.drawImage ctx (:image is) x y))
  nil)

(defn draw-image-scaled [win img x y w h]
  (let [s @(:state win)
        is @(:state img)
        ctx (:ctx s)]
    (.drawImage ctx (:image is) x y w h))
  nil)

(defn draw-image-rotated [win img x y angle]
  (let [s @(:state win)
        is @(:state img)
        ctx (:ctx s)
        iw (:width is)
        ih (:height is)
        cx (+ x (/ iw 2.0))
        cy (+ y (/ ih 2.0))]
    (.save ctx)
    (.translate ctx cx cy)
    (.rotate ctx (/ (* angle js/Math.PI) 180.0))
    (.drawImage ctx (:image is) (- (/ iw 2.0)) (- (/ ih 2.0)))
    (.restore ctx))
  nil)

(defn create-turtle [win]
  (let [turtle {:nex-builtin-type :Turtle
                :state (atom {:window win
                              :x 0.0 :y 0.0
                              :heading 90.0
                              :pen-down? true
                              :color "black"
                              :pen-size 1
                              :speed 6
                              :shape :classic
                              :visible? true
                              :filling? false
                              :fill-points []
                              :fill-color "black"})}]
    (swap! (:state win) update :turtles conj turtle)
    (repaint-window win)
    turtle))

(defn- turtle-state [state-key turtle]
  (state-key @(:state turtle)))

(def turtle-window (partial turtle-state :window))
(def turtle-x (partial turtle-state :x))
(def turtle-y (partial turtle-state :y))

(defn- stroke-segment! [ctx s sx sy ex ey]
  (set! (.-strokeStyle ctx) (parse-color (:color s)))
  (set! (.-lineWidth ctx) (:pen-size s))
  (set! (.-lineCap ctx) "round")
  (set! (.-lineJoin ctx) "round")
  (.beginPath ctx)
  (.moveTo ctx sx sy)
  (.lineTo ctx ex ey)
  (.stroke ctx))

(defn turtle-forward [turtle dist]
  (let [ts @(:state turtle)
        win (:window ts)
        ws @(:state win)
        ctx (:ctx ws)
        rad (/ (* (:heading ts) js/Math.PI) 180.0)
        dx (* dist (js/Math.cos rad))
        dy (* dist (js/Math.sin rad))
        new-x (+ (:x ts) dx)
        new-y (+ (:y ts) dy)
        next-ts (assoc ts :x new-x :y new-y)
        [sx sy] (canvas-coords ws ts)
        [ex ey] (canvas-coords ws next-ts)]
    (when (:pen-down? ts)
      (stroke-segment! ctx ts sx sy ex ey))
    (swap! (:state turtle) assoc :x new-x :y new-y)
    (when (:filling? ts)
      (swap! (:state turtle) update :fill-points conj [new-x new-y]))
    (repaint-window win)
    (let [delay (speed-delay (:speed ts))]
      (when (pos? delay)
        (window-sleep win delay))))
  nil)

(defn turtle-backward [turtle dist]
  (turtle-forward turtle (- dist)))

(defn turtle-right [turtle angle]
  (swap! (:state turtle) update :heading #(- % angle))
  (repaint-window (:window @(:state turtle)))
  nil)

(defn turtle-left [turtle angle]
  (swap! (:state turtle) update :heading #(+ % angle))
  (repaint-window (:window @(:state turtle)))
  nil)

(defn turtle-penup [turtle]
  (swap! (:state turtle) assoc :pen-down? false)
  nil)

(defn turtle-pendown [turtle]
  (swap! (:state turtle) assoc :pen-down? true)
  nil)

(defn turtle-color [turtle color-str]
  (swap! (:state turtle) assoc :color (str color-str) :fill-color (str color-str))
  (repaint-window (:window @(:state turtle)))
  nil)

(defn turtle-pensize [turtle size]
  (swap! (:state turtle) assoc :pen-size size)
  nil)

(defn turtle-speed [turtle spd]
  (swap! (:state turtle) assoc :speed spd)
  nil)

(defn turtle-shape [turtle shape-str]
  (let [s (str/lower-case (str shape-str))]
    (swap! (:state turtle) assoc :shape (case s "circle" :circle :classic)))
  (repaint-window (:window @(:state turtle)))
  nil)

(defn turtle-goto [turtle x y]
  (let [ts @(:state turtle)
        win (:window ts)
        ws @(:state win)
        ctx (:ctx ws)
        nx (double x)
        ny (double y)
        next-ts (assoc ts :x nx :y ny)
        [sx sy] (canvas-coords ws ts)
        [ex ey] (canvas-coords ws next-ts)]
    (when (:pen-down? ts)
      (stroke-segment! ctx ts sx sy ex ey))
    (swap! (:state turtle) assoc :x nx :y ny)
    (when (:filling? ts)
      (swap! (:state turtle) update :fill-points conj [nx ny]))
    (repaint-window win)
    (let [delay (speed-delay (:speed ts))]
      (when (pos? delay)
        (window-sleep win delay))))
  nil)

(defn turtle-circle [turtle radius]
  (let [ts @(:state turtle)
        win (:window ts)
        ws @(:state win)
        ctx (:ctx ws)
        [cx cy] (canvas-coords ws ts)]
    (when (:pen-down? ts)
      (set! (.-strokeStyle ctx) (parse-color (:color ts)))
      (set! (.-lineWidth ctx) (:pen-size ts))
      (.beginPath ctx)
      (.arc ctx cx cy (js/Math.abs radius) 0 (* 2 js/Math.PI))
      (.stroke ctx))
    (repaint-window win)
    (let [delay (speed-delay (:speed ts))]
      (when (pos? delay)
        (window-sleep win delay))))
  nil)

(defn turtle-begin-fill [turtle]
  (let [ts @(:state turtle)]
    (swap! (:state turtle) assoc
           :filling? true
           :fill-points [[(:x ts) (:y ts)]]
           :fill-color (:color ts)))
  nil)

(defn turtle-end-fill [turtle]
  (let [ts @(:state turtle)
        win (:window ts)
        ws @(:state win)
        ctx (:ctx ws)
        points (:fill-points ts)]
    (when (and (:filling? ts) (>= (count points) 3))
      (let [[fx fy] (first points)
            [sx sy] (canvas-coords ws {:x fx :y fy})]
        (set! (.-fillStyle ctx) (parse-color (:fill-color ts)))
        (.beginPath ctx)
        (.moveTo ctx sx sy)
        (doseq [[px py] (rest points)]
          (let [[cx cy] (canvas-coords ws {:x px :y py})]
            (.lineTo ctx cx cy)))
        (.closePath ctx)
        (.fill ctx)))
    (swap! (:state turtle) assoc :filling? false :fill-points [])
    (repaint-window win))
  nil)

(defn turtle-hide [turtle]
  (swap! (:state turtle) assoc :visible? false)
  (repaint-window (:window @(:state turtle)))
  nil)

(defn turtle-show [turtle]
  (swap! (:state turtle) assoc :visible? true)
  (repaint-window (:window @(:state turtle)))
  nil)
