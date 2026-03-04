(ns nex.core
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [nex.turtle-browser :as turtle]))

(def repl-history-storage-key "nex.browser.repl.history.v1")
(def editor-files-storage-key "nex.browser.editor.files.v1")
(def editor-active-file-storage-key "nex.browser.editor.active-file.v1")
(def typecheck-storage-key "nex.browser.repl.typecheck.v1")

(def default-editor-source
  (str "-- Example program\n"
       "let win := create Window.with_title(\"Nex Browser\", 640, 360)\n"
       "win.show()\n"
       "let t := create Turtle.on_window(win)\n"
       "t.color(\"blue\")\n"
       "t.forward(80.0)\n"
       "t.right(120.0)\n"
       "t.forward(80.0)\n"
       "t.right(120.0)\n"
       "t.forward(80.0)\n"))

(def nex-keywords
  #{"class" "feature" "inherit" "end" "do" "if" "then" "else" "elseif"
    "when" "from" "until" "invariant" "variant" "require" "ensure"
    "let" "as" "and" "or" "not" "fn" "old" "create" "private" "note"
    "with" "import" "intern" "function" "raise" "rescue" "retry"
    "repeat" "across" "case" "of"})

(def nex-types
  #{"Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Any" "Void" "Function" "Cursor" "Window" "Turtle" "Image"})

(def nex-constants
  #{"true" "false" "nil"})

(def nex-builtins
  #{"print" "println" "result" "exception"})

(defonce app-state
  (atom {:ctx (interp/make-context)
         :repl-history []
         :repl-history-index nil
         :repl-history-draft ""
         :typecheck-enabled false
         :repl-var-types {}
         :editor-files {"scratch.nex" default-editor-source}
         :editor-active-file "scratch.nex"
         :docs-pages
         [(str "<h3>Getting Started</h3>"
               "<p>Nex syntax is designed to read like plain English. Use the REPL for short expressions and the editor for larger programs.</p>"
               "<pre><code>-- This is a comment\n"
               "let name: String := \"Alice\"\n"
               "let age: Integer := 10\n"
               "print(\"Hello, \" + name)\n"
               "print(age + 2)</code></pre>"
               "<p>Core value types: <code>String</code>, <code>Integer</code>, <code>Real</code>, <code>Boolean</code>.</p>")
          (str "<h3>Expressions and Control Flow</h3>"
               "<p>Use normal arithmetic and boolean operators in expressions.</p>"
               "<pre><code>let total: Integer := 3 + 4 * 2\n"
               "let ok: Boolean := total &gt;= 10 and not false</code></pre>"
               "<p>Conditional branching:</p>"
               "<pre><code>if age &gt;= 18 then\n"
               "  print(\"adult\")\n"
               "elseif age &gt;= 13 then\n"
               "  print(\"teen\")\n"
               "else\n"
               "  print(\"child\")\n"
               "end\n\n"
               "let label: String := when age &gt;= 18 \"adult\" else \"minor\" end</code></pre>")
          (str "<h3>Loops</h3>"
               "<p>Nex supports structured loops: <code>from/until</code>, <code>repeat</code>, and <code>across</code>.</p>"
               "<pre><code>from let i: Integer := 1 until i &gt; 5 do\n"
               "  print(i)\n"
               "  i := i + 1\n"
               "end</code></pre>"
               "<pre><code>repeat 3 do\n"
               "  print(\"hello\")\n"
               "end</code></pre>"
               "<pre><code>across [10, 20, 30] as x do\n"
               "  print(x)\n"
               "end</code></pre>")
          (str "<h3>Functions and Collections</h3>"
               "<p>Define reusable behavior with <code>function</code> and return values via <code>result</code>.</p>"
               "<pre><code>function double(n: Integer): Integer\n"
               "do\n"
               "  result := n * 2\n"
               "end\n\n"
               "print(double(5))</code></pre>"
               "<p>Collections:</p>"
               "<pre><code>let colors: Array [String] := [\"red\", \"green\"]\n"
               "colors.push(\"blue\")\n"
               "print(colors.at(0))\n\n"
               "let pet: Map [String, String] := {name: \"Max\", kind: \"dog\"}\n"
               "print(pet.at(\"name\"))</code></pre>")
          (str "<h3>Classes and Objects</h3>"
               "<p>Classes group state and behavior. Create objects with <code>create</code>.</p>"
               "<pre><code>class Pet\n"
               "  feature\n"
               "    name: String\n"
               "    sound: String\n\n"
               "    speak do\n"
               "      print(name + \" says \" + sound)\n"
               "    end\n"
               "end\n\n"
               "let cat: Pet := create Pet\n"
               "cat.name := \"Mimi\"\n"
               "cat.sound := \"meow\"\n"
               "cat.speak</code></pre>"
               "<p>Constructors run during object creation:</p>"
               "<pre><code>let c: Circle := create Circle.make(5.0)</code></pre>")
          (str "<h3>Contracts, Errors, and Next Steps</h3>"
               "<p>Nex supports Design by Contract with <code>require</code>, <code>ensure</code>, and <code>invariant</code>.</p>"
               "<pre><code>spend(amount: Real)\n"
               "  require\n"
               "    enough: amount &lt;= money\n"
               "  do\n"
               "    money := money - amount\n"
               "  ensure\n"
               "    less: money = old money - amount\n"
               "  end</code></pre>"
               "<p>Error handling:</p>"
               "<pre><code>do\n"
               "  raise \"not ready\"\n"
               "rescue\n"
               "  print(exception)\n"
               "  retry\n"
               "end</code></pre>"
               "<p>Also available: <code>case/of</code>, scoped <code>do...end</code> blocks, anonymous functions, and generics.</p>"
               "<p>This tutorial is based on <code>docs/SYNTAX.md</code>.</p>")]
         :docs-page 0}))

(defn- by-id [id]
  (.getElementById js/document id))

(defn- storage-get [k]
  (try
    (.getItem js/localStorage k)
    (catch :default _ nil)))

(defn- storage-set! [k v]
  (try
    (.setItem js/localStorage k v)
    (catch :default _ nil)))

(defn- storage-get-edn [k fallback]
  (if-let [raw (storage-get k)]
    (try
      (reader/read-string raw)
      (catch :default _ fallback))
    fallback))

(defn- persist-repl-history! []
  (let [hist (:repl-history @app-state)]
    (storage-set! repl-history-storage-key (pr-str hist))))

(defn- persist-editor-state! []
  (let [{:keys [editor-files editor-active-file]} @app-state]
    (storage-set! editor-files-storage-key (pr-str editor-files))
    (storage-set! editor-active-file-storage-key editor-active-file)))

(defn- persist-typecheck-state! []
  (storage-set! typecheck-storage-key
                (if (:typecheck-enabled @app-state) "on" "off")))

(defn- load-storage-state! []
  (let [history (storage-get-edn repl-history-storage-key [])
        files (storage-get-edn editor-files-storage-key nil)
        active-file (or (storage-get editor-active-file-storage-key) "scratch.nex")
        typecheck-raw (storage-get typecheck-storage-key)
        typecheck-enabled? (= "on" typecheck-raw)
        safe-history (if (vector? history) history [])
        safe-files (if (and (map? files) (seq files))
                     files
                     {"scratch.nex" default-editor-source})
        safe-active (if (contains? safe-files active-file)
                      active-file
                      (first (sort (keys safe-files))))]
    (swap! app-state assoc
           :repl-history safe-history
           :typecheck-enabled typecheck-enabled?
           :repl-var-types {}
           :editor-files safe-files
           :editor-active-file safe-active)))

(defn- append-line! [kind text]
  (let [out (by-id "repl-output")
        line (.createElement js/document "div")]
    (set! (.-className line) (str "repl-line " kind))
    (set! (.-textContent line) text)
    (.appendChild out line)
    (set! (.-scrollTop out) (.-scrollHeight out))))

(defn- update-typecheck-ui! []
  (let [enabled? (:typecheck-enabled @app-state)
        btn (by-id "repl-typecheck")]
    (when btn
      (set! (.-textContent btn) (str "Typecheck: " (if enabled? "ON" "OFF")))
      (set! (.-className btn)
            (str "toggle " (if enabled? "on" "off"))))))

(defn- toggle-typecheck! []
  (let [enabled? (not (:typecheck-enabled @app-state))]
    (swap! app-state assoc :typecheck-enabled enabled?)
    (persist-typecheck-state!)
    (update-typecheck-ui!)
    (append-line! "info" (str "Type checking " (if enabled? "enabled." "disabled.")))))

(defn- throw-type-errors! [result]
  (when-not (:success result)
    (let [errors (or (:errors result) [])
          msg (if (seq errors)
                (str "Type check failed:\n"
                     (str/join "\n" (map tc/format-type-error errors)))
                "Type check failed.")]
      (throw (ex-info msg {:type :typecheck :errors errors})))))

(defn- typecheck-ast! [ast]
  (when (:typecheck-enabled @app-state)
    (throw-type-errors!
     (tc/type-check ast {:var-types (:repl-var-types @app-state)}))))

(defn- typecheck-error? [e]
  (= :typecheck (:type (ex-data e))))

(defn- remember-typed-lets! [stmts]
  (doseq [stmt stmts]
    (when (and (map? stmt)
               (= :let (:type stmt))
               (:var-type stmt))
      (swap! app-state assoc-in [:repl-var-types (:name stmt)] (:var-type stmt)))))

(defn- escape-html [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- token-html [tok]
  (let [esc (escape-html tok)]
    (cond
      (str/starts-with? tok "\"") (str "<span class='tok-str'>" esc "</span>")
      (re-matches #"^[0-9]+(\.[0-9]+)?$" tok) (str "<span class='tok-num'>" esc "</span>")
      (#{"->" ":="} tok) (str "<span class='tok-op'>" esc "</span>")
      (re-matches #"^[A-Za-z_][A-Za-z0-9_]*$" tok)
      (cond
        (contains? nex-keywords tok) (str "<span class='tok-kw'>" esc "</span>")
        (contains? nex-types tok) (str "<span class='tok-type'>" esc "</span>")
        (contains? nex-constants tok) (str "<span class='tok-const'>" esc "</span>")
        (contains? nex-builtins tok) (str "<span class='tok-builtin'>" esc "</span>")
        :else esc)
      :else esc)))

(defn- highlight-code-html [source]
  (let [source* (str source)
        line-html
        (fn [line]
          (let [comment-idx (str/index-of line "--")
                code-part (if (some? comment-idx) (subs line 0 comment-idx) line)
                comment-part (when (some? comment-idx) (subs line comment-idx))
                ;; Use a non-capturing top-level group so re-seq returns strings, not match vectors.
                parts (re-seq #"(?:\"(?:[^\"\\]|\\.)*\"|->|:=|[A-Za-z_][A-Za-z0-9_]*|[0-9]+(?:\.[0-9]+)?|\s+|.)" code-part)
                code-html (->> parts
                               (map token-html)
                               (str/join ""))
                comment-html (when comment-part
                               (str "<span class='tok-comment'>" (escape-html comment-part) "</span>"))]
            (str code-html (or comment-html ""))))]
    (->> (str/split source* #"\n" -1)
         (map line-html)
         (str/join "\n"))))

(defn- update-editor-highlight! []
  (try
    (let [editor (by-id "editor-input")
          hl (by-id "editor-highlight")]
      (when (and editor hl)
        (set! (.-innerHTML hl) (highlight-code-html (.-value editor)))))
    (catch :default e
      ;; Never let highlighting failures break the editor/repl.
      (js/console.error "Highlight error:" e))))

(defn- parser-debug-info []
  (let [cljs-mods (or js/$CLJS #js {})
        shadow-js (.-js js/shadow)
        shadow-require (fn [module-id]
                         (try
                           (when shadow-js
                             (.require shadow-js module-id #js {}))
                           (catch :default _
                             nil)))]
    (str "parser-debug: "
         "global-lexer=" (boolean (js* "typeof module$nex$parser_js$grammar$nexlangLexer !== 'undefined'"))
         ", global-parser=" (boolean (js* "typeof module$nex$parser_js$grammar$nexlangParser !== 'undefined'"))
         ", cljs-lexer=" (boolean (aget cljs-mods "module$nex$parser_js$grammar$nexlangLexer"))
         ", cljs-parser=" (boolean (aget cljs-mods "module$nex$parser_js$grammar$nexlangParser"))
         ", require-antlr=" (boolean (shadow-require "module$node_modules$antlr4$dist$antlr4_web_cjs"))
         ", require-lexer=" (boolean (or (shadow-require "module$nex$parser_js$grammar$nexlangLexer")
                                         (shadow-require "module$nex$parser_js$grammar$nexlangLexer.js")))
         ", require-parser=" (boolean (or (shadow-require "module$nex$parser_js$grammar$nexlangParser")
                                          (shadow-require "module$nex$parser_js$grammar$nexlangParser.js")))
         ", tag-listener-loaded=" (boolean js/window.__nex_listener_loaded)
         ", tag-lexer-loaded=" (boolean js/window.__nex_lexer_loaded)
         ", tag-parser-loaded=" (boolean js/window.__nex_parser_loaded)
         ", tag-listener-error=" (boolean js/window.__nex_listener_error)
         ", tag-lexer-error=" (boolean js/window.__nex_lexer_error)
         ", tag-parser-error=" (boolean js/window.__nex_parser_error)
         ", tag-listener-ex=" (or js/window.__nex_listener_exception "none")
         ", tag-lexer-ex=" (or js/window.__nex_lexer_exception "none")
         ", tag-parser-ex=" (or js/window.__nex_parser_exception "none")
         ", tag-listener-status=" (or js/window.__nex_listener_status "none")
         ", tag-lexer-status=" (or js/window.__nex_lexer_status "none")
         ", tag-parser-status=" (or js/window.__nex_parser_status "none")
         ", antlr-ready=" (boolean js/window.__nex_antlr_ready)
         ", bootstrap-timeout=" (boolean js/window.__nex_parser_bootstrap_timeout)
         ", win-lexer=" (boolean js/window.__nexlangLexer)
         ", win-parser=" (boolean js/window.__nexlangParser))))

(defn- clear-repl-output! []
  (set! (.-innerHTML (by-id "repl-output")) ""))

(defn- clamp-history-index [idx hist]
  (let [max-idx (dec (count hist))]
    (cond
      (neg? max-idx) nil
      (< idx 0) 0
      (> idx max-idx) max-idx
      :else idx)))

(defn- push-repl-history! [entry]
  (let [trimmed (str/trim entry)]
    (when-not (str/blank? trimmed)
      (swap! app-state
             (fn [s]
               (let [old (:repl-history s)
                     no-dupe (if (= trimmed (last old))
                               old
                               (conj old trimmed))
                     capped (if (> (count no-dupe) 200)
                              (vec (take-last 200 no-dupe))
                              no-dupe)]
                 (assoc s
                        :repl-history capped
                        :repl-history-index nil
                        :repl-history-draft ""))))
      (persist-repl-history!))))

(defn- set-repl-input-value! [v]
  (let [input-el (by-id "repl-input")]
    (set! (.-value input-el) v)
    ;; move caret to end for predictable editing
    (let [pos (count v)]
      (.setSelectionRange input-el pos pos))))

(defn- navigate-repl-history! [direction]
  (let [{:keys [repl-history repl-history-index repl-history-draft]} @app-state
        input-el (by-id "repl-input")
        current-input (.-value input-el)
        n (count repl-history)]
    (when (pos? n)
      (cond
        (= direction :up)
        (let [base-idx (if (nil? repl-history-index) n repl-history-index)
              next-idx (clamp-history-index (dec base-idx) repl-history)]
          (swap! app-state assoc
                 :repl-history-index next-idx
                 :repl-history-draft (if (nil? repl-history-index) current-input repl-history-draft))
          (set-repl-input-value! (nth repl-history next-idx)))

        (= direction :down)
        (when-not (nil? repl-history-index)
          (let [next-idx (inc repl-history-index)]
            (if (>= next-idx n)
              (do
                (swap! app-state assoc :repl-history-index nil)
                (set-repl-input-value! repl-history-draft))
              (do
                (swap! app-state assoc :repl-history-index next-idx)
                (set-repl-input-value! (nth repl-history next-idx))))))))))

(defn- refresh-editor-file-list! []
  (let [select-el (by-id "editor-file-list")
        files (:editor-files @app-state)
        current (:editor-active-file @app-state)
        names (sort (keys files))]
    (set! (.-innerHTML select-el) "")
    (doseq [name names]
      (let [opt (.createElement js/document "option")]
        (set! (.-value opt) name)
        (set! (.-textContent opt) name)
        (when (= name current)
          (set! (.-selected opt) true))
        (.appendChild select-el opt)))))

(defn- set-active-editor-file! [filename]
  (when-let [content (get-in @app-state [:editor-files filename])]
    (swap! app-state assoc :editor-active-file filename)
    (set! (.-value (by-id "editor-file-name")) filename)
    (set! (.-value (by-id "editor-input")) content)
    (update-editor-highlight!)
    (refresh-editor-file-list!)
    (persist-editor-state!)))

(defn- sanitize-filename [s]
  (let [trimmed (str/trim s)]
    (if (str/blank? trimmed) "scratch.nex" trimmed)))

(defn- save-editor-file! []
  (let [filename (sanitize-filename (.-value (by-id "editor-file-name")))
        content (.-value (by-id "editor-input"))]
    (swap! app-state assoc-in [:editor-files filename] content)
    (swap! app-state assoc :editor-active-file filename)
    (persist-editor-state!)
    (refresh-editor-file-list!)
    (set! (.-value (by-id "editor-file-name")) filename)
    (append-line! "info" (str "Saved '" filename "' to browser storage."))))

(defn- load-editor-file! []
  (let [filename (sanitize-filename (.-value (by-id "editor-file-list")))]
    (if (contains? (:editor-files @app-state) filename)
      (do
        (set-active-editor-file! filename)
        (append-line! "info" (str "Loaded '" filename "' from browser storage.")))
      (append-line! "err" (str "No saved file named '" filename "'.")))))

(defn- line-opens-block? [trimmed]
  (or
   (= trimmed "feature")
   (= trimmed "create")
   (= trimmed "private feature")
   (some #(str/ends-with? trimmed %)
         ["class" "do" "then" "else" "elseif" "require" "ensure"
          "from" "until" "inherit" "invariant" "variant" "rescue" "of"])))

(defn- line-closes-block? [trimmed]
  (or (str/starts-with? trimmed "end")
      (str/starts-with? trimmed "else")
      (str/starts-with? trimmed "elseif")
      (str/starts-with? trimmed "rescue")))

(defn- auto-format-source [source]
  (let [lines (str/split (str source) #"\n" -1)]
    (loop [remaining lines
           indent-level 0
           acc []]
      (if (empty? remaining)
        (str/join "\n" acc)
        (let [line (first remaining)
              trimmed (str/trim line)]
          (if (str/blank? trimmed)
            (recur (rest remaining) indent-level (conj acc ""))
            (let [before-level (max 0 (if (line-closes-block? trimmed)
                                        (dec indent-level)
                                        indent-level))
                  formatted (str (apply str (repeat (* 2 before-level) " ")) trimmed)
                  after-level (if (and (line-opens-block? trimmed)
                                       (not (str/starts-with? trimmed "end")))
                                (inc before-level)
                                before-level)]
              (recur (rest remaining) after-level (conj acc formatted)))))))))

(defn- format-editor! []
  (let [editor (by-id "editor-input")
        formatted (auto-format-source (.-value editor))]
    (set! (.-value editor) formatted)
    (update-editor-highlight!)
    (append-line! "info" "Formatted editor buffer.")))

(defn- count-leading-spaces [s]
  (count (re-find #"^ *" s)))

(defn- previous-non-empty-line [lines idx]
  (loop [i (dec idx)]
    (when (>= i 0)
      (let [line (nth lines i)
            t (str/trim line)]
        (if (str/blank? t)
          (recur (dec i))
          line)))))

(defn- next-non-empty-line [lines idx]
  (loop [i idx]
    (when (< i (count lines))
      (let [line (nth lines i)
            t (str/trim line)]
        (if (str/blank? t)
          (recur (inc i))
          line)))))

(defn- line-without-comment [line]
  (if-let [idx (str/index-of line "--")]
    (subs line 0 idx)
    line))

(defn- do-opener-line? [trimmed]
  (boolean (re-find #"\bdo$" trimmed)))

(defn- if-opener-line? [trimmed]
  (boolean (re-find #"^if\b.*\bthen$" trimmed)))

(defn- else-opener-line? [trimmed]
  (or (str/starts-with? trimmed "else")
      (str/starts-with? trimmed "elseif")))

(defn- should-auto-scaffold-end?
  [source-trim current-line-suffix has-end-below?]
  (and (str/blank? current-line-suffix)
       (not has-end-below?)
       (or (do-opener-line? source-trim)
           (if-opener-line? source-trim)
           (else-opener-line? source-trim))))

(defn- editor-auto-indent-enter! []
  (let [editor (by-id "editor-input")
        value (.-value editor)
        caret (.-selectionStart editor)
        before (subs value 0 caret)
        after (subs value caret)
        lines-before (str/split before #"\n" -1)
        lines-after (str/split after #"\n" -1)
        current-line-suffix (first lines-after)
        next-after-line (next-non-empty-line lines-after 1)
        current-line (or (last lines-before) "")
        prev-line (or (previous-non-empty-line lines-before (count lines-before)) "")
        current-code-trim (str/trim (line-without-comment current-line))
        next-after-trim (some-> next-after-line line-without-comment str/trim)
        prev-code-trim (str/trim (line-without-comment prev-line))
        current-indent (count-leading-spaces current-line)
        prev-indent (count-leading-spaces prev-line)
        source-trim (if (str/blank? current-code-trim) prev-code-trim current-code-trim)
        source-indent (if (str/blank? current-code-trim) prev-indent current-indent)
        has-end-below? (and (some? next-after-trim)
                            (str/starts-with? next-after-trim "end"))
        next-indent (cond
                      ;; Transitional keywords close one block and open another; next line is inner body.
                      (or (str/starts-with? source-trim "else")
                          (str/starts-with? source-trim "elseif")
                          (str/starts-with? source-trim "rescue"))
                      (+ source-indent 2)
                      ;; `end` moves back to outer level.
                      (str/starts-with? source-trim "end")
                      (max 0 (- source-indent 2))
                      ;; Generic block openers.
                      (line-opens-block? source-trim)
                      (+ source-indent 2)
                      :else source-indent)
        scaffold-end? (should-auto-scaffold-end? source-trim current-line-suffix has-end-below?)
        indent-str (apply str (repeat next-indent " "))
        end-indent-str (apply str (repeat source-indent " "))
        inserted (if scaffold-end?
                   (str "\n" indent-str "\n" end-indent-str "end")
                   (str "\n" indent-str))
        new-value (str before inserted after)
        new-caret (+ caret 1 (count indent-str))]
    (set! (.-value editor) new-value)
    (.setSelectionRange editor new-caret new-caret)
    (update-editor-highlight!)))

(defn- editor-indent-selection! [direction]
  (let [editor (by-id "editor-input")
        value (.-value editor)
        start (.-selectionStart editor)
        end (.-selectionEnd editor)
        before (subs value 0 start)
        selected (subs value start end)
        after (subs value end)
        lines (str/split selected #"\n" -1)
        shifted (map (fn [line]
                       (case direction
                         :right (str "  " line)
                         :left (if (str/starts-with? line "  ")
                                 (subs line 2)
                                 (if (str/starts-with? line " ")
                                   (subs line 1)
                                   line))
                         line))
                     lines)
        new-selected (str/join "\n" shifted)
        new-value (str before new-selected after)
        delta (- (count new-selected) (count selected))]
    (set! (.-value editor) new-value)
    (.setSelectionRange editor start (+ end delta))
    (update-editor-highlight!)))

(defn- editor-maybe-dedent-control-line! []
  (let [editor (by-id "editor-input")
        value (.-value editor)
        caret (.-selectionStart editor)
        before (subs value 0 caret)
        after (subs value caret)
        lines-before (str/split before #"\n" -1)
        current-fragment (or (last lines-before) "")
        current-line-full (str current-fragment (first (str/split after #"\n" -1)))
        current-trim (str/trim (line-without-comment current-line-full))]
    (when (re-find #"^(else|elseif|rescue|end)\b" current-trim)
      (let [line-idx (dec (count lines-before))
            all-lines (str/split value #"\n" -1)
            prev-line (or (previous-non-empty-line all-lines line-idx) "")
            prev-indent (count-leading-spaces prev-line)
            target-indent (max 0 (- prev-indent 2))
            desired-prefix (apply str (repeat target-indent " "))
            desired-line (str desired-prefix current-trim)
            current-leading (count-leading-spaces current-line-full)]
        (when (not= current-line-full desired-line)
          (let [line-start (- caret (count current-fragment))
                line-end (+ line-start (count current-line-full))
                new-value (str (subs value 0 line-start) desired-line (subs value line-end))
                caret-delta (- target-indent current-leading)
                new-caret (max line-start (+ caret caret-delta))]
            (set! (.-value editor) new-value)
            (.setSelectionRange editor new-caret new-caret)
            (update-editor-highlight!)))))))

(defn- fmt-value [v]
  (cond
    (and (map? v) (:nex-builtin-type v)) (str "#<" (name (:nex-builtin-type v)) ">")
    (array? v) (pr-str (vec v))
    (instance? js/Map v) (pr-str (into {} (for [entry (js/Array.from (.entries v))]
                                            [(aget entry 0) (aget entry 1)])))
    (string? v) v
    :else (pr-str v)))

(defn- looks-like-definition? [input]
  (boolean (re-find #"^\s*(class|function|import|intern)\b" input)))

(defn- wrap-expression [input]
  (str "class __BrowserRepl__\n"
       "feature\n"
       "  __eval__() do\n"
       "    print(" input ")\n"
       "  end\n"
       "end"))

(defn- wrap-statement-block [input]
  (str "class __BrowserRepl__\n"
       "feature\n"
       "  __eval__() do\n"
       input "\n"
       "  end\n"
       "end"))

(defn- eval-wrapped! [ctx wrapped-code]
  (let [ast (p/ast wrapped-code)
        _ (typecheck-ast! ast)
        method-def (-> ast :classes first :body first :members first)
        body (:body method-def)
        result (last (mapv #(interp/eval-node ctx %) body))]
    (when (:typecheck-enabled @app-state)
      (remember-typed-lets! body))
    {:result result
     :output @(:output ctx)}))

(defn- run-program! [ctx source]
  (let [ast (p/ast source)
        _ (typecheck-ast! ast)
        raw-result (interp/eval-node ctx ast)
        result (if (= :program (:type ast)) nil raw-result)]
    {:result result
     :output @(:output ctx)}))

(defn- show-runtime-output! [output result]
  (doseq [line output]
    (append-line! "out" (str line)))
  (when (and (some? result) (empty? output))
    (append-line! "result" (fmt-value result))))

(defn- eval-repl-input! []
  (let [input-el (by-id "repl-input")
        source (.-value input-el)
        trimmed (str/trim source)
        ctx (:ctx @app-state)]
    (when-not (str/blank? trimmed)
      (push-repl-history! trimmed)
      (append-line! "input" (str "nex> " trimmed))
      (set! (.-value input-el) "")
      (reset! (:output ctx) [])
      (try
        (if (looks-like-definition? trimmed)
          (let [{:keys [result output]} (run-program! ctx trimmed)]
            ;; Definitions should not dump interpreter internals (e.g. Context).
            ;; Show runtime output if any, otherwise a concise confirmation.
            (doseq [line output]
              (append-line! "out" (str line)))
            (when (empty? output)
              (append-line! "info" "Loaded definition.")))
          (let [{:keys [result output]}
                (try
                  (eval-wrapped! ctx (wrap-expression trimmed))
                  (catch :default e1
                    (if (typecheck-error? e1)
                      (throw e1)
                      (try
                        (eval-wrapped! ctx (wrap-statement-block trimmed))
                        (catch :default _e2
                          (throw e1))))))]
            (show-runtime-output! output result)))
        (catch :default e
          (let [msg (str "Error: " (or (.-message e) (str e)))]
            (append-line! "err" msg)
            (when (str/includes? msg "Parser module missing")
              (append-line! "err" (parser-debug-info)))))))))

(defn- run-editor! []
  (let [editor (by-id "editor-input")
        source (.-value editor)
        trimmed (str/trim source)
        ctx (:ctx @app-state)]
    (if (str/blank? trimmed)
      (append-line! "info" "Editor is empty.")
      (do
        (append-line! "input" "nex> :run editor")
        (reset! (:output ctx) [])
        (try
          (let [ast (p/ast source)
                has-defs? (and (= :program (:type ast))
                               (or (seq (:classes ast))
                                   (seq (:functions ast))
                                   (seq (:imports ast))
                                   (seq (:interns ast))))
                {:keys [result output]}
                (if has-defs?
                  (do
                    (interp/eval-node ctx ast)
                    {:result nil :output @(:output ctx)})
                  (eval-wrapped! ctx (wrap-statement-block trimmed)))]
            (show-runtime-output! output result)
            (when (and (empty? output) (nil? result))
              (append-line! "info" "Program loaded and executed.")))
          (catch :default e
            (append-line! "err" (str "Error: " (or (.-message e) (str e))))))))))

(defn- update-docs! []
  (let [{:keys [docs-pages docs-page]} @app-state
        max-page (dec (count docs-pages))
        prev-btn (by-id "docs-prev")
        next-btn (by-id "docs-next")
        title-el (by-id "docs-title")
        body-el (by-id "docs-body")]
    (set! (.-textContent title-el) (str "Page " (inc docs-page) " / " (count docs-pages)))
    (set! (.-innerHTML body-el) (nth docs-pages docs-page))
    (set! (.-disabled prev-btn) (zero? docs-page))
    (set! (.-disabled next-btn) (= docs-page max-page))))

(defn- render! []
  (let [root (or (by-id "app")
                 (let [el (.createElement js/document "div")]
                   (set! (.-id el) "app")
                   (.appendChild (.-body js/document) el)
                   el))]
    (set! (.-innerHTML root)
          (str "<div class='shell'>"
               "  <header class='topbar'><h1>Nex Browser IDE</h1><p>Explore Nex directly in your browser.</p></header>"
               "  <main class='layout'>"
               "    <section class='panel repl'>"
               "      <h2>1. REPL</h2>"
               "      <div id='repl-output' class='repl-output'></div>"
               "      <div class='repl-controls'>"
               "        <div class='repl-input-row'>"
               "          <input id='repl-input' type='text' placeholder='Enter any Nex expression...' />"
               "        </div>"
               "        <div class='repl-actions'>"
               "          <button id='repl-typecheck' class='toggle off'>Typecheck: OFF</button>"
               "          <button id='repl-eval'>Evaluate</button>"
               "          <button id='repl-clear'>Clear</button>"
               "        </div>"
               "      </div>"
               "    </section>"
               "    <section class='panel editor'>"
               "      <h2>2. Nex Editor</h2>"
               "      <div class='editor-code-wrap'>"
               "        <pre id='editor-highlight' aria-hidden='true'></pre>"
               "        <textarea id='editor-input' spellcheck='false'></textarea>"
               "      </div>"
               "      <div class='editor-file-controls'>"
                "        <input id='editor-file-name' type='text' value='scratch.nex' placeholder='filename.nex' />"
                "        <button id='editor-save'>Save</button>"
                "        <select id='editor-file-list'></select>"
                "        <button id='editor-load'>Load</button>"
               "      </div>"
               "      <div class='editor-controls'><button id='editor-format'>Format</button><button id='editor-run'>Run In REPL</button></div>"
               "    </section>"
               "    <section class='panel canvas'>"
               "      <h2>3. Canvas</h2>"
               "      <div id='canvas-host' class='canvas-host'></div>"
               "    </section>"
               "    <section class='panel docs'>"
               "      <h2>4. Documentation</h2>"
               "      <div class='docs-nav'>"
               "        <button id='docs-prev'>Previous</button>"
               "        <span id='docs-title'></span>"
               "        <button id='docs-next'>Next</button>"
               "      </div>"
               "      <article id='docs-body' class='docs-body'></article>"
               "    </section>"
               "  </main>"
               "</div>"
               "<style>"
               "  :root { --bg:#f3efe8; --card:#fffdf9; --ink:#18130b; --muted:#7a6a58; --accent:#165d5a; --line:#d9cdbf; }"
               "  * { box-sizing: border-box; }"
               "  body { margin:0; font-family: 'IBM Plex Sans', ui-sans-serif, system-ui, sans-serif; color:var(--ink); background: radial-gradient(circle at 0% 0%, #efe5d7, #f3efe8 45%); }"
               "  .shell { min-height:100vh; padding:16px; }"
               "  .topbar h1 { margin:0; font-size:1.3rem; }"
               "  .topbar p { margin:4px 0 14px 0; color:var(--muted); }"
               "  .layout { display:grid; grid-template-columns: 1fr 1fr; gap:12px; }"
               "  .panel { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:10px; }"
               "  .panel h2 { margin:0 0 8px; font-size:0.98rem; }"
               "  .repl-output { height:230px; overflow:auto; background:#18130b; color:#f8f2e8; border-radius:8px; padding:8px; font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size:0.9rem; }"
               "  .repl-line { margin-bottom:4px; white-space:pre-wrap; }"
               "  .repl-line.input { color:#6ec6ff; } .repl-line.result { color:#8ee59e; } .repl-line.err { color:#ff8a8a; } .repl-line.info { color:#f7cf86; }"
               "  .repl-controls { margin-top:8px; display:grid; grid-template-rows:auto auto; row-gap:8px; }"
               "  .repl-input-row { display:block; }"
               "  .repl-controls input { width:100%; min-width:0; padding:8px; border:1px solid var(--line); border-radius:8px; }"
               "  .repl-actions { margin-top:8px; display:flex; gap:8px; }"
               "  .editor-code-wrap { position:relative; height:230px; border:1px solid var(--line); border-radius:8px; overflow:auto; background:#fff; }"
               "  #editor-highlight { margin:0; padding:8px; min-height:100%; white-space:pre; font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size:0.9rem; line-height:1.35; pointer-events:none; }"
               "  textarea#editor-input { position:absolute; inset:0; width:100%; height:100%; resize:none; margin:0; padding:8px; border:0; outline:none; font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size:0.9rem; line-height:1.35; background:transparent; color:transparent; caret-color:#111; }"
               "  textarea#editor-input::selection { background:rgba(22,93,90,0.2); color:transparent; }"
               "  .tok-comment { color:#7a6a58; font-style:italic; }"
               "  .tok-kw { color:#6a1b9a; font-weight:600; }"
               "  .tok-type { color:#1e4ea1; font-weight:600; }"
               "  .tok-const { color:#b71c1c; font-weight:600; }"
               "  .tok-builtin { color:#00796b; }"
               "  .tok-num { color:#ad1457; }"
               "  .tok-str { color:#2e7d32; }"
               "  .tok-op { color:#bf360c; font-weight:600; }"
               "  .editor-file-controls { margin-top:8px; display:grid; grid-template-columns: 1fr auto 180px auto; gap:8px; align-items:center; }"
               "  .editor-file-controls input, .editor-file-controls select { width:100%; min-width:0; padding:8px; border:1px solid var(--line); border-radius:8px; background:#fff; }"
               "  .editor-controls { margin-top:8px; display:flex; justify-content:flex-end; gap:8px; }"
               "  button { border:1px solid var(--accent); background:var(--accent); color:#fff; border-radius:8px; padding:8px 12px; cursor:pointer; }"
               "  button.toggle.off { background:#8a7f70; border-color:#8a7f70; }"
               "  button.toggle.on { background:#2f7a34; border-color:#2f7a34; }"
               "  button:disabled { opacity:0.5; cursor:default; }"
               "  .canvas-host { min-height:280px; border:1px dashed var(--line); border-radius:8px; padding:8px; background:#fff; overflow:auto; }"
               "  .docs-nav { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:8px; }"
               "  .docs-body { min-height:240px; border:1px solid var(--line); border-radius:8px; padding:10px; color:var(--ink); background:#fff; overflow:auto; }"
               "  .docs-body h3 { margin:0 0 8px; font-size:1rem; color:var(--ink); }"
               "  .docs-body p { margin:0 0 8px; color:var(--muted); line-height:1.35; }"
               "  .docs-body code { font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size:0.88rem; }"
               "  .docs-body pre { margin:0 0 8px; padding:8px; border:1px solid var(--line); border-radius:8px; background:#faf7f1; overflow:auto; }"
               "  .docs-body pre code { color:var(--ink); }"
               "  @media (max-width: 980px) { .layout { grid-template-columns:1fr; } .repl-output, .editor-code-wrap { height:200px; } .editor-file-controls { grid-template-columns: 1fr 1fr; } }"
               "</style>"))

    (load-storage-state!)
    (update-typecheck-ui!)
    (let [active-file (:editor-active-file @app-state)
          active-content (get-in @app-state [:editor-files active-file] default-editor-source)]
      (set! (.-value (by-id "editor-file-name")) active-file)
      (set! (.-value (by-id "editor-input")) active-content)
      (refresh-editor-file-list!)
      (update-editor-highlight!))

    (turtle/set-window-host! (by-id "canvas-host"))
    (update-docs!)

    (.addEventListener (by-id "repl-eval") "click" (fn [_] (eval-repl-input!)))
    (.addEventListener (by-id "repl-typecheck") "click" (fn [_] (toggle-typecheck!)))
    (.addEventListener (by-id "repl-clear") "click" (fn [_] (clear-repl-output!)))
    (.addEventListener (by-id "editor-save") "click" (fn [_] (save-editor-file!)))
    (.addEventListener (by-id "editor-load") "click" (fn [_] (load-editor-file!)))
    (.addEventListener (by-id "editor-format") "click" (fn [_] (format-editor!)))
    (.addEventListener (by-id "editor-file-list") "change"
                       (fn [e]
                         (let [filename (.-value (.-target e))]
                           (set-active-editor-file! filename))))
    (.addEventListener (by-id "editor-run") "click" (fn [_] (run-editor!)))
    (.addEventListener (by-id "editor-input") "input"
                       (fn [_]
                         (editor-maybe-dedent-control-line!)
                         (update-editor-highlight!)))
    (.addEventListener (by-id "editor-input") "scroll"
                       (fn [e]
                         (let [input (.-target e)
                               hl (by-id "editor-highlight")]
                           (set! (.-scrollTop hl) (.-scrollTop input))
                           (set! (.-scrollLeft hl) (.-scrollLeft input)))))
    (.addEventListener (by-id "repl-input") "keydown"
                       (fn [e]
                         (case (.-key e)
                           "Enter" (do (.preventDefault e)
                                       (eval-repl-input!))
                           "ArrowUp" (do (.preventDefault e)
                                         (navigate-repl-history! :up))
                           "ArrowDown" (do (.preventDefault e)
                                           (navigate-repl-history! :down))
                           nil)))
    (.addEventListener (by-id "editor-input") "keydown"
                       (fn [e]
                         (cond
                           (and (= "Enter" (.-key e)) (.-ctrlKey e))
                           (do (.preventDefault e)
                               (run-editor!))
                           (and (= "F" (.-key e)) (.-ctrlKey e) (.-shiftKey e))
                           (do (.preventDefault e)
                               (format-editor!))
                           (and (= "Tab" (.-key e)) (.-shiftKey e))
                           (do (.preventDefault e)
                               (editor-indent-selection! :left))
                           (= "Tab" (.-key e))
                           (do (.preventDefault e)
                               (editor-indent-selection! :right))
                           (= "Enter" (.-key e))
                           (do (.preventDefault e)
                               (editor-auto-indent-enter!))
                           :else nil)))

    (.addEventListener (by-id "docs-prev") "click"
                       (fn [_]
                         (swap! app-state update :docs-page #(max 0 (dec %)))
                         (update-docs!)))
    (.addEventListener (by-id "docs-next") "click"
                       (fn [_]
                         (let [last-page (dec (count (:docs-pages @app-state)))]
                           (swap! app-state update :docs-page #(min last-page (inc %))))
                         (update-docs!)))

    (append-line! "info" "Browser IDE build: 2026-03-03q")))

(defn init []
  (render!))
