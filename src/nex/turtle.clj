(ns nex.turtle
  "Turtle graphics for Nex – Window and Turtle built-in types.
   All Swing/AWT rendering logic lives here so the interpreter
   never needs to call Java static methods directly."
  (:import [java.awt Color Graphics2D BasicStroke RenderingHints]
           [java.awt.image BufferedImage]
           [java.awt.geom AffineTransform Path2D$Double Ellipse2D$Double]
           [javax.swing JFrame JLabel SwingUtilities]
           [java.util.concurrent CopyOnWriteArrayList]))

;;
;; Color Helpers
;;

(def named-colors
  {"black"   (Color. 0 0 0)
   "white"   (Color. 255 255 255)
   "red"     (Color. 255 0 0)
   "green"   (Color. 0 128 0)
   "blue"    (Color. 0 0 255)
   "yellow"  (Color. 255 255 0)
   "orange"  (Color. 255 165 0)
   "purple"  (Color. 128 0 128)
   "cyan"    (Color. 0 255 255)
   "magenta" (Color. 255 0 255)
   "brown"   (Color. 139 69 19)
   "pink"    (Color. 255 192 203)
   "gray"    (Color. 128 128 128)
   "grey"    (Color. 128 128 128)})

(defn parse-color
  "Parse a color string. Accepts named colors or hex (#RRGGBB)."
  [s]
  (let [s (clojure.string/lower-case (clojure.string/trim s))]
    (or (get named-colors s)
        (try (Color/decode s) (catch Exception _ (Color. 0 0 0))))))

;;
;; Speed / Delay
;;

(defn speed-delay
  "Convert a speed value (0-10) to a sleep delay in ms.
   0 = instant (no delay), 1 = slowest, 10 = fastest non-zero."
  [speed]
  (cond
    (<= speed 0) 0
    (>= speed 10) 5
    :else (long (/ 200 speed))))

;;
;; Drawing the turtle cursor (overlay, not on canvas)
;;

(defn draw-turtle-cursor
  "Draw a turtle cursor on the given Graphics2D.
   shape is :classic (triangle) or :circle."
  [^Graphics2D g2d x y heading color shape]
  (let [saved (.getTransform g2d)]
    (.translate g2d (double x) (double y))
    (.rotate g2d (Math/toRadians heading))
    (.setColor g2d color)
    (case shape
      :circle
      (let [r 6]
        (.fill g2d (Ellipse2D$Double. (- r) (- r) (* 2 r) (* 2 r))))
      ;; default: classic triangle pointing right (heading 0 = east)
      (let [path (Path2D$Double.)]
        (.moveTo path 12.0 0.0)
        (.lineTo path -6.0 -7.0)
        (.lineTo path -6.0 7.0)
        (.closePath path)
        (.fill g2d path)))
    (.setTransform g2d saved)))

;;
;; Window
;;

(defn create-window
  "Create a Window state map.  The JFrame, label, and canvas are created
   but the frame is not yet visible (call show-window)."
  ([] (create-window "Nex Turtle Graphics" 800 600))
  ([title] (create-window title 800 600))
  ([title w h]
   (let [canvas (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)
         turtles (CopyOnWriteArrayList.)
         state  (atom {:width w :height h :bg-color (Color. 255 255 255)
                       :canvas canvas :turtles turtles
                       :frame nil :label nil})
         ;; Fill canvas with white
         g2d   (.createGraphics canvas)]
     (.setColor g2d (Color. 255 255 255))
     (.fillRect g2d 0 0 w h)
     (.dispose g2d)
     ;; Create JLabel proxy that paints canvas + turtle cursors
     (let [label (proxy [JLabel] []
                   (paintComponent [^java.awt.Graphics g]
                     (let [g2d (.create ^java.awt.Graphics g)
                           s   @state]
                       (.drawImage ^Graphics2D g2d ^BufferedImage (:canvas s) 0 0 nil)
                       ;; Draw turtle cursors
                       (.setRenderingHint ^Graphics2D g2d
                                          RenderingHints/KEY_ANTIALIASING
                                          RenderingHints/VALUE_ANTIALIAS_ON)
                       (doseq [t (.toArray ^CopyOnWriteArrayList (:turtles s))]
                         (let [ts @(:state t)]
                           (when (:visible? ts)
                             (let [cx (+ (/ (:width s) 2.0) (:x ts))
                                   cy (- (/ (:height s) 2.0) (:y ts))]
                               (draw-turtle-cursor ^Graphics2D g2d
                                                   cx cy
                                                   (- (:heading ts))
                                                   (parse-color (:color ts))
                                                   (:shape ts))))))
                       (.dispose g2d))))
           frame (JFrame. (str title))]
       (.setPreferredSize label (java.awt.Dimension. w h))
       (.add (.getContentPane frame) label)
       (.pack frame)
       (.setLocationRelativeTo frame nil)
       (.setDefaultCloseOperation frame JFrame/DISPOSE_ON_CLOSE)
       (swap! state assoc :frame frame :label label)
       {:nex-builtin-type :Window :state state}))))

(defn show-window [win]
  (let [s @(:state win)]
    (SwingUtilities/invokeLater
     #(.setVisible ^JFrame (:frame s) true)))
  nil)

(defn close-window [win]
  (let [s @(:state win)]
    (SwingUtilities/invokeLater
     #(.dispose ^JFrame (:frame s))))
  nil)

(defn set-bgcolor [win color-str]
  (let [color (parse-color color-str)
        s     (swap! (:state win) assoc :bg-color color)
        ^BufferedImage canvas (:canvas s)
        g2d   (.createGraphics canvas)]
    (.setColor g2d color)
    (.fillRect g2d 0 0 (:width s) (:height s))
    (.dispose g2d)
    (.repaint ^JLabel (:label s)))
  nil)

(defn repaint-window [win]
  (let [s @(:state win)]
    (.repaint ^JLabel (:label s))))

;;
;; Turtle
;;

(defn create-turtle
  "Create a Turtle attached to the given Window."
  [win]
  (let [turtle {:nex-builtin-type :Turtle
                :state (atom {:window win
                              :x 0.0 :y 0.0
                              :heading 90.0    ;; 90 = north (turtle-style)
                              :pen-down? true
                              :color "black"
                              :pen-size 1
                              :speed 6
                              :shape :classic
                              :visible? true
                              :filling? false
                              :fill-points []
                              :fill-color "black"})}]
    ;; Register turtle with window so cursor overlay works
    (let [ws @(:state win)]
      (.add ^CopyOnWriteArrayList (:turtles ws) turtle))
    (repaint-window win)
    turtle))

(defn- canvas-coords
  "Convert turtle (x,y) to canvas pixel coordinates."
  [state win-state]
  [(+ (/ (:width win-state) 2.0) (:x state))
   (- (/ (:height win-state) 2.0) (:y state))])

(defn turtle-forward [turtle dist]
  (let [ts       @(:state turtle)
        win      (:window ts)
        ws       @(:state win)
        rad      (Math/toRadians (:heading ts))
        dx       (* dist (Math/cos rad))
        dy       (* dist (Math/sin rad))
        new-x    (+ (:x ts) dx)
        new-y    (+ (:y ts) dy)]
    ;; Draw line on canvas if pen is down
    (when (:pen-down? ts)
      (let [^BufferedImage canvas (:canvas ws)
            g2d (.createGraphics canvas)
            [sx sy] (canvas-coords ts ws)
            new-ts (assoc ts :x new-x :y new-y)
            [ex ey] (canvas-coords new-ts ws)]
        (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING
                           RenderingHints/VALUE_ANTIALIAS_ON)
        (.setColor g2d (parse-color (:color ts)))
        (.setStroke g2d (BasicStroke. (float (:pen-size ts))
                                     BasicStroke/CAP_ROUND
                                     BasicStroke/JOIN_ROUND))
        (.drawLine g2d (int sx) (int sy) (int ex) (int ey))
        (.dispose g2d)))
    ;; Update position
    (swap! (:state turtle) assoc :x new-x :y new-y)
    ;; Record fill point
    (when (:filling? ts)
      (swap! (:state turtle) update :fill-points conj [new-x new-y]))
    ;; Repaint and delay
    (repaint-window win)
    (let [delay (speed-delay (:speed ts))]
      (when (pos? delay) (Thread/sleep delay))))
  nil)

(defn turtle-backward [turtle dist]
  (turtle-forward turtle (- dist)))

(defn turtle-right [turtle angle]
  (swap! (:state turtle) update :heading #(- % angle))
  (let [win (:window @(:state turtle))]
    (repaint-window win))
  nil)

(defn turtle-left [turtle angle]
  (swap! (:state turtle) update :heading #(+ % angle))
  (let [win (:window @(:state turtle))]
    (repaint-window win))
  nil)

(defn turtle-penup [turtle]
  (swap! (:state turtle) assoc :pen-down? false)
  nil)

(defn turtle-pendown [turtle]
  (swap! (:state turtle) assoc :pen-down? true)
  nil)

(defn turtle-color [turtle color-str]
  (swap! (:state turtle) assoc :color color-str :fill-color color-str)
  nil)

(defn turtle-pensize [turtle size]
  (swap! (:state turtle) assoc :pen-size size)
  nil)

(defn turtle-speed [turtle spd]
  (swap! (:state turtle) assoc :speed spd)
  nil)

(defn turtle-shape [turtle shape-str]
  (let [s (clojure.string/lower-case shape-str)]
    (swap! (:state turtle) assoc :shape (case s "circle" :circle :classic)))
  nil)

(defn turtle-goto [turtle x y]
  (let [ts       @(:state turtle)
        win      (:window ts)
        ws       @(:state win)]
    ;; Draw line if pen is down
    (when (:pen-down? ts)
      (let [^BufferedImage canvas (:canvas ws)
            g2d (.createGraphics canvas)
            [sx sy] (canvas-coords ts ws)
            new-ts (assoc ts :x (double x) :y (double y))
            [ex ey] (canvas-coords new-ts ws)]
        (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING
                           RenderingHints/VALUE_ANTIALIAS_ON)
        (.setColor g2d (parse-color (:color ts)))
        (.setStroke g2d (BasicStroke. (float (:pen-size ts))
                                     BasicStroke/CAP_ROUND
                                     BasicStroke/JOIN_ROUND))
        (.drawLine g2d (int sx) (int sy) (int ex) (int ey))
        (.dispose g2d)))
    ;; Update position
    (swap! (:state turtle) assoc :x (double x) :y (double y))
    ;; Record fill point
    (when (:filling? ts)
      (swap! (:state turtle) update :fill-points conj [(double x) (double y)]))
    (repaint-window win)
    (let [delay (speed-delay (:speed ts))]
      (when (pos? delay) (Thread/sleep delay))))
  nil)

(defn turtle-circle [turtle radius]
  (let [ts       @(:state turtle)
        win      (:window ts)
        ws       @(:state win)
        segments 36
        angle-step (/ 360.0 segments)
        ;; The circle is drawn by moving the turtle in small arcs
        ^BufferedImage canvas (:canvas ws)
        g2d     (.createGraphics canvas)]
    (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING
                       RenderingHints/VALUE_ANTIALIAS_ON)
    (.setColor g2d (parse-color (:color ts)))
    (.setStroke g2d (BasicStroke. (float (:pen-size ts))
                                 BasicStroke/CAP_ROUND
                                 BasicStroke/JOIN_ROUND))
    (let [start-heading (:heading ts)]
      (loop [i 0
             cx (:x ts)
             cy (:y ts)
             h  start-heading]
        (when (< i segments)
          (let [new-h (+ h angle-step)
                rad   (Math/toRadians new-h)
                ;; Each step moves along the circle perimeter
                step-len (* 2.0 radius (Math/sin (Math/toRadians (/ angle-step 2.0))))
                ;; Direction of movement is perpendicular to radius
                move-rad (Math/toRadians (+ h (/ angle-step 2.0)))
                nx  (+ cx (* step-len (Math/cos move-rad)))
                ny  (+ cy (* step-len (Math/sin move-rad)))]
            (when (:pen-down? ts)
              (let [[sx sy] (canvas-coords {:x cx :y cy} ws)
                    [ex ey] (canvas-coords {:x nx :y ny} ws)]
                (.drawLine g2d (int sx) (int sy) (int ex) (int ey))))
            (when (:filling? @(:state turtle))
              (swap! (:state turtle) update :fill-points conj [nx ny]))
            (recur (inc i) nx ny new-h))))
      ;; Update turtle position and heading
      (let [final-ts @(:state turtle)
            ;; After a full circle the turtle returns to start position
            ;; but heading has rotated by 360 degrees (back to original)
            ]
        ;; For a circle, turtle ends up back where it started with same heading
        ;; but we advanced the heading through all segments
        nil))
    (.dispose g2d)
    (repaint-window win)
    (let [delay (speed-delay (:speed ts))]
      (when (pos? delay) (Thread/sleep delay))))
  nil)

(defn turtle-begin-fill [turtle]
  (let [ts @(:state turtle)]
    (swap! (:state turtle) assoc
           :filling? true
           :fill-points [[(:x ts) (:y ts)]]
           :fill-color (:color ts)))
  nil)

(defn turtle-end-fill [turtle]
  (let [ts       @(:state turtle)
        win      (:window ts)
        ws       @(:state win)
        points   (:fill-points ts)]
    (when (and (:filling? ts) (>= (count points) 3))
      (let [^BufferedImage canvas (:canvas ws)
            g2d (.createGraphics canvas)
            path (Path2D$Double.)]
        (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING
                           RenderingHints/VALUE_ANTIALIAS_ON)
        (let [[fx fy] (canvas-coords {:x (first (first points))
                                      :y (second (first points))} ws)]
          (.moveTo path fx fy))
        (doseq [[px py] (rest points)]
          (let [[cx cy] (canvas-coords {:x px :y py} ws)]
            (.lineTo path cx cy)))
        (.closePath path)
        (.setColor g2d (parse-color (:fill-color ts)))
        (.fill g2d path)
        (.dispose g2d)
        (repaint-window win)))
    (swap! (:state turtle) assoc :filling? false :fill-points []))
  nil)

(defn turtle-hide [turtle]
  (swap! (:state turtle) assoc :visible? false)
  (let [win (:window @(:state turtle))]
    (repaint-window win))
  nil)

(defn turtle-show [turtle]
  (swap! (:state turtle) assoc :visible? true)
  (let [win (:window @(:state turtle))]
    (repaint-window win))
  nil)
