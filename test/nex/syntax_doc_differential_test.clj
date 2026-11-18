(ns nex.syntax-doc-differential-test
  "Every ```nex block in docs/md/SYNTAX.md that type-checks as a file must
   run, compiled and interpreted, with the same output and without an error.

   A block that does not parse, or that the checker rejects, is skipped:
   these are templates (`do ... end`), fragments that use names from earlier
   blocks, and errors shown on purpose. A block the checker accepts but a
   backend cannot run is the gap this test closes. A bare constructor
   call (`make(0)`) once type-checked and then failed in both backends, and a
   program ending in `let x: Integer := 3` failed in compiled code only.

   Some blocks fail at run time on purpose (`expected-errors`), and a block
   that waits for input is not run (`interactive?`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]))

(def docs ["docs/md/SYNTAX.md"])

(def expected-errors
  "Run-time errors the docs show on purpose, by message."
  #{"Byte value must be in range 0..255, got 300"})

(defn- interactive?
  "Whether a block waits for input, which a test cannot give it."
  [src]
  (str/includes? src "read_line"))

(defn- nex-blocks
  "The ```nex blocks of the markdown file MD, as {:line :src}: LINE is the
   block's first line in the file."
  [md]
  (loop [lines (map-indexed vector (str/split-lines (slurp md)))
         cur nil
         out []]
    (if-let [[i line] (first lines)]
      (let [fence (str/trim line)]
        (cond
          (and (nil? cur) (= fence "```nex")) (recur (rest lines) {:line (+ i 2) :src []} out)
          (and cur (= fence "```")) (recur (rest lines) nil
                                           (conj out (update cur :src #(str/join "\n" %))))
          cur (recur (rest lines) (update cur :src conj line) out)
          :else (recur (rest lines) cur out)))
      out)))

(defn- run
  "The output of SRC run as a file, and the message of the error it raised,
   if any."
  [src interpret?]
  (let [f (java.io.File/createTempFile "syntax_doc" ".nex")
        out (java.io.StringWriter.)]
    (try
      (spit f src)
      (binding [*out* out]
        (e/eval-file (.getPath f) {:interpret? interpret?}))
      {:out (str out)}
      (catch Throwable t
        {:out (str out) :error (or (ex-message t) (str (class t)))})
      (finally (.delete f)))))

(defn- parses? [src]
  (try (p/ast src) true
       (catch Exception _ false)))

(defn- rejected?
  "Whether a run stopped at the type check, before running anything."
  [{:keys [error]}]
  (boolean (some-> error (str/starts-with? "Type checking failed"))))

(deftest syntax-doc-blocks-run-alike-in-both-backends
  (doseq [md docs
          :let [blocks (nex-blocks md)]]
    (is (seq blocks) (str "no ```nex blocks found in " md))
    (doseq [{:keys [line src]} blocks
            :when (and (not (interactive? src)) (parses? src))
            ;; checked as a file is, with the same options: by running it
            :let [compiled (run src false)]
            :when (not (rejected? compiled))]
      (testing (str md ":" line)
        (is (= compiled (run src true))
            "the compiled and interpreted runs differ")
        (when-let [err (:error compiled)]
          (is (contains? expected-errors err)
              (str "type-checked, but failed at run time: " err)))))))
