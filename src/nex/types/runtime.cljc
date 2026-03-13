(ns nex.types.runtime
  (:require [clojure.string :as str]))

(declare nex-set?)

(defn nex-array [] #?(:clj (java.util.ArrayList.) :cljs #js []))
(defn nex-array-from [coll] #?(:clj (java.util.ArrayList. (vec coll)) :cljs (js/Array.from (to-array coll))))
(defn nex-array? [v] #?(:clj (instance? java.util.ArrayList v) :cljs (array? v)))
(defn nex-array-get [arr idx] #?(:clj (.get arr idx) :cljs (aget arr idx)))
(defn nex-array-add [arr val] #?(:clj (.add arr val) :cljs (.push arr val)))
(defn nex-array-add-at [arr idx val] #?(:clj (.add arr idx val) :cljs (.splice arr idx 0 val)))
(defn nex-array-set [arr idx val] #?(:clj (.set arr idx val) :cljs (aset arr idx val)))
(defn nex-array-size [arr] #?(:clj (.size arr) :cljs (.-length arr)))
(defn nex-array-empty? [arr] #?(:clj (.isEmpty arr) :cljs (zero? (.-length arr))))
(defn nex-array-contains [arr elem] #?(:clj (.contains arr elem) :cljs (.includes arr elem)))
(defn nex-array-index-of [arr elem] #?(:clj (.indexOf arr elem) :cljs (.indexOf arr elem)))
(defn nex-array-remove [arr idx] #?(:clj (.remove arr (int idx)) :cljs (.splice arr idx 1)))
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
(defn nex-map-put [m key val] #?(:clj (.put m key val) :cljs (.set m key val)))
(defn nex-map-size [m] #?(:clj (.size m) :cljs (.-size m)))
(defn nex-map-empty? [m] #?(:clj (.isEmpty m) :cljs (zero? (.-size m))))
(defn nex-map-contains-key [m key] #?(:clj (.containsKey m key) :cljs (.has m key)))
(defn nex-map-keys [m] #?(:clj (vec (.keySet m)) :cljs (vec (es6-iterator-seq (.keys m)))))
(defn nex-map-values [m] #?(:clj (vec (.values m)) :cljs (vec (es6-iterator-seq (.values m)))))
(defn nex-map-remove [m key] #?(:clj (.remove m key) :cljs (.delete m key)))
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
(defn nex-file? [v] (and (map? v) (= (:nex-builtin-type v) :File)))
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
