(ns nex.eval
  "Evaluate Nex code snippets from the command line"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nex.parser :as parser]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]))

(defn- augment-ast-with-interns
  [source-id ast]
  (assoc ast
         :classes (vec (concat (interp/resolve-interned-classes source-id ast)
                               (:classes ast)))))

(defn- type-check-ast!
  [source-id ast]
  (let [module-ast (augment-ast-with-interns source-id ast)
        result (tc/type-check module-ast)]
    (when-not (:success result)
      (throw (ex-info (str "Type checking failed"
                           (when (seq (:errors result))
                             (str "\n"
                                  (str/join "\n" (map tc/format-type-error (:errors result))))))
                      {:errors (map tc/format-type-error (:errors result))})))))

(defn- run-ast
  [source-id ast]
  (let [ctx (assoc (interp/make-context) :debug-source source-id)]
    (interp/eval-node ctx ast)
    (let [output @(:output ctx)]
      (doseq [line output]
        (println line))
      output)))

(defn eval-file
  "Parse and evaluate a Nex file"
  [file-path]
  (let [source-id (.getCanonicalPath (io/file file-path))
        source (slurp source-id)
        ast (parser/ast source)]
    (type-check-ast! source-id ast)
    (run-ast source-id ast)))

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
