(ns nex.json-lib-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
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
