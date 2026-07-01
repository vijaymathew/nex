(ns nex.eval
  "Evaluate Nex code snippets from the command line"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nex.parser :as parser]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [nex.compiler.jvm.file :as jvm-file]
            [nex.compiler.jvm.classloader :as loader])
  (:import [clj_antlr ParseError]))

(defn- augment-ast-with-interns
  [source-id ast]
  (assoc ast
         :classes (vec (concat (interp/resolve-interned-classes source-id ast)
                               (:classes ast)))
         :imports (vec (concat (interp/resolve-interned-imports source-id ast)
                               (:imports ast)))))

(defn- type-check-ast!
  [source-id ast]
  (let [module-ast (augment-ast-with-interns source-id ast)
        result (tc/type-check module-ast {:strict-undefined-targets? true})]
    (doseq [w (:warnings result)]
      (binding [*out* *err*]
        (println (str "Warning: " w))))
    (when-not (:success result)
      (throw (ex-info (str "Type checking failed"
                           (when (seq (:errors result))
                             (str "\n"
                                  (str/join "\n" (map tc/format-type-error (:errors result))))))
                      {:errors (map tc/format-type-error (:errors result))})))))

(defn- run-interpreted
  [source-id ast]
  (let [ctx (assoc (interp/make-context) :debug-source source-id)]
    (interp/eval-node ctx ast)
    (let [output @(:output ctx)]
      (doseq [line output]
        (println line))
      output)))

(defn- try-compile
  "Compile the whole program with the JVM backend. Returns the compile result, or
   nil if the program is outside the compilable subset (any compile-time failure).
   Type errors are caught earlier by `type-check-ast!`, so a failure here means an
   unsupported construct and the caller falls back to the interpreter."
  [source-id ast]
  (try
    (jvm-file/compile-ast source-id ast {:skip-type-check true})
    (catch Throwable _ nil)))

(defn- run-compiled
  "Define the generated classes in a fresh loader and invoke the program's `Main`,
   running with the JVM backend's reference semantics (the same engine the REPL
   uses). Output is captured so that if the compiled run fails partway, the caller
   can discard it and fall back cleanly. Returns captured stdout on success, or
   nil if the compiled run raised."
  [{:keys [main-class classes]}]
  (let [ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          buf (java.io.ByteArrayOutputStream.)
          real-out System/out]
      (try
        (System/setOut (java.io.PrintStream. buf true "UTF-8"))
        (.invoke m nil (object-array [(into-array String [])]))
        (.toString buf "UTF-8")
        (catch Throwable _ nil)
        (finally
          (System/setOut real-out))))))

(defn- run-ast
  "Run a whole program. Prefers the compiled JVM backend (reference semantics,
   matching the REPL) and falls back to the tree-walking interpreter when the
   program is outside the compilable subset or the compiled run fails."
  [source-id ast]
  (let [compiled (try-compile source-id ast)
        captured (when compiled (run-compiled compiled))]
    (if captured
      (do (print captured) (flush) captured)
      (run-interpreted source-id ast))))

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
      (catch ParseError e
        (println "Syntax error:")
        (let [source (try (slurp file) (catch Exception _ ""))]
          (parser/format-parse-errors e source 0))
        (System/exit 1))
      (catch Exception e
        (println "Error:" (interp/nex-error-message e))
        (System/exit 1)))))
