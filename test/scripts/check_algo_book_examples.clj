#!/usr/bin/env clojure

(require '[clojure.java.io :as io])
(require '[clojure.string :as str])
(require '[nex.parser :as parser])

(def code-fence-pattern
  #"(?s)```([^\r\n`]*)\r?\n(.*?)\r?\n```")

(defn parse-args [args]
  (loop [opts {:root "."
               :files []}
         remaining args]
    (if (empty? remaining)
      opts
      (let [[arg & rest] remaining]
        (cond
          (= arg "--root")
          (recur (assoc opts :root (first rest)) (next rest))

          :else
          (recur (update opts :files conj (io/file arg)) rest))))))

(defn md-file? [^java.io.File f]
  (and (.isFile f)
       (.endsWith (.getName f) ".md")))

(defn ordered-root-files [root]
  (let [preface (io/file root "preface.md")
        toc (io/file root "toc.md")
        chapters (->> (range 1 13)
                      (map #(io/file root (format "chapter_%d.md" %)))
                      (filter #(.exists ^java.io.File %)))]
    (vec (concat (when (.exists preface) [preface])
                 chapters
                 (when (.exists toc) [toc])))))

(defn- transcript-block? [code]
  (boolean (re-find #"(?m)^\s*nex>\s*" code)))

(defn- code-like? [code]
  (boolean
   (re-find #"(?m)^\s*(intern|import|class|deferred class|function|let|feature|create|inherit|if\b|elseif\b|else$|from\b|across\b|repeat\b|print\(|result :=|invariant$|require$|ensure$|do$|end$|retry$|raise\b|select\b|spawn\(|await\(|with\b|case\b|convert\b)"
            code)))

(defn- illustrative-block? [lang code]
  (or
   (and lang
        (not (#{"nex" "text" ""} lang)))
   (re-find #"(?m)^\s*\.\.\.\s*$" code)
   (re-find #"[├└│─→ε]" code)
   (and (= lang "text")
        (not (transcript-block? code)))
   (not (code-like? code))))

(defn extract-blocks [^java.io.File f]
  (->> (re-seq code-fence-pattern (slurp f))
       (map-indexed
        (fn [idx [_ lang code]]
          {:index (inc idx)
           :lang (some-> lang str/trim str/lower-case)
           :code (str/trim code)}))
       (remove #(str/blank? (:code %)))
       vec))

(defn parse-block! [path {:keys [index code lang]}]
  (when-not (illustrative-block? lang code)
    (try
      (parser/ast code)
      {:ok true}
      (catch Throwable t
        {:ok false
         :block index
         :message (.getMessage t)}))))

(defn run-file! [^java.io.File f]
  (let [path (.getPath f)
        blocks (extract-blocks f)
        parsed-results (keep #(parse-block! path %) blocks)
        first-fail (first (remove :ok parsed-results))]
    (println "RUN " path "(" (count parsed-results) "active blocks)")
    (flush)
    (if first-fail
      {:file path
       :ok false
       :message (format "Block %d failed: %s"
                        (:block first-fail)
                        (:message first-fail))}
      {:file path
       :ok true})))

(defn print-summary [root results]
  (let [passed (count (filter :ok results))
        failed (remove :ok results)]
    (println "Algo book code check")
    (println "Root:" root)
    (println)
    (doseq [result results]
      (if (:ok result)
        (println "PASS" (:file result))
        (do
          (println "FAIL" (:file result))
          (println "  " (:message result)))))
    (println)
    (println "Files:" (count results))
    (println "Passed:" passed)
    (println "Failed:" (count failed))
    (when (seq failed)
      (System/exit 1))))

(let [{:keys [root files]} (parse-args *command-line-args*)
      files (if (seq files) files (ordered-root-files root))
      results (mapv run-file! files)]
  (print-summary root results))
