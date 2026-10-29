(ns nex.lsp
  "`nex lsp`: a Language Server Protocol server over stdin/stdout.

   It gives any LSP editor (VS Code, Neovim, Helix, Zed, Emacs eglot, ...)
   - diagnostics: the syntax and type errors `nex <file>` would report,
     checked as you type and again on save;
   - formatting: `nex format` on the whole document;
   - an outline: classes, their constructors and features, functions,
     unions, type aliases and top-level `let`s.

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

(defn check-text
  "The diagnostics for TEXT, checked as the file at SOURCE-ID, as
   [{:line :col :length :message :severity :related}] (line 1-based, col
   0-based) — the errors `nex <file>` would stop at before running it."
  [source-id text]
  (let [lines (text-lines text)]
    (try
      (let [ast (predeclared/add (parser/ast text))
            module-ast (jvm-file/augment-ast-with-interns source-id ast)
            result (tc/type-check module-ast {:strict-undefined-targets? true
                                              :reject-discarded-top-level? true
                                              :max-errors 100})]
        (vec (concat (map #(type-error-diagnostic lines %) (:errors result))
                     (map #(warning-diagnostic lines %) (:warnings result)))))
      (catch ParseError e
        (parse-error-diagnostics lines e text))
      (catch Throwable e
        [(exception-diagnostic lines text e)]))))

(defn- publish-diagnostics! [uri text version]
  (let [lines (text-lines text)
        diags (binding [*out* *err*]
                (mapv #(diagnostic lines %) (check-text (source-id uri) text)))]
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
   :documentSymbolProvider true})

(defn- handle-request [method params]
  (case method
    "initialize" {:capabilities server-capabilities
                  :serverInfo {:name "nex-lsp"}}
    "shutdown" (do (reset! shut-down? true) nil)
    "textDocument/formatting" (format-edits (get-in params [:textDocument :uri]))
    "textDocument/documentSymbol" (symbols-for (get-in params [:textDocument :uri]))
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
