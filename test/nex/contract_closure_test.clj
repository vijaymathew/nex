(ns nex.contract-closure-test
  "A lambda in a contract or rescue clause that uses the routine's own
   arguments. The closure rewrite used to skip `require`/`ensure` (and a free
   function's clauses, which live on its wrapper's callN method), so the
   compiled backend either failed with \"Unable to infer expression type
   during lowering\" or built the lambda without its captures, reading 0."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "contract_closure" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) opts))))
      (finally (.delete f)))))

(def ^:private program
  "function fit(xs: Array[String], n: Integer): Array[String]
require
  all_fit: xs.all(fn (w) => w.length <= n)
do
  result := xs
ensure
  still_fit: result.all(fn (w) => w.length <= n)
end

function risky(n: Integer): Integer do
  raise \"boom\"
rescue
  result := [1, 2].fold(0, fn (acc, x: Integer): Integer do result := acc + x * n end)
end

class Box
create
  make(limit: Integer)
  require
    sensible: [1, 2].all(fn (x) => x <= limit)
  do
    this.limit := limit
  end
feature
  limit: Integer

  fits(xs: Array[Integer], extra: Integer): Boolean
  require
    small: xs.all(fn (x) => x + extra <= limit)
  do
    result := true
  ensure
    seen: xs.all(fn (x) => x <= limit + extra)
  end
end

print(fit([\"ab\", \"c\"], 3))
print(risky(10))
print(create Box.make(5).fits([1, 2], 3))
let refused: String := \"\"
do
  fit([\"abcd\"], 3)
rescue
  refused := \"\" + exception
end
print(refused)")

(def ^:private expected
  ["[\"ab\", \"c\"]" "30" "true" "\"Precondition violation: all_fit\""])

(deftest lambdas-in-contracts-capture-the-routine-arguments
  (testing "compiled"
    (is (= expected (run program {}))))
  (testing "interpreted"
    (is (= expected (run program {:interpret? true})))))

(def ^:private invariant-program
  "class Bag
create
  make do xs := [1, 2] end
feature
  xs: Array[Integer]
  twice(n: Integer): Integer do result := n * 2 end
  add(n: Integer) do xs.add(n) end
invariant
  small: xs.all(fn (x: Integer): Boolean do result := twice(x) < 10 end)
  also_small: xs.all(fn (x: Integer): Boolean do result := this.twice(x) < 10 end)
end

let b := create Bag.make
b.add(3)
print(b.xs)
do
  b.add(7)
rescue
  print(exception.to_string)
end")

(def ^:private invariant-expected
  ["[1, 2, 3]" "\"Class invariant violation: small\""])

(deftest lambdas-in-a-class-invariant-reach-the-object
  ;; The closure rewrite skipped the class invariant, so a lambda there that
  ;; called the class's own routine, bare or through `this.`, resolved it
  ;; against the lambda's own class and failed to compile.
  (testing "compiled"
    (is (= invariant-expected (run invariant-program {}))))
  (testing "interpreted"
    (is (= invariant-expected (run invariant-program {:interpret? true})))))
