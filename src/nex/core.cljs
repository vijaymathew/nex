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
  #{"class" "deferred" "feature" "inherit" "end" "do" "if" "then" "else" "elseif"
    "when" "from" "until" "invariant" "variant" "require" "ensure"
    "let" "as" "and" "or" "not" "fn" "old" "create" "private" "note"
    "with" "import" "intern" "function" "raise" "rescue" "retry" "spawn" "select"
    "repeat" "across" "case" "of"})

(def nex-types
  #{"Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Task" "Channel" "Any" "Void" "Function" "Cursor" "Window" "Turtle" "Image"})

(def nex-constants
  #{"true" "false" "nil"})

(def nex-builtins
  #{"print" "println" "type_of" "type_is" "result" "exception"})

(defonce app-state
  (atom {:ctx (interp/make-context)
         :repl-history []
         :repl-history-index nil
         :repl-history-draft ""
         :typecheck-enabled false
         :repl-var-types {}
         :editor-files {"scratch.nex" default-editor-source}
         :editor-active-file "scratch.nex"
         :editor-file-handle nil
         :docs-pages
         [(str "<h3>1) First Program</h3>"
               "<p>Start with a tiny program and comment syntax.</p>"
               "<pre><code>print(\"Hello, Nex\")\n"
               "-- This line is ignored by the compiler/interpreter\n"
               "print(\"Hello again\")</code></pre>"
               "<p>Use the REPL for short experiments and the editor for larger snippets.</p>")
          (str "<h3>2) Values, Variables, and Types</h3>"
               "<p>Variables use <code>let</code> and assignment <code>:=</code>.</p>"
               "<pre><code>let name: String := \"Ada\"\n"
               "let age: Integer := 12\n"
               "let height: Real := 1.52\n"
               "let ok: Boolean := true\n\n"
               "let x := 10\n"
               "let y := x + 5\n"
               "let maybe_name: ?String := nil</code></pre>")
          (str "<h3>3) Expressions and Control Flow</h3>"
               "<pre><code>let a := 10 + 2 * 3\n"
               "let b := (10 + 2) * 3\n"
               "let same := a = b\n"
               "let valid := (a &gt; 5) and not false</code></pre>"
               "<p>Conditionals and expression-level choice:</p>"
               "<pre><code>if age &gt;= 18 then\n"
               "  print(\"adult\")\n"
               "elseif age &gt;= 13 then\n"
               "  print(\"teen\")\n"
               "else\n"
               "  print(\"child\")\n"
               "end\n\n"
               "let category := when age &gt;= 18 \"adult\" else \"minor\" end</code></pre>"
               "<p><code>case/of</code> is also available for branch-by-value logic.</p>")
          (str "<h3>4) Repetition</h3>"
               "<p>Nex loops from the tutorial: <code>from/until</code>, <code>repeat</code>, <code>across</code>.</p>"
               "<pre><code>from\n"
               "  let i: Integer := 1\n"
               "until\n"
               "  i &gt; 5\n"
               "do\n"
               "  print(i)\n"
               "  i := i + 1\n"
               "end</code></pre>"
               "<pre><code>repeat 3 do\n"
               "  print(\"tick\")\n"
               "end\n\n"
               "across [10, 20, 30] as x do\n"
               "  print(x)\n"
               "end</code></pre>")
          (str "<h3>5) Functions and Data Structures</h3>"
               "<pre><code>function double(n: Integer): Integer\n"
               "do\n"
               "  result := n * 2\n"
               "end\n\n"
               "let inc := fn (n: Integer): Integer do\n"
               "  result := n + 1\n"
               "end</code></pre>"
               "<p>Arrays and maps:</p>"
               "<pre><code>let xs: Array [Integer] := [1, 2, 3]\n"
               "print(xs.get(0))\n\n"
               "let m: Map [String, String] := {\"name\": \"Nex\", \"kind\": \"language\"}\n"
               "print(m.get(\"name\"))</code></pre>")
          (str "<h3>6) Classes, Generics, and Inheritance</h3>"
               "<pre><code>class Counter\n"
               "  create\n"
               "    make(start: Integer) do\n"
               "      this.value := start\n"
               "    end\n\n"
               "  feature\n"
               "    value: Integer\n\n"
               "    inc() do\n"
               "      this.value := this.value + 1\n"
               "    end\n"
               "end\n\n"
               "let c: Counter := create Counter.make(10)\n"
               "c.inc</code></pre>"
               "<p>Generics and inheritance are part of the core language and covered in detail in the full tutorial.</p>")
          (str "<h3>7) Contracts and Error Handling</h3>"
               "<p>Nex supports Design by Contract with <code>require</code>, <code>ensure</code>, and <code>invariant</code>.</p>"
               "<pre><code>spend(amount: Real)\n"
               "  require\n"
               "    enough: amount &lt;= money\n"
               "  do\n"
               "    money := money - amount\n"
               "  ensure\n"
               "    less: money = old money - amount\n"
               "  end</code></pre>"
               "<p>Error handling pattern:</p>"
               "<pre><code>do\n"
               "  raise \"not ready\"\n"
               "rescue\n"
               "  print(exception)\n"
               "  retry\n"
               "end</code></pre>"
               "<p>In-app pages are synced to the flow in <code>docs/md/TUTORIAL.md</code>.</p>")]
         :web-ide-pages
         [(str "<h3>1) Web IDE Layout</h3>"
               "<p>The browser IDE is split into three working areas:</p>"
               "<ul><li><b>Editor</b>: write and run larger programs.</li>"
               "<li><b>REPL</b>: test expressions quickly.</li>"
               "<li><b>Canvas</b>: drawing output for Window/Turtle examples.</li></ul>")
          (str "<h3>2) File Workflow</h3>"
               "<p>Use the <code>File</code> menu for project-style editing in the browser:</p>"
               "<ul><li><b>New File</b>: create a new buffer.</li>"
               "<li><b>Open File</b>: load a local <code>.nex</code> file.</li>"
               "<li><b>Save File</b>: save to the filesystem (or download fallback).</li></ul>"
               "<p>Keyboard shortcuts: <code>Ctrl/Cmd+N</code>, <code>Ctrl/Cmd+O</code>, <code>Ctrl/Cmd+S</code>.</p>")
          (str "<h3>3) Editor + REPL Usage</h3>"
               "<p>Editor controls:</p>"
               "<ul><li><b>Format</b>: auto-format the current buffer.</li>"
               "<li><b>Run In REPL</b>: execute editor content through the REPL runtime.</li></ul>"
               "<p>REPL tips:</p>"
               "<ul><li>Press <code>Enter</code> in REPL input to evaluate.</li>"
               "<li>Use <code>ArrowUp</code>/<code>ArrowDown</code> for REPL history.</li>"
               "<li>Toggle <b>Typecheck</b> before evaluation if you want static checks.</li></ul>")
          (str "<h3>4) Productivity Shortcuts</h3>"
               "<ul><li><code>Ctrl+Enter</code> in editor: run editor buffer.</li>"
               "<li><code>Ctrl+Shift+F</code> in editor: format buffer.</li>"
               "<li><code>Tab</code>/<code>Shift+Tab</code>: indent/outdent selection.</li>"
               "<li><code>Esc</code>: close open menus and the help pane.</li></ul>"
               "<p>Use the Tutorial and this guide together: tutorial teaches language features, this guide teaches IDE workflow.</p>")]
         :docs-page 0
         :docs-mode :tutorial
         :tutorial-visible false}))

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

(declare update-active-file-label!)

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

(defn- set-active-editor-file! [filename]
  (when-let [content (get-in @app-state [:editor-files filename])]
    (swap! app-state assoc :editor-active-file filename :editor-file-handle nil)
    (set! (.-value (by-id "editor-input")) content)
    (update-editor-highlight!)
    (persist-editor-state!)
    (update-active-file-label!)))

(defn- sanitize-filename [s]
  (let [trimmed (str/trim s)]
    (if (str/blank? trimmed) "scratch.nex" trimmed)))

(defn- fs-access-supported? []
  (and (fn? (.-showOpenFilePicker js/window))
       (fn? (.-showSaveFilePicker js/window))))

(defn- nex-picker-types []
  (clj->js [{:description "Nex source files"
             :accept {"text/plain" [".nex" ".txt"]}}]))

(defn- snapshot-active-editor! []
  (let [active (sanitize-filename (:editor-active-file @app-state))
        content (.-value (by-id "editor-input"))]
    (swap! app-state assoc-in [:editor-files active] content)
    (swap! app-state assoc :editor-active-file active)
    (persist-editor-state!)
    {:filename active :content content}))

(defn- apply-opened-file! [filename content handle]
  (let [safe-name (sanitize-filename filename)]
    (swap! app-state assoc-in [:editor-files safe-name] content)
    (swap! app-state assoc :editor-active-file safe-name :editor-file-handle handle)
    (persist-editor-state!)
    (set! (.-value (by-id "editor-input")) content)
    (update-editor-highlight!)
    (update-active-file-label!)
    (append-line! "info" (str "Loaded '" safe-name "' from filesystem."))))

(defn- report-file-error! [prefix err]
  (when-not (= "AbortError" (.-name err))
    (append-line! "err" (str prefix ": " (or (.-message err) err)))))

(defn- open-file-with-handle! [handle]
  (-> (.getFile handle)
      (.then (fn [file]
               (-> (.text file)
                   (.then (fn [text]
                            (apply-opened-file! (or (.-name file) "scratch.nex")
                                                text
                                                handle)))
                   (.catch (fn [err]
                             (report-file-error! "Open failed" err))))))
      (.catch (fn [err]
                (report-file-error! "Open failed" err)))))

(defn- open-file-via-input! []
  (if-let [input (by-id "open-file-input")]
    (do
      (set! (.-value input) "")
      (.click input))
    (append-line! "err" "Open failed: file input not available.")))

(defn- choose-file-to-open []
  (if (fs-access-supported?)
    (-> (.showOpenFilePicker js/window
                             #js {:multiple false
                                  :types (nex-picker-types)})
        (.then (fn [handles]
                 (when-let [handle (aget handles 0)]
                   (open-file-with-handle! handle))))
        (.catch (fn [err]
                  (report-file-error! "Open failed" err))))
    (open-file-via-input!)))

(defn- trigger-download! [filename content]
  (let [blob (js/Blob. #js [content] #js {:type "text/plain;charset=utf-8"})
        url (.createObjectURL js/URL blob)
        a (.createElement js/document "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (set! (.-style.display a) "none")
    (.appendChild (.-body js/document) a)
    (.click a)
    (.remove a)
    (.revokeObjectURL js/URL url)))

(defn- finalize-file-save! [filename chosen-name content handle]
  (swap! app-state
         (fn [s]
           (-> s
               (update :editor-files
                       (fn [files]
                         (let [files' (if (and filename (not= filename chosen-name))
                                        (dissoc files filename)
                                        files)]
                           (assoc files' chosen-name content))))
               (assoc :editor-active-file chosen-name
                      :editor-file-handle handle))))
  (persist-editor-state!)
  (update-active-file-label!)
  (append-line! "info" (str "Saved '" chosen-name "' to filesystem.")))

(defn- write-file-handle! [handle filename content]
  (let [chosen-name (sanitize-filename (or (.-name handle) filename))]
    (-> (.createWritable handle)
        (.then (fn [writable]
                 (-> (.write writable content)
                     (.then (fn [_]
                              (-> (.close writable)
                                  (.then (fn [_]
                                           (finalize-file-save! filename chosen-name content handle))))))
                     (.catch (fn [err]
                               (report-file-error! "Save failed" err))))))
        (.catch (fn [err]
                  (report-file-error! "Save failed" err))))))

(defn- save-current-file! []
  (let [{:keys [filename content]} (snapshot-active-editor!)
        existing-handle (:editor-file-handle @app-state)]
    (if (fs-access-supported?)
      (if existing-handle
        (write-file-handle! existing-handle filename content)
        (-> (.showSaveFilePicker js/window
                                 #js {:suggestedName filename
                                      :types (nex-picker-types)})
            (.then (fn [handle]
                     (when handle
                       (write-file-handle! handle filename content))))
            (.catch (fn [err]
                      (report-file-error! "Save failed" err)))))
      (do
        (trigger-download! filename content)
        (append-line! "info" (str "Downloaded '" filename "' (browser fallback)."))))))

(defn- create-new-file! []
  (let [suggested (str "file" (inc (count (:editor-files @app-state))) ".nex")
        input (js/prompt "New file name:" suggested)]
    (when (some? input)
      (let [filename (sanitize-filename input)
            exists? (contains? (:editor-files @app-state) filename)
            overwrite? (or (not exists?)
                           (js/confirm (str "File '" filename "' already exists. Overwrite?")))]
        (when overwrite?
          (swap! app-state assoc-in [:editor-files filename] "")
          (swap! app-state assoc :editor-file-handle nil)
          (set-active-editor-file! filename)
          (persist-editor-state!)
          (append-line! "info" (str "Created '" filename "'.")))))))

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
  (try
    ;; Reuse interpreter-safe formatting so REPL values like anonymous functions
    ;; (NexObject with closure env) never recurse through cyclic runtime state.
    (interp/nex-format-value v)
    (catch :default _
      (cond
        (and (map? v) (:nex-builtin-type v)) (str "#<" (name (:nex-builtin-type v)) ">")
        (array? v) (pr-str (vec v))
        (instance? js/Map v) (pr-str (into {} (for [entry (js/Array.from (.entries v))]
                                                [(aget entry 0) (aget entry 1)])))
        (string? v) v
        :else (str v)))))

(defn- looks-like-definition? [input]
  (boolean (re-find #"^\s*(class|function|import|intern)\b" input)))

(defn- wrap-expression [input]
  (str "class __BrowserRepl__\n"
       "feature\n"
       "  __eval__(): Any do\n"
       "    " input "\n"
       "  end\n"
       "end"))

(defn- wrap-statement-block [input]
  (str "class __BrowserRepl__\n"
       "feature\n"
       "  __eval__(): Any do\n"
       input "\n"
       "  end\n"
       "end"))

(defn- eval-wrapped! [ctx wrapped-code]
  (let [ast (p/ast wrapped-code)
        _ (typecheck-ast! ast)
        method-def (-> ast :classes first :body first :members first)
        body (:body method-def)]
    (.then (reduce (fn [acc stmt]
                     (.then acc
                            (fn [_]
                              (interp/eval-node-async ctx stmt))))
                   (js/Promise.resolve nil)
                   body)
           (fn [result]
             (when (:typecheck-enabled @app-state)
               (remember-typed-lets! body))
             {:result result
              :output @(:output ctx)}))))

(defn- run-program! [ctx source]
  (let [ast (p/ast source)
        _ (typecheck-ast! ast)]
    (.then (interp/eval-node-async ctx ast)
           (fn [raw-result]
             {:result (if (= :program (:type ast)) nil raw-result)
              :output @(:output ctx)}))))

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
        (let [run-promise
              (if (looks-like-definition? trimmed)
                (run-program! ctx trimmed)
                (try
                  (eval-wrapped! ctx (wrap-expression trimmed))
                  (catch :default e1
                    (if (typecheck-error? e1)
                      (throw e1)
                      (try
                        (eval-wrapped! ctx (wrap-statement-block trimmed))
                        (catch :default _e2
                          (throw e1)))))))]
          (.then run-promise
                 (fn [{:keys [result output]}]
                   (if (looks-like-definition? trimmed)
                     (do
                       (doseq [line output]
                         (append-line! "out" (str line)))
                       (when (empty? output)
                         (append-line! "info" "Loaded definition.")))
                     (show-runtime-output! output result))))
          (.catch run-promise
                  (fn [e]
                    (let [msg (str "Error: " (or (.-message e) (str e)))]
                      (append-line! "err" msg)
                      (when (str/includes? msg "Parser module missing")
                        (append-line! "err" (parser-debug-info)))))))
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
                run-promise
                (if has-defs?
                  (.then (interp/eval-node-async ctx ast)
                         (fn [_]
                           {:result nil :output @(:output ctx)}))
                  (eval-wrapped! ctx (wrap-statement-block trimmed)))]
            (.then run-promise
                   (fn [{:keys [result output]}]
                     (show-runtime-output! output result)
                     (when (and (empty? output) (nil? result))
                       (append-line! "info" "Program loaded and executed."))))
            (.catch run-promise
                    (fn [e]
                      (append-line! "err" (str "Error: " (or (.-message e) (str e)))))))
          (catch :default e
            (append-line! "err" (str "Error: " (or (.-message e) (str e))))))))))

(defn- update-docs! []
  (let [{:keys [docs-pages web-ide-pages docs-page docs-mode]} @app-state
        pages (if (= docs-mode :web-ide) web-ide-pages docs-pages)
        pane-title (if (= docs-mode :web-ide) "Web IDE Guide" "Tutorial")
        max-page (dec (count pages))
        prev-btn (by-id "docs-prev")
        next-btn (by-id "docs-next")
        pane-title-el (by-id "docs-pane-title")
        title-el (by-id "docs-title")
        body-el (by-id "docs-body")]
    (when (and prev-btn next-btn pane-title-el title-el body-el)
      (set! (.-textContent pane-title-el) pane-title)
      (set! (.-textContent title-el) (str "Page " (inc docs-page) " / " (count pages)))
      (set! (.-innerHTML body-el) (nth pages docs-page))
      (set! (.-disabled prev-btn) (zero? docs-page))
      (set! (.-disabled next-btn) (= docs-page max-page)))))

(defn- update-tutorial-visibility! []
  (let [editor-main (by-id "editor-main")
        pane (by-id "tutorial-pane")
        visible? (:tutorial-visible @app-state)]
    (when editor-main
      (set! (.-className editor-main)
            (str "panel editor-main " (if visible? "with-tutorial" "without-tutorial"))))
    (when pane
      (set! (.-className pane)
            (str "tutorial-pane " (if visible? "open" "closed"))))))

(defn- open-tutorial! []
  (swap! app-state assoc :tutorial-visible true :docs-mode :tutorial :docs-page 0)
  (update-docs!)
  (update-tutorial-visibility!))

(defn- open-web-ide-guide! []
  (swap! app-state assoc :tutorial-visible true :docs-mode :web-ide :docs-page 0)
  (update-docs!)
  (update-tutorial-visibility!))

(defn- close-tutorial! []
  (swap! app-state assoc :tutorial-visible false)
  (update-tutorial-visibility!))

(defn- update-active-file-label! []
  (let [label (by-id "active-file-label")
        active (:editor-active-file @app-state)]
    (when label
      (set! (.-textContent label) (str "Active: " active)))))

(defn- close-all-menus! []
  (doseq [menu (array-seq (.querySelectorAll js/document ".menu[open]"))]
    (.removeAttribute menu "open")))

(defn- render! []
  (let [root (or (by-id "app")
                 (let [el (.createElement js/document "div")]
                   (set! (.-id el) "app")
                   (.appendChild (.-body js/document) el)
                   el))]
    (set! (.-innerHTML root)
          (str "<div class='shell'>"
               "  <header class='topbar'><h1>Nex Browser IDE</h1><p>Editor-first workflow with REPL and Canvas.</p></header>"
               "  <nav class='menu-bar'>"
               "    <details class='menu'><summary>File</summary>"
               "      <div class='menu-items'>"
               "        <button id='menu-new' class='menu-item-btn' aria-label='New File'>＋ <span>New File (Ctrl/Cmd+N)</span></button>"
               "        <button id='menu-open' class='menu-item-btn' aria-label='Open File'>⇪ <span>Open File (Ctrl/Cmd+O)</span></button>"
               "        <button id='menu-save' class='menu-item-btn' aria-label='Save File'>↓ <span>Save File (Ctrl/Cmd+S)</span></button>"
               "      </div>"
               "    </details>"
               "    <details class='menu'><summary>Help</summary>"
               "      <div class='menu-items'>"
               "        <button id='menu-tutorial' class='menu-item-btn' aria-label='Tutorial'>? <span>Tutorial</span></button>"
               "        <button id='menu-webide-guide' class='menu-item-btn' aria-label='Web IDE Guide'>ⓘ <span>Web IDE Guide</span></button>"
               "      </div>"
               "    </details>"
               "    <span id='active-file-label' class='active-file-label'></span>"
               "  </nav>"
               "  <input id='open-file-input' type='file' accept='.nex,.txt,text/plain' style='display:none' />"
               "  <main class='ide'>"
               "    <section id='editor-main' class='panel editor-main without-tutorial'>"
               "      <h2>Editor</h2>"
               "      <div class='editor-workarea'>"
               "        <div class='editor-code-wrap'>"
               "          <pre id='editor-highlight' aria-hidden='true'></pre>"
               "          <textarea id='editor-input' spellcheck='false'></textarea>"
               "        </div>"
               "        <aside id='tutorial-pane' class='tutorial-pane closed'>"
               "          <div class='tutorial-head'>"
               "            <h2 id='docs-pane-title'>Tutorial</h2>"
               "            <button id='tutorial-close' class='icon-btn' title='Close' aria-label='Close'>✕</button>"
               "          </div>"
               "          <div class='docs-nav'>"
               "            <button id='docs-prev' class='icon-btn' title='Previous' aria-label='Previous'>←</button>"
               "            <span id='docs-title'></span>"
               "            <button id='docs-next' class='icon-btn' title='Next' aria-label='Next'>→</button>"
               "          </div>"
               "          <article id='docs-body' class='docs-body'></article>"
               "        </aside>"
               "      </div>"
               "      <div class='editor-controls'><button id='editor-format' class='icon-btn' title='Format' aria-label='Format'>≣</button><button id='editor-run' class='icon-btn' title='Run In REPL' aria-label='Run In REPL'>▶</button></div>"
               "    </section>"
               "    <section class='bottom-split'>"
               "      <section class='panel repl'>"
               "        <h2>REPL</h2>"
               "        <div id='repl-output' class='repl-output'></div>"
               "        <div class='repl-controls'>"
               "          <div class='repl-input-row'>"
               "            <input id='repl-input' type='text' placeholder='Enter any Nex expression...' />"
               "          </div>"
               "          <div class='repl-actions'>"
               "            <button id='repl-eval' class='icon-btn' title='Evaluate' aria-label='Evaluate'>▶</button>"
               "            <button id='repl-clear' class='icon-btn' title='Clear' aria-label='Clear'>⌫</button>"
               "            <button id='repl-typecheck' class='toggle off'>Typecheck: OFF</button>"
               "          </div>"
               "        </div>"
               "      </section>"
               "      <section class='panel canvas'>"
               "        <h2>Canvas</h2>"
               "        <div id='canvas-host' class='canvas-host'></div>"
               "      </section>"
               "    </section>"
               "  </main>"
               "</div>"
               "<style>"
               "  :root { --bg-0:#07161e; --bg-1:#0f2b3a; --card:#0d2230; --line:#2f5263; --text:#e8f4ff; --muted:#9fb9c8; --accent:#33d1b0; --accent-2:#ffd166; --code-bg:#041018; --code-line:#234150; --editor-bg:#f8fbff; --editor-ink:#102a3a; --editor-line:#c6d8e5; }"
               "  * { box-sizing:border-box; }"
               "  html, body { height:100%; }"
               "  body { margin:0; font-family:'Space Grotesk', ui-sans-serif, system-ui, sans-serif; color:var(--text); background:radial-gradient(1200px 500px at 10% -10%, #1d5169 0%, transparent 60%), radial-gradient(900px 450px at 100% 0%, #174f44 0%, transparent 58%), linear-gradient(160deg, var(--bg-0), var(--bg-1)); }"
               "  .shell { height:100vh; padding:10px; display:grid; grid-template-rows:auto auto minmax(0, 1fr); gap:10px; overflow:hidden; }"
               "  .topbar h1 { margin:0; font-size:1.35rem; }"
               "  .topbar p { margin:4px 0 10px 0; color:var(--muted); }"
               "  .menu-bar { display:flex; align-items:center; gap:10px; margin-bottom:10px; }"
               "  .menu { position:relative; }"
               "  .menu > summary { list-style:none; cursor:pointer; padding:7px 10px; border:1px solid var(--line); border-radius:8px; background:color-mix(in srgb, var(--card) 88%, transparent); color:var(--text); user-select:none; }"
               "  .menu > summary::-webkit-details-marker { display:none; }"
               "  .menu-items { position:absolute; top:36px; left:0; z-index:20; min-width:150px; background:color-mix(in srgb, var(--card) 92%, transparent); border:1px solid var(--line); border-radius:8px; padding:6px; box-shadow:0 10px 22px rgba(0,0,0,0.25); display:grid; gap:6px; }"
               "  .menu-items button { width:100%; background:color-mix(in srgb, var(--card) 82%, transparent); color:var(--text); border:1px solid var(--line); }"
               "  .menu-item-btn { display:flex; align-items:center; gap:8px; text-align:left; font-weight:600; }"
               "  .active-file-label { margin-left:auto; font-size:0.9rem; color:var(--muted); font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; }"
               "  .ide { display:grid; grid-template-rows:minmax(0, 1fr) minmax(220px, 38vh); gap:12px; min-height:0; }"
               "  .panel { background:color-mix(in srgb, var(--card) 90%, transparent); border:1px solid var(--line); border-radius:10px; padding:10px; backdrop-filter:blur(4px); }"
               "  .panel h2 { margin:0 0 8px; font-size:1rem; color:var(--accent-2); }"
               "  .editor-main { min-height:0; display:grid; grid-template-rows:auto minmax(0, 1fr) auto; gap:8px; }"
               "  .editor-workarea { min-height:0; display:grid; grid-template-columns:minmax(0, 1fr); gap:12px; }"
               "  .editor-main.with-tutorial .editor-workarea { grid-template-columns:minmax(0, 1fr) minmax(300px, 40%); }"
               "  .editor-code-wrap { position:relative; height:100%; min-height:0; border:1px solid var(--editor-line); border-radius:8px; overflow:auto; background:var(--editor-bg); }"
               "  #editor-highlight { margin:0; padding:10px; min-height:100%; white-space:pre; font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size:0.92rem; line-height:1.4; pointer-events:none; color:var(--editor-ink); }"
               "  textarea#editor-input { position:absolute; inset:0; width:100%; height:100%; resize:none; margin:0; padding:10px; border:0; outline:none; font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size:0.92rem; line-height:1.4; background:transparent; color:transparent; caret-color:var(--editor-ink); }"
               "  textarea#editor-input::selection { background:rgba(51,209,176,0.25); color:transparent; }"
               "  .tok-comment { color:#5e7384; font-style:italic; } .tok-kw { color:#5a1a86; font-weight:600; } .tok-type { color:#1e4ea1; font-weight:600; } .tok-const { color:#a61b3b; font-weight:600; } .tok-builtin { color:#0a6c67; } .tok-num { color:#8f1f73; } .tok-str { color:#1f6f42; } .tok-op { color:#9f3a15; font-weight:600; }"
               "  .editor-controls { display:flex; justify-content:flex-start; gap:8px; }"
               "  .bottom-split { display:grid; grid-template-columns:1fr 1fr; gap:12px; min-height:0; }"
               "  .repl { min-height:0; display:grid; grid-template-rows:auto minmax(0, 1fr) auto; }"
               "  .repl-output { min-height:0; overflow:auto; background:var(--code-bg); color:#d4efff; border:1px solid var(--code-line); border-radius:8px; padding:8px; font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size:0.9rem; }"
               "  .repl-line { margin-bottom:4px; white-space:pre-wrap; } .repl-line.input { color:#6ec6ff; } .repl-line.result { color:#8ee59e; } .repl-line.err { color:#ff8a8a; } .repl-line.info { color:#f7cf86; }"
               "  .repl-controls { margin-top:8px; display:grid; row-gap:8px; }"
               "  .repl-controls input { width:100%; min-width:0; padding:8px; border:1px solid var(--line); border-radius:8px; background:#102a3a; color:var(--text); }"
               "  .repl-actions { display:flex; gap:8px; flex-wrap:wrap; }"
               "  button { border:1px solid var(--accent); background:var(--accent); color:#fff; border-radius:8px; padding:8px 12px; cursor:pointer; }"
               "  button.icon-btn { min-width:36px; min-height:36px; padding:6px 10px; font-size:1rem; line-height:1; font-weight:700; }"
               "  button.toggle.off { background:#5e7282; border-color:#5e7282; } button.toggle.on { background:#2f7a34; border-color:#2f7a34; }"
               "  button:disabled { opacity:0.5; cursor:default; }"
               "  .canvas { min-height:0; display:grid; grid-template-rows:auto minmax(0, 1fr); }"
               "  .canvas-host { min-height:0; height:100%; border:1px dashed var(--line); border-radius:8px; padding:8px; background:#0b1f2b; overflow:auto; }"
               "  .tutorial-pane { min-height:0; border:1px solid var(--line); border-radius:8px; background:color-mix(in srgb, var(--card) 94%, transparent); padding:8px; display:grid; grid-template-rows:auto auto minmax(0, 1fr); gap:8px; }"
               "  .tutorial-pane.closed { display:none; }"
               "  .tutorial-pane.open { display:grid; }"
               "  .tutorial-head { display:flex; align-items:center; justify-content:space-between; }"
               "  .docs-nav { display:flex; align-items:center; justify-content:space-between; gap:8px; }"
               "  .docs-body { min-height:0; border:1px solid var(--editor-line); border-radius:8px; padding:10px; color:var(--editor-ink); background:var(--editor-bg); overflow:auto; }"
               "  .docs-body h3 { margin:0 0 8px; font-size:1rem; color:var(--editor-ink); } .docs-body p { margin:0 0 8px; color:#36546a; line-height:1.35; }"
               "  .docs-body code { font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size:0.88rem; } .docs-body pre { margin:0 0 8px; padding:8px; border:1px solid var(--editor-line); border-radius:8px; background:#eef5fb; overflow:auto; } .docs-body pre code { color:var(--editor-ink); }"
               "  @media (max-width: 1200px) { .editor-main.with-tutorial .editor-workarea { grid-template-columns:1fr; grid-template-rows:minmax(0, 1fr) minmax(220px, 40%); } }"
               "  @media (max-width: 980px) { .shell { height:auto; min-height:100vh; overflow:visible; } .ide { grid-template-rows:minmax(0, 1fr) auto; } .bottom-split { grid-template-columns:1fr; } .canvas-host { min-height:220px; } }"
               "</style>"))

    (load-storage-state!)
    (update-typecheck-ui!)
    (let [active-file (:editor-active-file @app-state)
          active-content (get-in @app-state [:editor-files active-file] default-editor-source)]
      (set! (.-value (by-id "editor-input")) active-content)
      (update-editor-highlight!)
      (update-active-file-label!))

    (turtle/set-window-host! (by-id "canvas-host"))
    (update-docs!)
    (update-tutorial-visibility!)

    (.addEventListener (by-id "repl-eval") "click" (fn [_] (eval-repl-input!)))
    (.addEventListener (by-id "repl-typecheck") "click" (fn [_] (toggle-typecheck!)))
    (.addEventListener (by-id "repl-clear") "click" (fn [_] (clear-repl-output!)))
    (.addEventListener (by-id "menu-new") "click" (fn [_] (create-new-file!) (update-active-file-label!) (close-all-menus!)))
    (.addEventListener (by-id "menu-open") "click" (fn [_] (choose-file-to-open) (update-active-file-label!) (close-all-menus!)))
    (.addEventListener (by-id "menu-save") "click" (fn [_] (save-current-file!) (update-active-file-label!) (close-all-menus!)))
    (.addEventListener (by-id "menu-tutorial") "click" (fn [_] (open-tutorial!) (close-all-menus!)))
    (.addEventListener (by-id "menu-webide-guide") "click" (fn [_] (open-web-ide-guide!) (close-all-menus!)))
    (.addEventListener (by-id "tutorial-close") "click" (fn [_] (close-tutorial!)))
    (.addEventListener (by-id "editor-format") "click" (fn [_] (format-editor!)))
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
    (.addEventListener (by-id "open-file-input") "change"
                       (fn [e]
                         (let [input (.-target e)
                               files (.-files input)
                               file (when (and files (> (.-length files) 0))
                                      (aget files 0))]
                           (when file
                             (-> (.text file)
                                 (.then (fn [text]
                                          (apply-opened-file! (or (.-name file) "scratch.nex")
                                                              text
                                                              nil)))
                                 (.catch (fn [err]
                                           (report-file-error! "Open failed" err))))))))
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
                         (let [{:keys [docs-mode docs-pages web-ide-pages]} @app-state
                               pages (if (= docs-mode :web-ide) web-ide-pages docs-pages)
                               last-page (dec (count pages))]
                           (swap! app-state update :docs-page #(min last-page (inc %))))
                         (update-docs!)))

    ;; Close open menus when clicking anywhere outside them.
    (.addEventListener js/document "click"
                       (fn [e]
                         (let [target (.-target e)
                               in-menu? (when (and target (.-closest target))
                                          (.closest target ".menu"))]
                           (when-not in-menu?
                             (close-all-menus!)))))

    ;; Keyboard shortcuts:
    ;; Ctrl/Cmd+S => Save, Ctrl/Cmd+O => Open, Ctrl/Cmd+N => New, Esc => close menu/tutorial
    (.addEventListener js/window "keydown"
                       (fn [e]
                         (let [k (str/lower-case (.-key e))
                               mod? (or (.-ctrlKey e) (.-metaKey e))]
                           (cond
                             (and mod? (= k "s"))
                             (do (.preventDefault e)
                                 (save-current-file!)
                                 (update-active-file-label!)
                                 (close-all-menus!))

                             (and mod? (= k "o"))
                             (do (.preventDefault e)
                                 (choose-file-to-open)
                                 (update-active-file-label!)
                                 (close-all-menus!))

                             (and mod? (= k "n"))
                             (do (.preventDefault e)
                                 (create-new-file!)
                                 (update-active-file-label!)
                                 (close-all-menus!))

                             (= k "escape")
                             (do
                               (close-all-menus!)
                               (when (:tutorial-visible @app-state)
                                 (close-tutorial!)))

                             :else nil))))

    (append-line! "info" "Browser IDE build: 2026-03-11a")))

(defn init []
  (render!))
