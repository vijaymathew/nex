(ns nex.builtin-name-clash-test
  "A free function may not reuse a built-in function's name. Before the check,
   the compiled backend silently ignored the user's definition while the
   interpreter honoured it (and let it hijack library code that calls the
   builtin). Library-support builtins carry a `__` prefix; the prefix is not
   reserved, so user functions may use it too as long as the full name is not
   itself a built-in."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.types.builtins :as bi]))

(defn- error-text [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (str/join "\n" (map tc/format-type-error (:errors result))))))

(defn- run-lines [code interpret?]
  (let [f (java.io.File/createTempFile "clash" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline
                        (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))))
      (finally (.delete f)))))

(deftest redefining-a-builtin-is-a-type-error
  (testing "an internal library helper"
    (is (str/includes? (str (error-text "function __byte_array_from_array(items: Array[Integer]): Integer
do
  result := 42
end"))
                       "same name as a built-in")))
  (testing "a documented public builtin"
    (is (str/includes? (str (error-text "function json_parse(s: String): Integer
do
  result := 1
end"))
                       "json_parse"))))

(deftest unprefixed-helper-names-are-free-for-users
  (is (nil? (error-text "function byte_array_from_array(items: Array[Integer]): Integer
do
  result := 42
end"))))

(deftest double-underscore-prefix-is-not-reserved
  (let [code "function __mine(x: Integer): Integer
do
  result := x + 1
end
print(__mine(1))"]
    (is (nil? (error-text code)))
    (is (= ["2"] (run-lines code false)))
    (is (= ["2"] (run-lines code true)))))

(deftest library-helpers-are-all-prefixed
  (is (empty? (filter #(re-find #"^(binary_file_|byte_array_|datetime_|path_|regex_|text_file_)" %)
                      (keys bi/builtins)))))
