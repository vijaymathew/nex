(ns nex.class-constant-interning-test
  "A class-level constant denotes one canonical value for the whole run, on both
   backends. Regression tests for two defects in object- and collection-valued
   constants (scalar constants already worked):

     1. The compiled backend's `<clinit>` emitted the initializer with the state
        slot hardcoded to 0. A scalar lowers to an LDC and ignores it, but
        `create X.make(...)` dispatches a constructor that needs the session
        state, so it emitted `aload_0` into a static method with no local 0 —
        a VerifyError (`Bad local variable type`) that fell back to the
        interpreter. Fixed by bootstrapping a throwaway state in `<clinit>`.
     2. Neither backend interned the value: the initializer was re-evaluated per
        read, so `C.K == C.K` was false. The compiled fix interns via a
        write-once static field; the interpreter now memoizes per (class, name)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.classloader :as loader]))

(defn- run-interpreted
  [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    @(:output ctx)))

(defn- run-compiled
  "Compile the program, define its classes in a fresh loader, invoke `Main`, and
   return stdout split into lines — mirroring nex.eval/run-compiled. A VerifyError
   in a class's <clinit> surfaces here as an ExceptionInInitializerError."
  [code]
  (let [{:keys [main-class classes]} (file/compile-ast "const_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          ;; The compiled runtime prints via Clojure `println` (→ *out*), so
          ;; capture that rather than System/out.
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out)
           (remove str/blank?)
           vec))))

(def object-constant-program
  "class Point
  feature
    x: Integer
  create make(v: Integer) do x := v end
end

class Origins
  feature
    ORIGIN = create Point.make(0)
end

let a := Origins.ORIGIN
let b := Origins.ORIGIN
print(a.x)
print(a == b)")

(deftest object-valued-constant-links-and-interns
  (testing "compiled: an object constant links (no <clinit> VerifyError) and is interned"
    ;; `a == b` is reference identity — true only if both reads return the one
    ;; canonical instance written once by <clinit>.
    (is (= ["0" "true"] (run-compiled object-constant-program))))
  (testing "interpreter: same value, and memoized so identity holds"
    (is (= ["0" "true"] (run-interpreted object-constant-program)))))

(def array-constant-program
  "class Colors
  feature
    RED = 3
    ALL = [3, 2, 1]
end
print(Colors.RED)
print(Colors.ALL.length())")

(deftest collection-valued-constant-links-on-both-backends
  (testing "a mix of scalar and array constants (scalar regression + composite)"
    (is (= ["3" "3"] (run-compiled array-constant-program)))
    (is (= ["3" "3"] (run-interpreted array-constant-program)))))

(def scalar-constant-program
  "class Frame
  feature
    HELLO: String = \"hi\"
    MAX = 450
end
print(Frame.HELLO)
print(Frame.MAX + 10)")

(deftest scalar-constants-unregressed
  (testing "scalar constants keep the trivial <clinit> (no state bootstrap)"
    (is (= ["\"hi\"" "460"] (run-compiled scalar-constant-program)))
    (is (= ["\"hi\"" "460"] (run-interpreted scalar-constant-program)))))

(def inherited-constant-program
  "class Base
  feature
    A = 10
    B = A + 5
end

class Derived inherit Base
  feature
    C = B + 1
end
print(Base.B)
print(Derived.C)")

(deftest constant-referencing-another-constant
  (testing "a constant may reference an in-scope (own/inherited) constant"
    (is (= ["15" "16"] (run-compiled inherited-constant-program)))
    (is (= ["15" "16"] (run-interpreted inherited-constant-program)))))
