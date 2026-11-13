(ns nex.repl-vars-display-test
  "`:vars` showed each value with Clojure's pr-str, so an array read
   [\"a\" \"b\"] (no commas) and a map {\"a\" 1}, unlike the REPL's own echo of
   the same value. It now formats values exactly as the echo does."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.repl :as repl]))

(defn- vars-lines
  "Evaluate INPUTS in one fresh REPL session, then run :vars; return the
   `• name = value` lines it printed, keyed by name."
  [& inputs]
  (let [ctx (repl/init-repl-context)
        _ (with-out-str (doseq [input inputs] (repl/eval-code ctx input)))
        out (with-out-str (repl/handle-command ctx ":vars"))]
    (into {}
          (keep (fn [line]
                  (when-let [[_ name value] (re-matches #"\s*• (\S+) = (.*)" line)]
                    [name value])))
          (str/split-lines out))))

(deftest vars-shows-values-as-the-repl-echoes-them-test
  (testing "collections, scalars and objects read the same in :vars as in the echo"
    (let [vars (vars-lines "let greetings: Array[String] := [\"Hello\", \"Привет\", \"こんにちは\"]"
                           "let ages := {\"ann\": 31}"
                           "let nested := [[1, 2], [3]]"
                           "let name := \"Ada\""
                           "let c := #a"
                           "let nothing: ?String := nil"
                           "class Named create make() do end feature to_string(): String do result := \"named!\" end end"
                           "let q := create Named.make")]
      (is (= "[\"Hello\", \"Привет\", \"こんにちは\"]" (get vars "greetings")) (pr-str vars))
      (is (= "{\"ann\": 31}" (get vars "ages")) (pr-str vars))
      (is (= "[[1, 2], [3]]" (get vars "nested")) (pr-str vars))
      (is (= "\"Ada\"" (get vars "name")) (pr-str vars))
      (is (= "#a" (get vars "c")) (pr-str vars))
      (is (= "nil" (get vars "nothing")) (pr-str vars))
      (is (= "named!" (get vars "q")) (pr-str vars)))))
