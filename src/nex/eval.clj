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
         :functions (vec (concat (interp/resolve-interned-functions source-id ast)
                                 (:functions ast)))
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

(def ^:private issue-url "https://github.com/vijaymathew/nex/issues")

(def ^:private diagnostic-detail-keys
  "ex-data keys worth naming in a compile diagnostic, in the order shown. The
   message alone says a construct is unsupported without saying *which* — these
   turn \"Unsupported user-defined target access\" into the actionable
   \"to_string on Money\"."
  [:node-type :method :target-type :class-name :constructor :field :name])

(defn- diagnostic-details
  [e]
  (let [data (ex-data e)]
    (->> diagnostic-detail-keys
         (keep (fn [k]
                 (when-let [v (get data k)]
                   (str (name k) " " (pr-str v)))))
         (str/join ", ")
         not-empty)))

(defn- diagnostic-location
  "\"at line N, column M\" for the failing node, or nil. `compile-ast` has
   already resolved this into :errors by walking the ex-data for debug info."
  [e]
  (some-> (first (:errors (ex-data e)))
          (str/replace #"^At " "at ")))

(defn- compile-error-message
  "Report a compile failure as what it actually is.

   A marked gap (`:nex/unsupported`) is a valid program the backend cannot yet
   handle: --interpret is a real workaround. Anything else reaching here is a
   compiler defect — the typechecker already accepted this program — so asking
   the user to work around it silently would be wrong; it should be reported.
   Both name the construct and the line where they can."
  [e]
  (let [detail (str/join " " (remove nil? [(ex-message e)
                                           (some->> (diagnostic-details e)
                                                    (format "(%s)"))
                                           (diagnostic-location e)]))]
    (if (:nex/unsupported (ex-data e))
      (str "this program uses a construct the compiled backend does not support"
           " yet: " detail
           "\n  Run it with --interpret to use the tree-walking interpreter.")
      (str "internal error in the compiled backend: " detail
           "\n  This is a defect in Nex, not in your program — please report it"
           " at " issue-url
           "\n  Meanwhile, --interpret runs the program on the tree-walking"
           " interpreter."))))

(defn- try-compile
  "Compile the whole program with the JVM backend. Returns {:compiled result} or,
   when the program is outside the compilable subset, {:compile-error e}. Type
   errors are caught earlier by `type-check-ast!`, so a failure here means an
   unsupported construct (or a compiler defect) — `compile-error-message` tells
   the two apart."
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
  "Run a whole program on the compiled JVM backend (reference semantics,
   matching the REPL). The tree-walking interpreter runs only on explicit
   request (:interpret? — the CLI's --interpret flag); a program outside the
   compiled subset is otherwise an error naming the unsupported construct. A
   runtime failure of the compiled program is the program's outcome and is
   never re-executed. The one automatic fallback left is a LinkageError — a
   backend defect, not the program's behaviour — which runs interpreted with a
   warning rather than failing a valid program."
  [source-id ast {:keys [interpret?]}]
  (if interpret?
    (run-interpreted source-id ast)
    (let [{:keys [compiled compile-error]} (try-compile source-id ast)]
      (if compile-error
        (throw (ex-info (compile-error-message compile-error)
                        {:type :not-compilable}
                        compile-error))
        (if-let [{:keys [backend-defect]} (run-compiled compiled)]
          (do (warn-fallback! (str "compiled program failed to link ("
                                   (or (ex-message backend-defect) (str backend-defect))
                                   ")"))
              (run-interpreted source-id ast))
          nil)))))

(defn eval-file
  "Parse and evaluate a Nex file. opts: {:interpret? bool} to force the
   tree-walking interpreter instead of the compiled JVM backend."
  ([file-path] (eval-file file-path {}))
  ([file-path opts]
   (let [source-id (.getCanonicalPath (io/file file-path))
         source (slurp source-id)
         ast (parser/ast source)]
     (type-check-ast! source-id ast)
     (run-ast source-id ast opts))))

(defn -main
  "Main entry point for nex eval command.
   Usage: nex.eval [--interpret] <file.nex>"
  [& args]
  (let [interpret? (boolean (some #{"--interpret"} args))
        files (remove #{"--interpret"} args)
        file (first files)]
    (when (nil? file)
      (println "Error: No file provided")
      (println "Usage: nex <file.nex> [--interpret]")
      (System/exit 1))
    (try
      (eval-file file {:interpret? interpret?})
      (System/exit 0)
      (catch ParseError e
        (println "Syntax error:")
        (let [source (try (slurp file) (catch Exception _ ""))]
          (parser/format-parse-errors e source 0))
        (System/exit 1))
      (catch Exception e
        (println "Error:" (interp/nex-error-message e))
        (System/exit 1)))))
