(ns nex.source-text-test
  "Nex source text for types and expressions, from the AST (nex.fmt's
   format-type/format-expression), as error messages, editor hovers and
   `nex doc` show them. The text must read as the code does, and mean the
   same: printed and parsed again, it gives the same AST."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [nex.docgen :as docgen]
            [nex.fmt :as fmt]
            [nex.parser :as p]))

(defn- expr [src]
  (:value (first (:statements (p/ast (str "let x := " src))))))

(defn- printed [src] (fmt/format-expression (expr src)))

(deftest types
  (is (= "Array[String]" (fmt/format-type {:base-type "Array" :type-args ["String"]})))
  (is (= "?Array[Map[String, ?Integer]]"
         (fmt/format-type {:base-type "Array" :detachable true
                           :type-args [{:base-type "Map"
                                        :type-args ["String" {:base-type "Integer" :detachable true}]}]})))
  (is (= "Function(Integer): String"
         (fmt/format-type {:base-type "Function" :param-types [{:name nil :type "Integer"}] :return-type "String"})))
  (is (= "Function()" (fmt/format-type {:base-type "Function" :param-types [] :return-type "Void"}))))

(deftest expressions-read-as-written
  (testing "lambdas"
    (is (= "xs.all(fn (w) => w.length <= n)" (printed "xs.all(fn (w) => w.length <= n)")))
    (is (= "xs.all(fn (w: String): Boolean do result := w.length <= n end)"
           (printed "xs.all(fn (w: String): Boolean do result := w.length <= n end)"))))
  (testing "parentheses where the meaning needs them, and only there"
    (is (= "(a + b) * c" (printed "(a + b) * c")))
    (is (= "a + b * c" (printed "a + (b * c)")))
    (is (= "a - (b - c)" (printed "a - (b - c)")))
    (is (= "not (a = b) and -(x + 1) < 0" (printed "not (a = b) and -(x + 1) < 0")))
    (is (= "a implies b implies c" (printed "a implies (b implies c)")))
    (is (= "(a implies b) implies c" (printed "(a implies b) implies c")))
    (is (= "(a + b).to_string" (printed "(a + b).to_string"))))
  (testing "calls, with and without parentheses, and on a result"
    (is (= "s.length + s.trim().length + f()" (printed "s.length + s.trim().length + f()")))
    (is (= "make_adder(1)(2)" (printed "make_adder(1)(2)"))))
  (testing "literals"
    (is (= "\"say \\\"hi\\\"\\n\"" (printed "\"say \\\"hi\\\"\\n\"")))
    (is (= "#a" (printed "#a")))
    (is (= "{\"a\": 1}" (printed "{\"a\": 1}")))
    (is (= "#{1, 2}" (printed "#{1, 2}")))
    (is (= "create Box[Integer].make(1)" (printed "create Box[Integer].make(1)")))))

(defn- clean [x]
  (walk/postwalk #(cond
                    (map? %) (dissoc % :dbg/line :dbg/col :class-def :class-name :value-str :text :source)
                    :else %)
                 x))

(deftest every-contract-round-trips
  (testing "every contract in lib/ and examples/, printed and parsed again, is the same AST"
    (let [conditions (for [f (concat (file-seq (io/file "lib")) (file-seq (io/file "examples")))
                           :let [path (.getPath ^java.io.File f)]
                           :when (and (str/ends-with? path ".nex")
                                      (not (str/includes? path (str java.io.File/separator "build" java.io.File/separator))))
                           :let [ast (try (p/ast (slurp f)) (catch Throwable _ nil))]
                           :when ast
                           cls (:classes ast)
                           section (:body cls)
                           routine (concat (:members section) (:constructors section))
                           assertion (concat (:require routine) (:ensure routine))
                           :when (:condition assertion)]
                       [path (:condition assertion)])
          wrong (for [[path c] conditions
                      :let [text (fmt/format-expression c)]
                      :when (not= (clean c) (clean (try (expr text) (catch Throwable _ nil))))]
                  (str path ": " text))]
      (is (< 500 (count conditions)) "the contracts were found")
      (is (empty? wrong) (str/join "\n" (take 10 wrong))))))

(deftest nex-doc-shows-contracts-as-code
  (let [doc (docgen/generate-file-doc
             "class Wrapper
              feature
                wrap(text: String, width: Integer): Array[String]
                require
                  words_fit: words(text).all(fn (w) => w.length <= width)
                do
                  result := []
                ensure
                  all_fit: result.all(fn (line) => line.length <= width)
                end

                words(text: String): Array[String] do
                  result := text.split(\" \")
                end
              end")]
    (is (str/includes? doc "**words_fit**: `words(text).all(fn (w) => w.length <= width)`") doc)
    (is (str/includes? doc "**all_fit**: `result.all(fn (line) => line.length <= width)`") doc)
    (is (str/includes? doc "### wrap(`text: String`, `width: Integer`): `Array[String]`") doc)
    (is (not (str/includes? doc ":expression-body")) doc)))
