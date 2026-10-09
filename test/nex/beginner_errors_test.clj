(ns nex.beginner-errors-test
  "Error messages for the mistakes beginners make most, run through the same
   path as `nex file.nex` (nex.eval/run-file). Each case is a program in
   test/beginner_errors/ whose trailing `--` comments say what the output must
   and must not contain; see the README there. Only the program part is run,
   from a copy, so line numbers and end-of-file errors match what a learner
   with the same program would see.

   A `fixed` case must pass. A `pending` case records the message we want but
   do not produce yet: it is listed, not failed — until it starts passing,
   which fails the test so that the case gets marked `fixed` and is guarded
   from then on."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(def ^:private cases-dir (io/file "test/beginner_errors"))

(defn- parse-case [^java.io.File f]
  (let [directives (keep #(re-matches #"-- (Mistake|status|expect|reject|exit): (.*)" %)
                         (str/split-lines (slurp f)))
        values (fn [k] (map last (filter #(= k (second %)) directives)))]
    {:name (str/replace (.getName f) #"\.nex$" "")
     :path (.getPath f)
     :mistake (first (values "Mistake"))
     :status (keyword (first (values "status")))
     :expect (values "expect")
     :reject (values "reject")
     :exit (if-let [x (first (values "exit"))] (parse-long x) 1)}))

(defn- cases []
  (->> (.listFiles cases-dir)
       (filter #(str/ends-with? (.getName %) ".nex"))
       (sort-by #(.getName %))
       (map parse-case)))

(defn- program-text
  "The case's program alone: everything before its `-- Mistake:` comments, so
   that an error reported at the end of the file points at the program's own
   last line, not at the expectations."
  [path]
  (let [text (slurp path)
        end (str/index-of text "\n-- Mistake:")]
    (str (str/trimr (if end (subs text 0 end) text)) "\n")))

(defn- run-case
  "Run the case's program, returning everything it printed and the problems
   with that output (empty when it meets every expectation)."
  [{:keys [name path expect reject exit]}]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "beginner-errors" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file dir (str name ".nex"))
        _ (spit file (program-text path))
        code (atom nil)
        output (let [w (java.io.StringWriter.)]
                 (try
                   (binding [*out* w *err* w]
                     (reset! code (e/run-file (.getPath file) {})))
                   (finally
                     (.delete file)
                     (.delete dir)))
                 (str w))
        problems (concat
                  (when (not= exit @code)
                    [(str "exit code " @code ", expected " exit)])
                  (for [s expect :when (not (str/includes? output s))]
                    (str "missing: " s))
                  (for [s reject :when (str/includes? output s)]
                    (str "should not contain: " s)))]
    {:output output :problems problems}))

(defn- describe [{:keys [name mistake]} {:keys [output problems]}]
  (str name " (" mistake ")\n  "
       (str/join "\n  " problems)
       "\n  output was:\n    "
       (str/join "\n    " (str/split-lines output))))

(deftest beginner-error-messages
  (let [results (for [c (cases)] [c (run-case c)])
        pending (filter #(and (= :pending (:status (first %)))
                              (seq (:problems (second %))))
                        results)]
    (is (seq results) "no cases found in test/beginner_errors")
    (doseq [[c r] results]
      (testing (:name c)
        (case (:status c)
          :fixed (is (empty? (:problems r)) (describe c r))
          :pending (is (seq (:problems r))
                       (str (:name c) " now passes: change it to `-- status: fixed`."))
          (is false (str (:name c) ": unknown status " (:status c))))))
    (when (seq pending)
      (println (str "\nBeginner error messages still pending (" (count pending) "):"))
      (doseq [[c _] pending]
        (println (str "  " (:name c) " - " (:mistake c)))))))
