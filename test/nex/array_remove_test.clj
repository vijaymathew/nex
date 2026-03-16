(ns nex.array-remove-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.repl :as repl]))

(deftest array-remove-removes-by-index
  (testing "Array.remove mutates by index rather than trying to remove the index value"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "let names: Array[String] := [\"Alice\", \"Bob\"]")
                   (repl/eval-code ctx "names.add(\"Carol\")")
                   (repl/eval-code ctx "names.remove(names.length - 1)")
                   (repl/eval-code ctx "names"))]
      (is (str/includes? output "[\"Alice\", \"Bob\"]"))
      (is (not (str/includes? output "[\"Alice\", \"Bob\", \"Carol\"]"))))))
