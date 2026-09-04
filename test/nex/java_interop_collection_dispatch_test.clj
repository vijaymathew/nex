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
  (:require [clojure.test :refer [deftest is testing]]
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
      (is (some #(re-find #"Method not found: size" (:message %)) errors)))))
