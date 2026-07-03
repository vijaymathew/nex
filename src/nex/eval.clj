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

(defn- warn-fallback!
  [reason]
  (binding [*out* *err*]
    (println (str "Warning: falling back to the tree-walking interpreter: " reason))))

(defn- try-compile
  "Compile the whole program with the JVM backend. Returns {:compiled result} or,
   when the program is outside the compilable subset, {:compile-error e}. Type
   errors are caught earlier by `type-check-ast!`, so a failure here means an
   unsupported construct (or a compiler defect)."
  [source-id ast]
  (try
    {:compiled (jvm-file/compile-ast source-id ast {:skip-type-check true})}
    (catch Throwable e {:compile-error e})))

(defn- run-compiled
  "Define the generated classes in a fresh loader and invoke the program's `Main`,
   running with the JVM backend's reference semantics (the same engine the REPL
   uses). Stdout is captured only so the program's partial output survives an
   exception; it is always printed — a runtime exception is the program's
   outcome (spec §7.3), not a reason to re-execute under the interpreter.

   Returns nil on success. A LinkageError (VerifyError and kin) is a backend
   defect surfacing after compile time, not the program's behaviour, so it is
   returned as {:backend-defect e} for the caller to fall back on; any other
   throwable is rethrown (unwrapped from the reflective call) after the partial
   output is flushed."
  [{:keys [main-class classes]}]
  (let [ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          buf (java.io.ByteArrayOutputStream.)
          real-out System/out
          flush-buf! (fn [] (print (.toString buf "UTF-8")) (flush))]
      (try
        (System/setOut (java.io.PrintStream. buf true "UTF-8"))
        (.invoke m nil (object-array [(into-array String [])]))
        (System/setOut real-out)
        (flush-buf!)
        nil
        (catch Throwable t
          (System/setOut real-out)
          (let [cause (loop [e t]
                        (if (and (or (instance? java.lang.reflect.InvocationTargetException e)
                                     (instance? ExceptionInInitializerError e))
                                 (.getCause e))
                          (recur (.getCause e))
                          e))]
            (if (instance? LinkageError cause)
              ;; Classes link lazily, so in principle this can fire mid-run;
              ;; in practice the program's classes are resolved before user
              ;; statements execute, so no output has been produced yet.
              {:backend-defect cause}
              (do
                (flush-buf!)
                (throw (if (instance? Exception cause)
                         cause
                         (ex-info (str cause) {:cause cause})))))))
        (finally
          (System/setOut real-out))))))

(defn- run-ast
  "Run a whole program. Prefers the compiled JVM backend (reference semantics,
   matching the REPL). Falls back to the tree-walking interpreter — loudly, on
   stderr — only when the program cannot be compiled; a runtime failure of the
   compiled program is reported as the program's outcome and never re-executed."
  [source-id ast]
  (let [{:keys [compiled compile-error]} (try-compile source-id ast)]
    (if compile-error
      (do (warn-fallback! (str "program is outside the compiled subset ("
                               (or (ex-message compile-error) (str compile-error))
                               ")"))
          (run-interpreted source-id ast))
      (if-let [{:keys [backend-defect]} (run-compiled compiled)]
        (do (warn-fallback! (str "compiled program failed to link ("
                                 (or (ex-message backend-defect) (str backend-defect))
                                 ")"))
            (run-interpreted source-id ast))
        nil))))

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
