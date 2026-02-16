(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(def code "class Test feature run() do
  let a = 5
  let b = 10
  let greater = a > b
  let equal = a = b
  let result = greater or equal
  print(greater, equal, result)
end end")

(println "Testing boolean operations")
(println "Code:" code)

(let [ast (p/ast code)
      ctx (interp/make-context)
      _ (interp/register-class ctx (first (:classes ast)))
      method-body (-> ast :classes first :body first :members first :body)
      method-env (interp/make-env (:globals ctx))
      ctx-with-env (assoc ctx :current-env method-env)]

  (println "\nMethod body:")
  (clojure.pprint/pprint method-body)

  (println "\nExecuting...")
  (try
    (doseq [[i stmt] (map-indexed vector method-body)]
      (println (str "\nStep " (inc i) ":") (:type stmt))
      (when (= (:type stmt) :let)
        (println "  Variable:" (:name stmt))
        (println "  Value:" (:value stmt)))
      (let [result (interp/eval-node ctx-with-env stmt)]
        (println "  Result:" result))
      (println "  Env:" (keys @(:bindings method-env))))

    (println "\nFinal Output:")
    (doseq [line @(:output ctx-with-env)]
      (println line))

    (catch Exception e
      (println "\nError:" (.getMessage e))
      (clojure.pprint/pprint (ex-data e)))))
