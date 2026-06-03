(ns nex.parser
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nex.walker :as walker]
            [clj-antlr.core :as antlr])
  (:import [clj_antlr ParseError]))

(defn- packaged-grammar-path
  []
  (let [resource-name "grammar/nexlang.g4"
        local-file (io/file resource-name)]
    (cond
      (.exists local-file)
      (.getPath local-file)

      :else
      (when-let [resource (io/resource resource-name)]
        (let [tmp-dir (doto (.toFile (java.nio.file.Files/createTempDirectory
                                      "nexlang-grammar"
                                      (make-array java.nio.file.attribute.FileAttribute 0)))
                        (.deleteOnExit))
              tmp-file (doto (io/file tmp-dir "nexlang.g4")
                         (.deleteOnExit))]
          (with-open [in (io/input-stream resource)
                      out (io/output-stream tmp-file)]
            (io/copy in out))
          (.getPath tmp-file))))))

(def parser
  (antlr/parser (or (packaged-grammar-path)
                    (throw (ex-info "Could not locate grammar/nexlang.g4"
                                    {:resource "grammar/nexlang.g4"})))))

(defn parse [input]
  (antlr/parse parser input))

(defn ast [input]
  (-> input
      parse
      walker/walk-node))

(defn- simplify-expected
  [expected-str]
  (if-let [[_ tokens] (re-matches #"\{(.+)\}" expected-str)]
    (let [items (set (map str/trim (str/split tokens #",")))
          has-identifier (items "IDENTIFIER")
          has-literals (or (items "INTEGER") (items "REAL") (items "STRING"))
          has-class (items "'class'")
          has-function (or (items "'function'") (items "'fn'"))
          has-end (items "'end'")
          has-eof (items "<EOF>")
          expression-context? (and has-identifier has-literals (not has-eof))]
      (cond
        expression-context?        "an expression"
        (items "'then'")           "'then'"
        (and has-end (not has-eof) (not has-class)) "'end'"
        :else
        (let [readable (cond-> []
                         has-class    (conj "class declaration")
                         has-function (conj "function declaration")
                         has-end      (conj "'end'")
                         has-eof      (conj "end of input"))]
          (if (seq readable) (str/join ", " readable) expected-str))))
    expected-str))

(defn format-parse-errors
  "Print parse errors from a clj-antlr ParseError with source context and caret pointers.
   line-offset is subtracted from ANTLR line numbers (0 for files, >0 for REPL wrappers)."
  [^ParseError e source-code line-offset]
  (let [source-lines (str/split-lines source-code)
        num-lines    (count source-lines)
        errors       (.-errors e)
        adjusted     (keep (fn [err]
                             (let [line (- (:line err) line-offset)]
                               (when (and (pos? line) (<= line num-lines))
                                 (assoc err :adjusted-line line))))
                           errors)
        seen-lines   (atom #{})
        unique-errors (filter (fn [err]
                                (let [l (:adjusted-line err)]
                                  (when-not (@seen-lines l)
                                    (swap! seen-lines conj l)
                                    true)))
                              adjusted)
        limited-errors (take 3 unique-errors)]
    (if (empty? limited-errors)
      (let [last-line (last source-lines)
            last-num  num-lines]
        (println (str "  Line " last-num ": unexpected end of input"))
        (when last-line
          (println (str "  | " last-line))
          (println (str "  | " (apply str (repeat (count last-line) " ")) "^"))))
      (doseq [err limited-errors]
        (let [line (:adjusted-line err)
              col  (:char err)
              msg  (:message err)
              friendly-msg (-> msg
                               (str/replace #"mismatched input '(.+?)' expecting (.+)"
                                            (fn [[_ token expected]]
                                              (str "unexpected '" token "', expected " (simplify-expected expected))))
                               (str/replace #"extraneous input '(.+?)' expecting (.+)"
                                            (fn [[_ token expected]]
                                              (str "unexpected '" token "', expected " (simplify-expected expected))))
                               (str/replace #"missing '(.+?)' at '(.+?)'"
                                            "missing '$1' before '$2'"))]
          (println (str "  Line " line ", column " (inc col) ": " friendly-msg))
          (let [src-line (nth source-lines (dec line))]
            (println (str "  | " src-line))
            (when (and col (>= col 0))
              (println (str "  | " (apply str (repeat col " ")) "^")))))))))