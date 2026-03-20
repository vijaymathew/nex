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

(defn- block-cells
  [{:keys [code] :as block}]
  (if (transcript-block? block)
    (->> (transcript-cells code)
         (remove str/blank?)
         vec)
    [(str/trim code)]))

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

(defn skip-block-reason [root code]
  (cond
    (re-find #"(?m)^\s*\.\.\.\s*$" code)
    "contains ellipsis placeholder"

    (re-find #"(?m)^\s*Suggested files:\s*$" code)
    "contains prose instead of Nex code"

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

(defn eval-snippet! [ctx code source-id]
  (binding [repl/*type-checking-enabled* (atom true)]
    (let [out (with-out-str
                (repl/eval-code ctx code source-id))]
      (when (or (str/includes? out "Syntax error:")
                (re-find #"(?m)^Error:" out))
        (throw (ex-info (str "Snippet failed: " source-id)
                        {:source-id source-id
                         :output out})))
      out)))

(defn run-app! [ctx source-id]
  (eval-snippet! ctx "let __docs_app: App := create App\n__docs_app.run()" source-id))

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

(defn eval-with-fresh-state! [code source-id run-app? backend]
  (let [{:keys [ctx var-types backend-atom compiled-session]} (fresh-repl-state backend)]
    (binding [repl/*repl-var-types* var-types
              repl/*repl-backend* backend-atom
              repl/*compiled-repl-session* compiled-session]
      (eval-snippet! ctx code source-id)
      (when (and run-app? (runnable-app? ctx))
        (run-app! ctx (str source-id ":App.run")))
      ctx)))

(defn- active-blocks
  [root blocks]
  (->> blocks
       (remove #(skip-block-reason root (:code %)))
       (filter runnable-block?)
       vec))

(defn run-progressive-file! [^java.io.File f root run-app? backend]
  (let [{:keys [ctx var-types backend-atom compiled-session]} (fresh-repl-state backend)
        blocks (extract-nex-blocks f)
        runnable-blocks (active-blocks root blocks)
        block-total (count runnable-blocks)]
    (binding [repl/*repl-var-types* var-types
              repl/*repl-backend* backend-atom
              repl/*compiled-repl-session* compiled-session]
      (doseq [[position {:keys [index] :as block}] (map-indexed vector runnable-blocks)]
        (println (format "  block %d/%d  %s#block-%d"
                         (inc position)
                         block-total
                         (.getPath f)
                         index))
        (flush)
        (doseq [[cell-pos code] (map-indexed vector (block-cells block))]
          (eval-snippet! ctx code (str (.getPath f) "#block-" index ":cell-" (inc cell-pos)))))
      (when (and run-app? (runnable-app? ctx))
        (run-app! ctx (str (.getPath f) "#App.run"))))
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
          (doseq [[cell-pos code] (map-indexed vector (block-cells block))]
            (eval-with-fresh-state! code
                                    (str (.getPath f) "#block-" index ":cell-" (inc cell-pos))
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
