(ns nex.eval
  "Evaluate Nex code snippets from the command line"
  (:require [nex.parser :as parser]
            [nex.interpreter :as interp]))

(defn eval-file
  "Parse and evaluate a Nex file"
  [file-path]
  (let [source (slurp file-path)
        ast (parser/ast source)
        ctx (interp/make-context)]
    (interp/eval-node ctx ast)))

(defn -main
  "Main entry point for nex eval command"
  [& args]
  (when (empty? args)
    (println "Error: No file provided")
    (System/exit 1))

  (let [file (first args)]
    (try
      (eval-file file)
      (System/exit 0)
      (catch Exception e
        (println "Error:" (.getMessage e))
        (System/exit 1)))))
