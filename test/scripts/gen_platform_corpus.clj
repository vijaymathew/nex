(ns gen-platform-corpus
  "Cross-platform differential-harness generator (JVM side).

   For every program under test/resources/platform_corpus/*.nex this parses the
   source and runs it through the JVM interpreter to capture the golden output,
   then serialises {:name, :ast, :output} to target/platform_corpus.edn. The
   cljs runner (src/nex/platform_diff.cljs) replays the same program AST through
   the ClojureScript interpreter and asserts the output matches — guarding the
   shipped JS runtime against behavioural divergence from the JVM.

   (This harness was originally built to compare the sync vs async evaluators;
   the async evaluator has since been removed, so it now validates the single
   interpreter across the two host platforms.)"
  (:require [clojure.java.io :as io]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(def ^:private corpus-dir "test/resources/platform_corpus")

(defn- run [ast]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx ast)
    (vec @(:output ctx))))

(defn -main [& _]
  (let [files (->> (.listFiles (io/file corpus-dir))
                   (filter #(.endsWith (.getName %) ".nex"))
                   (sort-by #(.getName %)))
        entries (mapv (fn [f]
                        (let [name (subs (.getName f) 0 (- (count (.getName f)) 4))
                              ast (p/ast (slurp f))]
                          {:name name :ast ast :output (run ast)}))
                      files)]
    (io/make-parents "target/platform_corpus.edn")
    (spit "target/platform_corpus.edn" (pr-str entries))
    (println "Wrote" (count entries) "corpus entries to target/platform_corpus.edn:")
    (doseq [{:keys [name output]} entries]
      (println (format "  %-24s -> %s" name (pr-str output))))))

;; Self-invoke when run as a script:
;; clojure -M:test test/scripts/gen_platform_corpus.clj
(-main)
