(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(def code "class T feature r() do
  let a = true
  let b = false
end end")

(let [ast (p/ast code)
      ctx (interp/make-context)
      _ (interp/register-class ctx (first (:classes ast)))
      method-body (-> ast :classes first :body first :members first :body)
      method-env (interp/make-env (:globals ctx))
      ctx-with-env (assoc ctx :current-env method-env)]

  ;; Execute statements
  (interp/eval-node ctx-with-env (nth method-body 0))
  (interp/eval-node ctx-with-env (nth method-body 1))

  (println "Environment:" @(:bindings method-env))
  (println "\nKeys in environment:")
  (doseq [k (keys @(:bindings method-env))]
    (println "  Key:" k "Type:" (type k) "Class:" (class k)))

  (println "\nTrying to look up 'b':")
  (println "  Looking up with string \"b\":")
  (try
    (println "  Result:" (interp/env-lookup method-env "b"))
    (catch Exception e
      (println "  Error:" (.getMessage e))))

  (println "\n  Looking up with symbol 'b:")
  (try
    (println "  Result:" (interp/env-lookup method-env 'b))
    (catch Exception e
      (println "  Error:" (.getMessage e)))))
