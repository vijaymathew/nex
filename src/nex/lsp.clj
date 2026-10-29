(ns nex.lsp
  "`nex lsp`: a Language Server Protocol server over stdin/stdout.

   It gives any LSP editor (VS Code, Neovim, Helix, Zed, Emacs eglot, ...)
   - diagnostics: the syntax and type errors `nex <file>` would report,
     checked as you type and again on save;
   - formatting: `nex format` on the whole document;
   - an outline: classes, their constructors and features, functions,
     unions, type aliases and top-level `let`s;
   - hover: the type of a variable, or the signature, note and contracts of
     the feature, function or class a name refers to;
   - go to definition, into interned files too.

   Everything runs on one worker thread, in the order messages arrive, so
   the parser and type checker are never used from two threads at once.
   stdout carries only protocol messages: anything else the program prints
   goes to stderr, which editors show as the server's log."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nex.compiler.jvm.file :as jvm-file]
            [nex.fmt :as fmt]
            [nex.parser :as parser]
            [nex.predeclared :as predeclared]
            [nex.typechecker :as tc])
  (:import [clj_antlr ParseError]
           [java.io BufferedInputStream BufferedOutputStream ByteArrayOutputStream
            FileDescriptor FileInputStream FileOutputStream InputStream OutputStream
            PrintStream]
           [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.nio.file Paths]
           [java.util.concurrent Executors ScheduledExecutorService TimeUnit])
  (:gen-class))

;;
;; Wire format: JSON-RPC messages framed by a Content-Length header
;;

(defn- read-header-line
  "One header line (without its CRLF), or nil at end of input."
  [^InputStream in]
  (let [buf (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) (when (pos? (.size buf)) (.toString buf "US-ASCII"))
          (= b 10) (str/replace (.toString buf "US-ASCII") #"\r$" "")
          :else (do (.write buf b) (recur)))))))

(defn- read-message
  "The next message from IN as a map with keyword keys, or nil at end of
   input."
  [^InputStream in]
  (loop [length nil]
    (let [line (read-header-line in)]
      (cond
        (nil? line) nil
        (str/blank? line)
        (if length
          (let [bytes (byte-array length)]
            (loop [off 0]
              (when (< off length)
                (let [n (.read in bytes off (- length off))]
                  (when (neg? n)
                    (throw (ex-info "end of input inside a message" {})))
                  (recur (+ off n)))))
            (json/read-str (String. bytes StandardCharsets/UTF_8) :key-fn keyword))
          (recur nil))
        :else
        (let [[_ k v] (re-matches #"(?i)([^:]+):\s*(.*)" line)]
          (recur (if (and k (= "content-length" (str/lower-case (str/trim k))))
                   (parse-long (str/trim v))
                   length)))))))

(def protocol-out
  "Where protocol messages go: the real stdout (System/out itself is pointed
   at stderr in -main). Tests swap in their own stream."
  (atom (BufferedOutputStream. (FileOutputStream. FileDescriptor/out))))

(defn- send!
  [msg]
  (let [^OutputStream out @protocol-out
        body (.getBytes ^String (json/write-str (assoc msg :jsonrpc "2.0"))
                        StandardCharsets/UTF_8)
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n")
                          StandardCharsets/US_ASCII)]
    (locking out
      (.write out header)
      (.write out body)
      (.flush out))))

(defn- respond! [id result] (send! {:id id :result result}))

(defn- respond-error! [id code message]
  (send! {:id id :error {:code code :message message}}))

(defn- notify! [method params] (send! {:method method :params params}))

(defn- log [& parts]
  (binding [*out* *err*]
    (println (str/join " " (cons "[nex lsp]" parts)))))

;;
;; Documents
;;

(def ^:private documents
  "uri -> {:text :version}, for every document the editor has open."
  (atom {}))

(def ^:private last-symbols
  "uri -> the outline of the last version of the document that parsed, shown
   while the current one has a syntax error."
  (atom {}))

(defn- uri->path
  "The file a file: URI names, or nil for any other scheme."
  [uri]
  (try
    (let [u (URI. uri)]
      (when (= "file" (.getScheme u))
        (str (Paths/get u))))
    (catch Exception _ nil)))

(defn- path->uri [path]
  (str (.toUri (.toAbsolutePath (.toPath (io/file path))))))

(defn- source-id
  "The path the document is checked as: interns are found relative to it."
  [uri]
  (or (some-> (uri->path uri) io/file .getCanonicalPath)
      (str (io/file (or (System/getenv "NEX_USER_DIR") (System/getProperty "user.dir"))
                    "untitled.nex"))))

(defn- text-lines
  "TEXT's lines, without their line breaks; a trailing break gives a last,
   empty line, as editors count them."
  [text]
  (mapv #(str/replace % #"\r$" "") (str/split text #"\n" -1)))

;;
;; Diagnostics
;;

(defn- word-char? [c] (or (Character/isLetterOrDigit (char c)) (= \_ c)))

(defn- token-end
  "Where the token starting at COL (0-based) on LINE-TEXT ends: the end of the
   word there, else one character on."
  [line-text col]
  (let [n (count line-text)]
    (cond
      (>= col n) n
      (word-char? (.charAt ^String line-text col))
      (loop [i col]
        (if (and (< i n) (word-char? (.charAt ^String line-text i))) (recur (inc i)) i))
      :else (inc col))))

(defn- line-range
  "The LSP range for LINE (1-based) of LINES from COL (0-based; nil = the
   whole line), LENGTH characters long or else to the end of the token there."
  [lines line col length]
  (let [l (max 0 (min (dec (count lines)) (dec (or line 1))))
        text (nth lines l "")
        indent (count (re-find #"^\s*" text))]
    (if (nil? col)
      {:start {:line l :character indent} :end {:line l :character (count text)}}
      (let [c (max 0 (min col (count text)))
            end (if (and length (pos? length))
                  (min (count text) (+ c length))
                  (token-end text c))]
        {:start {:line l :character c}
         :end {:line l :character (max end (min (count text) (inc c)))}}))))

(defn- diagnostic
  [lines {:keys [line col length message severity related]}]
  (cond-> {:range (line-range lines line col length)
           :severity (or severity 1)
           :source "nex"
           :message message}
    related (assoc :relatedInformation related)))

(defn- message-line
  "A line number mentioned in an error message (\"line 12\"), or nil."
  [message]
  (some-> (re-find #"(?i)\bline (\d+)" (str message)) second parse-long))

(defn- intern-line
  "The line (1-based) of the `intern` statement that brings in FILE, else of
   the first `intern`, else nil."
  [lines file]
  (let [stem (some-> file io/file .getName (str/replace #"\.nex$" ""))
        interns (keep-indexed (fn [i l] (when (re-find #"^\s*intern\b" l) (inc i))) lines)]
    (or (when stem
          (first (filter #(str/includes? (nth lines (dec %)) stem) interns)))
        (first interns))))

(defn- parse-error-diagnostics [lines ^ParseError e text]
  (mapv (fn [{:keys [line col length message]}]
          {:line line :col col :length length :message (str "Syntax error: " message)})
        (parser/parse-error-entries e text 0)))

(defn- type-error-diagnostic
  [lines {:keys [message line column source-file]}]
  (if source-file
    ;; An error inside a file this one interns: shown on the `intern`, with
    ;; its own location attached.
    (cond-> {:line (intern-line lines source-file)
             :message (str "In " (.getName (io/file source-file))
                           (when line (str " (line " line ")")) ": " message)}
      (and line (.isAbsolute (io/file source-file)))
      (assoc :related [{:location {:uri (path->uri source-file)
                                   :range {:start {:line (dec line) :character (max 0 (dec (or column 1)))}
                                           :end {:line (dec line) :character (max 0 (dec (or column 1)))}}}
                        :message message}]))
    {:line line :col (some-> column dec) :message message}))

(defn- class-line
  "The line (1-based) where class NAME is declared, or nil."
  [lines name]
  (let [re (re-pattern (str "^\\s*(?:(?:deferred|sealed|enum)\\s+)*(?:class|union)\\s+"
                            (java.util.regex.Pattern/quote name) "\\b"))]
    (some (fn [[i l]] (when (re-find re l) (inc i))) (map-indexed vector lines))))

(defn- warning-diagnostic [lines message]
  {:line (or (some->> (re-find #"Class '(\w+)'" message) second (class-line lines))
             (message-line message))
   :message message
   :severity 2})

(defn- exception-diagnostic
  "A diagnostic for an error the checker threw rather than collected."
  [lines text ^Throwable e]
  (let [data (ex-data e)]
    (cond
      (:nex/intern-parse-error data)
      (let [{:keys [file-path source parse-error]} data
            entry (first (parser/parse-error-entries parse-error source 0))]
        {:line (intern-line lines file-path)
         :message (str "Syntax error in " (.getName (io/file file-path))
                       (when entry (str " (line " (:line entry) "): " (:message entry))))
         :related (when (and entry (.isAbsolute (io/file file-path)))
                    [{:location {:uri (path->uri file-path)
                                 :range {:start {:line (dec (:line entry)) :character (or (:col entry) 0)}
                                         :end {:line (dec (:line entry)) :character (or (:col entry) 0)}}}
                      :message (:message entry)}])})

      ;; An intern that names no file.
      (:searched-locations data)
      {:line (intern-line lines (:class-name data))
       :message (ex-message e)}

      :else
      (let [message (or (ex-message e) (str e))]
        {:line (or (:line data) (:dbg/line data) (message-line message))
         :col (some-> (or (:column data) (:dbg/col data)) dec)
         :message message}))))

(defn analyze-text
  "Check TEXT as the file at SOURCE-ID. Returns {:diagnostics :program}:
   the errors `nex <file>` would stop at before running it, as
   [{:line :col :length :message :severity :related}] (line 1-based, col
   0-based), and, when TEXT parses, what hover and go-to-definition need —
   {:ast (TEXT's own) :module-ast (with its interns) :types} where :types is
   what the checker recorded about each name (tc/*expression-types*)."
  [source-id text]
  (let [lines (text-lines text)
        recorded {:by-pos (atom {}) :by-node (java.util.IdentityHashMap.)}
        program (atom nil)]
    (try
      (let [own-ast (parser/ast text)
            ast (predeclared/add own-ast)
            module-ast (jvm-file/augment-ast-with-interns source-id ast)
            _ (reset! program {:ast own-ast :module-ast module-ast :types {}})
            result (binding [tc/*expression-types* recorded]
                     (tc/type-check module-ast {:strict-undefined-targets? true
                                                :reject-discarded-top-level? true
                                                :max-errors 100}))]
        {:diagnostics (vec (concat (map #(type-error-diagnostic lines %) (:errors result))
                                   (map #(warning-diagnostic lines %) (:warnings result))))
         :program (assoc @program :types @(:by-pos recorded))})
      (catch ParseError e
        {:diagnostics (parse-error-diagnostics lines e text)})
      (catch Throwable e
        {:diagnostics [(exception-diagnostic lines text e)]
         :program (some-> @program (assoc :types @(:by-pos recorded)))}))))

(defn check-text
  "The diagnostics for TEXT, checked as the file at SOURCE-ID (see
   analyze-text)."
  [source-id text]
  (:diagnostics (analyze-text source-id text)))

(def ^:private programs
  "uri -> {:version :lines :program}, from the last check of a version of
   the document that parsed: hover and go-to-definition read it."
  (atom {}))

(def ^:private checked-versions
  "uri -> the version of the document last checked, whether or not it
   parsed."
  (atom {}))

(defn- check-document!
  "Check URI's document as it is now, remember what hover needs, and return
   its diagnostics."
  [uri text version]
  (let [{:keys [diagnostics program]} (binding [*out* *err*]
                                        (analyze-text (source-id uri) text))]
    (swap! checked-versions assoc uri version)
    (when program
      (swap! programs assoc uri {:version version :lines (text-lines text) :program program}))
    diagnostics))

(defn- ensure-checked!
  "Check URI's document now unless its current version already has been:
   a hover can arrive before the check its last change scheduled."
  [uri]
  (when-let [{:keys [text version]} (get @documents uri)]
    (when-not (and (contains? @checked-versions uri)
                   (= version (get @checked-versions uri)))
      (check-document! uri text version))))

(defn- publish-diagnostics! [uri text version]
  (let [lines (text-lines text)
        diags (mapv #(diagnostic lines %) (check-document! uri text version))]
    (notify! "textDocument/publishDiagnostics"
             (cond-> {:uri uri :diagnostics diags}
               version (assoc :version version)))))

;;
;; Outline (textDocument/documentSymbol)
;;

(def ^:private symbol-kind
  {:class 5 :method 6 :field 8 :constructor 9 :enum 10 :function 12
   :variable 13 :constant 14 :enum-member 22 :type-parameter 26})

(defn- indent-of [line] (count (re-find #"^\s*" line)))

(defn- code-line? [line]
  (let [t (str/trim line)]
    (and (seq t) (not (str/starts-with? t "--")))))

(defn- block-end
  "The line (0-based) where the block starting on line START (0-based) ends:
   its `end` at the same indentation, else its last line before the next one
   indented no deeper than it."
  [lines start]
  (let [indent (indent-of (nth lines start ""))]
    (loop [i (inc start) last-code start]
      (if (>= i (count lines))
        last-code
        (let [l (nth lines i)]
          (cond
            (not (code-line? l)) (recur (inc i) last-code)
            (> (indent-of l) indent) (recur (inc i) i)
            (and (= (indent-of l) indent) (re-find #"^\s*end\b" l)) i
            :else last-code))))))

(defn- name-range
  "The range of NAME on line L (0-based), searching from COL (0-based)."
  [lines l col name]
  (let [text (nth lines l "")
        m (re-matcher (re-pattern (str "\\b" (java.util.regex.Pattern/quote name) "\\b")) text)
        c (if (.find m (min (max 0 col) (count text))) (.start m) (max 0 (min col (count text))))]
    {:start {:line l :character c}
     :end {:line l :character (min (count text) (+ c (count name)))}}))

(defn- make-symbol
  "A DocumentSymbol for NAME declared on LINE / COL (both 1-based). BLOCK?
   extends its range to the block's `end`."
  [lines {:keys [name detail kind line col block? children]}]
  (let [l (max 0 (min (dec (count lines)) (dec line)))
        c (dec (or col 1))
        end-l (if block? (block-end lines l) l)
        sel (name-range lines l c name)]
    (cond-> {:name name
             :kind (symbol-kind kind)
             :range {:start {:line l :character 0}
                     :end {:line end-l :character (count (nth lines end-l ""))}}
             :selectionRange sel}
      detail (assoc :detail detail)
      (seq children) (assoc :children (vec children)))))

(defn- type-str [t] (when t (fmt/format-type t)))

(defn- params-str [params]
  (str "(" (str/join ", " (map #(str (:name %) ": " (type-str (:type %))) params)) ")"))

(defn- routine-detail [{:keys [params return-type]}]
  (str (params-str params) (when return-type (str ": " (type-str return-type)))))

(defn- member-symbol [lines {:keys [type name] :as m}]
  (when (:dbg/line m)
    (case type
      :method (make-symbol lines {:name name :kind :method :detail (routine-detail m)
                                  :line (:dbg/line m) :col (:dbg/col m)
                                  :block? (not (:declaration-only? m))})
      :constructor (make-symbol lines {:name name :kind :constructor :detail (params-str (:params m))
                                       :line (:dbg/line m) :col (:dbg/col m) :block? true})
      :field (make-symbol lines {:name name :kind (if (:constant? m) :constant :field)
                                 :detail (type-str (:field-type m))
                                 :line (:dbg/line m) :col (:dbg/col m)})
      nil)))

(defn- class-members [class-def]
  (mapcat (fn [section]
            (case (:type section)
              :feature-section (:members section)
              :constructors (:constructors section)
              nil))
          (:body class-def)))

(defn- class-symbol [lines c]
  (make-symbol lines {:name (:name c)
                      :kind :class
                      :detail (str/join " " (remove nil? [(when (:sealed? c) "sealed")
                                                          (when (:deferred? c) "deferred")
                                                          (when-let [ps (seq (:parents c))]
                                                            (str "inherit " (str/join ", " (map :parent ps))))]))
                      :line (:dbg/line c) :col (:dbg/col c) :block? true
                      :children (keep #(member-symbol lines %) (class-members c))}))

(defn- union-symbols
  "Unions are desugared before the outline sees them, into classes without
   positions; they are found again in the source by name."
  [lines classes]
  (let [variants-of (group-by #(-> % :parents first :parent) classes)]
    (for [c classes
          :when (and (nil? (:dbg/line c)) (:sealed? c))
          :let [line (class-line lines (:name c))]
          :when line]
      (let [end (block-end lines (dec line))]
        (make-symbol lines
                     {:name (:name c) :kind :enum :line line :block? true
                      :children (for [v (variants-of (:name c))
                                      :let [re (re-pattern (str "^\\s*" (java.util.regex.Pattern/quote (:name v)) "\\b"))
                                            vl (some #(when (re-find re (nth lines %)) %) (range line (inc end)))]
                                      :when vl]
                                  (make-symbol lines {:name (:name v) :kind :enum-member :line (inc vl)}))})))))

(defn document-symbols
  "The outline of a program's AST (before predeclared names are added)."
  [lines ast]
  (let [classes (:classes ast)]
    (->> (concat
          (for [c classes :when (:dbg/line c)] (class-symbol lines c))
          (union-symbols lines classes)
          (for [f (:functions ast) :when (:dbg/line f)]
            (make-symbol lines {:name (:name f) :kind :function :detail (routine-detail f)
                                :line (:dbg/line f) :col (:dbg/col f)
                                :block? (not (:declaration-only? f))}))
          (for [a (:type-aliases ast) :when (:dbg/line a)]
            (make-symbol lines {:name (:name a) :kind :type-parameter
                                :detail (type-str (:type-expr a))
                                :line (:dbg/line a) :col (:dbg/col a)}))
          (for [s (:statements ast) :when (and (= :let (:type s)) (:dbg/line s) (string? (:name s)))]
            (make-symbol lines {:name (:name s) :kind :variable :detail (type-str (:var-type s))
                                :line (:dbg/line s) :col (:dbg/col s)})))
         (sort-by #(get-in % [:range :start :line]))
         vec)))

(defn- symbols-for [uri]
  (if-let [{:keys [text]} (get @documents uri)]
    (try
      (let [syms (document-symbols (text-lines text) (parser/ast text))]
        (swap! last-symbols assoc uri syms)
        syms)
      (catch Throwable _
        (get @last-symbols uri [])))
    []))

;;
;; Formatting
;;

(defn- whole-document-range [lines]
  {:start {:line 0 :character 0}
   :end {:line (dec (count lines)) :character (count (peek lines))}})

(defn- format-edits [uri]
  (when-let [{:keys [text]} (get @documents uri)]
    (try
      (let [formatted (fmt/format-code text)]
        (if (= formatted text)
          []
          [{:range (whole-document-range (str/split text #"\n" -1))
            :newText formatted}]))
      (catch Throwable e
        ;; A file that does not parse is already marked by its diagnostics.
        (when-not (instance? ParseError (:cause (ex-data e)))
          (notify! "window/showMessage"
                   {:type 2 :message (str "Nex: " (or (:error (ex-data e)) (ex-message e)))}))
        []))))

;;
;; Hover and go-to-definition
;;
;; Both start from the name under the cursor and resolve it the same way
;; (resolve-at): through what the checker recorded about it — the type of
;; a variable, the receiver type of a call — to the declaration it names,
;; which may be in an interned file. Locals and parameters, which the
;; checker keeps no positions for, are found in the text of the enclosing
;; routine.

(defn- word-at
  "[start end word] of the identifier at CHARACTER (0-based) on LINE-TEXT,
   or nil."
  [^String line-text character]
  (let [n (count line-text)
        c (max 0 (min character n))
        start (loop [i c] (if (and (pos? i) (word-char? (.charAt line-text (dec i)))) (recur (dec i)) i))
        end (loop [i c] (if (and (< i n) (word-char? (.charAt line-text i))) (recur (inc i)) i))]
    (when (and (< start end) (not (Character/isDigit (.charAt line-text start))))
      [start end (subs line-text start end)])))

(defn- word-pattern [name]
  (re-pattern (str "(?<![A-Za-z0-9_])" (java.util.regex.Pattern/quote name) "(?![A-Za-z0-9_])")))

(defn- name-start
  "Where NAME starts on TEXT at or just after FROM (a member access's `.`
   precedes its name), or nil."
  [^String text from name]
  (let [m (re-matcher (word-pattern name) text)
        from (max 0 (min from (count text)))]
    (when (.find m from)
      (when (<= (- (.start m) from) 3) (.start m)))))

(defn- index-types
  "The checker's records ({[line col] entry}, 1-based, col at a name or at
   the `.` before it) keyed by where each name starts on LINES: {[line col]
   entry}, 0-based."
  [lines types]
  (reduce-kv (fn [idx [l c] entry]
               (let [li (dec l)]
                 (if-let [start (some-> (nth lines li nil) (name-start (dec c) (:name entry)))]
                   (assoc idx [li start] entry)
                   idx)))
             {} types))

(defn- type-base
  "The class a type names: `?Pt` and `Pt` are both Pt, `Array[T]` is Array."
  [t]
  (cond
    (string? t) (str/replace t #"^\?" "")
    (map? t) (:base-type t)))

(defn- class-table [module-ast]
  (reduce (fn [m c]
            (cond-> (assoc m (:name c) c)
              (:qualified-name c) (assoc (:qualified-name c) c)))
          {} (:classes module-ast)))

(defn- find-member
  "The feature or constructor NAME of class CLASS-NAME or its nearest
   ancestor that has one: {:member m :class c}, or nil."
  [classes class-name name]
  (loop [queue [class-name] seen #{}]
    (when-let [[cn & more] (seq queue)]
      (if (seen cn)
        (recur more seen)
        (let [c (get classes cn)
              m (some #(when (= name (:name %)) %) (class-members c))]
          (if m
            {:member m :class c}
            (recur (concat more (keep :parent (:parents c))) (conj seen cn))))))))

(defn- find-function [module-ast name]
  (some #(when (= name (:name %)) %) (:functions module-ast)))

(defn- routine-ranges
  "[{:start :end}] (0-based lines) of every routine body in AST."
  [lines ast]
  (vec (concat
        (for [c (:classes ast)
              m (class-members c)
              :when (and (:dbg/line m) (#{:method :constructor} (:type m)))]
          {:start (dec (:dbg/line m)) :end (block-end lines (dec (:dbg/line m)))})
        (for [f (:functions ast) :when (:dbg/line f)]
          {:start (dec (:dbg/line f)) :end (block-end lines (dec (:dbg/line f)))}))))

(defn- binding-matches
  "Where NAME is bound on line text L: `let name`, `as name` (across,
   object tests, match), `to name:` (convert), and parameter lists —
   [[col kind]], kind :let, :local or :parameter."
  [^String l name]
  (let [q (java.util.regex.Pattern/quote name)
        found (fn [re kind]
                (let [m (re-matcher re l)]
                  (loop [acc []]
                    (if (.find m) (recur (conj acc [(.start m 1) kind])) acc))))]
    (concat (found (re-pattern (str "\\blet\\s+(" q ")\\b")) :let)
            (found (re-pattern (str "\\bas\\s+(" q ")\\b")) :local)
            (found (re-pattern (str "\\bto\\s+(" q ")\\s*:")) :local)
            ;; inside a parenthesised parameter list: `f(a, b: T)`, `fn (x)`
            (for [[open close] (let [m (re-matcher #"\(([^()]*)\)" l)]
                                 (loop [acc []] (if (.find m) (recur (conj acc [(.start m 1) (.end m 1)])) acc)))
                  :let [inner (subs l open close)]
                  :when (re-find #":" inner)
                  :let [m (re-matcher (word-pattern name) inner)]
                  :when (.find m)
                  :let [after (subs inner (.end m))]
                  :when (re-find #"^\s*[,:]" after)]
              [(+ open (.start m)) :parameter]))))

(defn- local-binding
  "The closest binding of NAME at or above (LINE, COL) within the routine
   around it, or at top level when there is none: {:line :col :kind},
   0-based, kind :local or :parameter."
  [lines ast line col name]
  (let [routine (->> (routine-ranges lines ast)
                     (filter #(<= (:start %) line (:end %)))
                     (sort-by :start)
                     last)
        from (or (:start routine) 0)]
    (some (fn [l]
            (let [text (nth lines l "")
                  ;; parameter lists: the routine's own header, or a `fn`
                  params-here? (or (and routine (= l from)) (re-find #"\bfn\b" text))
                  hits (cond->> (binding-matches text name)
                         (not params-here?) (remove #(= :parameter (second %)))
                         (= l line) (filter #(<= (first %) col)))]
              (when-let [[c kind] (last (sort-by first hits))]
                {:line l :col c
                 :kind (cond (= kind :parameter) :parameter
                             (and (nil? routine) (= kind :let)) :global
                             :else :local)})))
          (range line (dec from) -1))))

(defn- enclosing
  "The innermost of NODES (with :dbg/line) whose block contains LINE
   (0-based)."
  [lines nodes line]
  (->> nodes
       (filter #(and (:dbg/line %)
                     (<= (dec (:dbg/line %)) line (block-end lines (dec (:dbg/line %))))))
       (sort-by :dbg/line)
       last))

(defn- enclosing-routine
  "The method, constructor or function around LINE: {:routine r :class c}."
  [lines ast line]
  (let [in-class (enclosing lines (:classes ast) line)
        routines (if in-class
                   (filter #(#{:method :constructor} (:type %)) (class-members in-class))
                   (:functions ast))]
    (when-let [r (enclosing lines routines line)]
      {:routine r :class in-class})))

(defn- binding-type
  "The type of the variable bound at B ({:line :col}) named NAME: its
   annotation, else the type the checker gave its first use after it."
  [lines index b name]
  (let [text (nth lines (:line b) "")
        q (java.util.regex.Pattern/quote name)]
    (or (some-> (re-find (re-pattern (str "\\b" q "\\s*:\\s*([^:=,)]+?)\\s*(?::=|,|\\)|$)")) text)
                second str/trim not-empty)
        (some-> (re-find (re-pattern (str "\\b" q "\\s*,[^:()]*:\\s*([^:=,)]+?)\\s*(?:,|\\)|$)")) text)
                second str/trim not-empty)
        (->> index
             (filter (fn [[[l c] e]] (and (= name (:name e)) (not (:call? e))
                                          (or (> l (:line b)) (and (= l (:line b)) (> c (:col b)))))))
             (sort-by key)
             first val :type))))

(defn- create-class-before
  "The class named by `create C.` just before START on LINE-TEXT, or nil."
  [line-text start]
  (second (re-find #"\bcreate\s+([A-Za-z_][A-Za-z0-9_]*)(?:\[[^\]]*\])?\s*\.\s*$"
                   (subs line-text 0 start))))

(def ^:private builtin-env
  (delay (doto (tc/make-type-env) tc/register-builtin-methods)))

(defn- resolve-variable
  "What the variable NAME at (LINE, START) refers to: a local or
   parameter, a field of the class it is used in, a global or a function."
  [lines ast module-ast classes index entry line start name]
  (let [current-class (or (:current-class entry)
                          (:name (enclosing lines (:classes ast) line)))]
    (or (when-let [b (local-binding lines ast line start name)]
          (assoc b :what (if (= :global (:kind b)) :global :local)
                 :type (or (:type entry) (binding-type lines index b name))))
        (some-> (when current-class (find-member classes current-class name))
                (assoc :what :member))
        (when-let [s (some #(when (and (= :let (:type %)) (= name (:name %))) %) (:statements ast))]
          {:what :global :line (dec (:dbg/line s)) :col (name-start (nth lines (dec (:dbg/line s)) "") 0 name)
           :type (:type entry)})
        (some->> (find-function module-ast name) (hash-map :what :function :function))
        (when entry {:what :value :type (:type entry)}))))

(defn- resolve-at
  "What the name at POSITION in URI's document refers to, or nil:
   {:what kind :range r ...} with kind one of :member, :function, :class,
   :builtin, :local, :global, :result or :value."
  [uri {:keys [line character]}]
  (ensure-checked! uri)
  (let [{:keys [text]} (get @documents uri)
        {:keys [lines program]} (get @programs uri)
        current (when text (text-lines text))
        line-text (nth current line nil)]
    (when (and program line-text
               ;; The last check that parsed saw this line as it is now.
               (= line-text (nth lines line nil)))
      (when-let [[start end word] (word-at line-text character)]
        (let [{:keys [ast module-ast types]} program
              classes (class-table module-ast)
              index (index-types lines types)
              entry (get index [line start])
              word-range {:start {:line line :character start} :end {:line line :character end}}
              found
              (cond
                (:call? entry)
                (let [{:keys [name target target-type current-class]} entry
                      receiver (or (type-base target-type)
                                   (when (and target (get classes target)) target))]
                  (if receiver
                    (or (some-> (find-member classes receiver name) (assoc :what :member))
                        (when-let [sig (tc/env-lookup-method @builtin-env receiver name)]
                          {:what :builtin :sig sig}))
                    (or (some-> (when current-class (find-member classes current-class name))
                                (assoc :what :member))
                        (some->> (find-function module-ast name) (hash-map :what :function :function)))))

                (= "result" word)
                (when-let [{:keys [routine]} (enclosing-routine lines ast line)]
                  {:what :result :type (or (:type entry) (:return-type routine))})

                (create-class-before line-text start)
                (some-> (find-member classes (create-class-before line-text start) word)
                        (assoc :what :member))

                (get classes word)
                {:what :class :class (get classes word)}

                :else
                (or
                 ;; the name in a declaration itself
                 (some (fn [c]
                         (some #(when (and (= word (:name %)) (= (inc line) (:dbg/line %)))
                                  {:what :member :member % :class c})
                               (class-members c)))
                       (:classes ast))
                 (some #(when (and (= word (:name %)) (= (inc line) (:dbg/line %)))
                          {:what :function :function %})
                       (:functions ast))
                 (when (or entry (re-matches #"[a-z_][A-Za-z0-9_]*" word))
                   (resolve-variable lines ast module-ast classes index entry line start word))))]
          (when found
            (assoc found :range word-range :word word :entry entry :lines lines :classes classes)))))))

;; Hover text

(defn- code-block [s] (str "```nex\n" s "\n```"))

(defn- expr-str [e]
  (try (fmt/format-expression e) (catch Throwable _ "…")))

(defn- assertion-list [title clauses]
  (when (seq clauses)
    (str "\n\n**" title "**\n\n"
         (str/join "\n" (for [{:keys [label condition]} clauses]
                          (str "- " (when label (str label ": "))
                               "`" (expr-str condition) "`"))))))

(defn- show-type [t] (when t (tc/display-type t)))

(defn- routine-hover [owner r]
  (str (code-block (str (when owner (str owner ".")) (:name r) (routine-detail r)))
       (when-let [n (:note r)] (str "\n\n" n))
       (assertion-list "require" (:require r))
       (assertion-list "ensure" (:ensure r))))

(defn- member-hover [{:keys [member class]}]
  (let [owner (:name class)]
    (case (:type member)
      :field (str (code-block (str owner "." (:name member)
                                   (when-let [t (:field-type member)] (str ": " (type-str t)))
                                   (when (and (:constant? member) (:value member))
                                     (str " = " (expr-str (:value member))))))
                  (when-let [n (:note member)] (str "\n\n" n)))
      :constructor (str (code-block (str "create " owner "." (:name member) (params-str (:params member))))
                        (when-let [n (:note member)] (str "\n\n" n))
                        (assertion-list "require" (:require member))
                        (assertion-list "ensure" (:ensure member)))
      (routine-hover owner member))))

(defn- class-hover [c]
  (str (code-block (str (when (:sealed? c) "sealed ")
                        (when (:deferred? c) "deferred ")
                        "class " (:name c)
                        (when-let [gs (seq (:generic-params c))]
                          (str " [" (str/join ", " (map #(if (map? %) (:name %) %) gs)) "]"))
                        (when-let [ps (seq (:parents c))]
                          (str " inherit " (str/join ", " (map :parent ps))))))
       (when-let [n (:note c)] (str "\n\n" n))
       (assertion-list "invariant" (:invariant c))))

(defn- hover-markdown [{:keys [what entry word] :as found}]
  (case what
    :member (member-hover found)
    :function (routine-hover nil (:function found))
    :class (class-hover (:class found))
    :builtin (let [{:keys [params]} (:sig found)]
               (code-block (str (show-type (:target-type entry)) "." word (params-str params)
                                (when-not (= "Void" (:type entry)) (str ": " (show-type (:type entry)))))))
    :local (str (code-block (str word (when-let [t (:type found)] (str ": " (show-type t)))))
                "\n\n" (if (= :parameter (:kind found)) "parameter" "local variable"))
    :global (str (code-block (str word (when-let [t (:type found)] (str ": " (show-type t)))))
                 "\n\nglobal")
    :result (str (code-block (str "result" (when-let [t (:type found)] (str ": " (show-type t)))))
                 "\n\nthe value this routine returns")
    :value (when-let [t (:type found)] (code-block (str word ": " (show-type t))))
    nil))

(defn- hover [uri position]
  (when-let [found (resolve-at uri position)]
    (when-let [md (hover-markdown found)]
      {:contents {:kind "markdown" :value md}
       :range (:range found)})))

;; Definition

(defn- lines-of
  "The lines of the file at PATH: the open document's when it is URI's."
  [path uri lines]
  (if (or (nil? path) (= (path->uri path) uri))
    lines
    (try (text-lines (slurp path)) (catch Exception _ nil))))

(defn- declared-at
  "A Location for NAME declared on LINE / COL (1-based) of the file at PATH
   (nil: URI's own)."
  [uri lines path line col name]
  (when line
    (let [target-uri (if path (path->uri path) uri)
          target-lines (or (lines-of path uri lines) [""])]
      {:uri target-uri
       :range (name-range target-lines (dec line) (dec (or col 1)) name)})))

(defn- class-location [uri lines c]
  (let [path (:source-file c)
        target-lines (lines-of path uri lines)]
    (if (:dbg/line c)
      (declared-at uri lines path (:dbg/line c) (:dbg/col c) (:name c))
      ;; unions and their variants are desugared without positions
      (when-let [l (when target-lines
                     (or (class-line target-lines (:name c))
                         (some (fn [[i t]] (when (re-find (re-pattern (str "^\\s*" (java.util.regex.Pattern/quote (:name c))
                                                                            "\\s*(\\(|$)")) t)
                                             (inc i)))
                               (map-indexed vector target-lines))))]
        (declared-at uri lines path l 1 (:name c))))))

(defn- definition [uri position]
  (when-let [{:keys [what word lines] :as found} (resolve-at uri position)]
    (case what
      :member (let [{:keys [member class]} found]
                (declared-at uri lines (:source-file class) (:dbg/line member) (:dbg/col member) (:name member)))
      :function (let [f (:function found)]
                  (declared-at uri lines (:source-file f) (:dbg/line f) (:dbg/col f) (:name f)))
      :class (class-location uri lines (:class found))
      :local {:uri uri :range (name-range lines (:line found) (:col found) word)}
      :global {:uri uri :range (name-range lines (:line found) (or (:col found) 0) word)}
      nil)))

;;
;; Dispatch
;;

(def ^:private ^ScheduledExecutorService worker
  (Executors/newSingleThreadScheduledExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ r] (doto (Thread. ^Runnable r "nex-lsp-worker") (.setDaemon true))))))

(defn- on-worker
  "Run F on the worker thread, after DELAY-MS, logging what it throws."
  ([f] (on-worker 0 f))
  ([delay-ms f]
   (.schedule worker
              ^Runnable (fn []
                          (binding [*out* *err*]
                            (try (f) (catch Throwable e (log "error:" (str e))))))
              (long delay-ms) TimeUnit/MILLISECONDS)))

(def ^:private check-delay-ms
  "How long typing must pause before a document is checked."
  300)

(defn- schedule-check!
  "Check URI's document after DELAY-MS, unless it changes again first."
  [uri delay-ms]
  (let [version (:version (get @documents uri))]
    (on-worker delay-ms
               #(when-let [doc (get @documents uri)]
                  (when (= version (:version doc))
                    (publish-diagnostics! uri (:text doc) (:version doc)))))))

(def ^:private shut-down? (atom false))

(def server-capabilities
  {:textDocumentSync {:openClose true :change 1 :save {:includeText false}}
   :documentFormattingProvider true
   :documentSymbolProvider true
   :hoverProvider true
   :definitionProvider true})

(defn- handle-request [method params]
  (case method
    "initialize" {:capabilities server-capabilities
                  :serverInfo {:name "nex-lsp"}}
    "shutdown" (do (reset! shut-down? true) nil)
    "textDocument/formatting" (format-edits (get-in params [:textDocument :uri]))
    "textDocument/documentSymbol" (symbols-for (get-in params [:textDocument :uri]))
    "textDocument/hover" (hover (get-in params [:textDocument :uri]) (:position params))
    "textDocument/definition" (definition (get-in params [:textDocument :uri]) (:position params))
    ::unknown))

(defn- handle-notification [method params]
  (let [uri (get-in params [:textDocument :uri])]
    (case method
      "textDocument/didOpen"
      (let [{:keys [text version]} (:textDocument params)]
        (swap! documents assoc uri {:text text :version version})
        (schedule-check! uri 0))

      "textDocument/didChange"
      ;; Full sync: the last change carries the whole text.
      (when-let [text (:text (last (:contentChanges params)))]
        (swap! documents assoc uri {:text text :version (get-in params [:textDocument :version])})
        (schedule-check! uri check-delay-ms))

      "textDocument/didSave"
      ;; Interned files are read from disk, so a save can change what any
      ;; open document reports.
      (doseq [u (keys @documents)]
        (schedule-check! u 0))

      "textDocument/didClose"
      (do (swap! documents dissoc uri)
          (swap! last-symbols dissoc uri)
          (swap! programs dissoc uri)
          (swap! checked-versions dissoc uri)
          (on-worker #(notify! "textDocument/publishDiagnostics" {:uri uri :diagnostics []})))

      nil)))

(defn- handle-message [{:keys [id method params] :as msg}]
  (cond
    ;; A response to something we sent; we send no requests.
    (nil? method) nil

    (some? id)
    (on-worker #(try
                  (let [result (handle-request method params)]
                    (if (= ::unknown result)
                      (respond-error! id -32601 (str "Method not found: " method))
                      (respond! id result)))
                  (catch Throwable e
                    (log "error in" method ":" (str e))
                    (respond-error! id -32603 (or (ex-message e) (str e))))))

    :else
    (on-worker #(handle-notification method params))))

(defn await-idle
  "Wait until the work queued so far, including pending checks, is done."
  []
  @(on-worker (+ check-delay-ms 50) (fn [])))

(defn serve
  "Serve LSP messages from IN until `exit` or end of input. Returns the exit
   code."
  [^InputStream in]
  (loop []
    (let [msg (try (read-message in)
                   (catch Exception e (log "bad message:" (str e)) ::bad))]
      (cond
        (nil? msg) 1
        (= ::bad msg) (recur)
        ;; `shutdown` may still be queued on the worker.
        (= "exit" (:method msg)) (do @(on-worker (fn [])) (if @shut-down? 0 1))
        :else (do (handle-message msg) (recur))))))

(defn -main [& _args]
  ;; Only protocol messages may reach stdout.
  (System/setOut (PrintStream. (FileOutputStream. FileDescriptor/err) true "UTF-8"))
  (let [code (binding [*out* *err*]
               (serve (BufferedInputStream. (FileInputStream. FileDescriptor/in))))]
    (.shutdown worker)
    (.awaitTermination worker 2 TimeUnit/SECONDS)
    (shutdown-agents)
    (System/exit code)))
