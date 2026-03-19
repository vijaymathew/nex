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
            [nex.typechecker :as tc])
  (:import [java.io ByteArrayInputStream File FileInputStream]
           [java.util.jar JarEntry JarFile JarOutputStream Manifest]))

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
                                                :source-file source-id
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
                              :source-file source-id
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

(defn- classpath-entries
  []
  (->> (str/split (System/getProperty "java.class.path")
                  (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))
       (remove str/blank?)
       (map io/file)))

(defn- skip-jar-entry?
  [entry-name]
  (or (= entry-name "META-INF/MANIFEST.MF")
      (str/ends-with? entry-name ".SF")
      (str/ends-with? entry-name ".DSA")
      (str/ends-with? entry-name ".RSA")
      (= entry-name "nex/compiler/jvm/runtime.clj")
      (= entry-name "nex/compiler/jvm/runtime.cljc")))

(defn- add-entry!
  [^JarOutputStream jar-out seen entry-name input-fn]
  (when-not (or (skip-jar-entry? entry-name)
                (contains? @seen entry-name))
    (swap! seen conj entry-name)
    (.putNextEntry jar-out (JarEntry. entry-name))
    (input-fn jar-out)
    (.closeEntry jar-out)))

(defn- add-file-tree!
  [^JarOutputStream jar-out seen root]
  (let [root-file (io/file root)
        root-path (.toPath root-file)]
    (doseq [f (file-seq root-file)
            :when (.isFile f)]
      (let [entry-name (-> (.relativize root-path (.toPath f))
                           str
                           (str/replace File/separator "/"))]
        (add-entry! jar-out seen entry-name
                    (fn [out]
                      (with-open [in (FileInputStream. f)]
                        (io/copy in out))))))))

(defn- add-jar-file!
  [^JarOutputStream jar-out seen jar-path]
  (with-open [jar (JarFile. (io/file jar-path))]
    (doseq [entry (enumeration-seq (.entries jar))
            :when (not (.isDirectory ^JarEntry entry))]
      (let [entry-name (.getName ^JarEntry entry)]
        (add-entry! jar-out seen entry-name
                    (fn [out]
                      (with-open [in (.getInputStream jar entry)]
                        (io/copy in out))))))))

(defn- delete-tree!
  [root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (.delete f)))

(defn- compile-runtime-support!
  [compile-dir]
  (binding [*compile-path* (.getPath (io/file compile-dir))]
    (compile 'nex.compiler.jvm.runtime))
  compile-dir)

(defn compile-jar
  "Compile a Nex file to a standalone JVM jar that includes generated classes,
  the AOT-compiled compiled-runtime support, project source resources, and
  resolved dependency jars from the current classpath."
  ([nex-file] (compile-jar nex-file nil {}))
  ([nex-file output-dir] (compile-jar nex-file output-dir {}))
  ([nex-file output-dir opts]
   (let [jar-name (or (:jar-name opts)
                      (sanitize-stem nex-file))
         out-dir (io/file (or output-dir "."))
         build-dir (io/file (System/getProperty "java.io.tmpdir")
                            (str "nex-jvm-build-" (System/nanoTime)))
         classes-dir (io/file build-dir "classes")
         compile-dir (io/file build-dir "aot")
         _ (.mkdirs classes-dir)
         _ (.mkdirs compile-dir)]
     (try
       (let [compile-result (compile-file nex-file (.getPath classes-dir) opts)
             _ (compile-runtime-support! compile-dir)
             manifest-str (str "Manifest-Version: 1.0\n"
                               "Main-Class: " (:main-class compile-result) "\n\n")
             manifest (Manifest. (ByteArrayInputStream. (.getBytes manifest-str)))
             jar-file (io/file out-dir (str jar-name ".jar"))
             seen (atom #{})]
         (.mkdirs out-dir)
         (with-open [jar-out (JarOutputStream. (java.io.FileOutputStream. jar-file) manifest)]
           (add-file-tree! jar-out seen classes-dir)
           (add-file-tree! jar-out seen compile-dir)
           (when (.exists (io/file "src"))
             (add-file-tree! jar-out seen "src"))
           (doseq [cp-entry (classpath-entries)
                   :when (.exists cp-entry)]
             (cond
               (.isDirectory cp-entry)
               (when-not (= (.getCanonicalPath cp-entry) (.getCanonicalPath (io/file "test")))
                 (add-file-tree! jar-out seen cp-entry))

               (str/ends-with? (.getName cp-entry) ".jar")
               (add-jar-file! jar-out seen cp-entry)

               :else nil)))
         {:jar (.getPath jar-file)
          :main-class (:main-class compile-result)
          :program-class (:program-class compile-result)
          :class-files (:class-files compile-result)})
       (finally
         (when (.exists build-dir)
           (delete-tree! build-dir)))))))

(defn -main
  [& args]
  (when (empty? args)
    (println "Usage: nex compile jvm <input.nex> [output-dir]")
    (System/exit 1))
  (let [input-file (first args)
        output-dir (or (second args) ".")]
    (try
      (let [result (compile-jar input-file output-dir {})]
        (println (str "Compiled " input-file " -> " (:jar result)))
        (println (str "Main class: " (:main-class result))))
      (System/exit 0)
      (catch Exception e
        (println "Error:" (.getMessage e))
        (System/exit 1)))))
