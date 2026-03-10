#!/usr/bin/env clojure

(require '[clojure.java.io :as io])
(require '[clojure.string :as str])
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

          (= arg "--book")
          (recur (assoc opts :root "docs/book") rest)

          (= arg "--tut")
          (recur (assoc opts :root "docs/tut") rest)

          (= arg "--root")
          (recur (assoc opts :root (first rest)) (rest rest))

          :else
          (recur (update opts :files conj (io/file arg)) rest))))))

(def code-fence-pattern
  #"(?s)```nex\s*\r?\n(.*?)\r?\n```")

(defn extract-nex-blocks [^java.io.File f]
  (->> (re-seq code-fence-pattern (slurp f))
       (map-indexed (fn [idx [_ code]]
                      {:index (inc idx)
                       :file (.getPath f)
                       :code (str/trim code)}))
       (remove #(str/blank? (:code %)))
       vec))

(defn skip-block-reason [root code]
  (cond
    (re-find #"(?m)^\s*\.\.\.\s*$" code)
    "contains ellipsis placeholder"

    (re-find #"(?m)^\s*Suggested files:\s*$" code)
    "contains prose instead of Nex code"

    (and (= root "docs/tut")
         (re-find #"(?m)^\s*(nex>|\.{3}\s{0,2})" code))
    "contains interactive REPL prompt transcript"

    (re-find #"(create\s+(Window|Turtle|Image))|(\bWindow\b.*draw_)|(\bTurtle\b.*(forward|backward|left|right))|(\.draw_(line|rect|circle|text|image)\()|(\.(forward|backward|left|right)\()" code)
    "requires graphics runtime"

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

(defn fresh-repl-state []
  {:ctx (repl/init-repl-context)
   :var-types (atom {})})

(defn eval-with-fresh-state! [code source-id run-app?]
  (let [{:keys [ctx var-types]} (fresh-repl-state)]
    (binding [repl/*repl-var-types* var-types]
      (eval-snippet! ctx code source-id)
      (when (and run-app? (runnable-app? ctx))
        (run-app! ctx (str source-id ":App.run")))
      ctx)))

(defn run-progressive-file! [^java.io.File f root run-app?]
  (let [{:keys [ctx var-types]} (fresh-repl-state)
        blocks (extract-nex-blocks f)]
    (binding [repl/*repl-var-types* var-types]
      (doseq [{:keys [index code]} blocks]
        (when-not (skip-block-reason root code)
          (eval-snippet! ctx code (str (.getPath f) "#block-" index))))
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

(defn run-file! [^java.io.File f {:keys [mode run-app root]}]
  (try
    (case mode
      :isolated
      (do
        (doseq [{:keys [index code]} (extract-nex-blocks f)]
          (when-not (skip-block-reason root code)
            (eval-with-fresh-state! code (str (.getPath f) "#block-" index) run-app)))
        (result-ok (.getPath f) mode nil))

      :progressive
      (do
        (run-progressive-file! f root run-app)
        (result-ok (.getPath f) mode nil)))
    (catch Exception e
      (result-fail (.getPath f) mode e))))

(defn print-summary [root results]
  (let [passed (count (filter :ok results))
        failed (remove :ok results)
        total (count results)]
    (println "Docs example smoke test")
    (println "Root:" root)
    (println)
    (doseq [result results]
      (if (:ok result)
        (println "PASS" (:file result))
        (do
          (println "FAIL" (:file result))
          (println "  " (:message result)))))
    (println)
    (println "Files:" total)
    (println "Passed:" passed)
    (println "Failed:" (count failed))
    (when (seq failed)
      (System/exit 1))))

(let [opts (parse-args *command-line-args*)
      results (mapv #(run-file! % opts) (:files opts))]
  (print-summary (:root opts) results))
