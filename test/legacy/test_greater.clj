(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(def code "class T feature r() do let greater = 5 print(greater) end end")

(println "Testing 'greater' variable")
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
    (doseq [stmt method-body]
      (println "Executing:" (:type stmt))
      (interp/eval-node ctx-with-env stmt))

    (println "\nOutput:")
    (doseq [line @(:output ctx-with-env)]
      (println line))

    (println "\nEnvironment after execution:")
    (clojure.pprint/pprint @(:bindings method-env))

    (catch Exception e
      (println "Error:" (.getMessage e))
      (clojure.pprint/pprint (ex-data e)))))
