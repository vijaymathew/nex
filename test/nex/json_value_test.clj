(ns nex.json-value-test
  "data/Json's Json_Value: typed reading of parsed JSON (Json.read), with
   errors that name the place of a mismatch. Asserted on both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "json_value" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(def ^:private doc
  (str "let data: Json_Value := create Json.make().read('"
       "{\"n\": 3, \"r\": 2, \"x\": 2.5, \"s\": \"hi\", \"ok\": true, \"none\": null, "
       "\"xs\": [1, 2.5], \"names\": [\"a\", \"b\"], \"flags\": [true, false], \"ns\": [4, 5], "
       "\"grid\": [[1, 2], [3, 4.5]], \"pts\": [{\"x\": 1}, {\"x\": \"oops\"}]}')"))

(defn- both
  [& lines]
  (let [code (str/join "\n" (concat ["intern data/Json" doc] lines))
        compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- error-of
  "The message raised by EXPR (a Json_Value expression), on both backends."
  [expr]
  (let [[msg] (both "do"
                    (str "  print(" expr ")")
                    "rescue"
                    "  print(exception)"
                    "end")]
    (some-> msg (str/replace #"^\"|\"$" ""))))

(deftest single-values
  (is (= ["3" "2.0" "2.5" "\"hi\"" "true"]
         (both "print(data.get(\"n\").as_integer)"
               "print(data.get(\"r\").as_real)"
               "print(data.get(\"x\").as_real)"
               "print(data.get(\"s\").as_string)"
               "print(data.get(\"ok\").as_boolean)"))))

(deftest whole-arrays
  (is (= ["[1.0, 2.5]" "[\"a\", \"b\"]" "[true, false]" "[4, 5]" "[[1.0, 2.0], [3.0, 4.5]]"]
         (both "print(data.get(\"xs\").as_reals)"
               "print(data.get(\"names\").as_strings)"
               "print(data.get(\"flags\").as_booleans)"
               "print(data.get(\"ns\").as_integers)"
               "let grid: Array[Array[Real]] := []"
               "across data.get(\"grid\").items as row do grid.add(row.as_reals) end"
               "print(grid)"))))

(deftest navigation-and-kinds
  (is (= ["true" "false" "true" "2" "1" "\"pts[1].x\"" "{\"x\":\"oops\"}"
          "true" "true" "true" "true" "true"]
         (both "print(data.has(\"none\"))"
               "print(data.has(\"missing\"))"
               "print(data.get(\"none\").is_null)"
               "print(data.get(\"pts\").length)"
               "print(data.get(\"pts\").at(0).get(\"x\").as_integer)"
               "print(data.get(\"pts\").at(1).get(\"x\").path)"
               "print(data.get(\"pts\").at(1))"
               "print(data.is_object and data.get(\"xs\").is_array)"
               "print(data.get(\"s\").is_string and data.get(\"n\").is_number)"
               "print(data.get(\"x\").is_number and data.get(\"ok\").is_boolean)"
               "print(data.keys.length = 12)"
               "print(create Json_Value.make(42).as_integer = 42)"))))

(deftest errors-name-the-place
  (is (= "Json: pts[1].x is a string, expected a number"
         (error-of "data.get(\"pts\").at(1).get(\"x\").as_real")))
  (is (= "Json: n is an integer, expected a string"
         (error-of "data.get(\"n\").as_string")))
  (is (= "Json: x is a number, expected an integer"
         (error-of "data.get(\"x\").as_integer")))
  (is (= "Json: none is null, expected true or false"
         (error-of "data.get(\"none\").as_boolean")))
  (is (= "Json: the top level has no key \"missing\""
         (error-of "data.get(\"missing\")")))
  (is (= "Json: xs has no element 5 (it has 2)"
         (error-of "data.get(\"xs\").at(5)")))
  (is (= "Json: grid[1] is an array, expected an object"
         (error-of "data.get(\"grid\").at(1).get(\"a\")")))
  (is (= "Json: names[0] is a string, expected a number"
         (error-of "data.get(\"names\").as_reals")))
  (is (= "Json: s is a string, expected an array"
         (error-of "data.get(\"s\").length"))))
