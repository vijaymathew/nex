#!/usr/bin/env clojure

(require '[clojure.test :as test])
(require '[nex.interpreter :as interpreter])

(def integration-test-namespaces
  '[nex.compiler.jvm.cli-integration])

;; Load all configured integration namespaces explicitly.
(doseq [ns-sym integration-test-namespaces]
  (require ns-sym))

(println "╔════════════════════════════════════════════════════════════╗")
(println "║               RUNNING INTEGRATION TESTS                   ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

(let [results (apply test/run-tests integration-test-namespaces)]
  (interpreter/shutdown-runtime!)
  (println)
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║              INTEGRATION TEST SUMMARY                     ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println)
  (println "Namespaces:" (count integration-test-namespaces))
  (println "Total tests:" (:test results))
  (println "Passed:" (:pass results))
  (println "Failed:" (:fail results))
  (println "Errors:" (:error results))
  (println)
  (when (or (pos? (:fail results)) (pos? (:error results)))
    (System/exit 1)))
