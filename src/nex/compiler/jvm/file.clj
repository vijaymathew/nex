(ns nex.compiler.jvm.file
  "File-oriented JVM bytecode compilation for Nex.

  This compiles a `.nex` source file into `.class` files using the existing
  lowering/emission pipeline that previously existed only for the compiled REPL
  helper path."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nex.compiler.jvm.descriptor :as desc]
            [nex.compiler.jvm.emit :as emit]
            [nex.interpreter :as interp]
            [nex.lower :as lower]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- sanitize-stem
  [path]
  (let [stem (-> (io/file path)
                 .getName
                 (str/replace #"\.nex$" "")
                 (str/replace #"[^A-Za-z0-9_]+" "_"))]
    (if (re-matches #"^[0-9].*" stem)
      (str "_" stem)
      stem)))

(defn- hidden-package-root
  [source-path]
  (str "nex/file/" (sanitize-stem source-path)))

(defn- merge-import-like-nodes
  [existing incoming]
  (let [seen (atom (set (map pr-str existing)))]
    (reduce (fn [acc node]
              (let [k (pr-str node)]
                (if (contains? @seen k)
                  acc
                  (do
                    (swap! seen conj k)
                    (conj acc node)))))
            (vec existing)
            incoming)))

(defn- augment-ast-with-interns
  [source-id ast]
  (let [intern-classes (interp/resolve-interned-classes source-id ast)
        merged-imports (merge-import-like-nodes [] (:imports ast))]
    (assoc ast
           :imports merged-imports
           :classes (vec (concat intern-classes (:classes ast))))))

(defn- user-class-defs
  [ast]
  (let [synthetic-class-names (set (keep :class-name (:functions ast)))]
    (remove #(contains? synthetic-class-names (:name %))
            (:classes ast))))

(defn- emitted-anonymous-class-defs
  [ast]
  (vec (remove :closure-runtime-object? (lower/collect-anonymous-class-defs ast))))

(defn- file-class-metadata
  [source-path prepared-ast]
  (let [package-root (hidden-package-root source-path)
        emitted-classes (vec (concat (user-class-defs prepared-ast)
                                     (emitted-anonymous-class-defs prepared-ast)))]
    (into {}
          (map (fn [class-def]
                 (let [internal-name (format "%s/%s" package-root (:name class-def))]
                   [(:name class-def)
                    {:name (:name class-def)
                     :internal-name internal-name
                     :jvm-name internal-name
                     :binary-name (desc/binary-class-name internal-name)}]))
               emitted-classes))))

(defn- output-file-for-class
  [output-dir binary-name]
  (io/file output-dir
           (str (str/replace binary-name "." java.io.File/separator)
                ".class")))

(defn- write-class!
  [output-dir binary-name bytecode]
  (let [f (output-file-for-class output-dir binary-name)]
    (.mkdirs (.getParentFile f))
    (with-open [out (io/output-stream f)]
      (.write out ^bytes bytecode))
    (.getPath f)))

(defn compile-ast
  "Compile a parsed Nex AST into JVM class byte arrays.

  Returns:
  {:program-class <binary-name>
   :main-class <binary-name>
   :classes {<binary-name> <byte-array> ...}}"
  ([source-id ast] (compile-ast source-id ast {}))
  ([source-id ast opts]
   (let [module-ast (augment-ast-with-interns source-id ast)
         _ (when-not (:skip-type-check opts)
             (let [result (tc/type-check module-ast)]
               (when-not (:success result)
                 (throw (ex-info "Type checking failed"
                                 {:errors (map tc/format-type-error (:errors result))})))))
         prepared-ast (lower/prepare-program-for-closures
                       module-ast
                       {:classes (:classes module-ast)
                        :functions (:functions module-ast)
                        :imports (:imports module-ast)
                        :var-types {}})
         package-root (hidden-package-root source-id)
         program-internal-name (str package-root "/__nex/Program")
         launcher-internal-name (str package-root "/__nex/Main")
         compiled-classes (file-class-metadata source-id prepared-ast)
         {:keys [unit]} (lower/lower-repl-cell prepared-ast
                                               {:name program-internal-name
                                                :compiled-classes compiled-classes
                                                :classes []
                                                :functions []
                                                :imports (:imports prepared-ast)
                                                :var-types {}})
         class-asts (vec (concat (user-class-defs prepared-ast)
                                 (lower/collect-anonymous-class-defs prepared-ast)))
         class-bytes (into {(desc/binary-class-name program-internal-name)
                            (emit/compile-unit->bytes unit)
                            (desc/binary-class-name launcher-internal-name)
                            (emit/compile-launcher->bytes
                             {:internal-name launcher-internal-name
                              :binary-name (desc/binary-class-name launcher-internal-name)
                              :program-internal-name program-internal-name
                              :classes-edn (pr-str class-asts)
                              :imports-edn (pr-str (:imports prepared-ast))})}
                           (map (fn [lowered-class]
                                  [(desc/binary-class-name (:jvm-name lowered-class))
                                   (emit/compile-user-class->bytes lowered-class)])
                                (:classes unit)))]
     {:program-class (desc/binary-class-name program-internal-name)
      :main-class (desc/binary-class-name launcher-internal-name)
      :classes class-bytes})))

(defn compile-file
  "Compile a Nex source file into JVM `.class` files.

  When `output-dir` is supplied, class files are written there and the return
  value includes the output paths. Otherwise the byte arrays are returned
  in-memory."
  ([nex-file] (compile-file nex-file nil {}))
  ([nex-file output-dir] (compile-file nex-file output-dir {}))
  ([nex-file output-dir opts]
   (let [source-path (.getPath (io/file nex-file))
         ast (p/ast (slurp source-path))
         result (compile-ast source-path ast opts)]
     (if output-dir
       (let [out-dir (io/file output-dir)
             _ (.mkdirs out-dir)
             written (into {}
                           (map (fn [[binary-name bytecode]]
                                  [binary-name (write-class! out-dir binary-name bytecode)])
                                (:classes result)))]
         (assoc result
                :output-dir (.getPath out-dir)
                :class-files written))
       result))))

(defn -main
  [& args]
  (when (empty? args)
    (println "Usage: nex compile jvm <input.nex> [output-dir]")
    (System/exit 1))
  (let [input-file (first args)
        output-dir (or (second args) ".")]
    (try
      (let [result (compile-file input-file output-dir {})]
        (println (str "Compiled " input-file " -> " (:output-dir result)))
        (println (str "Main class: " (:main-class result))))
      (System/exit 0)
      (catch Exception e
        (println "Error:" (.getMessage e))
        (System/exit 1)))))
