(ns nex.compiled-clone-test
  "`clone` on the compiled backend. The default `clone` (nex.types.value/
   nex-clone-value) only understood the interpreter's object maps, so on a
   compiled object it was rejected at compile time (a known class) or returned
   the object itself (an `Any`-typed receiver), and a collection's `clone`
   shared the objects it held. Compiled objects are now deep-copied through the
   generated class's no-argument <init>, which runs no user constructor, with
   the interpreter's semantics: everything a value holds is copied, and only a
   top-level `clone` override is honoured. Shared and cyclic references stay
   shared and cyclic in the copy."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.file :as file]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- type-error-message [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (str/join "\n" (map tc/format-type-error (:errors result))))))

(defn- run [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    @(:output ctx)))

(defn- run-compiled [code]
  (let [{:keys [main-class classes]} (file/compile-ast "compiled_clone_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(defn- both
  "Run CODE on both backends, checking it type-checks and they agree."
  [code]
  (is (nil? (type-error-message code)))
  (let [compiled (run-compiled code)]
    (is (= (run code) compiled) "the backends agree")
    compiled))

(def ^:private point
  "class P
feature
  x: Integer
  items: Array[Integer]
  set_x(v: Integer) do x := v end
create
  make(v: Integer) do
    x := v
    items := [v]
  end
end
")

(deftest a-clone-is-an-independent-deep-copy
  (is (= ["true" "false" "1" "[1]" "99" "[1, 2]"]
         (both (str point "let p := create P.make(1)
let a: Any := p.clone
print(a = p)
print(a == p)
if convert a to q: P then
  q.set_x(99)
  q.items.add(2)
  print(p.x)
  print(p.items)
  print(q.x)
  print(q.items)
end
")))))

(deftest clone-through-an-any-typed-receiver
  (testing "used to return the same object on the compiled backend"
    (is (= ["false" "1"]
           (both (str point "let p := create P.make(1)
let a: Any := p
let b := a.clone
print(b == a)
if convert b to c: P then c.set_x(99) end
print(p.x)
"))))))

(def ^:private shapes
  "deferred class Shape
feature
  name: String
  area(): Integer deferred
  describe(): String do result := name + \" \" + area().to_string end
create
  init(n: String) do name := n end
end

class Square inherit Shape
feature
  side: Integer
  area(): Integer do result := side * side end
  grow do side := side + 1 end
create
  make(n: String, s: Integer) do
    Shape.init(n)
    side := s
  end
end
")

(deftest a-clone-copies-inherited-fields-and-dispatches-like-the-original
  (is (= ["\"sq 4\"" "\"sq 9\"" "\"sq 4\""]
         (both (str shapes "let s := create Square.make(\"sq\", 2)
let a: Any := s.clone
if convert a to t: Square then
  print(t.describe())
  t.grow
  print(t.describe())
end
print(s.describe())
")))))

(deftest a-collection-clone-copies-the-objects-it-holds
  (is (= ["1" "99"]
         (both (str point "let p := create P.make(1)
let xs := [p]
let ys := xs.clone
ys.get(0).set_x(99)
print(p.x)
print(ys.get(0).x)
")))))

(deftest a-top-level-clone-override-is-honoured
  (let [code "class C
feature
  n: Integer
  clone: Any do result := create C.make(n + 100) end
create
  make(v: Integer) do n := v end
end
let c := create C.make(1)
let a: Any := c
if convert a.clone to d: C then print(d.n) end
if convert c.clone to e: C then print(e.n) end
"]
    (is (= ["101" "101"] (both code)))))

(deftest clone-on-a-generic-receiver
  (is (= ["false" "1"]
         (both (str point "class Box [G]
feature
  value: G
  copy(): Any do result := value.clone end
create
  make(v: G) do value := v end
end
let p := create P.make(1)
let b := create Box[P].make(p)
let a: Any := b.copy()
print(a == p)
if convert a to q: P then
  q.set_x(5)
  print(p.x)
end
")))))

(def ^:private nodes
  "class Node
feature
  label: Integer
  next: ?Node
  other: ?Node
  link(n: ?Node) do next := n end
  also(n: ?Node) do other := n end
  relabel(v: Integer) do label := v end
create
  make(v: Integer) do label := v end
end
")

;; The interpreter's clone keeps neither: it recurses without end on a cycle,
;; and copies a shared object once per reference. These run on the compiled
;; backend only.
(deftest shared-and-cyclic-references-stay-shared-and-cyclic
  (testing "a cycle"
    (is (= ["true" "false" "1"]
           (run-compiled (str nodes "let n := create Node.make(1)
n.link(n)
let a: Any := n.clone
if convert a to m: Node then
  print(m.next == m)
  print(m.next == n)
  m.relabel(2)
  print(n.label)
end
")))))
  (testing "two references to one object stay one object in the copy"
    (is (= ["true" "false"]
           (run-compiled (str nodes "let shared := create Node.make(7)
let n := create Node.make(1)
n.link(shared)
n.also(shared)
let a: Any := n.clone
if convert a to m: Node then
  print(m.next == m.other)
  print(m.next == shared)
end
"))))))
