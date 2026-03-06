(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║      GROUPED PARAMETER SYNTAX DEMONSTRATION                ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "The Nex language now supports declaring multiple parameters")
(println "of the same type together using comma-separated syntax:")
(println)
(println "  Traditional:  gcd(a: Integer, b: Integer)")
(println "  New syntax:   gcd(a, b: Integer)")
(println)

;; Example 1: Point class with grouped parameters
(def point-code "class Point
  constructors
    make(x, y: Integer)
      do
        let x := x
        let y := y
      end

  feature
    x: Integer
    y: Integer

  feature
    distance(p1_x, p1_y, p2_x, p2_y: Integer) do
      let dx := p2_x - p1_x
      let dy := p2_y - p1_y
      let dist := dx * dx + dy * dy
      print(dist)
    end
end")

(println "═══════════════════════════════════════════════════════════")
(println "Example 1: Point Class with Grouped Parameters")
(println "═══════════════════════════════════════════════════════════")
(println)
(println point-code)
(println)

(let [ast (p/ast point-code)
      class-def (first (:classes ast))]
  (println "Parsed Structure:")
  (println)

  ;; Show constructor parameters
  (let [ctor (-> class-def :body first :constructors first)]
    (println "Constructor: make")
    (println "  Parameters:")
    (doseq [param (:params ctor)]
      (println "    •" (:name param) ":" (:type param))))

  (println)

  ;; Show method parameters (third element in body is second feature section)
  (let [method (-> class-def :body (nth 2) :members first)]
    (println "Method: distance")
    (println "  Parameters:")
    (doseq [param (:params method)]
      (println "    •" (:name param) ":" (:type param)))))

(println)

;; Example 2: Rectangle with mixed parameter types
(def rect-code "class Rectangle
  feature
    area(width, height: Integer) do
      print(width * height)
    end

    describe(width, height: Integer, label: String) do
      print(label, width, height)
    end
end")

(println "═══════════════════════════════════════════════════════════")
(println "Example 2: Rectangle Class with Mixed Parameters")
(println "═══════════════════════════════════════════════════════════")
(println)
(println rect-code)
(println)

(let [ast (p/ast rect-code)
      class-def (first (:classes ast))
      methods (-> class-def :body first :members)]
  (println "Parsed Structure:")
  (println)

  (doseq [method methods]
    (println "Method:" (:name method))
    (println "  Parameters:")
    (doseq [param (:params method)]
      (println "    •" (:name param) ":" (:type param)))
    (println)))

;; Example 3: GCD function exactly as specified
(def gcd-code "class Math
  feature
    gcd(a, b: Integer) do
      from
        let x := a
        let y := b
      invariant
        x_positive: x > 0
        y_positive: y > 0
      variant
        x + y
      until
        x = y
      do
        if x > y then
          x := x - y
        else
          y := y - x
        end
      end
      print(x)
    end
end")

(println "═══════════════════════════════════════════════════════════")
(println "Example 3: GCD with Grouped Parameter Syntax")
(println "═══════════════════════════════════════════════════════════")
(println)
(println "Method signature: gcd(a, b: Integer)")
(println)

(let [ast (p/ast gcd-code)
      class-def (first (:classes ast))
      method (-> class-def :body first :members first)]
  (println "Parsed Parameters:")
  (doseq [param (:params method)]
    (println "  •" (:name param) ":" (:type param))))

(println)
(println "Execution: gcd(48, 18)")

(let [ast (p/ast gcd-code)
      ctx (interp/make-context)
      _ (interp/register-class ctx (first (:classes ast)))
      method-def (-> ast :classes first :body first :members first)
      method-env (interp/make-env (:globals ctx))
      _ (do
          (interp/env-define method-env "a" 48)
          (interp/env-define method-env "b" 18))
      ctx-with-env (assoc ctx :current-env method-env)]
  (doseq [stmt (:body method-def)]
    (interp/eval-node ctx-with-env stmt))
  (println "result:" (first @(:output ctx-with-env))))

(println)
(println "╔════════════════════════════════════════════════════════════╗")
(println "║                 DEMONSTRATION COMPLETE                     ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Benefits of Grouped Parameter Syntax:")
(println "  ✓ More concise code")
(println "  ✓ Clearer intent when parameters are related")
(println "  ✓ Works with methods and constructors")
(println "  ✓ Can mix grouped and individual parameters")
(println "  ✓ Fully compatible with existing syntax")
