(ns nex.types.runtime
  (:require [clojure.string :as str]))

(declare nex-set?)

(defn nex-array [] #?(:clj (java.util.ArrayList.) :cljs #js []))
(defn nex-array-from [coll] #?(:clj (java.util.ArrayList. (vec coll)) :cljs (js/Array.from (to-array coll))))
(defn nex-array? [v] #?(:clj (instance? java.util.ArrayList v) :cljs (array? v)))
(defn nex-array-get [arr idx] #?(:clj (.get arr idx) :cljs (aget arr idx)))
(defn nex-array-add [arr val]
  #?(:clj (do
            (.add arr val)
            nil)
     :cljs (do
             (.push arr val)
             nil)))
(defn nex-array-add-at [arr idx val]
  #?(:clj (do
            (.add arr idx val)
            nil)
     :cljs (do
             (.splice arr idx 0 val)
             nil)))
(defn nex-array-set [arr idx val]
  #?(:clj (do
            (.set arr idx val)
            nil)
     :cljs (do
             (aset arr idx val)
             nil)))
(defn nex-array-size [arr] #?(:clj (.size arr) :cljs (.-length arr)))
(defn nex-array-empty? [arr] #?(:clj (.isEmpty arr) :cljs (zero? (.-length arr))))
(defn nex-array-contains [arr elem] #?(:clj (.contains arr elem) :cljs (.includes arr elem)))
(defn nex-array-index-of [arr elem] #?(:clj (.indexOf arr elem) :cljs (.indexOf arr elem)))
(defn nex-array-remove [arr idx]
  #?(:clj (do
            (.remove ^java.util.ArrayList arr ^int (int idx))
            nil)
     :cljs (do
             (.splice arr idx 1)
             nil)))
(defn nex-array-reverse [arr] #?(:clj (java.util.ArrayList. (.reversed arr)) :cljs (js/Array.from (.reverse (.slice arr)))))
(defn nex-array-sort [arr] #?(:clj (.sort arr nil) :cljs (.sort arr)))
(defn nex-array-slice [arr start end] #?(:clj (.subList arr start end) :cljs (.slice arr start end)))
(defn nex-array-str [formatter arr]
  (str "[" (str/join ", " (map formatter #?(:clj arr :cljs (array-seq arr)))) "]"))

(defn nex-map [] #?(:clj (java.util.HashMap.) :cljs (js/Map.)))
(defn nex-map-from [pairs]
  #?(:clj (java.util.HashMap. (into {} pairs))
     :cljs (js/Map. (to-array (map to-array pairs)))))
(defn nex-map? [v] #?(:clj (instance? java.util.HashMap v) :cljs (instance? js/Map v)))
(defn nex-map-get [m key] #?(:clj (.get m key) :cljs (.get m key)))
(defn nex-map-put [m key val]
  #?(:clj (do
            (.put m key val)
            nil)
     :cljs (do
             (.set m key val)
             nil)))
(defn nex-map-size [m] #?(:clj (.size m) :cljs (.-size m)))
(defn nex-map-empty? [m] #?(:clj (.isEmpty m) :cljs (zero? (.-size m))))
(defn nex-map-contains-key [m key] #?(:clj (.containsKey m key) :cljs (.has m key)))
(defn nex-map-keys [m] #?(:clj (vec (.keySet m)) :cljs (vec (es6-iterator-seq (.keys m)))))
(defn nex-map-values [m] #?(:clj (vec (.values m)) :cljs (vec (es6-iterator-seq (.values m)))))
(defn nex-map-remove [m key]
  #?(:clj (do
            (.remove m key)
            nil)
     :cljs (do
             (.delete m key)
             nil)))
(defn nex-map-str [formatter m]
  (let [entries #?(:clj (for [[k v] m] (str (formatter k) ": " (formatter v)))
                   :cljs (for [[k v] (es6-iterator-seq (.entries m))] (str (formatter k) ": " (formatter v))))]
    (str "{" (str/join ", " entries) "}")))

(defn nex-set [] #?(:clj (java.util.LinkedHashSet.) :cljs (js/Set.)))
(defn nex-set-from [coll]
  #?(:clj (doto (java.util.LinkedHashSet.)
            (#(doseq [v coll] (.add % v))))
     :cljs (js/Set. (to-array coll))))
(defn nex-set? [v] #?(:clj (instance? java.util.LinkedHashSet v) :cljs (instance? js/Set v)))
(defn nex-set-contains [s v] #?(:clj (.contains s v) :cljs (.has s v)))
(defn nex-set-size [s] #?(:clj (.size s) :cljs (.-size s)))
(defn nex-set-empty? [s] #?(:clj (.isEmpty s) :cljs (zero? (.-size s))))
(defn nex-set-union [a b]
  #?(:clj (let [out (java.util.LinkedHashSet. a)]
            (.addAll out b)
            out)
     :cljs (nex-set-from (concat (es6-iterator-seq (.values a))
                                 (es6-iterator-seq (.values b))))))
(defn nex-set-difference [a b]
  #?(:clj (let [out (java.util.LinkedHashSet. a)]
            (.removeAll out b)
            out)
     :cljs (nex-set-from (remove #(.has b %) (es6-iterator-seq (.values a))))))
(defn nex-set-intersection [a b]
  #?(:clj (let [out (java.util.LinkedHashSet. a)]
            (.retainAll out b)
            out)
     :cljs (nex-set-from (filter #(.has b %) (es6-iterator-seq (.values a))))))
(defn nex-set-symmetric-difference [a b]
  #?(:clj (let [out (java.util.LinkedHashSet.)]
            (doseq [v a]
              (when-not (.contains b v) (.add out v)))
            (doseq [v b]
              (when-not (.contains a v) (.add out v)))
            out)
     :cljs (nex-set-from (concat (remove #(.has b %) (es6-iterator-seq (.values a)))
                                 (remove #(.has a %) (es6-iterator-seq (.values b)))))))
(defn nex-set-str [formatter s]
  (str "#{"
       (str/join ", " (map formatter #?(:clj (seq s) :cljs (es6-iterator-seq (.values s)))))
       "}"))

(defn- int32 [n]
  #?(:clj (int n)
     :cljs (bit-or n 0)))

(defn- bit-index [n]
  #?(:clj (bit-and (int n) 31)
     :cljs (bit-and n 31)))

(defn nex-bitwise-left-shift [n shift]
  (int32 (bit-shift-left (int32 n) (bit-index shift))))

(defn nex-bitwise-right-shift [n shift]
  (int32 (bit-shift-right (int32 n) (bit-index shift))))

(defn nex-bitwise-logical-right-shift [n shift]
  #?(:clj (long (bit-shift-right (bit-and 0xFFFFFFFF (long (int32 n)))
                                 (bit-index shift)))
     :cljs (js* "(~{} >>> ~{})" (int32 n) (bit-index shift))))

(defn nex-bitwise-rotate-left [n shift]
  #?(:clj (Integer/rotateLeft (int32 n) (bit-index shift))
     :cljs (let [x (int32 n)
                 s (bit-index shift)]
             (int32 (bit-or (bit-shift-left x s)
                            (js* "(~{} >>> ~{})" x (- 32 s)))))))

(defn nex-bitwise-rotate-right [n shift]
  #?(:clj (Integer/rotateRight (int32 n) (bit-index shift))
     :cljs (let [x (int32 n)
                 s (bit-index shift)]
             (int32 (bit-or (js* "(~{} >>> ~{})" x s)
                            (bit-shift-left x (- 32 s)))))))

(defn nex-bitwise-and [n other]
  (int32 (bit-and (int32 n) (int32 other))))

(defn nex-bitwise-or [n other]
  (int32 (bit-or (int32 n) (int32 other))))

(defn nex-bitwise-xor [n other]
  (int32 (bit-xor (int32 n) (int32 other))))

(defn nex-bitwise-not [n]
  (int32 (bit-not (int32 n))))

(defn nex-bitwise-is-set [n idx]
  (not (zero? (bit-and (int32 n) (bit-shift-left 1 (bit-index idx))))))

(defn nex-bitwise-set [n idx]
  (int32 (bit-or (int32 n) (bit-shift-left 1 (bit-index idx)))))

(defn nex-bitwise-unset [n idx]
  (int32 (bit-and (int32 n) (bit-not (bit-shift-left 1 (bit-index idx))))))

(defn nex-abs [n]
  (if (neg? n) (- n) n))

(defn nex-round [n] #?(:clj (Math/round (double n)) :cljs (js/Math.round n)))

(defn nex-int-pow [base exponent]
  (when (neg? exponent)
    (throw (ex-info "Integral exponentiation requires a non-negative exponent"
                    {:base base :exponent exponent})))
  (loop [acc 1
         b base
         e exponent]
    (if (zero? e)
      acc
      (recur (if (odd? e) (* acc b) acc)
             (* b b)
             (quot e 2)))))

(defn nex-console-print [msg] #?(:clj (print msg) :cljs (.write js/process.stdout (str msg))))
(defn nex-console-println [msg] #?(:clj (println msg) :cljs (js/console.log msg)))
(defn nex-console-error [msg] #?(:clj (binding [*out* *err*] (println msg)) :cljs (js/console.error msg)))
(defn nex-console-newline [] #?(:clj (println) :cljs (js/console.log "")))
(defn nex-console-read-line [] #?(:clj (read-line) :cljs (throw (ex-info "read-line not supported in ClojureScript" {}))))

(defn nex-parse-integer64-string [s]
  (let [trimmed (str/trim s)
        negative? (str/starts-with? trimmed "-")
        unsigned (if negative? (subs trimmed 1) trimmed)
        normalized (str/replace unsigned "_" "")
        [radix digits] (cond
                         (str/starts-with? normalized "0b") [2 (subs normalized 2)]
                         (str/starts-with? normalized "0o") [8 (subs normalized 2)]
                         (str/starts-with? normalized "0x") [16 (subs normalized 2)]
                         :else [10 normalized])
        parsed #?(:clj (Long/parseLong digits radix)
                  :cljs (js/parseInt digits radix))]
    (if negative? (- parsed) parsed)))

(defn nex-parse-integer [s] #?(:clj (int (nex-parse-integer64-string s))
                               :cljs (nex-parse-integer64-string s)))
(defn nex-parse-real [s] #?(:clj (Double/parseDouble (.trim s)) :cljs (js/parseFloat s)))

(defn nex-file-read [path] #?(:clj (slurp path) :cljs (.toString (.readFileSync (js/require "fs") path "utf8"))))
(defn nex-file-write [path content] #?(:clj (spit path content) :cljs (.writeFileSync (js/require "fs") path content "utf8")))
(defn nex-file-append [path content] #?(:clj (spit path content :append true) :cljs (.appendFileSync (js/require "fs") path content "utf8")))
(defn nex-file-exists? [path] #?(:clj (.exists (java.io.File. path)) :cljs (.existsSync (js/require "fs") path)))
(defn nex-file-delete [path] #?(:clj (.delete (java.io.File. path)) :cljs (.unlinkSync (js/require "fs") path)))
(defn nex-file-lines [path] #?(:clj (nex-array-from (str/split-lines (slurp path)))
                                :cljs (nex-array-from (.split (.toString (.readFileSync (js/require "fs") path "utf8")) "\n"))))

(defn path-exists? [path]
  #?(:clj (.exists (java.io.File. path))
     :cljs (.existsSync (js/require "fs") path)))

(defn path-is-file? [path]
  #?(:clj (.isFile (java.io.File. path))
     :cljs (let [fs (js/require "fs")]
             (and (.existsSync fs path) (.isFile (.statSync fs path))))))

(defn path-is-directory? [path]
  #?(:clj (.isDirectory (java.io.File. path))
     :cljs (let [fs (js/require "fs")]
             (and (.existsSync fs path) (.isDirectory (.statSync fs path))))))

(defn path-name [path]
  #?(:clj (.getName (java.io.File. path))
     :cljs (.basename (js/require "path") path)))

(defn path-extension [path]
  (let [name (path-name path)
        dot-index (.lastIndexOf ^String name ".")]
    (if (or (neg? dot-index) (zero? dot-index) (= dot-index (dec (count name))))
      ""
      (subs name (inc dot-index)))))

(defn path-name-without-extension [path]
  (let [name (path-name path)
        dot-index (.lastIndexOf ^String name ".")]
    (if (or (neg? dot-index) (zero? dot-index))
      name
      (subs name 0 dot-index))))

(defn path-absolute [path]
  #?(:clj (.getAbsolutePath (java.io.File. path))
     :cljs (.resolve (js/require "path") path)))

(defn path-normalize [path]
  #?(:clj (.normalize (.toPath (java.io.File. path)))
     :cljs (.normalize (js/require "path") path)))

(defn path-size [path]
  #?(:clj (.length (java.io.File. path))
     :cljs (let [fs (js/require "fs")]
             (if (.existsSync fs path)
               (.size (.statSync fs path))
               0))))

(defn path-modified-time [path]
  #?(:clj (.lastModified (java.io.File. path))
     :cljs (let [fs (js/require "fs")]
             (if (.existsSync fs path)
               (.mtimeMs (.statSync fs path))
               0))))

(defn path-parent [path]
  #?(:clj (.getParent (java.io.File. path))
     :cljs (let [p (.dirname (js/require "path") path)]
             (if (= p path) nil p))))

(defn path-child [path child-name]
  #?(:clj (.getPath (java.io.File. ^String path ^String child-name))
     :cljs (.join (js/require "path") path child-name)))

(defn path-create-file [path]
  #?(:clj (.createNewFile (java.io.File. path))
     :cljs (let [fs (js/require "fs")]
             (when-not (.existsSync fs path)
               (.writeFileSync fs path "" "utf8"))))
  nil)

(defn path-create-directory [path]
  #?(:clj (.mkdir (java.io.File. path))
     :cljs (.mkdirSync (js/require "fs") path #js {:recursive false}))
  nil)

(defn path-create-directories [path]
  #?(:clj (.mkdirs (java.io.File. path))
     :cljs (.mkdirSync (js/require "fs") path #js {:recursive true}))
  nil)

(defn path-delete [path]
  #?(:clj (let [f (java.io.File. path)]
            (when (.exists f)
              (if (.isDirectory f)
                (throw (ex-info "path_delete does not remove directories" {:path path}))
                (.delete f))))
     :cljs (let [fs (js/require "fs")]
             (when (.existsSync fs path)
               (let [stat (.statSync fs path)]
                 (if (.isDirectory stat)
                   (throw (ex-info "path_delete does not remove directories" {:path path}))
                   (.unlinkSync fs path))))))
  nil)

#?(:clj
   (defn- delete-tree-clj! [^java.io.File f]
     (when (.exists f)
       (doseq [child (reverse (clojure.core/file-seq f))]
         (.delete ^java.io.File child)))))

#?(:clj
   (defn- copy-tree-clj! [^java.io.File source ^java.io.File target]
     (if (.isDirectory source)
       (do
         (.mkdirs target)
         (doseq [child (or (.listFiles source) (make-array java.io.File 0))]
           (copy-tree-clj! child (java.io.File. target (.getName ^java.io.File child)))))
       (do
         (when-let [parent (.getParentFile target)]
           (.mkdirs parent))
         (java.nio.file.Files/copy (.toPath source)
                                   (.toPath target)
                                   (into-array java.nio.file.CopyOption
                                               [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))))

(defn path-delete-tree [path]
  #?(:clj (delete-tree-clj! (java.io.File. path))
     :cljs (let [fs (js/require "fs")]
             (when (.existsSync fs path)
               (.rmSync fs path #js {:recursive true :force true}))))
  nil)

(defn path-copy [source-path target-path]
  #?(:clj (copy-tree-clj! (java.io.File. source-path) (java.io.File. target-path))
     :cljs (let [fs (js/require "fs")
                 pathmod (js/require "path")
                 copy! (fn copy! [src dst]
                         (let [stat (.statSync fs src)]
                           (if (.isDirectory stat)
                             (do
                               (.mkdirSync fs dst #js {:recursive true})
                               (doseq [name (js->clj (.readdirSync fs src))]
                                 (copy! (.join pathmod src name) (.join pathmod dst name))))
                             (do
                               (.mkdirSync fs (.dirname pathmod dst) #js {:recursive true})
                               (.copyFileSync fs src dst)))))]
             (copy! source-path target-path)))
  nil)

(defn path-move [source-path target-path]
  #?(:clj (do
            (path-copy source-path target-path)
            (path-delete-tree source-path))
     :cljs (let [fs (js/require "fs")
                 pathmod (js/require "path")]
             (.mkdirSync fs (.dirname pathmod target-path) #js {:recursive true})
             (.renameSync fs source-path target-path)))
  nil)

(defn path-read-text [path]
  (nex-file-read path))

(defn path-write-text [path text]
  (nex-file-write path text)
  nil)

(defn path-append-text [path text]
  (nex-file-append path text)
  nil)

(defn path-list [path]
  #?(:clj (let [dir (java.io.File. path)
                files (.listFiles dir)]
            (nex-array-from (mapv #(.getPath ^java.io.File %) (or files []))))
     :cljs (let [fs (js/require "fs")
                 pathmod (js/require "path")]
             (nex-array-from
              (mapv #(.join pathmod path %)
                    (js->clj (.readdirSync fs path)))))))

#?(:clj
   (defn text-file-open-read [path]
     {:nex-builtin-type :TextFileHandle
      :mode :read
      :reader (java.io.BufferedReader. (java.io.InputStreamReader. (java.io.FileInputStream. path) java.nio.charset.StandardCharsets/UTF_8))
      :writer nil}))

#?(:cljs
   (defn text-file-open-read [path]
     (let [content (.toString (.readFileSync (js/require "fs") path "utf8"))
           lines (vec (str/split content #"\r?\n"))]
       {:nex-builtin-type :TextFileHandle
        :mode :read
        :lines (atom lines)
        :index (atom 0)
        :writer nil})))

#?(:clj
   (defn text-file-open-write [path]
     {:nex-builtin-type :TextFileHandle
      :mode :write
      :reader nil
      :writer (java.io.BufferedWriter. (java.io.OutputStreamWriter. (java.io.FileOutputStream. path false) java.nio.charset.StandardCharsets/UTF_8))}))

#?(:cljs
   (defn text-file-open-write [path]
     (do
       (.writeFileSync (js/require "fs") path "" "utf8")
       {:nex-builtin-type :TextFileHandle
        :mode :write
        :path path})))

#?(:clj
   (defn text-file-open-append [path]
     {:nex-builtin-type :TextFileHandle
      :mode :append
      :reader nil
      :writer (java.io.BufferedWriter. (java.io.OutputStreamWriter. (java.io.FileOutputStream. path true) java.nio.charset.StandardCharsets/UTF_8))}))

#?(:cljs
   (defn text-file-open-append [path]
     {:nex-builtin-type :TextFileHandle
      :mode :append
      :path path}))

(defn text-file-read-line [handle]
  #?(:clj (.readLine ^java.io.BufferedReader (:reader handle))
     :cljs (let [idx @(:index handle)
                 lines @(:lines handle)]
             (when (< idx (count lines))
               (let [line (nth lines idx)]
                 (swap! (:index handle) inc)
                 line)))))

(defn text-file-write [handle text]
  #?(:clj (do (.write ^java.io.BufferedWriter (:writer handle) (str text))
              (.flush ^java.io.BufferedWriter (:writer handle)))
     :cljs (let [fs (js/require "fs")
                 path (:path handle)]
             (if (= (:mode handle) :write)
               (.appendFileSync fs path (str text) "utf8")
               (.appendFileSync fs path (str text) "utf8"))))
  nil)

(defn text-file-close [handle]
  #?(:clj (do
            (when-let [r (:reader handle)] (.close ^java.io.BufferedReader r))
            (when-let [w (:writer handle)] (.close ^java.io.BufferedWriter w)))
     :cljs nil)
  nil)

#?(:clj
   (defn- bytes->int-array [^bytes bs]
     (nex-array-from (mapv #(bit-and (int %) 0xFF) bs))))

#?(:cljs
   (defn- bytes->int-array [buf]
     (nex-array-from (mapv identity (js->clj (js/Array.from buf))))))

#?(:clj
   (defn- int-array->bytes [values]
     (byte-array (map (fn [v]
                        (when (or (neg? v) (> v 255))
                          (throw (ex-info "Binary byte values must be in range 0..255" {:value v})))
                        (byte v))
                      values))))

#?(:cljs
   (defn- int-array->bytes [values]
     (js/Buffer.from
      (to-array
       (map (fn [v]
              (when (or (neg? v) (> v 255))
                (throw (ex-info "Binary byte values must be in range 0..255" {:value v})))
              v)
            values)))))

#?(:clj
   (defn binary-file-open-read [path]
     {:nex-builtin-type :BinaryFileHandle
      :mode :read
      :data (byte-array (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get path (make-array String 0))))
      :index (atom 0)
      :out nil}))

#?(:cljs
   (defn binary-file-open-read [path]
     {:nex-builtin-type :BinaryFileHandle
      :mode :read
      :data (.readFileSync (js/require "fs") path)
      :index (atom 0)
      :path path}))

#?(:clj
   (defn binary-file-open-write [path]
     {:nex-builtin-type :BinaryFileHandle
      :mode :write
      :data nil
      :index (atom 0)
      :out (java.io.FileOutputStream. path false)}))

#?(:cljs
   (defn binary-file-open-write [path]
     (do
       (.writeFileSync (js/require "fs") path (js/Buffer.alloc 0))
       {:nex-builtin-type :BinaryFileHandle
        :mode :write
        :path path})))

#?(:clj
   (defn binary-file-open-append [path]
     {:nex-builtin-type :BinaryFileHandle
      :mode :append
      :data nil
      :index (atom 0)
      :out (java.io.FileOutputStream. path true)}))

#?(:cljs
   (defn binary-file-open-append [path]
     {:nex-builtin-type :BinaryFileHandle
      :mode :append
      :path path}))

(defn binary-file-read-all [handle]
  #?(:clj (bytes->int-array ^bytes (:data handle))
     :cljs (bytes->int-array (:data handle))))

(defn binary-file-read [handle count]
  #?(:clj (let [data ^bytes (:data handle)
                idx @(:index handle)
                n (min (+ idx count) (alength data))
                out (java.util.Arrays/copyOfRange data idx n)]
            (reset! (:index handle) n)
            (bytes->int-array out))
     :cljs (let [data (:data handle)
                 idx @(:index handle)
                 n (min (+ idx count) (.-length data))
                 out (.subarray data idx n)]
             (reset! (:index handle) n)
             (bytes->int-array out))))

(defn binary-file-write [handle values]
  #?(:clj (let [out ^java.io.FileOutputStream (:out handle)]
            (.write out ^bytes (int-array->bytes values))
            (.flush out))
     :cljs (.appendFileSync (js/require "fs") (:path handle) (int-array->bytes values)))
  nil)

(defn binary-file-close [handle]
  #?(:clj (when-let [out ^java.io.FileOutputStream (:out handle)]
            (.close out))
     :cljs nil)
  nil)

(defn nex-process-getenv [name]
  #?(:clj (System/getenv name)
     :cljs (aget (.-env js/process) name)))
(defn nex-process-setenv [name value]
  #?(:clj (throw (ex-info "Setting env vars not supported on JVM" {}))
     :cljs (aset (.-env js/process) name value)))
(defn nex-process-command-line []
  #?(:clj (nex-array-from (into [] (.getInputArguments (java.lang.management.ManagementFactory/getRuntimeMXBean))))
     :cljs (nex-array-from (vec (.-argv js/process)))))

(defn nex-console? [v] (and (map? v) (= (:nex-builtin-type v) :Console)))
(defn nex-process? [v] (and (map? v) (= (:nex-builtin-type v) :Process)))
(defn nex-window? [v] (and (map? v) (= (:nex-builtin-type v) :Window)))
(defn nex-turtle? [v] (and (map? v) (= (:nex-builtin-type v) :Turtle)))
(defn nex-image? [v] (and (map? v) (= (:nex-builtin-type v) :Image)))
(defn nex-task? [v] (and (map? v) (= (:nex-builtin-type v) :Task)))
(defn nex-channel? [v] (and (map? v) (= (:nex-builtin-type v) :Channel)))

(defn nex-array-cursor? [v] (and (map? v) (= (:nex-builtin-type v) :ArrayCursor)))
(defn nex-string-cursor? [v] (and (map? v) (= (:nex-builtin-type v) :StringCursor)))
(defn nex-map-cursor? [v] (and (map? v) (= (:nex-builtin-type v) :MapCursor)))
(defn nex-set-cursor? [v] (and (map? v) (= (:nex-builtin-type v) :SetCursor)))

(defn nex-coll-get [coll idx]
  (cond
    (nex-array? coll) (nex-array-get coll idx)
    (nex-map? coll) (nex-map-get coll idx)
    :else #?(:clj (.get coll idx) :cljs (aget coll idx))))

(defn nex-char? [v]
  #?(:clj (char? v)
     :cljs (and (string? v) (== (.-length v) 1))))
