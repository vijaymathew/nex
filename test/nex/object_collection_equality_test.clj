(ns nex.object-collection-equality-test
  "Set membership and Map keying honour a class's `equals`/`hash` overrides on
   both backends.

   Set and Map are a java.util.LinkedHashSet and a java.util.HashMap on the
   compiled backend, so dedup, membership and key lookup are decided by
   Object.equals/hashCode and nothing else — `.add` and `.put` are host calls
   that no hook reaches into. Generated classes did not override either, so an
   override was ignored the moment its instances entered a collection: `#{a, b}`
   kept two objects the program considered one, and `m.get(equal_key)` returned
   nil. The interpreter, whose collections consult the overrides (see
   `with-value-semantics*`), disagreed with all of it.

   These tests are backend-parity tests: the interpreter is the definition of
   correct, and each asserts the two agree."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "obj_coll" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (->> (str/split-lines (str/trim-newline out))
             (remove #(str/starts-with? % "Warning:"))
             vec))
      (finally (.delete f)))))

(defn- both
  [code]
  (let [compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(def ^:private keyed-class
  "Two instances with the same `id` are one value, however their other fields
   differ — so a container keyed on identity or on structure gives a different
   answer from one keyed on the override."
  "class K inherit Hashable
  feature
    id: Integer
    tag: String
  create make(i: Integer, n: String) do id := i tag := n end
  feature
    equals(o: K): Boolean do result := id = o.id end
    hash: Integer do result := id end
end
let a := create K.make(1, \"first\")
let b := create K.make(1, \"second\")
")

(deftest set-literal-dedups-by-equals-override
  (testing "a set literal keeps one of two objects the override calls equal"
    (is (= ["true" "1"] (both (str keyed-class "print(a = b)\nprint(#{a, b}.size)"))))))

(deftest set-membership-uses-equals-override
  (testing "`contains` finds an equal-but-not-identical element"
    (is (= ["true"] (both (str keyed-class
                               "print(#{a}.contains(create K.make(1, \"third\")))"))))))

(deftest map-keys-use-equals-override
  (testing "an equal key replaces rather than adds, and looks up"
    (is (= ["1" "\"two\""]
           (both (str keyed-class
                      "let m: Map[K, String] := {}
m.put(a, \"one\")
m.put(b, \"two\")
print(m.size)
print(m.get(create K.make(1, \"x\")))"))))))

(deftest objects-without-overrides-dedup-structurally
  (testing "with no override, collections fall back to structural value semantics"
    ;; The Any default is structural equality, so two objects with equal fields
    ;; are one value here too — the compiled backend used to key these on JVM
    ;; identity and keep both.
    (is (= ["true" "1" "true"]
           (both "class P
  feature x: Integer
  create make(v: Integer) do x := v end
end
let a := create P.make(1)
let b := create P.make(1)
print(a = b)
print(#{a, b}.size)
print(#{a}.contains(create P.make(1)))")))))

(deftest distinct-objects-stay-distinct
  (testing "objects the program calls different are kept apart"
    (is (= ["false" "2" "false"]
           (both "class P
  feature x: Integer
  create make(v: Integer) do x := v end
end
let a := create P.make(1)
let c := create P.make(2)
print(a = c)
print(#{a, c}.size)
print(#{a}.contains(c))")))))

(deftest object-compared-to-nil-terminates
  (testing "`/= nil` on an object does not recurse into Object.equals forever"
    ;; Regression: emitting equals/hashCode closed a cycle — Object.equals ->
    ;; value-equals -> deep-equals -> Clojure `=` -> Object.equals — that only
    ;; a non-object operand reaches. `?T` field plus a nil check is the shortest
    ;; program that hits it, and it died with StackOverflowError.
    (is (= ["\"leaf\""]
           (both "class Node
create
  make(left: ?Node) do
    this.left := left
  end
feature
  left: ?Node
end

function is_attached(node: ?Node): Boolean do
  result := node /= nil
end

let leaf := create Node.make(nil)
let root := create Node.make(leaf)
if is_attached(root.left) then
  print(\"leaf\")
end")))))

(deftest nested-objects-compare-and-hash-through
  (testing "an object-valued field participates in equality and hashing"
    (is (= ["true" "1"]
           (both "class Inner
  feature n: Integer
  create make(v: Integer) do n := v end
end
class Outer
  feature i: Inner
  create make(x: Inner) do i := x end
end
let a := create Outer.make(create Inner.make(1))
let b := create Outer.make(create Inner.make(1))
print(a = b)
print(#{a, b}.size)")))))

(deftest identity-comparison-is-unaffected
  (testing "`==` still compares references, whatever `equals` says"
    (is (= ["true" "false" "true"]
           (both (str keyed-class
                      "print(a = b)
print(a == b)
print(a == a)"))))))
