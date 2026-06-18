(ns nex.platform-diff
  "Cross-platform differential-harness runner (cljs / Node side).

   Reads target/platform_corpus.edn (produced by
   test/scripts/gen_platform_corpus.clj on the JVM), replays each program's AST
   through the ClojureScript interpreter, and asserts the captured output
   matches the golden output the JVM interpreter produced. A mismatch — or a
   thrown error — fails the process, so the single interpreter is held to the
   same observable behaviour on both host platforms.

   Run with:  node target/platform_diff.js   (after `shadow-cljs compile platform-diff`)."
  (:require [cljs.reader :as reader]
            [nex.interpreter :as interp]))

(defn- run-cljs
  "Evaluate an entry's program AST through the cljs interpreter and return the
   captured output vector (or an error marker)."
  [entry]
  (try
    (let [ctx (interp/make-context)]
      (interp/eval-node ctx (:ast entry))
      {:name (:name entry) :got (vec @(:output ctx)) :expected (:output entry)})
    (catch :default _
      ;; Match the JVM generator's ::raised marker so "both hosts raise" passes.
      {:name (:name entry) :got :gen-platform-corpus/raised :expected (:output entry)})))

(defn -main [& _]
  (let [fs (js/require "fs")
        entries (reader/read-string (.readFileSync fs "target/platform_corpus.edn" "utf8"))
        results (mapv run-cljs entries)
        fails (remove #(= (:got %) (:expected %)) results)]
    (println "Platform differential harness (JVM interpreter vs cljs interpreter):")
    (doseq [{:keys [name got expected]} results]
      (if (= got expected)
        (println (str "  PASS  " name))
        (println (str "  FAIL  " name
                      "\n          expected " (pr-str expected)
                      "\n          got      " (pr-str got)))))
    (println (str (- (count results) (count fails)) "/" (count results) " passed"))
    (when (seq fails)
      (js/process.exit 1))))
