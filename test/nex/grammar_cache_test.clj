(ns nex.grammar-cache-test
  "The prebuilt grammar (nex.grammar-cache), which a release starts from
   instead of running the ANTLR tool on nexlang.g4, must parse exactly as the
   tool-built parser does: the same trees, with the same positions, and the
   same errors."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clj-antlr.core :as antlr]
            [nex.grammar-cache :as grammar-cache]
            [nex.parser :as p]))

(def ^:private grammar-path "grammar/nexlang.g4")

(defn- prebuilt-data []
  (let [f (java.io.File/createTempFile "nexlang" ".atn")]
    (try
      (grammar-cache/write! grammar-path f)
      (grammar-cache/read-data f (slurp grammar-path :encoding "UTF-8"))
      (finally (.delete f)))))

(defn- same-tree?
  "Equal, with equal metadata (source positions) on every node."
  [a b]
  (and (= a b)
       (= (meta a) (meta b))
       (or (not (sequential? a)) (every? true? (map same-tree? a b)))))

(defn- outcome [parser text]
  (try
    {:tree (antlr/parse parser text)}
    (catch clj_antlr.ParseError e
      {:message (.getMessage e)
       :entries (p/parse-error-entries e text 0)})))

(def ^:private sources
  (delay
    (->> (file-seq (java.io.File. "."))
         (map str)
         (filter #(str/ends-with? % ".nex"))
         (remove #(str/includes? % "/target/"))
         sort
         (map slurp))))

(deftest prebuilt-parser-matches-tool-parser-test
  (let [tool-parser (antlr/parser grammar-path)
        prebuilt (grammar-cache/parser (prebuilt-data))
        texts (mapcat (fn [s] [s (subs s 0 (quot (count s) 2))]) @sources)
        mismatches (remove (fn [text]
                             (let [a (outcome tool-parser text)
                                   b (outcome prebuilt text)]
                               (if (:tree a)
                                 (and (:tree b) (same-tree? (:tree a) (:tree b)))
                                 (= a b))))
                           texts)]
    (is (< 500 (count texts)) "sanity check: the .nex files were found")
    (is (empty? mismatches)
        (str (count mismatches) " inputs parse differently, e.g.\n"
             (some-> (first mismatches) (subs 0 (min 300 (count (first mismatches)))))))))

(deftest prebuilt-grammar-must-match-its-grammar-test
  (testing "a prebuilt grammar made from a different grammar text is ignored"
    (let [f (java.io.File/createTempFile "nexlang" ".atn")]
      (try
        (grammar-cache/write! grammar-path f)
        (is (some? (grammar-cache/read-data f (slurp grammar-path :encoding "UTF-8"))))
        (is (nil? (grammar-cache/read-data f (str (slurp grammar-path :encoding "UTF-8") "\n// edited\n"))))
        (finally (.delete f))))))
