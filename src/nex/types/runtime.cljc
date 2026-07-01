(ns nex.types.runtime
  (:require [clojure.string :as str]))

(declare nex-set? nex-integer? ->nex-integer nex-int->number nex-hash-code)

(defn nex-array [] #?(:clj (java.util.ArrayList.) :cljs #js []))
(defn nex-array-from [coll] #?(:clj (java.util.ArrayList. (vec coll)) :cljs (js/Array.from (to-array coll))))
(defn nex-array? [v] #?(:clj (instance? java.util.ArrayList v) :cljs (array? v)))
;; Nex Integer indices are BigInt on JS; convert to a plain number for the host
;; collection. nex-int->number is identity on the JVM (a long index works as-is).
(defn nex-array-get [arr idx] (let [idx (nex-int->number idx)] #?(:clj (.get arr idx) :cljs (aget arr idx))))
(defn nex-array-add [arr val]
  #?(:clj (do
            (.add arr val)
            nil)
     :cljs (do
             (.push arr val)
             nil)))
(defn nex-array-add-at [arr idx val]
  (let [idx (nex-int->number idx)]
    #?(:clj (do
              (.add arr idx val)
              nil)
       :cljs (do
               (.splice arr idx 0 val)
               nil))))
(defn nex-array-set [arr idx val]
  (let [idx (nex-int->number idx)]
    #?(:clj (do
              (.set arr idx val)
              nil)
       :cljs (do
               (aset arr idx val)
               nil))))
(defn nex-array-size [arr] #?(:clj (.size arr) :cljs (.-length arr)))
(defn nex-array-empty? [arr] #?(:clj (.isEmpty arr) :cljs (zero? (.-length arr))))
(defn nex-array-contains [arr elem] #?(:clj (.contains arr elem) :cljs (.includes arr elem)))
(defn nex-array-index-of [arr elem] #?(:clj (.indexOf arr elem) :cljs (.indexOf arr elem)))
(defn nex-array-remove [arr idx]
  (let [idx (nex-int->number idx)]
    #?(:clj (do
              (.remove ^java.util.ArrayList arr ^int (int idx))
              nil)
       :cljs (do
               (.splice arr idx 1)
               nil))))
(defn nex-array-reverse [arr] #?(:clj (java.util.ArrayList. (.reversed arr)) :cljs (js/Array.from (.reverse (.slice arr)))))
(defn nex-array-sort [arr] #?(:clj (.sort arr nil) :cljs (.sort arr)))
(defn nex-array-slice [arr start end]
  (let [start (nex-int->number start)
        end (nex-int->number end)
        len #?(:clj (.size arr) :cljs (.-length arr))
        resolve (fn [i] (-> (if (< i 0) (+ len i) i) (max 0) (min len)))
        s (resolve start)
        e (resolve end)]
    #?(:clj (java.util.ArrayList. (.subList arr s e))
       :cljs (.slice arr s e))))

(defn nex-array-take [arr n]
  (let [n (nex-int->number n)
        len #?(:clj (.size arr) :cljs (.-length arr))
        end (-> n (max 0) (min len))]
    #?(:clj (java.util.ArrayList. (.subList arr 0 end))
       :cljs (.slice arr 0 end))))

(defn nex-array-drop [arr n]
  (let [n (nex-int->number n)
        len #?(:clj (.size arr) :cljs (.-length arr))
        start (-> n (max 0) (min len))]
    #?(:clj (java.util.ArrayList. (.subList arr start len))
       :cljs (.slice arr start))))

(defn nex-array-take-last [arr n]
  (let [n (nex-int->number n)
        len #?(:clj (.size arr) :cljs (.-length arr))
        start (-> len (- (max 0 n)) (max 0))]
    #?(:clj (java.util.ArrayList. (.subList arr start len))
       :cljs (.slice arr start))))

(defn nex-array-drop-last [arr n]
  (let [n (nex-int->number n)
        len #?(:clj (.size arr) :cljs (.-length arr))
        end (-> len (- (max 0 n)) (max 0))]
    #?(:clj (java.util.ArrayList. (.subList arr 0 end))
       :cljs (.slice arr 0 end))))
(defn nex-array-concat [arr other]
  #?(:clj (doto (java.util.ArrayList. arr)
            (.addAll other))
     :cljs (.concat arr other)))
(defn nex-array-str [formatter arr]
  (str "[" (str/join ", " (map formatter #?(:clj arr :cljs (array-seq arr)))) "]"))

;; ---------------------------------------------------------------------------
;; Value semantics for Set/Map: equality and hashing are injected so the
;; collections can match elements/keys by Nex equality. They default to structural
;; (host =/hash, value-based for Nex records on both backends); the interpreter
;; binds richer versions that honour a class's `equals`/`hash` overrides.
;; ---------------------------------------------------------------------------

(def ^:dynamic *value-equals*
  "Equality used for Set/Map membership and dedup. nil => structural host equality."
  nil)

(def ^:dynamic *value-hash*
  "Hash used to bucket Set elements and Map keys. nil => structural host hash."
  nil)

(defn value-equals? [a b] (if *value-equals* (*value-equals* a b) (= a b)))
(defn value-hash [v] (if *value-hash* (*value-hash* v) (nex-hash-code v)))

;; ---------------------------------------------------------------------------
;; Map: a portable, value-semantics collection (companion to Set below).
;;
;; Backed by plain Clojure data behind a single mutable cell rather than a host
;; HashMap/js.Map, so keys are matched by Nex equality on every backend (host maps
;; key by structural hashing on the JVM but reference identity on JS). A map is a
;; tagged map { :state (atom { :keys  <insertion-ordered key vector>,
;;                             :index <key-hash -> vector of [k v] pairs> }) }.
;; Keys keep insertion order on both backends. put/remove mutate the cell, so the
;; reference (and its aliases) observe updates as before.
;; ---------------------------------------------------------------------------

(defn nex-map [] {:nex-builtin-type :NexMap :state (atom {:keys [] :index {}})})

;; Portable map (the interpreter's representation). The compiled JVM/JS backends
;; still pass a host HashMap/js.Map through the shared interpreter bridge, so the
;; accessors below also accept a host map (the `:else` branches). Phase 4 removes
;; the host representation and the dual handling.
(defn- portable-map? [v] (and (map? v) (= (:nex-builtin-type v) :NexMap)))
(defn nex-map? [v]
  (or (portable-map? v)
      #?(:clj (instance? java.util.HashMap v) :cljs (instance? js/Map v))))

(defn- map-find-pair [state k]
  (some #(when (value-equals? (first %) k) %)
        (get (:index state) (value-hash k))))

(defn nex-map-entries
  "The map's [k v] pairs (insertion order for portable maps)."
  [m]
  (if (portable-map? m)
    (let [s @(:state m)] (map #(map-find-pair s %) (:keys s)))
    #?(:clj (map (fn [^java.util.Map$Entry e] [(.getKey e) (.getValue e)])
                 (.entrySet ^java.util.Map m))
       :cljs (map vec (es6-iterator-seq (.entries m))))))

(defn nex-map-get [m k]
  (if (portable-map? m)
    (when-let [pair (map-find-pair @(:state m) k)] (second pair))
    #?(:clj (.get ^java.util.Map m k) :cljs (.get m k))))
(defn nex-map-contains-key [m k]
  (if (portable-map? m)
    (boolean (map-find-pair @(:state m) k))
    #?(:clj (.containsKey ^java.util.Map m k) :cljs (.has m k))))

(defn nex-map-put [m k v]
  (if (portable-map? m)
    (swap! (:state m)
           (fn [{:keys [index] :as s}]
             (let [h (value-hash k)
                   bucket (get index h [])
                   i (first (keep-indexed (fn [idx p] (when (value-equals? (first p) k) idx)) bucket))]
               (if i
                 (assoc-in s [:index h i] [k v])
                 (-> s
                     (update :keys conj k)
                     (assoc-in [:index h] (conj bucket [k v])))))))
    #?(:clj (.put ^java.util.Map m k v) :cljs (.set m k v)))
  nil)

(defn nex-map-remove [m k]
  (if (portable-map? m)
    (swap! (:state m)
           (fn [{:keys [keys index] :as s}]
             (let [h (value-hash k)
                   new-bucket (filterv #(not (value-equals? (first %) k)) (get index h []))]
               (-> s
                   (assoc :keys (filterv #(not (value-equals? % k)) keys))
                   (assoc :index (if (seq new-bucket) (assoc index h new-bucket) (dissoc index h)))))))
    #?(:clj (.remove ^java.util.Map m k) :cljs (.delete m k)))
  nil)

(defn nex-map-size [m]
  (if (portable-map? m)
    (count (:keys @(:state m)))
    #?(:clj (.size ^java.util.Map m) :cljs (.-size m))))
(defn nex-map-empty? [m]
  (if (portable-map? m)
    (empty? (:keys @(:state m)))
    #?(:clj (.isEmpty ^java.util.Map m) :cljs (zero? (.-size m)))))
(defn nex-map-keys [m] (nex-array-from (map first (nex-map-entries m))))
(defn nex-map-values [m] (nex-array-from (map second (nex-map-entries m))))
(defn nex-map-from [pairs]
  (let [m (nex-map)]
    (doseq [[k v] pairs] (nex-map-put m k v))
    m))
(defn nex-host-map-from
  "Build a host-backed map (the representation the compiled backend uses)."
  [pairs]
  #?(:clj (let [m (java.util.HashMap.)] (doseq [[k v] pairs] (.put m k v)) m)
     :cljs (let [m (js/Map.)] (doseq [[k v] pairs] (.set m k v)) m)))
(defn nex-map-str [formatter m]
  (str "{"
       (str/join ", " (map (fn [[k v]] (str (formatter k) ": " (formatter v)))
                           (nex-map-entries m)))
       "}"))

;; ---------------------------------------------------------------------------
;; Set: a portable, value-semantics collection.
;;
;; Backed by plain immutable Clojure data rather than a host LinkedHashSet/js.Set,
;; so element identity is decided by Nex equality on every backend instead of host
;; hashing (structural on the JVM, but reference-based on JS). A set is a tagged map
;; { :items  <deduped vector, insertion order>, :index <hash -> vector of items> }.
;;
;; Element equality/hashing use the shared injected *value-equals*/*value-hash*
;; defined above (structural by default, override-aware when the interpreter binds
;; them). Sets are built once (literals, `from`, set algebra) then read, so there
;; is no incremental add.
;; ---------------------------------------------------------------------------

;; Portable set (the interpreter's representation). As with maps, the compiled
;; backends bridge a host LinkedHashSet/js.Set through the interpreter, so the read
;; accessors also accept a host set (the `:else` branches). Phase 4 removes this.
(defn- portable-set? [v] (and (map? v) (= (:nex-builtin-type v) :NexSet)))
(defn nex-set? [v]
  (or (portable-set? v)
      #?(:clj (instance? java.util.LinkedHashSet v) :cljs (instance? js/Set v))))

(defn- set-bucket-member? [index v]
  (boolean (some #(value-equals? % v) (get index (value-hash v)))))

(defn- set-conj [{:keys [index] :as s} v]
  (if (set-bucket-member? index v)
    s
    (-> s
        (update :items conj v)
        (update :index update (value-hash v) (fnil conj []) v))))

(defn nex-set [] {:nex-builtin-type :NexSet :items [] :index {}})
(defn nex-set-from [coll] (reduce set-conj (nex-set) coll))
(defn nex-host-set-from
  "Build a host-backed set (the representation the compiled backend uses)."
  [coll]
  #?(:clj (let [s (java.util.LinkedHashSet.)] (doseq [e coll] (.add s e)) s)
     :cljs (let [s (js/Set.)] (doseq [e coll] (.add s e)) s)))
(defn nex-set-seq
  "The set's elements as a seq, in insertion order."
  [s]
  (if (portable-set? s)
    (:items s)
    #?(:clj (seq s) :cljs (es6-iterator-seq (.values s)))))
(defn nex-set-contains [s v]
  (if (portable-set? s)
    (set-bucket-member? (:index s) v)
    (boolean (some #(value-equals? % v) (nex-set-seq s)))))
(defn nex-set-size [s]
  (if (portable-set? s)
    (count (:items s))
    #?(:clj (.size ^java.util.Set s) :cljs (.-size s))))
(defn nex-set-empty? [s]
  (if (portable-set? s)
    (empty? (:items s))
    #?(:clj (.isEmpty ^java.util.Set s) :cljs (zero? (.-size s)))))
(defn nex-set-union [a b]
  (nex-set-from (concat (:items a) (:items b))))
(defn nex-set-difference [a b]
  (nex-set-from (remove #(nex-set-contains b %) (:items a))))
(defn nex-set-intersection [a b]
  (nex-set-from (filter #(nex-set-contains b %) (:items a))))
(defn nex-set-symmetric-difference [a b]
  (nex-set-from (concat (remove #(nex-set-contains b %) (:items a))
                        (remove #(nex-set-contains a %) (:items b)))))
(defn nex-set-str [formatter s]
  (str "#{" (str/join ", " (map formatter (nex-set-seq s))) "}"))
(defn nex-set-to-array [s] (nex-array-from (nex-set-seq s)))

;; Bitwise operators are a 32-bit island: they mask operands to int32 and the
;; interpreter/compiler agree on that. On JS a Nex Integer is a BigInt, which
;; cannot be mixed with `number` in a bit op, so int32/bit-index convert through
;; `js/Number` on the way in, and `i32->int` lifts the int32 result back to the
;; Nex Integer representation on the way out.
(defn- int32 [n]
  #?(:clj (int n)
     :cljs (bit-or (js/Number n) 0)))

(defn- bit-index [n]
  #?(:clj (bit-and (int n) 31)
     :cljs (bit-and (js/Number n) 31)))

(defn- i32->int [v] (->nex-integer v))

(defn nex-bitwise-left-shift [n shift]
  (i32->int (int32 (bit-shift-left (int32 n) (bit-index shift)))))

(defn nex-bitwise-right-shift [n shift]
  (i32->int (int32 (bit-shift-right (int32 n) (bit-index shift)))))

(defn nex-bitwise-logical-right-shift [n shift]
  (i32->int
    #?(:clj (long (bit-shift-right (bit-and 0xFFFFFFFF (long (int32 n)))
                                   (bit-index shift)))
       :cljs (js* "(~{} >>> ~{})" (int32 n) (bit-index shift)))))

(defn nex-bitwise-rotate-left [n shift]
  (i32->int
    #?(:clj (Integer/rotateLeft (int32 n) (bit-index shift))
       :cljs (let [x (int32 n)
                   s (bit-index shift)]
               (int32 (bit-or (bit-shift-left x s)
                              (js* "(~{} >>> ~{})" x (- 32 s))))))))

(defn nex-bitwise-rotate-right [n shift]
  (i32->int
    #?(:clj (Integer/rotateRight (int32 n) (bit-index shift))
       :cljs (let [x (int32 n)
                   s (bit-index shift)]
               (int32 (bit-or (js* "(~{} >>> ~{})" x s)
                              (bit-shift-left x (- 32 s))))))))

(defn nex-bitwise-and [n other]
  (i32->int (int32 (bit-and (int32 n) (int32 other)))))

(defn nex-bitwise-or [n other]
  (i32->int (int32 (bit-or (int32 n) (int32 other)))))

(defn nex-bitwise-xor [n other]
  (i32->int (int32 (bit-xor (int32 n) (int32 other)))))

(defn nex-bitwise-not [n]
  (i32->int (int32 (bit-not (int32 n)))))

(defn nex-bitwise-is-set [n idx]
  (not (zero? (bit-and (int32 n) (bit-shift-left 1 (bit-index idx))))))

(defn nex-bitwise-set [n idx]
  (i32->int (int32 (bit-or (int32 n) (bit-shift-left 1 (bit-index idx))))))

(defn nex-bitwise-unset [n idx]
  (i32->int (int32 (bit-and (int32 n) (bit-not (bit-shift-left 1 (bit-index idx)))))))

(defn nex-abs [n]
  (if (neg? n) (- n) n))

(defn nex-round [n] #?(:clj (Math/round (double n)) :cljs (js/Math.round n)))

;; ---------------------------------------------------------------------------
;; Integer (Int64) representation
;;
;; On the JVM a Nex Integer is a Clojure `long` — 64-bit, and Clojure's +/-/*
;; already raise on overflow. On JavaScript a Nex Integer is a `BigInt`, so the
;; full signed 64-bit range is exact and overflow is detectable; a plain JS
;; `number` (IEEE binary64) would silently lose precision past 2^53. These
;; helpers centralize the representation so the shared interpreter and runtime
;; stay backend-agnostic. (See docs/md/NUMERIC_TOWER.md, Phase 4.)
;;
;; cljs facts these rely on (probed): native + - * / % < > <= >= = bit-* work on
;; BigInt; `js/BigInt.asIntN` wraps to 64 bits; `min`/`max`/`compare`/`quot`/
;; `rem`/`even?`/`odd?` and any BigInt<->number mix do NOT — hence the helpers.

(defn nex-integer?
  "True when v is a Nex Integer value in this host's representation."
  [v]
  #?(:clj (integer? v)
     :cljs (js* "typeof ~{} === 'bigint'" v)))

(defn ->nex-integer
  "Coerce a number, numeric string, or Integer to the host Integer representation."
  [v]
  #?(:clj (if (string? v) (Long/parseLong v) (long v))
     :cljs (js/BigInt v)))

(defn nex-int->number
  "Convert a Nex Integer to a plain host number — for JS array indices, char
   codepoints, and other positions that require a 32/53-bit number."
  [v]
  #?(:clj v :cljs (js/Number v)))

(defn nex-hash-code
  "A host-stable hash code for a Nex scalar value, used to bucket Set elements.
   Nex Integers are BigInt on JS, for which cljs `hash` is unreliable, so they are
   hashed via their (canonical) string form. Hashes need only be consistent within
   one host, not identical across hosts."
  [v]
  #?(:clj (hash v)
     :cljs (if (nex-integer? v) (hash (str v)) (hash v))))

(defn ->nex-real
  "Coerce a Nex numeric (Integer or Real) to the host Real (floating) value."
  [v]
  #?(:clj (double v)
     :cljs (if (nex-integer? v) (js/Number v) v)))

(defn nex-numeric?
  "True for any Nex number — Integer or Real — in this host's representation."
  [v]
  #?(:clj (number? v)
     :cljs (or (number? v) (nex-integer? v))))

#?(:cljs
   (defn- check-int64!
     "Raise if v has escaped the signed 64-bit range (BigInt arithmetic is
      unbounded); otherwise return v. Mirrors the JVM's checked long arithmetic."
     [v]
     (if (js* "~{} === ~{}" v (js/BigInt.asIntN 64 v))
       v
       (throw (ex-info "integer overflow" {:value (str v)})))))

;; cljs `zero?` tests `=== 0` against a *number*, so it is always false for a
;; BigInt; compare against a BigInt zero instead. (`<`/`>`/`neg?`/`pos?` are fine —
;; JS allows BigInt/number ordering — only `zero?`, `even?`, `odd?` need help.)
(defn nex-int-zero? [a] #?(:clj (zero? a) :cljs (js* "~{} === 0n" a)))

(defn nex-int-add [a b] #?(:clj (+ a b)    :cljs (check-int64! (+ a b))))
(defn nex-int-sub [a b] #?(:clj (- a b)    :cljs (check-int64! (- a b))))
(defn nex-int-mul [a b] #?(:clj (* a b)    :cljs (check-int64! (* a b))))
(defn nex-int-neg [a]   #?(:clj (- a)      :cljs (check-int64! (- a))))
;; Truncating integer division (toward zero), like quot / BigInt `/`.
(defn nex-int-quot [a b] #?(:clj (quot a b) :cljs (js* "~{} / ~{}" a b)))
;; Floored modulo, matching Clojure `mod` on both hosts.
(defn nex-int-mod [a b]
  #?(:clj (mod a b)
     :cljs (let [m (js* "~{} % ~{}" a b)]
             (if (or (nex-int-zero? m) (= (neg? m) (neg? b))) m (+ m b)))))
(defn nex-int-odd? [a]
  #?(:clj (odd? a) :cljs (js* "(~{} & 1n) === 1n" a)))

(defn nex-numeric-compare
  "Three-way compare of two Nex numbers. Integers compare in their own
   representation; a mixed Integer/Real pair is compared as Reals."
  [x y]
  (let [[a b] (if (and (nex-integer? x) (nex-integer? y))
                [x y]
                [(->nex-real x) (->nex-real y)])]
    (cond (< a b) -1 (> a b) 1 :else 0)))

(defn nex-numeric-equals?
  "Numeric equality with the JVM's kind-sensitive rule: 5 and 5.0 are not equal."
  [x y]
  (cond
    (and (nex-integer? x) (nex-integer? y)) (= x y)
    (and (number? x) (number? y)) (== x y)
    :else false))

(defn nex-int-pow [base exponent]
  (when (neg? exponent)
    (throw (ex-info "Integral exponentiation requires a non-negative exponent"
                    {:base base :exponent exponent})))
  (loop [acc (->nex-integer 1)
         b base
         e exponent]
    (if (nex-int-zero? e)
      acc
      ;; Square `b` only when another iteration will consume it. Squaring
      ;; unconditionally overflows the next power of `b` even when that value is
      ;; discarded (e.g. 2^40 computed (2^32)^2 and raised although 2^40 fits),
      ;; so the running product `acc` is the sole source of genuine overflow.
      (let [acc' (if (nex-int-odd? e) (nex-int-mul acc b) acc)
            e' (nex-int-quot e (->nex-integer 2))]
        (if (nex-int-zero? e')
          acc'
          (recur acc' (nex-int-mul b b) e'))))))

(defn nex-console-print [msg] #?(:clj (print msg) :cljs (.write js/process.stdout (str msg))))
(defn nex-console-println [msg] #?(:clj (println msg) :cljs (js/console.log msg)))
(defn nex-console-error [msg] #?(:clj (binding [*out* *err*] (println msg)) :cljs (js/console.error msg)))
(defn nex-console-newline [] #?(:clj (println) :cljs (js/console.log "")))
(defn nex-console-flush [] #?(:clj (flush) :cljs (.write js/process.stdout "")))
(defn nex-console-read-line []
  #?(:clj (do
            (flush)
            (read-line))
     :cljs (throw (ex-info "read-line not supported in ClojureScript" {}))))

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
        ;; cljs parses via js/BigInt (which understands 0x/0o/0b prefixes) so
        ;; values above 2^53 are exact — js/parseInt would round through a double.
        parsed #?(:clj (->nex-integer (Long/parseLong digits radix))
                  :cljs (js/BigInt normalized))]
    (if negative? (- parsed) parsed)))

;; Integer is 64-bit, so to_integer no longer truncates to 32 bits.
(defn nex-parse-integer [s] (nex-parse-integer64-string s))
(defn nex-parse-real [s] #?(:clj (Double/parseDouble (.trim s)) :cljs (js/parseFloat s)))

(defn nex-file-read [path] #?(:clj (slurp path) :cljs (.toString (.readFileSync (js/require "fs") path "utf8"))))
(defn nex-file-write [path content] #?(:clj (spit path content) :cljs (.writeFileSync (js/require "fs") path content "utf8")))
(defn nex-file-append [path content] #?(:clj (spit path content :append true) :cljs (.appendFileSync (js/require "fs") path content "utf8")))
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

#?(:cljs
   (defn- binary-file-fs []
     (js/require "fs")))

#?(:clj
   (defn- make-binary-file-handle
     [mode ^java.io.RandomAccessFile raf]
     {:nex-builtin-type :BinaryFileHandle
      :mode mode
      :index (atom (.getFilePointer raf))
      :raf raf}))

#?(:cljs
   (defn- make-binary-file-handle
     [mode fd index]
     {:nex-builtin-type :BinaryFileHandle
      :mode mode
      :index (atom index)
      :fd fd}))

#?(:clj
   (defn binary-file-open-read [path]
     (make-binary-file-handle :read
                              (java.io.RandomAccessFile. path "r"))))

#?(:cljs
   (defn binary-file-open-read [path]
     (make-binary-file-handle :read
                              (.openSync (binary-file-fs) path "r")
                              0)))

#?(:clj
   (defn binary-file-open-write [path]
     (let [raf (java.io.RandomAccessFile. path "rw")]
       (.setLength raf 0)
       (.seek raf 0)
        (make-binary-file-handle :write raf))))

#?(:cljs
   (defn binary-file-open-write [path]
     (make-binary-file-handle :write
                              (.openSync (binary-file-fs) path "w+")
                              0)))

#?(:clj
   (defn binary-file-open-append [path]
     (let [raf (java.io.RandomAccessFile. path "rw")
           size (.length raf)]
       (.seek raf size)
       (make-binary-file-handle :append raf))))

#?(:cljs
   (defn binary-file-open-append [path]
     (let [fs (binary-file-fs)]
       (when-not (.existsSync fs path)
         (.writeFileSync fs path (js/Buffer.alloc 0)))
       (let [fd (.openSync fs path "r+")
             size (.-size (.fstatSync fs fd))]
         (make-binary-file-handle :append fd size)))))

(defn binary-file-read-all [handle]
  #?(:clj (let [^java.io.RandomAccessFile raf (:raf handle)
                pos (.getFilePointer raf)
                size (int (.length raf))
                data (byte-array size)]
            (.seek raf 0)
            (.readFully raf data)
            (.seek raf pos)
            (bytes->int-array data))
     :cljs (let [fs (binary-file-fs)
                 fd (:fd handle)
                 size (.-size (.fstatSync fs fd))
                 out (js/Buffer.alloc size)]
             (.readSync fs fd out 0 size 0)
             (bytes->int-array out))))

(defn binary-file-read [handle count]
  #?(:clj (let [^java.io.RandomAccessFile raf (:raf handle)
                idx @(:index handle)
                size (.length raf)
                bytes-to-read (int (max 0 (min count (- size idx))))
                out (byte-array bytes-to-read)]
            (.seek raf idx)
            (when (pos? bytes-to-read)
              (.readFully raf out))
            (reset! (:index handle) (+ idx bytes-to-read))
            (bytes->int-array out))
     :cljs (let [fs (binary-file-fs)
                 fd (:fd handle)
                 idx @(:index handle)
                 size (.-size (.fstatSync fs fd))
                 bytes-to-read (max 0 (min count (- size idx)))
                 out (js/Buffer.alloc bytes-to-read)]
             (.readSync fs fd out 0 bytes-to-read idx)
             (reset! (:index handle) (+ idx bytes-to-read))
             (bytes->int-array out))))

(defn binary-file-write [handle values]
  #?(:clj (let [^java.io.RandomAccessFile raf (:raf handle)
                idx @(:index handle)
                data ^bytes (int-array->bytes values)]
            (.seek raf idx)
            (.write raf data)
            (reset! (:index handle) (+ idx (alength data))))
     :cljs (let [fs (binary-file-fs)
                 fd (:fd handle)
                 idx @(:index handle)
                 data (int-array->bytes values)]
             (.writeSync fs fd data 0 (.-length data) idx)
             (reset! (:index handle) (+ idx (.-length data)))))
  nil)

(defn binary-file-position [handle]
  @(:index handle))

(defn binary-file-seek [handle offset]
  (when (neg? offset)
    (throw (ex-info "binary file position must be non-negative" {:offset offset})))
  (reset! (:index handle) offset)
  #?(:clj (when-let [^java.io.RandomAccessFile raf (:raf handle)]
            (.seek raf offset))
     :cljs nil)
  nil)

(defn binary-file-close [handle]
  #?(:clj (when-let [^java.io.RandomAccessFile raf (:raf handle)]
            (.close raf))
     :cljs (when-let [fd (:fd handle)]
             (.closeSync (binary-file-fs) fd)))
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
(defn nex-task? [v] (and (map? v) (= (:nex-builtin-type v) :Task)))
(defn nex-channel? [v] (and (map? v) (= (:nex-builtin-type v) :Channel)))
(defn nex-min-heap? [v] (and (map? v) (= (:nex-builtin-type v) :MinHeap)))
(defn nex-atomic-integer? [v] (and (map? v) (= (:nex-builtin-type v) :AtomicInteger)))
(defn nex-atomic-integer64? [v] (and (map? v) (= (:nex-builtin-type v) :AtomicInteger64)))
(defn nex-atomic-boolean? [v] (and (map? v) (= (:nex-builtin-type v) :AtomicBoolean)))
(defn nex-atomic-reference? [v] (and (map? v) (= (:nex-builtin-type v) :AtomicReference)))

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
