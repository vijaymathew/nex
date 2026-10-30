(ns nex.cli
  "The `nex` command. The launchers (bin/nex on Linux and macOS, bin/nex.cmd
   on Windows) only find Java and Nex, put Nex's jars and any `--classpath`
   entries on the JVM classpath, and start this namespace from the Nex
   installation folder with:

     -Dnex.user.dir=<the folder nex was started from>
     -Dnex.home=<the Nex installation folder>

   and the NEX_USER_DIR environment variable set to the same folder as
   nex.user.dir. Everything else about the command line is handled here, so
   that both launchers behave the same.

   Each tool namespace is loaded only when its command runs, so `nex help`
   does not load the compiler."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def version "0.6.6")

(def ^:private help-text
  (str "Nex Language v" version " - A high-level language for design and implementation

USAGE:
    nex [NEX_SCRIPT | COMMAND] [OPTIONS]

COMMANDS:
    (no command)              Start the Nex REPL
    compile jvm <file> [output-dir] [--classpath entries] [--skip-contracts] [--max-errors N]
                              Compile a Nex file to a standalone JVM JAR
    format <file|dir>         Format Nex source files
    doc <file|dir> [output]   Generate documentation from Nex files
    eval <code>               Evaluate a Nex code snippet
    lsp                       Start the language server (for editors; speaks
                              LSP on stdin/stdout)
    help                      Show this help message
    version                   Show the Nex version

OPTIONS:
    --classpath entries       Extra jar/dir paths for Java interop, separated
                              like `java -cp` entries on this platform (`:` on
                              Linux and macOS, `;` on Windows). Available on
                              both running (nex <file>.nex) and compiling
                              (nex compile jvm). On compile, the entries are
                              also shaded into the output jar.
    --skip-contracts          Lower require/ensure/invariant checks to no-ops
                              (both class and loop invariants). Bare `assert`
                              always runs regardless — there is no mode that
                              strips it. Meant for a trusted, perf-critical
                              release build; risky wherever untrusted input
                              or latent bugs remain. Available on both
                              running (nex <file>.nex) and compiling
                              (nex compile jvm).
    --max-errors N            Report at most N type errors (default 10).
                              Available on both running (nex <file>.nex)
                              and compiling (nex compile jvm).

EXAMPLES:
    nex                                      # Start REPL
    nex app.nex                              # Run app.nex (compiled JVM backend)
    nex app.nex --interpret                  # Run on the tree-walking interpreter
    ./app                                    # Run a file whose first line is #!/usr/bin/env nex
    nex app.nex --classpath a.jar:b.jar      # Run with extra jars on the classpath
    nex app.nex --skip-contracts             # Run with require/ensure/invariant checks off
    nex compile jvm app.nex                  # Compile to a standalone app.jar in current dir
    nex compile jvm app.nex build/           # Compile to a standalone app.jar in build/
    nex compile jvm app.nex --classpath a.jar:b.jar
                                              # Compile, bundling extra jars into the output jar
    nex compile jvm app.nex --skip-contracts
                                              # Compile a release build with contracts off
    nex format src/                          # Format all files in directory
    nex format MyClass.nex                   # Format single file
    nex doc MyClass.nex MyClass.md           # Generate documentation
    nex doc src/ docs/                       # Generate docs for directory
    nex eval 'print(\"Hello, World!\")'        # Evaluate code

ENVIRONMENT VARIABLES:
    NEX_HOME                  Nex installation directory (default: auto-detected)
    JAVA_HOME                 The Java to run Nex with (default: `java` on PATH)

For more information, visit: https://github.com/vijaymathew/nex"))

(defn- fail!
  "Print LINES and exit with status 1."
  [& lines]
  (doseq [line lines]
    (println line))
  (flush)
  (System/exit 1))

(defn- user-dir
  "The folder nex was started from. The JVM itself runs in the Nex
   installation folder, so relative paths on the command line are resolved
   against this instead."
  []
  (or (System/getProperty "nex.user.dir")
      (System/getenv "NEX_USER_DIR")
      (System/getProperty "user.dir")))

(defn- user-file
  "PATH as a file, relative to the folder nex was started from."
  ^java.io.File [path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      f
      (io/file (user-dir) path))))

(defn- absolute-path [path]
  (.getAbsolutePath (.getCanonicalFile (user-file path))))

(defn- run-main
  "Call NS's -main with ARGS, loading NS first."
  [ns-sym args]
  (apply (requiring-resolve (symbol (name ns-sym) "-main")) args))

(defn- take-classpath-options
  "ARGS without each `--classpath ENTRIES` pair. The launcher has already put
   the entries on the JVM classpath, from process start, so that both
   compile-time interop resolution and runtime class linkage see them."
  [args]
  (loop [[a & more] args, out []]
    (cond
      (nil? a) out
      (= a "--classpath") (if (seq more)
                            (recur (rest more) out)
                            (fail! "Error: --classpath requires an argument"))
      :else (recur more (conj out a)))))

(defn- nex-files
  "The .nex files under DIR, in name order."
  [^java.io.File dir]
  (->> (file-seq dir)
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".nex")))
       (sort-by #(.getPath ^java.io.File %))))

(defn- cmd-compile [args]
  (let [[positional flags]
        (loop [[a & more] (take-classpath-options args), positional [], flags []]
          (cond
            (nil? a) [positional flags]
            (= a "--skip-contracts") (recur more positional (conj flags a))
            (= a "--max-errors") (if (seq more)
                                   (recur (rest more) positional (conj flags a (first more)))
                                   (fail! "Error: --max-errors requires an argument"))
            :else (recur more (conj positional a) flags)))
        [target file output] positional]
    (when (or (nil? target) (nil? file))
      (fail! "Error: compile requires target and file arguments"
             "Usage: nex compile jvm <file> [output-dir] [--classpath entries] [--skip-contracts] [--max-errors N]"))
    (when (not= target "jvm")
      (fail! (str "Error: Invalid target '" target "'. The only compile target is 'jvm'.")))
    (when-not (.isFile (user-file file))
      (fail! (str "Error: File not found: " file)))
    ;; The output folder defaults to the one nex was started from.
    (let [out-dir (user-file (or output (user-dir)))]
      (.mkdirs out-dir)
      (run-main 'nex.compiler.jvm.file
                (concat [(absolute-path file) (absolute-path (.getPath out-dir))] flags)))))

(defn- cmd-format [[path]]
  (when (nil? path)
    (fail! "Error: format requires a file or directory argument"
           "Usage: nex format <file|dir>"))
  (let [f (user-file path)]
    (when-not (.exists f)
      (fail! (str "Error: Path not found: " path)))
    (if (.isDirectory f)
      (doseq [file (nex-files (.getCanonicalFile f))]
        (println "Formatting:" (.getPath ^java.io.File file))
        (run-main 'nex.fmt [(.getPath ^java.io.File file)]))
      (run-main 'nex.fmt [(absolute-path path)]))))

(defn- cmd-doc [[input output]]
  (when (nil? input)
    (fail! "Error: doc requires an input file or directory"
           "Usage: nex doc <file|dir> [output]"))
  (let [f (user-file input)]
    (when-not (.exists f)
      (fail! (str "Error: Path not found: " input)))
    (if (.isDirectory f)
      (let [out-dir (.getCanonicalFile (user-file (or output ".")))]
        (.mkdirs out-dir)
        (doseq [^java.io.File file (nex-files (.getCanonicalFile f))]
          (let [out-file (io/file out-dir (str (str/replace (.getName file) #"\.nex$" "") ".md"))]
            (println "Generating docs:" (.getPath file) "->" (.getPath out-file))
            (run-main 'nex.docgen [(.getPath file) (.getPath out-file)]))))
      (run-main 'nex.docgen (cond-> [(absolute-path input)]
                              output (conj (.getPath (user-file output))))))))

(defn- cmd-eval [[code]]
  (when (nil? code)
    (fail! "Error: eval requires a code argument"
           "Usage: nex eval '<code>'"))
  (let [tmp (doto (java.io.File/createTempFile "nex" ".nex")
              (.deleteOnExit))]
    (spit tmp (str code "\n"))
    (try
      (run-main 'nex.eval [(.getPath tmp)])
      (finally
        (.delete tmp)))))

(defn- script-file?
  "True when PATH names a file to run: a .nex file, or a file whose first line
   is a `#!` line (`#!/usr/bin/env nex`), which may be named without .nex."
  [path]
  (let [f (user-file path)]
    (and (.isFile f)
         (or (str/ends-with? path ".nex")
             (with-open [in (io/input-stream f)]
               (and (= (.read in) (int \#))
                    (= (.read in) (int \!))))))))

(defn- cmd-run-script [path args]
  (run-main 'nex.eval (cons (absolute-path path) (take-classpath-options args))))

(defn -main [& args]
  (let [[command & more] args]
    (case command
      (nil "repl") (run-main 'nex.repl [])
      "compile" (cmd-compile more)
      "format" (cmd-format more)
      "doc" (cmd-doc more)
      "eval" (cmd-eval more)
      "lsp" (run-main 'nex.lsp [])
      ("help" "--help" "-h") (println help-text)
      ("version" "--version" "-v") (println (str "Nex Language v" version " (JVM)"))
      (if (script-file? command)
        (cmd-run-script command more)
        (fail! (str "Error: Unknown command '" command "'")
               "Run 'nex help' for usage information.")))))
