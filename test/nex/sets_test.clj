(ns nex.sets-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.repl :as repl]
            [nex.typechecker :as tc]))

(defn- execute-method-output [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [c (:classes ast)]
            (interp/register-class ctx c))
        class-def (last (:classes ast))
        method-def (-> class-def :body first :members first)
        method-env (interp/make-env (:globals ctx))
        obj (interp/make-object (:name class-def) {})
        _ (interp/env-define method-env "obj" obj)
        ctx-with-env (assoc ctx :current-env method-env)]
    (interp/eval-node ctx-with-env {:type :call
                                    :target "obj"
                                    :method (:name method-def)
                                    :args []})
    @(:output ctx-with-env)))

(deftest set-literal-parsing-and-typecheck
  (testing "Set literals parse and infer Set[T]"
    (let [ast (p/ast "class Test
  feature
    demo() do
      let s: Set[Integer] := #{0, 2, 4}
    end
end")
          set-expr (-> ast :classes first :body first :members first :body first :value)
          result (tc/type-check ast)]
      (is (= :set-literal (:type set-expr)))
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest empty-set-literal-parsing-and-typecheck
  (testing "Empty set literals parse with contextual type"
    (let [ast (p/ast "class Test
  feature
    demo() do
      let s: Set[Integer] := #{}
    end
end")
          set-expr (-> ast :classes first :body first :members first :body first :value)
          result (tc/type-check ast)]
      (is (= :set-literal (:type set-expr)))
      (is (empty? (:elements set-expr)))
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest set-runtime-operations
  (testing "Set literals, printing, and core operations work at runtime"
    (let [code "class Test
  feature
    demo() do
      let s: Set[Integer] := #{0, 2, 5}
      print(s)
      print(s.contains(2))
      print(s.union(#{3}))
      print(#{1, 2}.difference(#{2, 3}))
      print(#{1, 2}.intersection(#{2, 3}))
      print(#{1, 2}.symmetric_difference(#{2, 3}))
    end
end"
          output (execute-method-output code)]
      (is (= ["#{0, 2, 5}" "true" "#{0, 2, 5, 3}" "#{1}" "#{2}" "#{1, 3}"] output)))))

(deftest set-to-array-runtime
  (testing "Set.to_array returns elements as an array in insertion order"
    (let [code "class Test
  feature
    demo() do
      let s: Set[Integer] := #{0, 2, 5}
      let a: Array[Integer] := s.to_array()
      print(a)
      print(a.length())
      print(a.get(1))
    end
end"
          output (execute-method-output code)]
      (is (= ["[0, 2, 5]" "3" "2"] output)))))

(deftest set-from-array-runtime
  (testing "create Set[T].from_array builds a set"
    (let [code "class Test
  feature
    demo() do
      let s: Set[Integer] := create Set[Integer].from_array([0, 2, 4, 2])
      print(s)
      print(s.contains(4))
    end
end"
          output (execute-method-output code)]
      (is (= ["#{0, 2, 4}" "true"] output)))))

(deftest set-from-array-top-level-expression
  (testing "bare constructor expressions work at the REPL"
      (let [ctx (repl/init-repl-context)
          output (binding [repl/*type-checking-enabled* (atom false)
                           repl/*repl-var-types* (atom {})]
                   (with-out-str
                     (repl/eval-code ctx "create Set[Integer].from_array([1,2])" "<repl>")))]
      (is (= "#{1, 2}\n" output)))))

(deftest empty-set-literal-runtime
  (testing "empty set literals work at runtime"
    (let [ctx (repl/init-repl-context)
          output (binding [repl/*type-checking-enabled* (atom false)
                           repl/*repl-var-types* (atom {})]
                   (with-out-str
                     (repl/eval-code ctx "let s: Set[Integer] := #{}\nprint(s.is_empty())" "<repl>")))]
      (is (= "true\n" output)))))

(defn- run-program-output
  "Evaluate a whole program (the :program path, where Set/Map value semantics are
   bound) and return its captured output lines."
  [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    @(:output ctx)))

(deftest set-honours-equals-hash-override
  (testing "Set dedup and contains honour a class's equals/hash override"
    (let [code "class Money inherit Any
  feature
    cents: Integer
    equals(other: Any): Boolean do
      if type_is(\"Money\", other) then
        let m: Money := other
        result := cents = m.cents
      else
        result := false
      end
    end
    hash(): Integer do
      result := cents
    end
  create
    make(c: Integer) do
      cents := c
    end
end

let s: Set[Money] := #{create Money.make(10), create Money.make(10), create Money.make(20)}
print(s.size())
print(s.contains(create Money.make(10)))
print(s.contains(create Money.make(99)))"
          output (run-program-output code)]
      ;; The two Money(10) collapse to one element (override equality), and
      ;; membership finds an equal-but-distinct instance.
      (is (= ["2" "true" "false"] output)))))

(deftest set-structural-dedup-is-portable
  (testing "Without an override, structurally-equal objects dedup as set elements"
    (let [code "class Point inherit Any
  feature
    x: Integer
    y: Integer
  create
    make(a: Integer, b: Integer) do
      x := a
      y := b
    end
end

let s: Set[Point] := #{create Point.make(1, 2), create Point.make(1, 2), create Point.make(3, 4)}
print(s.size())
print(s.contains(create Point.make(1, 2)))
print(s.contains(create Point.make(9, 9)))"
          output (run-program-output code)]
      (is (= ["2" "true" "false"] output)))))

(deftest map-honours-equals-hash-override-for-keys
  (testing "Map lookup matches keys by a class's equals/hash override"
    (let [code "class Money inherit Any
  feature
    cents: Integer
    equals(other: Any): Boolean do
      if type_is(\"Money\", other) then
        let m: Money := other
        result := cents = m.cents
      else
        result := false
      end
    end
    hash(): Integer do
      result := cents
    end
  create
    make(c: Integer) do
      cents := c
    end
end

let prices: Map[Money, String] := create Map[Money, String]
prices.put(create Money.make(100), \"cheap\")
prices.put(create Money.make(100), \"still cheap\")
prices.put(create Money.make(250), \"pricey\")
print(prices.size)
print(prices.get(create Money.make(100)))
print(prices.contains_key(create Money.make(250)))
print(prices.contains_key(create Money.make(999)))"
          output (run-program-output code)]
      ;; The second put with an equal key updates rather than inserts.
      (is (= ["2" "\"still cheap\"" "true" "false"] output)))))

(deftest map-structural-keys-are-portable
  (testing "Without an override, structurally-equal object keys collide"
    (let [code "class Point inherit Any
  feature
    x: Integer
    y: Integer
  create
    make(a: Integer, b: Integer) do
      x := a
      y := b
    end
end

let m: Map[Point, String] := create Map[Point, String]
m.put(create Point.make(1, 2), \"origin-ish\")
print(m.size)
print(m.get(create Point.make(1, 2)))
print(m.contains_key(create Point.make(3, 4)))"
          output (run-program-output code)]
      (is (= ["1" "\"origin-ish\"" "false"] output)))))

(deftest old-bare-brace-set-syntax-rejected
  (testing "old {1, 2} set literal syntax is rejected"
    (is (thrown? Exception (p/ast "{1, 2}")))))
