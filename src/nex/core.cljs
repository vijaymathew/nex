(ns nex.core
  (:require [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.turtle-browser :as turtle]))

(defonce app-state
  (atom {:ctx (interp/make-context)
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

(defn- append-line! [kind text]
  (let [out (by-id "repl-output")
        line (.createElement js/document "div")]
    (set! (.-className line) (str "repl-line " kind))
    (set! (.-textContent line) text)
    (.appendChild out line)
    (set! (.-scrollTop out) (.-scrollHeight out))))

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
        method-def (-> ast :classes first :body first :members first)
        result (last (mapv #(interp/eval-node ctx %) (:body method-def)))]
    {:result result
     :output @(:output ctx)}))

(defn- run-program! [ctx source]
  (let [ast (p/ast source)
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
                    (try
                      (eval-wrapped! ctx (wrap-statement-block trimmed))
                      (catch :default e2
                        (throw e1)))))]
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
               "        <input id='repl-input' type='text' placeholder='Enter any Nex expression...' />"
               "        <button id='repl-eval'>Evaluate</button>"
               "        <button id='repl-clear'>Clear</button>"
               "      </div>"
               "    </section>"
               "    <section class='panel editor'>"
               "      <h2>2. Nex Editor</h2>"
               "      <textarea id='editor-input' spellcheck='false'></textarea>"
               "      <div class='editor-controls'><button id='editor-run'>Run In REPL</button></div>"
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
               "  .repl-controls { margin-top:8px; display:flex; gap:8px; }"
               "  .repl-controls input { flex:1; min-width:0; padding:8px; border:1px solid var(--line); border-radius:8px; }"
               "  textarea#editor-input { width:100%; height:230px; resize:vertical; padding:8px; border:1px solid var(--line); border-radius:8px; font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size:0.9rem; background:#fff; }"
               "  .editor-controls { margin-top:8px; display:flex; justify-content:flex-end; }"
               "  button { border:1px solid var(--accent); background:var(--accent); color:#fff; border-radius:8px; padding:8px 12px; cursor:pointer; }"
               "  button:disabled { opacity:0.5; cursor:default; }"
               "  .canvas-host { min-height:280px; border:1px dashed var(--line); border-radius:8px; padding:8px; background:#fff; overflow:auto; }"
               "  .docs-nav { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:8px; }"
               "  .docs-body { min-height:240px; border:1px solid var(--line); border-radius:8px; padding:10px; color:var(--ink); background:#fff; overflow:auto; }"
               "  .docs-body h3 { margin:0 0 8px; font-size:1rem; color:var(--ink); }"
               "  .docs-body p { margin:0 0 8px; color:var(--muted); line-height:1.35; }"
               "  .docs-body code { font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size:0.88rem; }"
               "  .docs-body pre { margin:0 0 8px; padding:8px; border:1px solid var(--line); border-radius:8px; background:#faf7f1; overflow:auto; }"
               "  .docs-body pre code { color:var(--ink); }"
               "  @media (max-width: 980px) { .layout { grid-template-columns:1fr; } .repl-output, textarea#editor-input { height:200px; } }"
               "</style>"))

    (set! (.-value (by-id "editor-input"))
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

    (turtle/set-window-host! (by-id "canvas-host"))
    (update-docs!)

    (.addEventListener (by-id "repl-eval") "click" (fn [_] (eval-repl-input!)))
    (.addEventListener (by-id "repl-clear") "click" (fn [_] (clear-repl-output!)))
    (.addEventListener (by-id "editor-run") "click" (fn [_] (run-editor!)))
    (.addEventListener (by-id "repl-input") "keydown"
                       (fn [e]
                         (when (= "Enter" (.-key e))
                           (.preventDefault e)
                           (eval-repl-input!))))
    (.addEventListener (by-id "editor-input") "keydown"
                       (fn [e]
                         (when (and (= "Enter" (.-key e)) (.-ctrlKey e))
                           (.preventDefault e)
                           (run-editor!))))

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
