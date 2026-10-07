(ns nex.class-invariant-typecheck-test
  "nex.typechecker/check-class-invariants! read each clause's expression as
   `:expr`, but the parser stores it under `:condition`, as for require and
   ensure. So no class invariant was ever type-checked: an undefined name, a
   non-Boolean clause, a Real compared with an Integer, or a call on a
   detachable field all passed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- type-check-errors
  "Formatted type errors with the location prefix stripped."
  [code]
  (let [result (tc/type-check (p/ast code))]
    (map #(str/replace % #"^Type error at line \d+, column \d+: " "")
         (map tc/format-type-error (:errors result)))))

(defn- type-checks? [code]
  (let [r (tc/type-check (p/ast code))]
    (and (:success r) (empty? (:errors r)))))

(def ^:private box
  "class Box feature n: Integer create make do n := 1 end end\n")

(deftest undefined-name-in-invariant-rejected
  (is (= ["Undefined variable: zzz"]
         (type-check-errors "class H feature b: Integer invariant pos: zzz > 0 end"))))

(deftest non-boolean-invariant-rejected
  (is (some #(str/includes? % "Invariant must be Boolean, got Integer")
            (type-check-errors "class H feature b: Integer invariant pos: b + 1 end"))))

(deftest invariant-operands-are-type-checked
  (testing "a Real compared with an Integer is the same error it is anywhere else"
    (is (some #(str/includes? % "Comparison requires compatible types")
              (type-check-errors "class H feature r: Real invariant ok: r >= 0 end")))))

(deftest invariant-error-carries-its-location
  (let [r (tc/type-check (p/ast "class H\n  feature b: Integer\n  invariant\n    pos: zzz > 0\nend"))]
    (is (some #(re-find #"^Type error at line 4, column \d+: Undefined variable: zzz" %)
              (map tc/format-type-error (:errors r))))))

(deftest detachable-field-in-invariant-needs-object-test
  (testing "a nil check does not narrow a field here either"
    (is (some #(str/includes? % "Cannot call feature 'n' on detachable field 'b'")
              (type-check-errors (str box "class H feature b: ?Box invariant pos: b /= nil and b.n > 0 end")))))
  (testing "the object test does"
    (is (type-checks? (str box "class H feature b: ?Box invariant pos: ?b as bb and bb.n > 0 end")))))

(deftest well-typed-invariants-accept
  (is (type-checks? (str box "class H
  feature
    count: Integer
    rate: Real
    b: ?Box
  create make do count := 0 rate := 0.5 end
  invariant
    valid_count: count >= 0
    valid_rate: rate >= 0.0 and rate <= 1.0
    detached_or_positive: b = nil or b /= nil
end"))))
