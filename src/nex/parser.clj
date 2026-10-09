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

;;
;; Hints for syntax from other languages
;;
;; ANTLR reports where parsing failed, which for a habit carried over from
;; another language (`else if`, `&&`, `xs[0]`, a missing `end`) is often not
;; where the mistake is — `else if` and a missing `end` both fail at the end
;; of the file — and words it in parser terms ("no viable alternative",
;; "token recognition error"). These hints look for the mistake itself in
;; the source, on lines at or before the failure, and name the Nex way.

(defn- mask-source
  "SOURCE with the contents of every string literal and every comment replaced
   by spaces (newlines kept, so lines and columns are unchanged), letting the
   hints below match only real code; and each string literal's first and last
   line (1-based) and whether it was closed. A string may span lines in Nex,
   so a missing closing quote shows up as a string running on to a later
   line, or to the end of the file."
  [^String source]
  (let [n (count source)
        sb (StringBuilder. source)
        blank! (fn [i] (when (not= \newline (.charAt sb i)) (.setCharAt sb i \space)))]
    (loop [i 0, line 1, line-start 0, strings []]
      (if (>= i n)
        {:masked (str sb) :strings strings}
        (let [c (.charAt source i)
              nxt (when (< (inc i) n) (.charAt source (inc i)))]
          (cond
            (= c \newline) (recur (inc i) (inc line) (inc i) strings)

            (and (= c \-) (= nxt \-))
            (let [end (or (str/index-of source "\n" i) n)]
              (doseq [j (range i end)] (blank! j))
              (recur end line line-start strings))

            ;; A character literal, `#"` included, is not a string.
            (and (= c \#) nxt (not= nxt \newline))
            (do (when (not= nxt \{) (blank! (inc i)))
                (recur (+ i 2) line line-start strings))

            (or (= c \") (= c \'))
            (let [[end end-line end-line-start closed?]
                  (loop [j (inc i), l line, ls line-start]
                    (cond
                      (>= j n) [n l ls false]
                      (= (.charAt source j) \\) (recur (+ j 2) l ls)
                      (= (.charAt source j) c) [j l ls true]
                      (= (.charAt source j) \newline) (recur (inc j) (inc l) (inc j))
                      :else (recur (inc j) l ls)))]
              (doseq [j (range (inc i) (min end n))] (blank! j))
              (recur (inc end) end-line end-line-start
                     (conj strings {:line line :col (- i line-start)
                                    :end-line end-line :closed? closed?})))

            :else (recur (inc i) line line-start strings)))))))

(defn- first-word [line]
  (second (re-find #"^\s*([A-Za-z_]\w*)" line)))

(defn- indent-of [line]
  (count (re-find #"^\s*" line)))

(defn- section-of
  "The keyword of the class section, or the routine, that line LINE-IDX
   (0-based) of LINES belongs to, found by looking back for the line that
   opened it."
  [lines line-idx]
  (some (fn [i]
          (let [l (nth lines i)
                w (first-word l)]
            (cond
              (#{"feature" "create" "invariant" "class" "inherit" "require" "ensure" "function" "end"} w) w
              (re-find #"\bdo\s*$" l) "do")))
        (range (dec line-idx) -1 -1)))

(def ^:private block-openers
  "Statements that need a matching `end`, whose opening keyword starts a line."
  #{"if" "from" "across" "repeat" "case" "match" "select" "class" "function"})

(defn- unclosed-block
  "The innermost block, among those opened by a keyword at the start of a
   line, left without its `end` — matched by indentation, as beginners'
   code almost always is. {:keyword :line} or nil."
  [lines]
  (loop [[[i l] & more] (map-indexed vector lines), stack []]
    (if (nil? l)
      (when-let [{:keys [keyword line]} (peek stack)] {:keyword keyword :line line})
      (let [w (first-word l)
            ind (indent-of l)]
        (cond
          (and (block-openers w) (not (re-find #"\bend\s*$" l)))
          (recur more (conj stack {:keyword w :line (inc i) :indent ind}))

          (= w "end")
          (if-let [deeper (first (filter #(> (:indent %) ind) stack))]
            {:keyword (:keyword deeper) :line (:line deeper)}
            (recur more (if (some #(= (:indent %) ind) stack)
                          (vec (take-while #(< (:indent %) ind) stack))
                          stack)))

          :else (recur more stack))))))

(def ^:private section-keywords
  #{"end" "feature" "create" "invariant" "inherit" "do" "require" "ensure" "rescue"
    "else" "note" "then"})

(defn- next-code-line
  "The first non-blank line of LINES after line IDX (0-based), or \"\"."
  [lines idx]
  (or (first (remove str/blank? (drop (inc idx) lines))) ""))

(defn- line-hints
  "Hints for one masked source line: [{:col :message}], col 0-based. RAW is
   the same line unmasked, for quoting the user's own text back. EOF? is
   true when the parser ran off the end of the input."
  [line raw lines idx eof?]
  (let [hint (fn [re message-fn]
               (when-let [m (re-matcher re line)]
                 (when (.find m)
                   {:col (.start m) :message (message-fn m)})))
        quote-raw (fn [^java.util.regex.Matcher m g] (subs raw (.start m (int g)) (.end m (int g))))]
    (keep identity
          [(hint #"//" (constantly "Nex comments start with `--`, not `//`."))
           (hint #"&&" (constantly "Nex writes `and`, not `&&`."))
           (hint #"\|\|" (constantly "Nex writes `or`, not `||`."))
           (hint #";" (constantly "Nex does not end statements with a semicolon; remove the `;`."))
           (when eof?
             (hint #"\belse\s+if\b"
                   (constantly (str "Write `elseif` as one word. `else if` starts a second `if` inside"
                                    " the `else`, which would then need its own `end`."))))
           (hint #"\b(then|do|else)\s*\{|\)\s*\{\s*$"
                 (constantly (str "Nex blocks don't use braces: a block runs from `then` or `do`"
                                  " to `end`, as in `if x > 0 then ... end`.")))
           (hint #"([A-Za-z_][\w.]*)\s*([+\-*])=\s*(\S.*?)\s*$"
                 (fn [m] (let [target (quote-raw m 1) op (quote-raw m 2) value (quote-raw m 3)]
                           (str "Nex has no `" op "=`: write `" target " := " target " " op " " value "`."))))
           (when-not (#{"function" "class" "declare"} (first-word line))
             (let [m (re-matcher #"(?<![\w.])([a-z_][\w.]*)\[\s*([^\]\sA-Z][^\]]*)\]" line)]
               (when (.find m)
                 (let [target (quote-raw m 1) index (str/trim (quote-raw m 2))]
                   {:col (.start m)
                    :message (str "Nex has no `" target "[...]` indexing: use `" target ".get(" index
                                  ")` to read an element, and `" target ".put(" index
                                  ", value)` to change one.")}))))
           (when (and (re-matches #"\s*[a-z_]\w*\s*" line)
                      (not (section-keywords (first-word line)))
                      (not (#{"do" "require" "note"} (first-word (next-code-line lines idx))))
                      (= "feature" (section-of lines idx)))
             {:col (indent-of line)
              :message (str "A field needs a type: write `" (str/trim raw) ": Integer`, or whatever type"
                            " it should hold.")})])))

(defn- source-hints
  "Hints naming the likely mistake behind a syntax error, from the source
   itself: [{:line :col :message}], earliest first. ERROR-LINE is where the
   parser failed, or nil when it ran off the end of the input."
  [source error-line]
  (let [{:keys [masked strings]} (mask-source source)
        lines (str/split-lines masked)
        raw-lines (str/split-lines source)
        upto (or error-line (count lines))
        open-string (first (filter #(and (or (not (:closed? %)) (> (:end-line %) (:line %)))
                                         (or (nil? error-line)
                                             (<= (:line %) error-line (:end-line %))))
                                   strings))
        line-level (for [idx (range (min upto (count lines)))
                         h (line-hints (nth lines idx) (nth raw-lines idx "") lines idx (nil? error-line))]
                     (assoc h :line (inc idx)))
        outside-feature (when error-line
                          (let [idx (dec error-line)
                                l (nth lines idx "")]
                            (when (and (re-find #"^\s*[a-z_]\w*\s*:\s*\S" l)
                                       (#{"class" "inherit"} (section-of lines idx)))
                              {:line error-line :col (indent-of l)
                               :message (str "Fields and routines go in a `feature` section:"
                                             " add a line `feature` above this one.")})))
        unclosed (when (nil? error-line)
                   (when-let [{:keys [keyword line]} (unclosed-block lines)]
                     {:line line :col (indent-of (nth lines (dec line)))
                      :message (str "The `" keyword "` on line " line " is never closed:"
                                    " add `end` after its body.")}))]
    (->> (concat (when open-string
                   [{:line (:line open-string) :col (:col open-string)
                     :message "This string is never closed: add the missing `\"` at its end."}])
                 line-level
                 [outside-feature]
                 ;; A missing `end` is only a guess once nothing above explains
                 ;; running off the end.
                 (when (and unclosed (empty? line-level) (nil? open-string)) [unclosed]))
         (remove nil?)
         (sort-by (juxt :line :col))
         (reduce (fn [acc h] (if (some #(= (:line %) (:line h)) acc) acc (conj acc h))) [])
         (take 3))))

(defn- print-located
  [source-lines line col message]
  (println (str "  Line " line (when col (str ", column " (inc col))) ": " message))
  (when-let [src-line (nth source-lines (dec line) nil)]
    (println (str "  | " src-line))
    (when (and col (>= col 0))
      (println (str "  | " (apply str (repeat col " ")) "^")))))

(defn- plain-parser-message
  "ANTLR's wording for errors no hint covers, minus its jargon."
  [msg src-line col]
  (let [token-at (fn [] (when (and src-line col (< col (count src-line)))
                          (re-find #"^\S+" (subs src-line col))))]
    (cond
      (re-find #"^token recognition error at: '(.*)'$" msg)
      (str "unexpected character '" (second (re-find #"^token recognition error at: '(.*)'$" msg)) "'")

      (str/starts-with? msg "no viable alternative at input")
      (if-let [t (token-at)]
        (str "unexpected '" t "'")
        "this line could not be understood")

      :else msg)))

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
        limited-errors (take 3 unique-errors)
        ;; nil when the parser ran off the end of the input
        error-line (let [err (first limited-errors)]
                     (when (and err (not (str/includes? (str (:message err)) "<EOF>")))
                       (:adjusted-line err)))
        hints (try (source-hints source-code error-line)
                   (catch Exception _ nil))]
    (cond
      (seq hints)
      (doseq [{:keys [line col message]} hints]
        (print-located source-lines line col message))

      (empty? limited-errors)
      (let [last-line (last source-lines)
            last-num  num-lines]
        (println (str "  Line " last-num ": unexpected end of input"))
        (when last-line
          (println (str "  | " last-line))
          (println (str "  | " (apply str (repeat (count last-line) " ")) "^"))))

      :else
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
                                            "missing '$1' before '$2'")
                               (plain-parser-message (nth source-lines (dec line) nil) col))]
          (print-located source-lines line col friendly-msg))))))
