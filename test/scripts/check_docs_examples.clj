#!/usr/bin/env clojure

(require '[clojure.java.io :as io])
(require '[clojure.string :as str])
(require '[nex.compiler.jvm.repl :as compiled-repl])
(require '[nex.interpreter :as interpreter])
(require '[nex.parser :as parser])
(require '[nex.repl :as repl])

(defn md-file? [^java.io.File f]
  (and (.isFile f)
       (.endsWith (.getName f) ".md")))

(defn root-markdown-files [root]
  (->> (.listFiles (io/file root))
       (filter md-file?)
       (sort-by #(.getPath ^java.io.File %))
       vec))

(defn parse-args [args]
  (loop [opts {:mode :progressive
               :backend :interpreter
               :run-app true
               :root "docs/book"
               :files []}
         remaining args]
    (if (empty? remaining)
      (update opts :files #(if (seq %) % (root-markdown-files (:root opts))))
      (let [[arg & rest] remaining]
        (cond
          (= arg "--isolated")
          (recur (assoc opts :mode :isolated) rest)

          (= arg "--progressive")
          (recur (assoc opts :mode :progressive) rest)

          (= arg "--no-app")
          (recur (assoc opts :run-app false) rest)

          (= arg "--compiled-backend")
          (recur (assoc opts :backend :compiled) rest)

          (= arg "--interpreter-backend")
          (recur (assoc opts :backend :interpreter) rest)

          (= arg "--book")
          (recur (assoc opts :root "docs/book") rest)

          (= arg "--tut")
          (recur (assoc opts :root "docs/tut") rest)

          (= arg "--root")
          (recur (assoc opts :root (first rest)) (rest rest))

          :else
          (recur (update opts :files conj (io/file arg)) rest))))))

(def code-fence-pattern
  #"(?s)```([^\r\n`]*)\r?\n(.*?)\r?\n```")

(defn extract-nex-blocks [^java.io.File f]
  (->> (re-seq code-fence-pattern (slurp f))
       (map-indexed (fn [idx [_ lang code]]
                      {:index (inc idx)
                       :file (.getPath f)
                       :lang (some-> lang str/trim str/lower-case not-empty)
                       :code (str/trim code)}))
       (remove #(str/blank? (:code %)))
       vec))

(defn- transcript-block?
  [{:keys [code]}]
  (boolean (re-find #"(?m)^\s*nex>\s*" code)))

(defn- transcript-cells
  [code]
  (loop [lines (str/split-lines code)
         current []
         cells []]
    (if-let [line (first lines)]
      (if-let [[_ cell] (re-matches #"\s*nex>\s?(.*)" line)]
        (recur (rest lines)
               [(str/trimr cell)]
               (cond-> cells
                 (seq current) (conj (str/trim (str/join "\n" current)))))
        (if (and (seq current)
                 (re-matches #"\s+\S.*" line))
          (recur (rest lines)
                 (conj current (str/triml line))
                 cells)
          (recur (rest lines)
                 []
                 (cond-> cells
                   (seq current) (conj (str/trim (str/join "\n" current)))))))
      (cond-> cells
        (seq current) (conj (str/trim (str/join "\n" current)))))))

(defn- transcript-cell-records
  [code]
  (loop [lines (str/split-lines code)
         current-code []
         current-output []
         mode nil
         cells []]
    (if-let [line (first lines)]
      (if-let [[_ cell] (re-matches #"\s*nex>\s?(.*)" line)]
        (recur (rest lines)
               [(str/trimr cell)]
               []
               :code
               (cond-> cells
                 (seq current-code)
                 (conj {:code (str/trim (str/join "\n" current-code))
                        :expected-output (some->> current-output
                                                  (str/join "\n")
                                                  str/trim
                                                  not-empty)})))
        (cond
          (and (= mode :code)
               (seq current-code)
               (re-matches #"\s+\S.*" line))
          (recur (rest lines)
                 (conj current-code (str/triml line))
                 current-output
                 :code
                 cells)

          (seq current-code)
          (recur (rest lines)
                 current-code
                 (conj current-output (str/trimr line))
                 :output
                 cells)

          :else
          (recur (rest lines) [] [] nil cells)))
      (cond-> cells
        (seq current-code)
        (conj {:code (str/trim (str/join "\n" current-code))
               :expected-output (some->> current-output
                                         (str/join "\n")
                                         str/trim
                                         not-empty)})))))

(defn- block-cell-records
  [{:keys [code] :as block}]
  (if (transcript-block? block)
    (->> (transcript-cell-records code)
         (remove #(str/blank? (:code %)))
         vec)
    [{:code (str/trim code)
      :expected-output nil}]))

(defn- block-cells
  [block]
  (mapv :code (block-cell-records block)))

(defn- runnable-cell?
  [code]
  (try
    (parser/parse code)
    true
    (catch Exception _
      false)))

(defn- runnable-block?
  [block]
  (let [cells (block-cells block)]
    (and (seq cells)
         (every? runnable-cell? cells))))

(defn- purely-illustrative-transcript?
  [code]
  (let [lines (->> (str/split-lines code)
                   (remove str/blank?))
        prompt-lines (filter #(re-matches #"\s*nex>.*" %) lines)
        continuation-lines (filter #(re-matches #"\s+\S.*" %) lines)]
    (and (seq prompt-lines)
         (empty? continuation-lines)
         (every? #(re-matches #"\s*nex>.*--.*" %) prompt-lines))))

(defn skip-block-reason [root {:keys [lang code] :as _block}]
  (cond
    (and lang
         (not (#{"nex" "text"} lang)))
    (str "contains a " lang " code block rather than Nex code")

    (and (= lang "text")
         (not (re-find #"(?m)^\s*nex>\s*" code)))
    "contains explanatory text examples rather than runnable REPL examples"

    (purely-illustrative-transcript? code)
    "contains illustrative transcript comments rather than a runnable session"

    (re-find #"(?m)^\s*\.\.\.\s*$" code)
    "contains ellipsis placeholder"

    (re-find #"(?s)^\s*name\s*\(params\)\s*(?::\s*ReturnType)?\s*do\s*end\s*$" code)
    "contains a syntax template rather than runnable Nex code"

    (re-find #"(?m)^\s*Suggested files:\s*$" code)
    "contains prose instead of Nex code"

    (re-find #"(?im)^\s*nex>\s*--\s*do not run this\s*$" code)
    "contains an explicitly non-runnable example"

    :else
    nil))

(defn class-has-run? [class-def]
  (boolean
   (some (fn [section]
           (cond
             (= (:type section) :feature-section)
             (some #(and (= (:type %) :method)
                         (= (:name %) "run"))
                   (:members section))

             (= (:type section) :method)
             (= (:name section) "run")

             :else
             false))
         (:body class-def))))

(defn runnable-app? [ctx]
  (when-let [class-def (get @(:classes ctx) "App")]
    (class-has-run? class-def)))

(defn- repl-command?
  [code]
  (str/starts-with? (str/triml code) ":"))

(defn eval-snippet! [ctx code source-id expected-output]
  (binding [repl/*type-checking-enabled* (atom true)]
    (let [result* (atom nil)
          out (with-out-str
                (reset! result*
                        (if (repl-command? code)
                          (repl/handle-command ctx code)
                          (repl/eval-code ctx code source-id))))
          next-ctx (let [result @result*]
                     (cond
                       (= result :quit) ctx
                       (some? result) result
                       :else ctx))
          expected-error? (and expected-output
                               (or (str/includes? expected-output "Syntax error:")
                                   (re-find #"(?m)^Error:" expected-output)))
          actual-error? (or (str/includes? out "Syntax error:")
                            (re-find #"(?m)^Error:" out))]
      (when (and actual-error?
                 (not expected-error?))
        (throw (ex-info (str "Snippet failed: " source-id)
                        {:source-id source-id
                         :output out})))
      (when (and expected-error?
                 (not actual-error?))
        (throw (ex-info (str "Snippet did not fail as expected: " source-id)
                        {:source-id source-id
                         :output out
                         :expected-output expected-output})))
      {:ctx next-ctx
       :output out})))

(defn run-app! [ctx source-id]
  (:ctx (eval-snippet! ctx "let __docs_app: App := create App\n__docs_app.run()" source-id nil)))

(defn fresh-repl-state [backend]
  (let [ctx (repl/init-repl-context)
        var-types (atom {})
        backend-atom (atom :interpreter)
        compiled-session (atom (compiled-repl/make-session))]
    (binding [repl/*repl-var-types* var-types
              repl/*repl-backend* backend-atom
              repl/*compiled-repl-session* compiled-session]
      (when (= :compiled backend)
        (with-out-str
          (repl/handle-command ctx ":backend compiled"))))
    {:ctx ctx
     :var-types var-types
     :backend-atom backend-atom
     :compiled-session compiled-session}))

(defn eval-with-fresh-state! [code source-id expected-output run-app? backend]
  (let [{:keys [ctx var-types backend-atom compiled-session]} (fresh-repl-state backend)]
    (binding [repl/*repl-var-types* var-types
              repl/*repl-backend* backend-atom
              repl/*compiled-repl-session* compiled-session]
      (let [ctx' (:ctx (eval-snippet! ctx code source-id expected-output))]
        (when (and run-app? (runnable-app? ctx'))
          (run-app! ctx' (str source-id ":App.run")))
        ctx'))))

(defn- declaration-key
  [code]
  (when-let [[_ kind name]
             (or (re-find #"(?m)^\s*(class|function)\s+([A-Za-z_][A-Za-z0-9_]*)" code)
                 (re-find #"(?m)^\s*(type)\s+([A-Za-z_][A-Za-z0-9_]*)" code))]
    [(keyword (str/lower-case kind)) name]))

(defn- declaration-block?
  [block]
  (boolean (declaration-key (first (block-cells block)))))

(defn- active-blocks
  [root blocks]
  (->> blocks
       (remove #(skip-block-reason root %))
       (filter runnable-block?)
       vec))

(defn run-progressive-file! [^java.io.File f root run-app? backend]
  (let [state (atom (fresh-repl-state backend))
        seen-declarations (atom #{})
        blocks (extract-nex-blocks f)
        runnable-blocks (active-blocks root blocks)
        block-total (count runnable-blocks)]
    (doseq [[position {:keys [index] :as block}] (map-indexed vector runnable-blocks)]
      (println (format "  block %d/%d  %s#block-%d"
                       (inc position)
                       block-total
                       (.getPath f)
                       index))
      (flush)
      (let [cells (block-cells block)
            first-cell (first cells)
            decl (declaration-key first-cell)]
        (cond
          (and (transcript-block? block)
               decl
               (contains? @seen-declarations decl))
          (reset! state (fresh-repl-state backend)))
        (when decl
          (swap! seen-declarations conj decl))
        (if (and (not (transcript-block? block))
          (declaration-block? block))
          (doseq [[cell-pos {:keys [code expected-output]}] (map-indexed vector (block-cell-records block))]
            (eval-with-fresh-state! code
                                    (str (.getPath f) "#block-" index ":cell-" (inc cell-pos))
                                    expected-output
                                    run-app?
                                    backend))
          (let [{:keys [ctx var-types backend-atom compiled-session]} @state]
            (binding [repl/*repl-var-types* var-types
                      repl/*repl-backend* backend-atom
                      repl/*compiled-repl-session* compiled-session]
              (let [final-ctx (reduce (fn [current-ctx [cell-pos {:keys [code expected-output]}]]
                                        (:ctx (eval-snippet! current-ctx
                                                             code
                                                             (str (.getPath f) "#block-" index ":cell-" (inc cell-pos))
                                                             expected-output)))
                                      ctx
                                      (map-indexed vector (block-cell-records block)))]
                (when (not (identical? final-ctx ctx))
                  (swap! state assoc :ctx final-ctx))))))))
    (let [{:keys [ctx var-types backend-atom compiled-session]} @state]
      (binding [repl/*repl-var-types* var-types
                repl/*repl-backend* backend-atom
                repl/*compiled-repl-session* compiled-session]
        (when (and run-app? (runnable-app? ctx))
          (run-app! ctx (str (.getPath f) "#App.run")))))
    {:blocks blocks}))

(defn result-ok [file mode details]
  {:file file :mode mode :ok true :details details})

(defn result-fail [file mode e]
  {:file file
   :mode mode
   :ok false
   :message (or (ex-message e) (str e))
   :data (ex-data e)})

(defn run-file! [position total ^java.io.File f {:keys [mode run-app root backend]}]
  (let [blocks (extract-nex-blocks f)
        runnable-blocks (active-blocks root blocks)]
    (println (format "RUN  %d/%d  %s  (%s, %d active block%s)"
                     position
                     total
                     (.getPath f)
                     (name backend)
                     (count runnable-blocks)
                     (if (= 1 (count runnable-blocks)) "" "s")))
    (flush))
  (try
    (case mode
      :isolated
      (do
        (doseq [[block-position {:keys [index] :as block}]
                (map-indexed vector (active-blocks root (extract-nex-blocks f)))]
          (println (format "  block %d/%d  %s#block-%d"
                           (inc block-position)
                           (count (active-blocks root (extract-nex-blocks f)))
                           (.getPath f)
                           index))
          (flush)
          (doseq [[cell-pos {:keys [code expected-output]}] (map-indexed vector (block-cell-records block))]
            (eval-with-fresh-state! code
                                    (str (.getPath f) "#block-" index ":cell-" (inc cell-pos))
                                    expected-output
                                    run-app
                                    backend)))
        (result-ok (.getPath f) mode nil))

      :progressive
      (do
        (run-progressive-file! f root run-app backend)
        (result-ok (.getPath f) mode nil)))
    (catch Exception e
      (result-fail (.getPath f) mode e))))

(defn print-summary [root backend results]
  (let [passed (count (filter :ok results))
        failed (remove :ok results)
        total (count results)]
    (println "Docs example smoke test")
    (println "Root:" root)
    (println "Backend:" (name backend))
    (println)
    (doseq [result results]
      (if (:ok result)
        (do
          (println "PASS" (:file result))
          (flush))
        (do
          (println "FAIL" (:file result))
          (println "  " (:message result))
          (flush))))
    (println)
    (println "Files:" total)
    (println "Passed:" passed)
    (println "Failed:" (count failed))
    (when (seq failed)
      (System/exit 1))))

(let [opts (parse-args *command-line-args*)]
  (try
    (let [files (:files opts)
          total (count files)
          results (mapv (fn [[idx f]]
                          (run-file! (inc idx) total f opts))
                        (map-indexed vector files))]
      (print-summary (:root opts) (:backend opts) results))
    (finally
      (interpreter/shutdown-runtime!))))
