#!/usr/bin/env clojure

(require '[clojure.java.shell :as sh])
(require '[clojure.test :as test])
(require '[nex.interpreter :as interpreter])

(def integration-test-namespaces
  '[nex.compiler.jvm.cli-integration])

(def performance-commands
  [{:label "compiled-repl micro performance"
    :argv ["clojure" "-M:test" "test/scripts/run_compiled_repl_perf.clj"
           "--iterations" "25" "--warmup" "5"]}
   {:label "compiled-repl soak performance"
    :argv ["clojure" "-M:test" "test/scripts/run_compiled_repl_soak_perf.clj"
           "--iterations" "8" "--warmup" "2"]}])

(defn run-command!
  [{:keys [label argv]}]
  (println "------------------------------------------------------------")
  (println label)
  (println "------------------------------------------------------------")
  (let [{:keys [exit out err]} (apply sh/sh argv)]
    (when (seq out)
      (print out))
    (when (seq err)
      (binding [*out* *err*]
        (print err)))
    {:label label
     :exit exit}))

;; Load all configured integration namespaces explicitly.
(doseq [ns-sym integration-test-namespaces]
  (require ns-sym))

(println "╔════════════════════════════════════════════════════════════╗")
(println "║               RUNNING INTEGRATION TESTS                   ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

(let [results (apply test/run-tests integration-test-namespaces)]
  (interpreter/shutdown-runtime!)
  (let [perf-results (mapv run-command! performance-commands)
        perf-failures (filter #(pos? (:exit %)) perf-results)]
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
  (println "Performance checks:" (count performance-commands))
  (println "Performance failures:" (count perf-failures))
  (println)
  (when (seq perf-failures)
    (doseq [{:keys [label exit]} perf-failures]
      (println (format "Performance check failed: %s (exit %d)" label exit)))
    (println))
  (when (or (pos? (:fail results)) (pos? (:error results)) (seq perf-failures))
    (System/exit 1))))
