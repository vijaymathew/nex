#!/usr/bin/env clojure

(require '[clojure.java.io :as io])
(require '[clojure.string :as str])
(require '[nex.compiler.jvm.repl :as compiled-repl])
(require '[nex.interpreter :as interpreter])
(require '[nex.parser :as parser])
(require '[nex.repl :as repl])

(def complete-heading-pattern
  #"(?i)(complete|putting it together|piece table implementation|the implementation)")

(def placeholder-patterns
  [(re-pattern #"(?im)^\s*--\s*implementation from section\b")
   (re-pattern #"(?im)^\s*--\s*in real impl\b")
   (re-pattern #"(?im)^\s*--\s*real impl\b")
   (re-pattern #"(?im)^\s*--\s*sketch\b")
   (re-pattern #"(?im)^\s*--\s*dummy\b")
   (re-pattern #"(?m)^\s*\.\.\.\s*$")])

(def heading-pattern
  #"(?m)^(#{2,6})\s+(.+)$")

(def code-fence-pattern
  #"(?s)```([^\r\n`]*)\r?\n(.*?)\r?\n```")

(defn parse-args [args]
  (loop [opts {:root "."
               :backend :compiled
               :files []}
         remaining args]
    (if (empty? remaining)
      opts
      (let [[arg & rest] remaining]
        (cond
          (= arg "--root")
          (recur (assoc opts :root (first rest)) (next rest))

          (= arg "--compiled-backend")
          (recur (assoc opts :backend :compiled) rest)

          (= arg "--interpreter-backend")
          (recur (assoc opts :backend :interpreter) rest)

          :else
          (recur (update opts :files conj (io/file arg)) rest))))))

(defn ordered-root-files [root]
  (let [preface (io/file root "preface.md")
        toc (io/file root "toc.md")
        chapters (->> (range 1 21)
                      (map #(io/file root (format "chapter_%d.md" %)))
                      (filter #(.exists ^java.io.File %)))]
    (vec (concat (when (.exists preface) [preface])
                 chapters
                 (when (.exists toc) [toc])))))

(defn section-headings [text]
  (->> (re-seq heading-pattern text)
       (map-indexed
        (fn [idx [full hashes title]]
          {:index idx
           :level (count hashes)
           :title (str/trim title)
           :match full
           :start (.indexOf text full)}))
       vec))

(defn code-blocks [text]
  (->> (re-seq code-fence-pattern text)
       (map-indexed
        (fn [idx [_ lang code]]
          {:index (inc idx)
           :lang (some-> lang str/trim str/lower-case not-empty)
           :code (str/trim code)
           :start (.indexOf text code)}))
       (remove #(str/blank? (:code %)))
       vec))

(defn- transcript-block?
  [{:keys [code]}]
  (boolean (re-find #"(?m)^\s*nex>\s*" code)))

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

(defn block-cell-records
  [{:keys [code] :as block}]
  (if (transcript-block? block)
    (->> (transcript-cell-records code)
         (remove #(str/blank? (:code %)))
         vec)
    [{:code (str/trim code)
      :expected-output nil}]))

(defn runnable-cell?
  [code]
  (try
    (parser/parse code)
    true
    (catch Throwable _
      false)))

(defn code-like? [code]
  (boolean
   (re-find #"(?m)^\s*(intern|import|class|deferred class|declare function|function|let|feature|create|inherit|if\b|elseif\b|else$|from\b|across\b|repeat\b|print\(|result :=|invariant$|require$|ensure$|do$|end$|retry$|raise\b|select\b|spawn\(|await\(|with\b|case\b|convert\b)"
            code)))

(defn illustrative-block?
  [{:keys [lang code]}]
  (or
   (and lang
        (not (#{"nex" "text" "" nil} lang)))
   (and (= lang "text")
        (not (transcript-block? {:code code})))
   (not (code-like? code))))

(defn placeholder-reason [code]
  (some (fn [pattern]
          (when (re-find pattern code)
            (str "placeholder marker matched: " pattern)))
        placeholder-patterns))

(defn section-range [headings heading]
  (let [next-heading (some #(when (and (> (:start %) (:start heading))
                                       (<= (:level %) (:level heading)))
                              %)
                           headings)]
    [(:start heading)
     (or (:start next-heading) Long/MAX_VALUE)]))

(defn complete-section? [heading]
  (boolean (re-find complete-heading-pattern (:title heading))))

(defn targeted-sections [text]
  (let [headings (section-headings text)
        blocks (code-blocks text)]
    (->> headings
         (filter complete-section?)
         (map (fn [heading]
                (let [[start end] (section-range headings heading)
                      section-blocks (->> blocks
                                          (filter #(and (<= start (:start %))
                                                        (< (:start %) end)))
                                          vec)
                      prefix-blocks (->> blocks
                                         (filter #(< (:start %) end))
                                          vec)]
                  {:heading heading
                   :blocks section-blocks
                   :prefix-blocks prefix-blocks})))
         (filter #(seq (:blocks %)))
         vec)))

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

(defn run-section! [file-path section backend]
  (let [{:keys [heading blocks prefix-blocks]} section
        placeholder (some #(placeholder-reason (:code %)) blocks)]
    (when placeholder
      (throw (ex-info (str "Section contains placeholder code: " (:title heading))
                      {:file file-path
                       :section (:title heading)
                       :reason placeholder})))
    (let [{:keys [ctx var-types backend-atom compiled-session]} (fresh-repl-state backend)]
      (binding [repl/*repl-var-types* var-types
                repl/*repl-backend* backend-atom
                repl/*compiled-repl-session* compiled-session]
        (reduce
         (fn [current-ctx {:keys [index] :as block}]
           (if (illustrative-block? block)
             current-ctx
             (reduce
              (fn [ctx' [cell-pos {:keys [code expected-output]}]]
                (when-not (runnable-cell? code)
                  (throw (ex-info (str "Unparseable runnable cell in "
                                       (:title heading))
                                  {:file file-path
                                   :section (:title heading)
                                   :block index
                                   :cell (inc cell-pos)
                                   :code code})))
                (:ctx (eval-snippet! ctx'
                                     code
                                     (format "%s#%s:block-%d:cell-%d"
                                             file-path
                                             (:title heading)
                                             index
                                             (inc cell-pos))
                                     expected-output)))
              current-ctx
              (map-indexed vector (block-cell-records block)))))
         ctx
         prefix-blocks)))))

(defn run-file! [^java.io.File f backend]
  (let [path (.getPath f)
        text (slurp f)
        sections (targeted-sections text)]
    (println "RUN " path "(" (count sections) "complete sections)")
    (flush)
    (if (empty? sections)
      {:file path :ok true :sections 0}
      (try
        (doseq [[idx section] (map-indexed vector sections)]
          (println (format "  section %d/%d  %s"
                           (inc idx)
                           (count sections)
                           (-> section :heading :title)))
          (flush)
          (run-section! path section backend))
        {:file path :ok true :sections (count sections)}
        (catch Exception e
          {:file path
           :ok false
           :message (or (ex-message e) (str e))
           :data (ex-data e)})))))

(defn print-summary [root backend results]
  (let [passed (count (filter :ok results))
        failed (remove :ok results)]
    (println "Algo book runnable implementation check")
    (println "Root:" root)
    (println "Backend:" (name backend))
    (println)
    (doseq [result results]
      (if (:ok result)
        (println "PASS" (:file result))
        (do
          (println "FAIL" (:file result))
          (println "  " (:message result))
          (when-let [data (:data result)]
            (doseq [[k v] data]
              (println "   " (name k) ":" v))))))
    (println)
    (println "Files:" (count results))
    (println "Passed:" passed)
    (println "Failed:" (count failed))
    (when (seq failed)
      (System/exit 1))))

(let [{:keys [root files backend]} (parse-args *command-line-args*)
      files (if (seq files) files (ordered-root-files root))
      results (try
                (mapv #(run-file! % backend) files)
                (finally
                  (interpreter/shutdown-runtime!)))]
  (print-summary root backend results))
