(ns nex.implies-operator-test
  "`a implies b`: the loosest binary operator, grouping to the right, short-
   circuiting (b runs only when a holds), with b checked under a's narrowings
   as for `and`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- shape
  "An expression AST as a small s-expression: `(implies (and a b) c)`."
  [e]
  (if (map? e)
    (case (:type e)
      :binary (list (symbol (:operator e)) (shape (:left e)) (shape (:right e)))
      :unary (list (symbol (:operator e)) (shape (:expr e)))
      :identifier (symbol (:name e))
      (:type e))
    e))

(defn- parse-expr [src]
  (shape (:value (first (:statements (p/ast (str "let x := " src)))))))

(deftest implies-precedence-and-grouping
  (testing "`implies` binds looser than `or`"
    (is (= '(implies (and a b) (or c d)) (parse-expr "a and b implies c or d")))
    (is (= '(implies (or a b) (or (and c d) e)) (parse-expr "a or b implies c and d or e")))
    (is (= '(implies a (= b c)) (parse-expr "a implies b = c")))
    (is (= '(implies (not a) b) (parse-expr "not a implies b"))))
  (testing "`implies` groups to the right"
    (is (= '(implies a (implies b c)) (parse-expr "a implies b implies c")))))

(def ^:private program
  "class Box
create
  make() do
    items := []
  end
feature
  items: Array[Integer]
  head: ?Box
  push(x: Integer) do
    items.add(x)
  ensure
    first_kept: old items.length > 0 implies items.get(0) = old items.get(0)
    tested: ?head as h implies h.items.length >= 0
  end
invariant
  sized: items.length > 3 implies items.get(0) >= 0
end

let t := true
let f := false
let calls := [0]
let probe := fn (): Boolean do
  calls.add(1)
  result := true
end
let skipped := f implies probe()
let b := create Box.make()
b.push(5)
b.push(6)
let n: ?Integer := nil
let truth := [t implies t, t implies f, f implies t, f implies f,
              f implies f implies f, skipped, n /= nil implies n > 0]
")

(def ^:private expected-truth [true false true true true true true])

(deftest implies-evaluates-in-the-interpreter
  (let [ctx (-> program p/ast interp/interpret)
        lookup #(interp/env-lookup (:current-env ctx) %)]
    (is (= expected-truth (vec (lookup "truth"))))
    (testing "the right operand is not evaluated when the left is false"
      (is (= 1 (interp/nex-array-size (lookup "calls")))))
    (is (= 2 (interp/nex-array-size (:items (:fields (lookup "b")))))))
  (testing "`10 / z` on the right would raise if it ran"
    (let [ctx (-> "let z := 0\nlet r := false implies 10 / z > 0" p/ast interp/interpret)]
      (is (true? (interp/env-lookup (:current-env ctx) "r"))))))

(deftest implies-evaluates-compiled
  (let [session (compiled-repl/make-session)
        result (compiled-repl/compile-and-eval! session
                                                (p/ast (str program "\ntruth")))
        calls (compiled-repl/compile-and-eval! session (p/ast "calls.length"))]
    (is (:compiled? result))
    (is (= expected-truth (vec (:result result))))
    (testing "the right operand is not evaluated when the left is false"
      (is (= 1 (:result calls))))))

(defn- check [code]
  (tc/type-check (p/ast code)))

(defn- rejected-with [code substr]
  (let [r (check code)]
    (and (not (:success r))
         (some #(str/includes? (str (tc/format-type-error %)) substr) (:errors r)))))

(deftest implies-type-rules
  (testing "both operands must be Boolean"
    (is (rejected-with "let x := 1 implies true"
                       "Operator implies requires Boolean operands")))
  (testing "the right operand sees the left's narrowings and bindings"
    (is (:success (check "let n: ?Integer := 3\nlet ok := n /= nil implies n > 2")))
    (is (:success (check "let n: ?Integer := 3\nlet ok := ?n as m implies m > 2"))))
  (testing "a binding on the left does not reach an `if`'s then-branch: the left may be false"
    (is (rejected-with "let n: ?Integer := nil\nif ?n as m implies true then\n  print(m + 1)\nend"
                       "Undefined variable: m"))))
