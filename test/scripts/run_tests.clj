#!/usr/bin/env clojure

(require '[clojure.test :as test])
(require '[clojure.java.io :as io])

(defn test-file?
  [^java.io.File f]
  (and (.isFile f)
       (.endsWith (.getName f) "_test.clj")))

(defn ns-from-test-file
  [^java.io.File f]
  (let [content (slurp f)
        m (re-find #"\(ns\s+([^\s\)]+)" content)]
    (when-not m
      (throw (ex-info (str "Could not find ns declaration in " (.getPath f)) {})))
    (symbol (second m))))

(def all-test-namespaces
  (->> (file-seq (io/file "test/nex"))
       (filter test-file?)
       (sort-by #(.getPath ^java.io.File %))
       (map ns-from-test-file)
       vec))

;; Load all discovered test namespaces
(doseq [ns-sym all-test-namespaces]
  (require ns-sym))

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                    RUNNING ALL TESTS                       ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Run all tests
(let [results (apply test/run-tests all-test-namespaces)]
  (println)
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║                    TEST SUMMARY                            ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println)
  (println "Namespaces:" (count all-test-namespaces))
  (println "Total tests:" (:test results))
  (println "Passed:" (:pass results))
  (println "Failed:" (:fail results))
  (println "Errors:" (:error results))
  (println)
  (when (or (pos? (:fail results)) (pos? (:error results)))
    (System/exit 1)))
