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

(deftest array-add-returns-nil
  (testing "Array.add is a command-style mutator and returns nil"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "let names: Array[String] := [\"Alice\", \"Bob\"]")
                   (repl/eval-code ctx "names.add(\"Carol\")")
                   (repl/eval-code ctx "names"))]
      (is (not (str/includes? output "true")))
      (is (str/includes? output "[\"Alice\", \"Bob\", \"Carol\"]")))))

(deftest array-slice-and-reverse-behave-like-nex-arrays
  (testing "slice returns a Nex array value and reverse works as a zero-arg feature"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "let scores := [85, 92, 78, 95, 60]")
                   (repl/eval-code ctx "scores.slice(1, 4)")
                   (repl/eval-code ctx "scores.reverse"))]
      (is (str/includes? output "[92, 78, 95]"))
      (is (str/includes? output "[60, 95, 78, 92, 85]")))))

(deftest array-sort-supports-builtins-and-comparable-objects
  (testing "sort returns a new sorted array for builtins and Comparable user types"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "let scores := [10, 2, 3]")
                   (repl/eval-code ctx "scores.sort")
                   (repl/eval-code ctx "class Box inherit Comparable
  feature
    value: Integer
  create
    make(value: Integer) do
      this.value := value
    end
  feature
    compare(other: Box): Integer do
      result := value.compare(other.value)
    end
end")
                   (repl/eval-code ctx "let boxes := [create Box.make(7), create Box.make(2), create Box.make(5)]")
                   (repl/eval-code ctx "let sorted := boxes.sort")
                   (repl/eval-code ctx "print(sorted[0].value)")
                   (repl/eval-code ctx "print(sorted[1].value)")
                   (repl/eval-code ctx "print(sorted[2].value)"))]
      (is (str/includes? output "[2, 3, 10]"))
      (is (str/includes? output "2"))
      (is (str/includes? output "5"))
      (is (str/includes? output "7")))))
