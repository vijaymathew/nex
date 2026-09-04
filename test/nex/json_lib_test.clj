(ns nex.json-lib-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.classloader :as loader]
            [nex.repl :as repl]))

(deftest json-library-runtime
  (testing "Json parses and stringifies nested JSON values in the JVM interpreter"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern data/Json")
                   (repl/eval-code ctx "let json: Json := create Json.make()")
                   (repl/eval-code ctx "let q: Char := #34")
                   (repl/eval-code ctx "let text: String := \"{\" + q + \"name\" + q + \":\" + q + \"nex\" + q + \",\" + q + \"count\" + q + \":3,\" + q + \"pi\" + q + \":3.5,\" + q + \"active\" + q + \":true,\" + q + \"items\" + q + \":[1,2],\" + q + \"meta\" + q + \":{\" + q + \"ok\" + q + \":true},\" + q + \"none\" + q + \":null}\"")
                   (repl/eval-code ctx "let root: Map[String, Any] := json.parse(text)")
                   (repl/eval-code ctx "let items: Array[Any] := root.get(\"items\")")
                   (repl/eval-code ctx "let meta: Map[String, Any] := root.get(\"meta\")")
                   (repl/eval-code ctx "print(root.get(\"name\"))")
                   (repl/eval-code ctx "print(type_of(root.get(\"count\")))")
                   (repl/eval-code ctx "print(type_of(root.get(\"pi\")))")
                   (repl/eval-code ctx "print(items.get(1))")
                   (repl/eval-code ctx "print(meta.get(\"ok\"))")
                   (repl/eval-code ctx "print(json.stringify(root))"))]
      (is (.contains output "\"nex\""))
      (is (.contains output "\"Integer\""))
      (is (.contains output "\"Real\""))
      (is (.contains output "2"))
      (is (.contains output "true"))
      (is (str/includes? output "\"name\":\"nex\""))
      (is (str/includes? output "\"items\":[1,2]"))
      (is (str/includes? output "\"none\":null")))))

(defn- run-interpreted [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    @(:output ctx)))

(defn- run-compiled [code]
  (let [{:keys [main-class classes]} (file/compile-ast "json_lib_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(def json-null-value-round-trip-program
  "intern data/Json
let j: Json := create Json.make()
let parsed: Any := j.parse(\"{\\\"a\\\": true, \\\"b\\\": null}\")
if convert parsed to m: Map[String, Any] then
  print(m.contains_key(\"b\"))
  print(m.get(\"b\"))
else
  print(\"not a map\")
end")

(deftest json-null-value-get-does-not-raise-key-must-exist-test
  ;; A key present with a JSON `null` (-> Nex nil) value used to raise
  ;; \"Precondition violation: key_must_exist\" on `.get`, because Map.get's
  ;; presence check was `(nil? v)` on the retrieved value — indistinguishable
  ;; from an absent key. See map_runtime_semantics_test.clj for the direct,
  ;; non-JSON regression; this pins the exact json_parse round-trip that
  ;; surfaced it.
  (testing "a JSON null round-trips to a present key with a nil value, not a precondition violation, on both backends"
    (is (= ["true" "nil"] (run-compiled json-null-value-round-trip-program)))
    (is (= ["true" "nil"] (run-interpreted json-null-value-round-trip-program)))))

(def json-object-key-order-round-trip-program
  "intern data/Json
let j: Json := create Json.make()
let parsed: Any := j.parse(\"{\\\"z\\\": 1, \\\"a\\\": 2, \\\"m\\\": 3}\")
if convert parsed to m: Map[String, Any] then
  print(m.keys())
end")

(deftest json-object-key-order-is-preserved-on-both-backends-test
  ;; The compiled backend's portable-map -> compiled-value bridge built a
  ;; plain java.util.HashMap, scrambling json_parse's object-key order to
  ;; hash-bucket order; the interpreter's portable map already preserved it.
  ;; Fixed by bridging into a LinkedHashMap instead.
  (testing "json_parse's object key order survives to .keys() on both backends"
    (is (= ["[\"z\", \"a\", \"m\"]"] (run-compiled json-object-key-order-round-trip-program)))
    (is (= ["[\"z\", \"a\", \"m\"]"] (run-interpreted json-object-key-order-round-trip-program)))))
