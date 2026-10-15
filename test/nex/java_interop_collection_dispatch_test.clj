(ns nex.java-interop-collection-dispatch-test
  "`with \"java\"` interop against java.util.ArrayList/HashMap/LinkedHashSet:
   the interpreter's `get-type-name` classifies by JVM class alone
   (nex.types.runtime's nex-array?/nex-map?/nex-set? are literally
   `instance? java.util.ArrayList` etc.), so a *real* Java collection
   obtained via `create ArrayList`/`create HashMap`/`create LinkedHashSet`
   is indistinguishable at that point from Nex's own Array/Map/Set, which
   reuse the exact same JVM classes as their runtime representation. A
   method name Nex's builtin type doesn't define (`size` — Nex arrays use
   `length`; `containsKey`; `isEmpty`; ...) used to hard-crash with \"Method
   not found on type\", even though the real Java object plainly has it.
   Fixed by falling back to real reflection (java-call-method) inside a
   `with \"java\"` block when the builtin lookup itself reports \"not
   found\" for that exact method name. The compiled backend was never
   affected (it dispatches on the value's *static* Nex type, not runtime
   value shape), so these are interpreter-only regressions; the compiled
   assertions alongside them just confirm the two backends agree."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.file :as file]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- run [code]
  (let [ast (p/ast code)
        result (tc/type-check ast)]
    (when-not (:success result)
      (throw (ex-info "type check failed" result)))
    @(:output (interp/interpret ast))))

(deftest arraylist-size-does-not-collide-with-nex-array-builtin-test
  (testing "ArrayList.size() (Nex arrays only define `length`) falls through to real reflection instead of crashing"
    (is (= ["\"added\"" "1"]
           (run "import java.util.ArrayList

with \"java\" do
  let list: ArrayList := create ArrayList
  list.add(\"a\")
  print(\"added\")
  print(list.size())
end")))))

(deftest hashmap-containskey-does-not-collide-with-nex-map-builtin-test
  (testing "HashMap.containsKey (camelCase, not Nex's contains_key) falls through to real reflection"
    (is (= ["1" "true"]
           (run "import java.util.HashMap

with \"java\" do
  let m: HashMap := create HashMap
  m.put(\"k\", 1)
  print(m.size())
  print(m.containsKey(\"k\"))
end")))))

(deftest linkedhashset-size-does-not-collide-with-nex-set-builtin-test
  (testing "LinkedHashSet.size() falls through to real reflection"
    (is (= ["1"]
           (run "import java.util.LinkedHashSet

with \"java\" do
  let s: LinkedHashSet := create LinkedHashSet
  s.add(5)
  print(s.size())
end")))))

(deftest genuine-unsupported-method-on-a-real-nex-array-still-reports-clearly-test
  (testing "the fallback is scoped to `with \"java\"` blocks — an ordinary Nex Array typo outside one is still caught at typecheck time with a direct, unambiguous error, never a confusing runtime reflection failure"
    (let [errors (try
                   (run "let a: Array[Integer] := [1, 2, 3]
print(a.size())")
                   ::no-error
                   (catch clojure.lang.ExceptionInfo e (:errors (ex-data e))))]
      (is (not= ::no-error errors))
      (is (some #(re-find #"`size` is not a feature of Array" (:message %)) errors)))))

(defn- run-compiled
  [code]
  (let [{:keys [main-class classes]} (file/compile-ast "dispatch_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out)
           (remove str/blank?)
           vec))))

(def any-held-java-get-program
  "import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class Holder
  create
    make() do
      with \"java\" do
        table := ConcurrentHashMap.new()
        table.put(\"k\", 7)
        counter := AtomicInteger.new(41)
      end
    end
  feature
    table: Any
    counter: Any

    show() do
      with \"java\" do
        counter.incrementAndGet()
        print(table.get(\"k\"))
        print(counter.get())
      end
    end
end

(create Holder.make()).show()")

(deftest any-protocol-name-on-an-any-held-java-object-test
  ;; `get` (and `length`, ...) on an `Any` is a Nex Any-protocol name, so the
  ;; compiled backend sent `table.get(k)` on a ConcurrentHashMap and
  ;; `counter.get()` on an AtomicInteger to the builtin table only, failing
  ;; with "Method not found on type: get". Inside `with "java"` it now falls
  ;; back to reflection, as the interpreter already did.
  (is (= ["7" "42"] (run-compiled any-held-java-get-program)))
  (is (= ["7" "42"] (run any-held-java-get-program))))

(def java-int-as-collection-key-program
  "import java.util.concurrent.ConcurrentHashMap

let code: Integer := 0
let found: Boolean := false
with \"java\" do
  let m := ConcurrentHashMap.new()
  let text: String := \"hello\"
  code := text.indexOf(\"l\")
  m.put(code, true)
  found := m.containsKey(2)
end
print(found)")

(deftest java-int-result-is-a-nex-integer-test
  ;; A Java `int` result reached interpreted Nex code as a java.lang.Integer
  ;; (the compiled backend makes it a long). It compared equal in Nex, but as
  ;; a key in a Java collection it did not match the Long a Nex literal is.
  (is (= ["true"] (run java-int-as-collection-key-program)))
  (is (= ["true"] (run-compiled java-int-as-collection-key-program))))
