(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(def code "class T feature r() do
  let a = true
  let b = false
  let x = a or b
  print(x)
end end")

(println "Testing simple or expression")
(println "Code:" code)

(let [ast (p/ast code)
      ctx (interp/make-context)
      _ (interp/register-class ctx (first (:classes ast)))
      method-body (-> ast :classes first :body first :members first :body)
      method-env (interp/make-env (:globals ctx))
      ctx-with-env (assoc ctx :current-env method-env)]

  (println "\nExecuting...")
  (try
    (doseq [stmt method-body]
      (interp/eval-node ctx-with-env stmt))

    (println "\nOutput:")
    (doseq [line @(:output ctx-with-env)]
      (println line))

    (println "\nEnvironment:")
    (clojure.pprint/pprint @(:bindings method-env))

    (catch Exception e
      (println "\nError:" (.getMessage e))
      (clojure.pprint/pprint (ex-data e)))))
