(ns nex.union-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.interpreter :as interp]))

(defn- strip-dbg
  "Remove source-position metadata so ASTs compare on structure alone."
  [x]
  (walk/postwalk
   (fn [n]
     (if (map? n)
       (into {} (remove (fn [[k _]] (= "dbg" (namespace k))) n))
       n))
   x))

;; The concise `union` form desugars in the walker to the sealed-class AST Nex
;; already produces. These tests pin that equivalence (structural, runtime, and
;; the exhaustiveness guarantee) so the desugaring cannot silently drift.

(defn- run-output
  "Register all classes then execute top-level statements, returning printed output."
  [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)]
    (doseq [c (:classes ast)]
      (interp/register-class ctx c))
    (doseq [stmt (:statements ast)]
      (interp/eval-node ctx stmt))
    @(:output ctx)))

(deftest union-desugars-to-sealed-class-ast
  (testing "a union produces exactly the classes of the hand-written sealed form"
    (let [union-code "union Order
  Draft
  Placed(id: String, total: Real)
  Shipped(tracking: String, at: Integer)
end"
          ;; The hand-written equivalent. Payload-free variants get an explicit
          ;; nullary `make` — a constructor-less class cannot be instantiated,
          ;; so the desugaring must synthesize one.
          hand-code "sealed deferred class Order
end
class Draft inherit Order
  create make() do end
end
class Placed inherit Order
  feature
    id: String
    total: Real
  create make(arg__1: String, arg__2: Real) do id := arg__1 total := arg__2 end
end
class Shipped inherit Order
  feature
    tracking: String
    at: Integer
  create make(arg__1: String, arg__2: Integer) do tracking := arg__1 at := arg__2 end
end"
          union-classes (strip-dbg (:classes (p/ast union-code)))
          hand-classes (strip-dbg (:classes (p/ast hand-code)))]
      (is (= (count union-classes) (count hand-classes)))
      (is (= union-classes hand-classes)))))

(deftest union-parent-is-sealed-and-deferred
  (testing "the parent class is sealed + deferred, variants inherit it"
    (let [ast (p/ast "union Order\n  Draft\n  Placed(id: String)\nend")
          by-name (into {} (map (juxt :name identity) (:classes ast)))]
      (is (= true (:sealed? (by-name "Order"))))
      (is (= true (:deferred? (by-name "Order"))))
      (is (= [{:parent "Order"}] (:parents (by-name "Draft"))))
      (is (= [{:parent "Order"}] (:parents (by-name "Placed")))))))

(deftest union-runtime-construct-and-match
  (testing "variants construct and dispatch through match at runtime"
    (let [code "union Order
  Draft
  Placed(id: String, total: Real)
  Shipped(tracking: String, at: Integer)
end

let a: Order := create Draft.make()
let b: Order := create Placed.make(\"A-100\", 42.0)
let c: Order := create Shipped.make(\"Z9\", 3)
match a of
  when Draft   as d then print(\"draft\")
  when Placed  as p then print(p.id)
  when Shipped as s then print(s.tracking)
end
match b of
  when Draft   as d then print(\"draft\")
  when Placed  as p then print(p.id)
  when Shipped as s then print(s.tracking)
end
match c of
  when Draft   as d then print(\"draft\")
  when Placed  as p then print(p.id)
  when Shipped as s then print(s.tracking)
end"
          out (run-output code)]
      ;; the interpreter's print buffer keeps String values quoted
      (is (= ["\"draft\"" "\"A-100\"" "\"Z9\""] out)))))

(deftest union-payload-fields-are-readable
  (testing "payload entries become ordinary readable fields"
    (let [code "union Money
  Cash(amount: Integer)
end
let c: Money := create Cash.make(99)
match c of
  when Cash as k then print(k.amount)
end"
          out (run-output code)]
      (is (= ["99"] out)))))

(deftest union-match-exhaustiveness-enforced
  (testing "a match missing a variant is a compile-time error"
    (let [code "union Order
  Draft
  Placed(id: String)
  Shipped(tracking: String)
end

function describe(o: Order): String do
  result := \"?\"
  match o of
    when Draft  as d then result := \"draft\"
    when Placed as p then result := \"placed\"
  end
end
print(describe(create Draft.make()))"
          result (tc/type-check (p/ast code))]
      (is (not (:success result)))
      (is (some #(re-find #"Shipped" (str (:message %))) (:errors result))))))

(deftest union-is-a-soft-keyword
  (testing "`union` stays usable as a member name (Set.union) alongside the declaration"
    (let [code "union Tag
  A
  B
end
let s: Set[Integer] := #{1, 2}
let t: Set[Integer] := #{2, 3}
let u: Set[Integer] := s.union(t)
print(u.size())
let x: Tag := create A.make()
match x of
  when A as a then print(\"a\")
  when B as b then print(\"b\")
end"
          out (run-output code)]
      (is (= ["3" "\"a\""] out)))))

(deftest union-exhaustive-match-typechecks
  (testing "covering every variant passes the type checker"
    (let [code "union Order
  Draft
  Placed(id: String)
end

function describe(o: Order): String do
  result := \"?\"
  match o of
    when Draft  as d then result := \"draft\"
    when Placed as p then result := \"placed\"
  end
end
print(describe(create Draft.make()))"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))
