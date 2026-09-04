(ns nex.map-runtime-semantics-test
  "Two Map runtime-behavior regressions, both backends:

     1. Map.get(k)'s presence check used `(nil? v)` on the retrieved value to
        decide whether the key exists — indistinguishable from a key that IS
        present but whose stored value is legitimately nil (e.g. a
        Map[String, Any] round-tripped through data/Json, where a JSON
        `null` becomes a present key with a nil value). `get` on such a key
        raised the `key_must_exist` precondition instead of returning nil.
        Fixed on both backends to check presence via nex-map-contains-key /
        map-contains-key instead of the retrieved value.

     2. The compiled backend represented Map as a plain java.util.HashMap,
        which iterates in hash-bucket order — diverging from the
        interpreter's portable map, which preserves insertion order (and
        from Set, which the compiled backend already backs with the
        order-preserving LinkedHashSet). `m.keys()`/`m.values()` on the same
        `put` sequence came back in a different order per backend. Fixed by
        allocating LinkedHashMap instead of HashMap at every site a Map
        value is actually constructed (a `{...}` literal, a Map-typed
        field's zero-value, cloning, and the portable-map -> compiled-value
        bridge json_parse and friends cross through)."
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
  (let [{:keys [main-class classes]} (file/compile-ast "map_runtime_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(defn- both-backends [code]
  {:compiled (run-compiled code) :interpreted (run-interpreted code)})

(def nil-valued-key-program
  "let m: Map[String, Any] := {}
m.put(\"present_nil\", nil)
m.put(\"present_val\", 42)
print(m.contains_key(\"present_nil\"))
print(m.get(\"present_nil\"))
print(m.get(\"present_val\"))
print(m.try_get(\"present_nil\", -1))
print(m.try_get(\"absent\", -1))")

(deftest map-get-distinguishes-present-nil-from-absent-key-test
  (testing "get()/try_get() on a key present with a nil value returns nil, not a key_must_exist violation, on both backends"
    (let [{:keys [compiled interpreted]} (both-backends nil-valued-key-program)]
      (is (= ["true" "nil" "42" "nil" "-1"] compiled))
      (is (= ["true" "nil" "42" "nil" "-1"] interpreted)))))

(deftest map-get-still-rejects-a-truly-absent-key-test
  (testing "get() on a key that was never put still raises key_must_exist, on both backends"
    (is (thrown? Exception
                 (run-compiled "let m: Map[String, Integer] := {}
print(m.get(\"missing\"))")))
    (is (thrown? Exception
                 (run-interpreted "let m: Map[String, Integer] := {}
print(m.get(\"missing\"))")))))

(def insertion-order-program
  "let m: Map[String, Integer] := {}
m.put(\"z\", 1)
m.put(\"a\", 2)
m.put(\"m\", 3)
print(m.keys())")

(deftest map-keys-preserve-insertion-order-on-both-backends-test
  (testing "Map iteration order matches put order, not hash-bucket order, on both backends"
    (let [{:keys [compiled interpreted]} (both-backends insertion-order-program)]
      (is (= ["[\"z\", \"a\", \"m\"]"] compiled))
      (is (= ["[\"z\", \"a\", \"m\"]"] interpreted)))))

(deftest map-literal-preserves-insertion-order-test
  (testing "a `{...}` map literal's own key order survives to .keys() on both backends"
    (let [code "let m: Map[String, Integer] := {\"z\": 1, \"a\": 2, \"m\": 3}
print(m.keys())"
          {:keys [compiled interpreted]} (both-backends code)]
      (is (= ["[\"z\", \"a\", \"m\"]"] compiled))
      (is (= ["[\"z\", \"a\", \"m\"]"] interpreted)))))
