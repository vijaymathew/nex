;; Build the ahead-of-time compiled distribution that install.sh installs:
;;
;;   target/dist/nex.jar     every namespace under src/, AOT-compiled with
;;                           direct linking, plus grammar/nexlang.g4
;;   target/dist/deps/*.jar  the runtime dependency jars
;;   target/dist/classpath   nex.jar then each dependency, one path per line,
;;                           relative to the install directory
;;
;; With these, bin/nex starts Nex with plain `java` instead of loading the
;; Clojure source through the Clojure CLI on every run, which is several
;; times faster. Run through the :dist alias, which puts the classes
;; directory on the classpath and turns on direct linking:
;;
;;   clojure -M:dist

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(def dist-dir (io/file "target/dist"))
(def classes-dir (io/file dist-dir "classes"))

(defn- source-namespaces
  "Every namespace defined under src/, from its file path."
  []
  (->> (file-seq (io/file "src"))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (map #(-> (.getPath %)
                 (str/replace #"^src/" "")
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn- compile-all!
  "Compile each namespace not already compiled as a dependency of an earlier
   one (`compile` always reloads the namespace it is given)."
  []
  (.mkdirs classes-dir)
  (binding [*compile-path* (.getPath classes-dir)]
    (doseq [ns-sym (source-namespaces)
            :when (not (contains? (loaded-libs) ns-sym))]
      (compile ns-sym))))

(defn- add-entry! [^java.util.jar.JarOutputStream out ^String name ^java.io.File f]
  (.putNextEntry out (doto (java.util.jar.JarEntry. name)
                       (.setTime (.lastModified f))))
  (io/copy f out)
  (.closeEntry out))

(defn- write-jar! []
  (let [manifest (doto (java.util.jar.Manifest.)
                   (-> .getMainAttributes
                       (.put java.util.jar.Attributes$Name/MANIFEST_VERSION "1.0")))
        root (.toPath classes-dir)]
    (with-open [out (java.util.jar.JarOutputStream.
                     (io/output-stream (io/file dist-dir "nex.jar")) manifest)]
      (doseq [f (file-seq classes-dir)
              :when (.isFile f)]
        (add-entry! out (str/replace (str (.relativize root (.toPath f))) "\\" "/") f))
      (add-entry! out "grammar/nexlang.g4" (io/file "grammar/nexlang.g4")))))

(defn- dependency-jars
  "The jars on this JVM's classpath: the runtime dependencies from deps.edn."
  []
  (->> (str/split (System/getProperty "java.class.path") (re-pattern java.io.File/pathSeparator))
       (map io/file)
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".jar")))))

(defn- copy-dependencies! []
  (let [jars (dependency-jars)
        deps-dir (io/file dist-dir "deps")
        clashes (->> jars (map #(.getName %)) frequencies (filter #(> (val %) 1)) keys)]
    (when (seq clashes)
      (throw (ex-info (str "Two dependency jars share a file name: " (str/join ", " clashes))
                      {:clashes clashes})))
    (.mkdirs deps-dir)
    (doseq [jar jars]
      (io/copy jar (io/file deps-dir (.getName jar))))
    (spit (io/file dist-dir "classpath")
          (str (str/join "\n" (cons "nex.jar" (map #(str "deps/" (.getName %)) jars))) "\n"))))

(compile-all!)
(write-jar!)
(copy-dependencies!)
(println "Built" (.getPath (io/file dist-dir "nex.jar")))
(shutdown-agents)
