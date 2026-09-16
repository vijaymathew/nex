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
      ancestor-instantiation, used exactly where the base-types differ.

   3. A bare (implicit-self) call to an INHERITED GENERIC method —
      `set_x(p)` inside `Same[P, Q] inherit Base[P, Q]`'s own constructor,
      say — checked each argument straight against the method's raw
      declared parameter/return types (`X`, `Y`, Base's own generic
      params), never resolved to what the current class's own generic
      arguments actually instantiate them to. The explicit `this.set_x(p)`
      spelling of the identical call already resolved this correctly, via
      the shared build-member-generic-type-map/check-call-signature path;
      nex.typechecker/check-bare-name-call just never used it, hand-
      rolling the same argument/return checks without a type-map. Fixed by
      making it delegate to check-call-signature like every other call
      path already does.

   4. A bare call to a method reached through an INTERMEDIATE class that
      declares no methods of its own — `set_v(n)` inside `Level2 inherit
      Level1`'s constructor, where `Level1 inherit Level0` is otherwise
      empty and `set_v` is `Level0`'s own — crashed with the unrelated-
      looking \"Missing compiled class metadata during lowering\"
      (:class-name nil). nex.lower/direct-parent-method-map only ever
      collected a *direct* parent's own class-methods, with none of
      direct-parent-field-map's recursion into that parent's own parents —
      so an empty intermediate class contributed nothing at all to the
      map, the lookup for `set_v` came back nil, and destructuring nil
      bound owner-internal-name/carrier-owner/carrier-field all to nil.
      The explicit `this.set_v(n)` spelling worked throughout, since it
      resolves the call through a different, already fully-recursive path
      (inherited-method-def + lower-instance-user-method-call). Fixed by
      giving direct-parent-method-map the same recursive :carrier-path
      composition direct-parent-field-map already has, walked at each call
      site by the same carrier-path-target-ir both now share.

   5. An inherited-but-not-overridden method with two or more parameters —
      compiled as a thin forwarding stub by nex.lower/make-delegation-
      method-node, since the JVM has no real `extends` between Nex classes
      to dispatch through — got its own unpacked parameters' local-variable
      slots wrong two ways at once. The stub's calling convention is
      (this=slot 0, state=slot 1, boxed-args-array=slot 2), so a parameter
      being unpacked FROM that array must land at slot 3 or later; the code
      instead started numbering unpacked params at slot 2 — the args
      array's own slot — so unpacking the very first parameter clobbered
      the array reference the *next* parameter still needed to read from
      it. Separately, the slot for each parameter after the first was
      computed as a flat `(+ 2 idx)`, never accounting for a preceding
      :long/:double (Nex Integer/Real) parameter's actual JVM width of 2
      slots, not 1 — so two Integer parameters back to back collided their
      slots outright. Either bug alone produces a JVM VerifyError (\"Bad
      local variable type\" / \"is not assignable to reference type\") at
      class-load time; a delegated 2-Integer-parameter method (matching
      result-slot's own already-width-aware calculation just below it in
      the same function) hits both at once. Fixed by numbering unpacked
      params starting at slot 3 and accumulating each preceding param's
      real JVM width, exactly as result-slot already did."
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
  feature
    set_x(v: X) do x := v end
    set_y(v: Y) do y := v end
end

class Mid[P, Q]
  inherit
    Base[Q, P]
  create make(p: P, q: Q) do set_x(q)  set_y(p) end
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
  feature
    set_x(v: X) do x := v end
    set_y(v: Y) do y := v end
end

class Mid[P, Q]
  inherit
    Base[Q, P]
  create make(p: P, q: Q) do set_x(q)  set_y(p) end
end

class Leaf
  inherit
    Mid[String, Integer]
  create make(s: String, i: Integer) do set_x(i)  set_y(s) end
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
  feature
    set_x(v: X) do x := v end
    set_y(v: Y) do y := v end
end

class Mid[P, Q]
  inherit
    Base[Q, P]
  create make(p: P, q: Q) do set_x(q)  set_y(p) end
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
  feature
    set_x(v: X) do x := v end
    set_y(v: Y) do y := v end
end

class Mid[P, Q]
  inherit
    Base[Q, P]
  create make(p: P, q: Q) do set_x(q)  set_y(p) end
end

class Leaf
  inherit
    Mid[String, Integer]
  create make(s: String, i: Integer) do set_x(i)  set_y(s) end
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

(def bare-call-to-inherited-generic-method-program
  "class Base[X, Y]
  feature
    x: X
    y: Y
    set_x(v: X) do x := v end
    set_y(v: Y) do y := v end
  create make(a: X, b: Y) do x := a  y := b end
end

class Same[P, Q]
  inherit
    Base[P, Q]
  create make(p: P, q: Q) do set_x(p)  set_y(q) end
end

let s: Same[String, Integer] := create Same[String, Integer].make(\"hi\", 5)
print(s.x)
print(s.y)")

(deftest bare-call-to-inherited-generic-method-substitutes-type-params-test
  ;; Before the fix: \"Expected X, got P\" — check-bare-name-call compared the
  ;; argument straight against Base's own declared parameter type, never
  ;; substituted through Same's actual generic arguments the way the
  ;; explicit this.set_x(p) spelling of the identical call already did.
  (testing "set_x(p)/set_y(q), called bare inside Same[P, Q] inherit Base[P, Q]'s own constructor, type-check and run"
    (is (= ["\"hi\"" "5"] (run-compiled bare-call-to-inherited-generic-method-program)))))

(def bare-call-through-empty-intermediate-program
  "deferred class Level0
  feature
    v: Integer
    set_v(n: Integer) do v := n end
    val(): Integer deferred
end

deferred class Level1 inherit Level0 end

class Level2
  inherit Level1
  create make(n: Integer) do set_v(n) end
  feature
    val(): Integer do result := v end
end

let lv := create Level2.make(42)
print(lv.val)")

(deftest bare-call-through-empty-intermediate-class-test
  ;; Before the fix: \"Missing compiled class metadata during lowering\"
  ;; (:class-name nil) — direct-parent-method-map only ever collected
  ;; Level1's own class-methods (none: Level1 declares nothing itself),
  ;; never recursing into Level1's own parent Level0 the way direct-parent-
  ;; field-map already does for fields, so the lookup for set_v came back
  ;; nil and destructuring it bound every key to nil. The explicit
  ;; this.set_v(n) spelling worked throughout (a different, already
  ;; fully-recursive lowering path).
  (testing "set_v(n), called bare from Level2's own constructor, reaches Level0's method through the empty intermediate Level1"
    (is (= ["42"] (run-compiled bare-call-through-empty-intermediate-program)))))

(def delegated-two-integer-param-method-program
  "class Base
  feature
    connected(p, q: Integer): Boolean
    do
      result := p = q
    end
end

class Sub
  inherit Base
  create
    make() do end
end

let s := create Sub.make
print(s.connected(1, 1))
print(s.connected(1, 2))")

(deftest delegated-method-with-two-integer-params-does-not-clobber-slots-test
  ;; Before the fix: JVM VerifyError (\"Bad local variable type ... is not
  ;; assignable to reference type\") at class-load time — connected's
  ;; delegation stub unpacked p and q starting at slot 2 (the boxed args
  ;; array's own slot, clobbered the moment p was stored there) with each
  ;; slot computed as a flat `(+ 2 idx)` that ignored p's actual two-slot
  ;; :long width, so q's slot collided with p's second half too.
  (testing "connected(p, q: Integer), inherited unoverridden from Base, delegates correctly for both Sub instances"
    (is (= ["true" "false"] (run-compiled delegated-two-integer-param-method-program)))))
