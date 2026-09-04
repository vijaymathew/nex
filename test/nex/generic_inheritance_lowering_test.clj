(ns nex.generic-inheritance-lowering-test
  "Two compiled-backend-only lowering/codegen bugs found stress-testing
   generic inheritance chains, both reproduced through a real standalone
   class-load-and-run (loader/define-class! + reflective invoke, the same
   JVM linking/access-control path a real `nex compile jvm` jar goes
   through) rather than through nex.eval, whose --skip-contracts-adjacent
   fallback-to-interpreter safety net silently recovers from either and so
   never surfaces them as failures on its own.

   1. A composition field backing an inherited-parent relationship
      (`_parent_Base`, holding the ancestor half of a Nex object under
      composition-based inheritance) was emitted JVM `private`. That's
      only ever accessible from within the exact declaring class, which a
      class more than one inheritance level down never is when it needs to
      forward-construct through an INTERMEDIATE ancestor's own composition
      field — so a 2+-level inheritance chain crashed at class-load time
      with IllegalAccessError. Fixed by emitting these fields package-
      private instead: every class one compiled program generates shares
      one package, so that's the correct width, not part of any public API
      a real Java caller could see either way.

   2. A generic free function's type parameters are inferred at each call
      site by matching the declared parameter type against the actual
      argument's type (nex.lower/infer-generic-type-map-from-arg). That
      match only ever compared base-types by pointwise equality: an
      argument whose OWN class differs from the declared parameter's --
      `first_field[T](b: Base[T, Any])` called with a `Mid[String,
      Integer]` (or a plain `Leaf`, further down: `Leaf inherit
      Mid[String, Integer] inherit Base[Q, P]`) -- fell straight to a
      `{}` (no binding at all) instead of walking the argument's ancestor
      chain to Base with proper generic substitution. The unbound `T` then
      leaked past type checking (which already got this right, via its own
      separate inference) into codegen as if \"T\" were a real class name,
      compiling fine but crashing at run time with NoClassDefFoundError: T.
      Fixed by adding lower-ancestor-instantiation, a lower.clj-local
      counterpart to nex.typechecker's (private, incompatible env shape)
      ancestor-instantiation, used exactly where the base-types differ."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.classloader :as loader]))

(defn- run-compiled [code]
  (let [{:keys [main-class classes]} (file/compile-ast "generic_inheritance_lowering_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(def two-level-generic-inheritance-program
  "class Base[X, Y]
  feature
    x: X
    y: Y
  create make(a: X, b: Y) do x := a  y := b end
end

class Mid[P, Q]
  inherit
    Base[Q, P]
  create make(p: P, q: Q) do x := q  y := p end
end

let m: Mid[String, Integer] := create Mid[String, Integer].make(\"hello\", 42)
print(m.x)
print(m.y)")

(deftest composition-field-is-accessible-two-levels-down-test
  (testing "a class inheriting a reordered-generic-args parent no longer crashes at class-load time with IllegalAccessError on the parent's composition field"
    (is (= ["42" "\"hello\""] (run-compiled two-level-generic-inheritance-program)))))

(def three-level-generic-inheritance-program
  "class Base[X, Y]
  feature
    x: X
    y: Y
  create make(a: X, b: Y) do x := a  y := b end
end

class Mid[P, Q]
  inherit
    Base[Q, P]
  create make(p: P, q: Q) do x := q  y := p end
end

class Leaf
  inherit
    Mid[String, Integer]
  create make(s: String, i: Integer) do x := i  y := s end
end

let l: Leaf := create Leaf.make(\"hello\", 42)
print(l.x)
print(l.y)")

(deftest composition-field-is-accessible-three-levels-down-test
  (testing "a 3-level inheritance chain (a non-generic leaf over two reordered-generic ancestors) no longer crashes at class-load time"
    (is (= ["42" "\"hello\""] (run-compiled three-level-generic-inheritance-program)))))

(def generic-fn-inferred-through-reordered-ancestor-program
  "class Base[X, Y]
  feature
    x: X
    y: Y
  create make(a: X, b: Y) do x := a  y := b end
end

class Mid[P, Q]
  inherit
    Base[Q, P]
  create make(p: P, q: Q) do x := q  y := p end
end

function first_field[T](b: Base[T, Any]): T do
  result := b.x
end

let m: Mid[String, Integer] := create Mid[String, Integer].make(\"hello\", 42)
print(first_field(m))")

(deftest generic-function-type-param-inferred-through-a-reordered-ancestor-test
  ;; Before the fix: type-checked fine (the typechecker's own, separate
  ;; inference already handled this), compiled fine, but crashed at run
  ;; time with NoClassDefFoundError: T — the unbound generic name leaking
  ;; into codegen as though it were a real class.
  (testing "first_field[T](b: Base[T, Any]) called with a Mid[String, Integer] argument infers T = Integer through Mid's reordered `inherit Base[Q, P]`, not just when the argument's own class matches Base directly"
    (is (= ["42"] (run-compiled generic-fn-inferred-through-reordered-ancestor-program)))))

(def generic-fn-inferred-through-non-generic-leaf-program
  "class Base[X, Y]
  feature
    x: X
    y: Y
  create make(a: X, b: Y) do x := a  y := b end
end

class Mid[P, Q]
  inherit
    Base[Q, P]
  create make(p: P, q: Q) do x := q  y := p end
end

class Leaf
  inherit
    Mid[String, Integer]
  create make(s: String, i: Integer) do x := i  y := s end
end

function first_field[T](b: Base[T, Any]): T do
  result := b.x
end

let l: Leaf := create Leaf.make(\"hello\", 42)
print(first_field(l))")

(deftest generic-function-type-param-inferred-through-a-non-generic-leaf-test
  ;; The harder case: `l`'s own static type (Leaf) carries no type-args at
  ;; all (it isn't itself generic), so its argument-type is a bare string,
  ;; not the {:base-type ... :type-args ...} map shape the ancestor walk
  ;; otherwise expects — the fix has to accept both shapes.
  (testing "first_field[T](b: Base[T, Any]) called with a non-generic Leaf (inheriting Mid[String, Integer] inheriting Base[Q, P]) still infers T = Integer"
    (is (= ["42"] (run-compiled generic-fn-inferred-through-non-generic-leaf-program)))))
