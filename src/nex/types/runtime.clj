(ns nex.types.runtime
  (:require [clojure.string :as str]))

(declare nex-set? nex-integer? ->nex-integer nex-int->number nex-hash-code)

(defn nex-array [] (java.util.ArrayList.))
(defn nex-array-from [coll] (java.util.ArrayList. (vec coll)))
(defn nex-array? [v] (instance? java.util.ArrayList v))
;; Nex Integer indices are BigInt on JS; convert to a plain number for the host
;; collection. nex-int->number is identity on the JVM (a long index works as-is).
(defn nex-array-get [arr idx] (let [idx (nex-int->number idx)] (.get arr idx)))
(defn nex-array-add [arr val]
  (do
            (.add arr val)
            nil))
(defn nex-array-add-at [arr idx val]
  (let [idx (nex-int->number idx)]
    (do
              (.add arr idx val)
              nil)))
(defn nex-array-set [arr idx val]
  (let [idx (nex-int->number idx)]
    (do
              (.set arr idx val)
              nil)))
(defn nex-array-size [arr] (.size arr))
(defn nex-array-empty? [arr] (.isEmpty arr))
(defn nex-array-contains [arr elem] (.contains arr elem))
(defn nex-array-index-of [arr elem] (.indexOf arr elem))
(defn nex-array-remove [arr idx]
  (let [idx (nex-int->number idx)]
    (do
              (.remove ^java.util.ArrayList arr ^int (int idx))
              nil)))
(defn nex-array-reverse [arr] (java.util.ArrayList. (.reversed arr)))
(defn nex-array-sort [arr] (.sort arr nil))
(defn nex-array-slice [arr start end]
  (let [start (nex-int->number start)
        end (nex-int->number end)
        len (.size arr)
        resolve (fn [i] (-> (if (< i 0) (+ len i) i) (max 0) (min len)))
        s (resolve start)
        e (resolve end)]
    (java.util.ArrayList. (.subList arr s e))))

(defn nex-array-take [arr n]
  (let [n (nex-int->number n)
        len (.size arr)
        end (-> n (max 0) (min len))]
    (java.util.ArrayList. (.subList arr 0 end))))

(defn nex-array-drop [arr n]
  (let [n (nex-int->number n)
        len (.size arr)
        start (-> n (max 0) (min len))]
    (java.util.ArrayList. (.subList arr start len))))

(defn nex-array-take-last [arr n]
  (let [n (nex-int->number n)
        len (.size arr)
        start (-> len (- (max 0 n)) (max 0))]
    (java.util.ArrayList. (.subList arr start len))))

(defn nex-array-drop-last [arr n]
  (let [n (nex-int->number n)
        len (.size arr)
        end (-> len (- (max 0 n)) (max 0))]
    (java.util.ArrayList. (.subList arr 0 end))))
(defn nex-array-concat [arr other]
  (doto (java.util.ArrayList. arr)
            (.addAll other)))
(defn nex-array-str [formatter arr]
  (str "[" (str/join ", " (map formatter arr)) "]"))

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

(def ^:dynamic *operator-equals-hook*
  "Object-aware equality for the `=`/`/=` operators, bound by the compiled
   backend when it evaluates a class invariant through the interpreter (the
   invariant's operands are compiled instances the interpreter does not
   recognise as its own objects). A fn [a b] returning a Boolean when at least
   one operand is a user object of either model — dispatching structural /
   `equals` comparison that understands compiled instances — and nil otherwise,
   so scalar and numeric equality keep their normal path. nil => no hook, the
   ordinary case for interpreter-only runs."
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
      (instance? java.util.HashMap v)))

(defn- map-find-pair [state k]
  (some #(when (value-equals? (first %) k) %)
        (get (:index state) (value-hash k))))

(defn nex-map-entries
  "The map's [k v] pairs (insertion order for portable maps)."
  [m]
  (if (portable-map? m)
    (let [s @(:state m)] (map #(map-find-pair s %) (:keys s)))
    (map (fn [^java.util.Map$Entry e] [(.getKey e) (.getValue e)])
                 (.entrySet ^java.util.Map m))))

(defn nex-map-get [m k]
  (if (portable-map? m)
    (when-let [pair (map-find-pair @(:state m) k)] (second pair))
    (.get ^java.util.Map m k)))
(defn nex-map-contains-key [m k]
  (if (portable-map? m)
    (boolean (map-find-pair @(:state m) k))
    (.containsKey ^java.util.Map m k)))

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
    (.put ^java.util.Map m k v))
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
    (.remove ^java.util.Map m k))
  nil)

(defn nex-map-size [m]
  (if (portable-map? m)
    (count (:keys @(:state m)))
    (.size ^java.util.Map m)))
(defn nex-map-empty? [m]
  (if (portable-map? m)
    (empty? (:keys @(:state m)))
    (.isEmpty ^java.util.Map m)))
(defn nex-map-keys [m] (nex-array-from (map first (nex-map-entries m))))
(defn nex-map-values [m] (nex-array-from (map second (nex-map-entries m))))
(defn nex-map-from [pairs]
  (let [m (nex-map)]
    (doseq [[k v] pairs] (nex-map-put m k v))
    m))
(defn nex-host-map-from
  "Build a host-backed map (the representation the compiled backend uses)."
  [pairs]
  (let [m (java.util.HashMap.)] (doseq [[k v] pairs] (.put m k v)) m))
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
      (instance? java.util.LinkedHashSet v)))

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
  (let [s (java.util.LinkedHashSet.)] (doseq [e coll] (.add s e)) s))
(defn nex-set-seq
  "The set's elements as a seq, in insertion order."
  [s]
  (if (portable-set? s)
    (:items s)
    (seq s)))
(defn nex-set-contains [s v]
  (if (portable-set? s)
    (set-bucket-member? (:index s) v)
    (boolean (some #(value-equals? % v) (nex-set-seq s)))))
(defn nex-set-size [s]
  (if (portable-set? s)
    (count (:items s))
    (.size ^java.util.Set s)))
(defn nex-set-empty? [s]
  (if (portable-set? s)
    (empty? (:items s))
    (.isEmpty ^java.util.Set s)))
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
  ;; Truncate, never range-check: values outside int32 keep their low 32 bits
  ;; (`(int n)` would raise), matching the compiled backend's l2i truncation.
  (unchecked-int (long n)))

(defn- bit-index [n]
  (bit-and (unchecked-int (long n)) 31))

(defn- i32->int [v] (->nex-integer v))

(defn nex-bitwise-left-shift [n shift]
  (i32->int (int32 (bit-shift-left (int32 n) (bit-index shift)))))

(defn nex-bitwise-right-shift [n shift]
  (i32->int (int32 (bit-shift-right (int32 n) (bit-index shift)))))

(defn nex-bitwise-logical-right-shift [n shift]
  (i32->int
    (long (bit-shift-right (bit-and 0xFFFFFFFF (long (int32 n)))
                                   (bit-index shift)))))

(defn nex-bitwise-rotate-left [n shift]
  (i32->int
    (Integer/rotateLeft (int32 n) (bit-index shift))))

(defn nex-bitwise-rotate-right [n shift]
  (i32->int
    (Integer/rotateRight (int32 n) (bit-index shift))))

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

(defn nex-round [n] (Math/round (double n)))

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
  (integer? v))

(defn ->nex-integer
  "Coerce a number, numeric string, or Integer to the host Integer representation."
  [v]
  (if (string? v) (Long/parseLong v) (long v)))

(defn nex-int->number
  "Convert a Nex Integer to a plain host number — for JS array indices, char
   codepoints, and other positions that require a 32/53-bit number."
  [v]
  v)

(defn nex-hash-code
  "A host-stable hash code for a Nex scalar value, used to bucket Set elements.
   Nex Integers are BigInt on JS, for which cljs `hash` is unreliable, so they are
   hashed via their (canonical) string form. Hashes need only be consistent within
   one host, not identical across hosts."
  [v]
  (hash v))

(defn ->nex-real
  "Coerce a Nex numeric (Integer or Real) to the host Real (floating) value."
  [v]
  (double v))

(defn nex-numeric?
  "True for any Nex number — Integer or Real — in this host's representation."
  [v]
  (number? v))

;; cljs `zero?` tests `=== 0` against a *number*, so it is always false for a
;; BigInt; compare against a BigInt zero instead. (`<`/`>`/`neg?`/`pos?` are fine —
;; JS allows BigInt/number ordering — only `zero?`, `even?`, `odd?` need help.)
(defn nex-int-zero? [a] (zero? a))

(defn nex-int-add [a b] (+ a b))
(defn nex-int-sub [a b] (- a b))
(defn nex-int-mul [a b] (* a b))
(defn nex-int-neg [a]   (- a))
;; Truncating integer division (toward zero), like quot / BigInt `/`.
;; Raw — no zero or overflow check; nex-int-div is the checked entry point.
(defn nex-int-quot [a b] (quot a b))

(defn nex-int-div
  "Checked Integer division: raises on a zero divisor and on the one
   overflowing case (MIN_LONG / -1). Truncates toward zero. The canonical
   entry point for Nex `/` on Integers in every backend."
  [a b]
  (when (nex-int-zero? b)
    (throw (ex-info "Division by zero" {:left a :right b})))
  (if (and (= a Long/MIN_VALUE) (= b -1))
            (throw (ArithmeticException. "long overflow"))
            (quot a b)))

;; Truncated remainder (sign of the dividend), like Java % / BigInt %.
;; Nex `%` is truncated in every backend — the JVM's LREM/DREM and JS's `%`
;; are truncated natively, and the interpreter matches them through here.
(defn nex-int-mod
  "Checked Integer remainder: raises on a zero divisor; truncated semantics."
  [a b]
  (when (nex-int-zero? b)
    (throw (ex-info "Division by zero" {:left a :right b})))
  (rem a b))

(defn nex-real-rem
  "Truncated remainder on Reals (IEEE `%`, sign of the dividend), matching the
   JVM's DREM and JS's `%`; x % 0.0 is NaN."
  [a b]
  (let [d (double b)]
            (if (zero? d) Double/NaN (rem (double a) d))))
(defn nex-int-odd? [a]
  (odd? a))

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

;; IEEE-correct numeric ordering: unlike the 3-way nex-numeric-compare (which
;; cannot express "unordered"), these return false for any comparison against
;; NaN, as spec §B.3 requires. Integer pairs compare in their own
;; representation; mixed pairs as Reals.
(defn nex-numeric-lt [x y]
  (if (and (nex-integer? x) (nex-integer? y))
    (< x y)
    (< (->nex-real x) (->nex-real y))))
(defn nex-numeric-lte [x y]
  (if (and (nex-integer? x) (nex-integer? y))
    (<= x y)
    (<= (->nex-real x) (->nex-real y))))
(defn nex-numeric-gt [x y]
  (if (and (nex-integer? x) (nex-integer? y))
    (> x y)
    (> (->nex-real x) (->nex-real y))))
(defn nex-numeric-gte [x y]
  (if (and (nex-integer? x) (nex-integer? y))
    (>= x y)
    (>= (->nex-real x) (->nex-real y))))

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

(defn nex-console-print [msg] (print msg))
(defn nex-console-println [msg] (println msg))
(defn nex-console-error [msg] (binding [*out* *err*] (println msg)))
(defn nex-console-newline [] (println))
(defn nex-console-flush [] (flush))
(defn nex-console-read-line []
  (do
            (flush)
            (read-line)))

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
        parsed (->nex-integer (Long/parseLong digits radix))]
    (if negative? (- parsed) parsed)))

;; Integer is 64-bit, so to_integer no longer truncates to 32 bits.
(defn nex-parse-integer [s] (nex-parse-integer64-string s))
(defn nex-parse-real [s] (Double/parseDouble (.trim s)))

(defn nex-file-read [path] (slurp path))
(defn nex-file-write [path content] (spit path content))
(defn nex-file-append [path content] (spit path content :append true))
(defn path-exists? [path]
  (.exists (java.io.File. path)))

(defn path-is-file? [path]
  (.isFile (java.io.File. path)))

(defn path-is-directory? [path]
  (.isDirectory (java.io.File. path)))

(defn path-name [path]
  (.getName (java.io.File. path)))

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
  (.getAbsolutePath (java.io.File. path)))

(defn path-normalize [path]
  (.normalize (.toPath (java.io.File. path))))

(defn path-size [path]
  (.length (java.io.File. path)))

(defn path-modified-time [path]
  (.lastModified (java.io.File. path)))

(defn path-parent [path]
  (.getParent (java.io.File. path)))

(defn path-child [path child-name]
  (.getPath (java.io.File. ^String path ^String child-name)))

(defn path-create-file [path]
  (.createNewFile (java.io.File. path))
  nil)

(defn path-create-directory [path]
  (.mkdir (java.io.File. path))
  nil)

(defn path-create-directories [path]
  (.mkdirs (java.io.File. path))
  nil)

(defn path-delete [path]
  (let [f (java.io.File. path)]
            (when (.exists f)
              (if (.isDirectory f)
                (throw (ex-info "path_delete does not remove directories" {:path path}))
                (.delete f))))
  nil)

(defn- delete-tree-clj! [^java.io.File f]
     (when (.exists f)
       (doseq [child (reverse (clojure.core/file-seq f))]
         (.delete ^java.io.File child))))

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
                                               [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))))

(defn path-delete-tree [path]
  (delete-tree-clj! (java.io.File. path))
  nil)

(defn path-copy [source-path target-path]
  (copy-tree-clj! (java.io.File. source-path) (java.io.File. target-path))
  nil)

(defn path-move [source-path target-path]
  (do
            (path-copy source-path target-path)
            (path-delete-tree source-path))
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
  (let [dir (java.io.File. path)
                files (.listFiles dir)]
            (nex-array-from (mapv #(.getPath ^java.io.File %) (or files [])))))

(defn text-file-open-read [path]
     {:nex-builtin-type :TextFileHandle
      :mode :read
      :reader (java.io.BufferedReader. (java.io.InputStreamReader. (java.io.FileInputStream. path) java.nio.charset.StandardCharsets/UTF_8))
      :writer nil})

(defn text-file-open-write [path]
     {:nex-builtin-type :TextFileHandle
      :mode :write
      :reader nil
      :writer (java.io.BufferedWriter. (java.io.OutputStreamWriter. (java.io.FileOutputStream. path false) java.nio.charset.StandardCharsets/UTF_8))})

(defn text-file-open-append [path]
     {:nex-builtin-type :TextFileHandle
      :mode :append
      :reader nil
      :writer (java.io.BufferedWriter. (java.io.OutputStreamWriter. (java.io.FileOutputStream. path true) java.nio.charset.StandardCharsets/UTF_8))})

(defn text-file-read-line [handle]
  (.readLine ^java.io.BufferedReader (:reader handle)))

(defn text-file-write [handle text]
  (do (.write ^java.io.BufferedWriter (:writer handle) (str text))
              (.flush ^java.io.BufferedWriter (:writer handle)))
  nil)

(defn text-file-close [handle]
  (do
            (when-let [r (:reader handle)] (.close ^java.io.BufferedReader r))
            (when-let [w (:writer handle)] (.close ^java.io.BufferedWriter w)))
  nil)

(defn- bytes->int-array [^bytes bs]
     (nex-array-from (mapv #(bit-and (int %) 0xFF) bs)))

(defn- int-array->bytes [values]
     (byte-array (map (fn [v]
                        (when (or (neg? v) (> v 255))
                          (throw (ex-info "Binary byte values must be in range 0..255" {:value v})))
                        (byte v))
                      values)))

(defn- make-binary-file-handle
     [mode ^java.io.RandomAccessFile raf]
     {:nex-builtin-type :BinaryFileHandle
      :mode mode
      :index (atom (.getFilePointer raf))
      :raf raf})

(defn binary-file-open-read [path]
     (make-binary-file-handle :read
                              (java.io.RandomAccessFile. path "r")))

(defn binary-file-open-write [path]
     (let [raf (java.io.RandomAccessFile. path "rw")]
       (.setLength raf 0)
       (.seek raf 0)
        (make-binary-file-handle :write raf)))

(defn binary-file-open-append [path]
     (let [raf (java.io.RandomAccessFile. path "rw")
           size (.length raf)]
       (.seek raf size)
       (make-binary-file-handle :append raf)))

(defn binary-file-read-all [handle]
  (let [^java.io.RandomAccessFile raf (:raf handle)
                pos (.getFilePointer raf)
                size (int (.length raf))
                data (byte-array size)]
            (.seek raf 0)
            (.readFully raf data)
            (.seek raf pos)
            (bytes->int-array data)))

(defn binary-file-read [handle count]
  (let [^java.io.RandomAccessFile raf (:raf handle)
                idx @(:index handle)
                size (.length raf)
                bytes-to-read (int (max 0 (min count (- size idx))))
                out (byte-array bytes-to-read)]
            (.seek raf idx)
            (when (pos? bytes-to-read)
              (.readFully raf out))
            (reset! (:index handle) (+ idx bytes-to-read))
            (bytes->int-array out)))

(defn binary-file-write [handle values]
  (let [^java.io.RandomAccessFile raf (:raf handle)
                idx @(:index handle)
                data ^bytes (int-array->bytes values)]
            (.seek raf idx)
            (.write raf data)
            (reset! (:index handle) (+ idx (alength data))))
  nil)

(defn binary-file-position [handle]
  @(:index handle))

(defn binary-file-seek [handle offset]
  (when (neg? offset)
    (throw (ex-info "binary file position must be non-negative" {:offset offset})))
  (reset! (:index handle) offset)
  (when-let [^java.io.RandomAccessFile raf (:raf handle)]
            (.seek raf offset))
  nil)

(defn binary-file-close [handle]
  (when-let [^java.io.RandomAccessFile raf (:raf handle)]
            (.close raf))
  nil)

(defn nex-process-getenv [name]
  (System/getenv name))
(defn nex-process-setenv [name value]
  (throw (ex-info "Setting env vars not supported on JVM" {})))
(defn nex-process-command-line []
  (nex-array-from (into [] (.getInputArguments (java.lang.management.ManagementFactory/getRuntimeMXBean)))))

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
    :else (.get coll idx)))

(defn nex-char? [v]
  (char? v))
