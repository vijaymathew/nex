(ns nex.java-overload-resolution-test
  "Compile-time Java overload resolution (see the \"Compile-time Java
   overload resolution\" section of nex.lower). Every Java interop call
   used to dispatch through clojure.lang.Reflector at runtime, on both
   backends, which performs no real overload resolution: when a method name
   is overloaded, it reliably prefers a wider reference-type (Object)
   candidate over a more specific primitive/numeric one --
   java.util.ArrayList.remove(int)/(Object) is the textbook case:
   `list.remove(1)` silently ran as \"remove the value 1\", not \"remove
   index 1\". The interpreter still has no way to fix this (it dispatches
   on runtime value shape, with no static receiver type to reason about),
   but the compiled backend can resolve unambiguous cases at lowering
   time, using each argument's already-type-checked static Nex type --
   these tests pin that, and its safe fallback to the pre-existing runtime
   dispatch whenever resolution isn't confident enough to act."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.classloader :as loader]))

(defn- run-interpreted [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    @(:output ctx)))

(defn- run-compiled [code]
  (let [{:keys [main-class classes]} (file/compile-ast "java_overload_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(def remove-int-vs-object-program
  "import java.util.ArrayList

with \"java\" do
  let list: ArrayList := create ArrayList
  list.add(10)
  list.add(20)
  list.add(30)
  list.remove(1)
  print(list)
end")

(deftest instance-call-resolves-int-overload-over-object-test
  (testing "list.remove(1) picks remove(int) — removes index 1 — not remove(Object), on the compiled backend; interpreter output pinned as-is (it already happened to get this one right via its own Array builtin, for an unrelated reason)"
    (is (= ["[10, 30]"] (run-compiled remove-int-vs-object-program)))
    (is (= ["[10, 30]"] (run-interpreted remove-int-vs-object-program)))))

(def bridge-method-append-program
  "import java.lang.StringBuilder

with \"java\" do
  let sb: StringBuilder := create StringBuilder
  sb.append(\"ab\")
  sb.append(\"cd\")
  print(sb.toString())
end")

(deftest covariant-return-bridge-method-is-not-a-false-ambiguity-test
  ;; StringBuilder.append(String) reflects twice off Class.getMethods(): the
  ;; declared method (returning StringBuilder) and a synthetic covariant-
  ;; return bridge method inherited from AbstractStringBuilder (same
  ;; parameter types, returning AbstractStringBuilder) — a naive candidate
  ;; count would see 2 candidates and report a spurious "ambiguous overload"
  ;; compile error for a method that was never actually overloaded. Fixed by
  ;; excluding bridge methods and deduping by parameter-type signature.
  (testing "a covariant-return bridge method never triggers the compile-time ambiguity error"
    (is (= ["\"abcd\""] (run-compiled bridge-method-append-program)))
    (is (= ["\"abcd\""] (run-interpreted bridge-method-append-program)))))

(def constructor-overload-program
  "import java.lang.StringBuilder

with \"java\" do
  let capacity_ctor: StringBuilder := create StringBuilder.new(16)
  capacity_ctor.append(\"hi\")
  print(capacity_ctor.toString())

  let text_ctor: StringBuilder := create StringBuilder.new(\"hello\")
  text_ctor.append(\" world\")
  print(text_ctor.toString())
end")

(deftest constructor-overload-resolves-by-argument-type-test
  (testing "create StringBuilder.new(16) picks the int-capacity constructor, create StringBuilder.new(\"hello\") picks the String constructor"
    (is (= ["\"hi\"" "\"hello world\""] (run-compiled constructor-overload-program)))
    (is (= ["\"hi\"" "\"hello world\""] (run-interpreted constructor-overload-program)))))

(def out-of-range-narrowing-program
  "import java.util.ArrayList

with \"java\" do
  let list: ArrayList := create ArrayList
  list.add(1)
  list.remove(5000000000)
  print(\"unreachable\")
end")

(deftest narrowing-a-nex-integer-to-int-is-checked-not-truncated-test
  (testing "a Nex Integer that does not fit in the resolved `int` parameter raises a clear error rather than silently wrapping"
    (let [message (try
                    (run-compiled out-of-range-narrowing-program)
                    nil
                    ;; run-compiled invokes the generated main reflectively
                    ;; (Method/invoke), so a program-level exception surfaces
                    ;; wrapped in InvocationTargetException — its own
                    ;; getMessage is nil; the real message is on the cause.
                    (catch Exception e (.getMessage (or (.getCause e) e))))]
      (is (some? message))
      (is (re-find #"does not fit in a Java int parameter" (or message ""))))))

(def single-candidate-unaffected-program
  "import java.lang.Math

with \"java\" do
  print(Math.abs(-5))
  print(Math.abs(-5.5))
  print(Math.pow(2, 10))
end")

(deftest non-overloaded-calls-are-unaffected-test
  (testing "a call with no real overload ambiguity (fewer than 2 non-varargs candidates) bails straight to the pre-existing runtime dispatch, unchanged"
    (is (= ["5" "5.5" "1024.0"] (run-compiled single-candidate-unaffected-program)))
    (is (= ["5" "5.5" "1024.0"] (run-interpreted single-candidate-unaffected-program)))))

(def any-typed-receiver-still-bails-program
  "import java.util.ArrayList

function make_list(): Any do
  with \"java\" do
    let l: ArrayList := create ArrayList
    l.add(1)
    l.add(2)
    result := l
  end
end

with \"java\" do
  let x: Any := make_list()
  print(x.size())
end")

(deftest any-typed-receiver-still-bails-to-runtime-dispatch-test
  (testing "resolution needs the receiver's static Java class; an Any-typed receiver (the with-\"java\" idiom the typechecker cannot give a real Java type) bails cleanly rather than erroring, same as before this fix"
    (is (= ["2"] (run-compiled any-typed-receiver-still-bails-program)))
    (is (= ["2"] (run-interpreted any-typed-receiver-still-bails-program)))))
