(ns nex.eval
  "Evaluate Nex code snippets from the command line"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nex.parser :as parser]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [nex.types.runtime :as rt]
            [nex.compiler.jvm.file :as jvm-file]
            [nex.compiler.jvm.classloader :as loader])
  (:import [clj_antlr ParseError]))

(defn- augment-ast-with-interns
  [source-id ast]
  (let [merged-functions (vec (concat (interp/resolve-interned-functions source-id ast)
                                      (:functions ast)))]
    (assoc ast
           :classes (vec (concat (interp/resolve-interned-classes source-id ast)
                                 (:classes ast)))
           :functions merged-functions
           ;; This file's own same-file duplicates (already collapsed into
           ;; merged-functions, and already flagged in :duplicate-functions
           ;; by the walker at parse time) plus any NEW collision the merge
           ;; itself introduces — two interned files defining the same bare
           ;; function name. See nex.interpreter/duplicate-function-names.
           :duplicate-functions (vec (distinct (concat (:duplicate-functions ast)
                                                        (interp/duplicate-function-names merged-functions))))
           :imports (vec (concat (interp/resolve-interned-imports source-id ast)
                                 (:imports ast)))
           :type-aliases (vec (concat (interp/resolve-interned-type-aliases source-id ast)
                                      (:type-aliases ast))))))

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
  [source-id ast program-args skip-contracts?]
  ;; Process.command_line() is process-wide state (see nex.types.runtime),
  ;; not threaded through ctx — set once, here, before the program runs.
  (rt/set-program-args! program-args)
  (let [ctx (assoc (interp/make-context)
                    :debug-source source-id
                    :skip-contracts? skip-contracts?)]
    ;; The program writes its own output as it runs, interleaved with `Console`
    ;; in the order the program produced it; nothing is echoed afterwards.
    (interp/eval-node ctx ast)
    @(:output ctx)))

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
   handle: --interpret is a real workaround. A walker rejection (`:error` —
   nex.walker's own convention, e.g. `resolve-convert-alias` rejecting a
   refinement used as a runtime type test) is a deliberate diagnosis of the
   program itself, already phrased for the user; report it verbatim, the same
   way the same rejection reads when it fires at parse time for a same-file
   case instead of here (a cross-file one, reached via
   nex.compiler.jvm.file/augment-ast-with-interns re-running the walker pass
   post-intern-resolution). Anything else reaching here is a genuine compiler
   defect — the typechecker already accepted this program — so asking the
   user to work around it silently would be wrong; it should be reported.
   All three name the construct and the line where they can."
  [e]
  (let [detail (str/join " " (remove nil? [(ex-message e)
                                           (some->> (diagnostic-details e)
                                                    (format "(%s)"))
                                           (diagnostic-location e)]))]
    (cond
      (:nex/unsupported (ex-data e))
      (str "this program uses a construct the compiled backend does not support"
           " yet: " detail
           "\n  Run it with --interpret to use the tree-walking interpreter.")

      (:error (ex-data e))
      (ex-message e)

      :else
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
  [source-id ast skip-contracts?]
  (try
    {:compiled (jvm-file/compile-ast source-id ast {:skip-type-check true
                                                     :skip-contracts? skip-contracts?})}
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
  [{:keys [main-class classes]} program-args]
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
        ;; The class's own `main` (emit-launcher-main!) records these as
        ;; Process.command_line() before running the program — this is not
        ;; just plumbing for this in-process call; it is what makes a jar
        ;; built from `classes` behave identically whether run through
        ;; nex.eval or directly (`java -jar foo.jar arg1 arg2`).
        (.invoke m nil (object-array [(into-array String program-args)]))
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
  [source-id ast {:keys [interpret? program-args skip-contracts?]}]
  (let [program-args (or program-args [])]
    (if interpret?
      (run-interpreted source-id ast program-args skip-contracts?)
      (let [{:keys [compiled compile-error]} (try-compile source-id ast skip-contracts?)]
        (if compile-error
          (throw (ex-info (compile-error-message compile-error)
                          {:type :not-compilable}
                          compile-error))
          (if-let [{:keys [backend-defect]} (run-compiled compiled program-args)]
            (do (warn-fallback! (str "compiled program failed to link ("
                                     (or (ex-message backend-defect) (str backend-defect))
                                     ")"))
                (run-interpreted source-id ast program-args skip-contracts?))
            nil))))))

(defn eval-file
  "Parse and evaluate a Nex file. opts: {:interpret? bool} to force the
   tree-walking interpreter instead of the compiled JVM backend; {:skip-contracts?
   bool} to lower require/ensure/invariant checks to no-ops (bare `assert`
   always runs regardless); {:program-args [...]} the program's own argv,
   returned by Process.command_line() — everything after the file name on the
   command line, not to be confused with the `nex` launcher's own flags like
   --interpret."
  ([file-path] (eval-file file-path {}))
  ([file-path opts]
   (let [source-id (.getCanonicalPath (io/file file-path))
         source (slurp source-id)
         ast (parser/ast source)]
     (type-check-ast! source-id ast)
     (run-ast source-id ast opts))))

(defn -main
  "Main entry point for nex eval command.
   Usage: nex.eval [--interpret] [--skip-contracts] <file.nex> [program-args...]"
  [& args]
  (let [interpret? (boolean (some #{"--interpret"} args))
        skip-contracts? (boolean (some #{"--skip-contracts"} args))
        files (vec (remove #{"--interpret" "--skip-contracts"} args))
        file (first files)
        program-args (vec (rest files))]
    (when (nil? file)
      (println "Error: No file provided")
      (println "Usage: nex <file.nex> [--interpret] [--skip-contracts] [program-args...]")
      (System/exit 1))
    (try
      (eval-file file {:interpret? interpret?
                       :skip-contracts? skip-contracts?
                       :program-args program-args})
      (System/exit 0)
      (catch ParseError e
        (println "Syntax error:")
        (let [source (try (slurp file) (catch Exception _ ""))]
          (parser/format-parse-errors e source 0))
        (System/exit 1))
      ;; A syntax error in a file `file` interns, not `file` itself
      ;; (nex.interpreter/parse-interned-file) — arrives wrapped in an
      ;; ex-info, not a bare ParseError, precisely so it does NOT match the
      ;; clause above: rendering it against `file`'s own source (which the
      ;; ParseError's line/column have nothing to do with) is what this
      ;; case exists to avoid. Any other ex-info (a type error, say) falls
      ;; through to the same rendering the generic `catch Exception` below
      ;; already gives it.
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (:nex/intern-parse-error data)
            (let [{:keys [file-path source parse-error]} data]
              (println (str "Syntax error in " file-path ":"))
              (parser/format-parse-errors parse-error source 0))
            (println "Error:" (interp/nex-error-message e)))
          (System/exit 1)))
      (catch Exception e
        (println "Error:" (interp/nex-error-message e))
        (System/exit 1)))))
