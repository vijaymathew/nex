(ns nex.generic-inference-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.interpreter :as interp]))

;; Generic-argument inference at construction (Finding 1 of
;; docs/proposals/generic-inference.md): `create Ok.make(5)` infers `Ok[Integer,
;; Any]`, and `Any` is a per-argument wildcard so it assigns to a concrete
;; instantiation. Concrete mismatches still error.

(defn- run [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)]
    (interp/eval-node ctx ast)
    @(:output ctx)))

(defn- type-error? [code]
  (not (:success (tc/type-check (p/ast code)))))

(def ^:private opt-decl
  "union Option[T]
  Some(value: T)
  None
end
")

(deftest infers-type-arg-from-constructor-argument
  (testing "create Some.make(42) infers Some[Integer] and interoperates"
    (is (= ["43"]
           (run (str opt-decl
                     "let a: Option[Integer] := create Some.make(42)
match a of
  when Some(value) then print(value + 1)
  when None        then print(-1)
end"))))))

(deftest unmentioned-param-becomes-any-wildcard
  (testing "a parameter the constructor does not fix stays Any and assigns to a concrete type"
    (is (= ["10" "true"]
           (run "sealed deferred class Result[T, E]
feature
  is_ok(): Boolean deferred
  unwrap_or(fallback: T): T deferred
end
class Ok[T, E] inherit Result[T, E]
feature
  value: T
  is_ok(): Boolean do result := true end
  unwrap_or(fallback: T): T do result := value end
create make(v: T) do value := v end
end
class Err[T, E] inherit Result[T, E]
feature
  error: E
  is_ok(): Boolean do result := false end
  unwrap_or(fallback: T): T do result := fallback end
create make(e: E) do error := e end
end
let r: Result[Integer, String] := create Ok.make(10)
print(r.unwrap_or(0))
print(r.is_ok())")))))

(deftest inference-from-concrete-arg-still-checks
  (testing "a concrete inferred argument that mismatches the target is rejected"
    (is (type-error?
         (str opt-decl
              "let a: Option[Integer] := create Some.make(\"text\")
print(0)")))))

(deftest genuine-generic-mismatch-still-errors
  (testing "Any wildcard does not mask a real Integer/String argument mismatch"
    (is (type-error?
         (str opt-decl
              "let a: Option[Integer] := create Some[Integer].make(1)
let b: Option[String] := a
print(0)")))))

(deftest explicit-args-still-authoritative
  (testing "explicit type arguments override inference"
    (is (= ["5"]
           (run (str opt-decl
                     "let a: Option[Integer] := create Some[Integer].make(5)
match a of
  when Some(value) then print(value)
  when None        then print(-1)
end"))))))
