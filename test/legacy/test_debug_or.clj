(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(def code "class T feature r() do
  let a = true
  let b = false
  let x = a or b
end end")

(println "Testing or expression with detailed debugging")

(let [ast (p/ast code)
      ctx (interp/make-context)
      _ (interp/register-class ctx (first (:classes ast)))
      method-body (-> ast :classes first :body first :members first :body)
      method-env (interp/make-env (:globals ctx))
      ctx-with-env (assoc ctx :current-env method-env)]

  (println "\nMethod body:")
  (doseq [[i stmt] (map-indexed vector method-body)]
    (println (str "  " i ":") stmt))

  (println "\nExecuting statements...")

  ;; Execute first two statements
  (println "\n1. let a = true")
  (interp/eval-node ctx-with-env (nth method-body 0))
  (println "   Environment:" @(:bindings method-env))

  (println "\n2. let b = false")
  (interp/eval-node ctx-with-env (nth method-body 1))
  (println "   Environment:" @(:bindings method-env))

  (println "\n3. let x = a or b")
  (println "   Third statement:" (nth method-body 2))
  (println "   Value to evaluate:" (:value (nth method-body 2)))

  (try
    (println "\n   Evaluating value expression...")
    (let [value-expr (:value (nth method-body 2))]
      (println "   Expression type:" (:type value-expr))
      (println "   Operator:" (:operator value-expr))
      (println "   Left:" (:left value-expr))
      (println "   Right:" (:right value-expr))

      (println "\n   Evaluating left side...")
      (let [left-val (interp/eval-node ctx-with-env (:left value-expr))]
        (println "   Left value:" left-val)

        (println "\n   Current environment before evaluating right:")
        (println "   " @(:bindings method-env))
        (println "   Current env in ctx:" @(:bindings (:current-env ctx-with-env)))

        (println "\n   Evaluating right side...")
        (let [right-val (interp/eval-node ctx-with-env (:right value-expr))]
          (println "   Right value:" right-val))))

    (catch Exception e
      (println "\n   Error:" (.getMessage e))
      (println "   Error data:" (ex-data e)))))
