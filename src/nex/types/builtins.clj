(ns nex.types.builtins
  "The builtin method library of Nex values (`builtin-type-methods` and
   `call-builtin-method`), plus the value-level helpers it is built from:
   ordering, structural hashing, membership, sorting, heaps, atomics, and the
   contract-violation raiser. Extracted from nex.interpreter (backend-alignment
   Stage D) so the compiled backends can use the library without the tree
   walker; engine-specific behaviour is injected via set-engine-hooks!."
  (:require [clojure.string :as str]
            [nex.types.runtime :as rt]
            [nex.types.value :as value]
            [nex.types.typeinfo :as typeinfo]
            [nex.types.json :as json-types]
            [nex.types.datetime :as dt]
            [nex.types.regex :as regex-types]
            [nex.types.http :as http]
            [nex.types.concurrency :as conc])
  (:import [java.nio.charset StandardCharsets]
                   [java.util.concurrent CompletableFuture ExecutionException TimeUnit TimeoutException CancellationException]
                   [java.util.concurrent.atomic AtomicBoolean AtomicInteger AtomicLong AtomicReference]))

(declare nex-format-value)
(declare call-builtin-method)
(declare nex-ordering-compare)

(def channel-timeout-signal conc/channel-timeout-signal)
(def task-timeout-signal conc/task-timeout-signal)
(def current-time-ms conc/current-time-ms)
(def timeout-ms conc/timeout-ms)
(def channel-closed-signal conc/channel-closed-signal)
(def queue-empty conc/queue-empty)
(def queue-conj conc/queue-conj)
(def queue-pop conc/queue-pop)
(def make-task conc/make-task)
(def make-channel conc/make-channel)
(def task-await conc/task-await)
(def task-done? conc/task-done?)
(def await-all-tasks conc/await-all-tasks)
(def await-any-task conc/await-any-task)
(def task-cancel conc/task-cancel)
(def task-cancelled? conc/task-cancelled?)
(def queue-remove-first conc/queue-remove-first)
(def channel-send conc/channel-send)
(def channel-try-send conc/channel-try-send)
(def channel-receive conc/channel-receive)
(def channel-try-receive conc/channel-try-receive)
(def channel-close conc/channel-close)

(def nex-array rt/nex-array)
(def nex-array-from rt/nex-array-from)
(def nex-array? rt/nex-array?)
(def nex-array-get rt/nex-array-get)
(def nex-array-add rt/nex-array-add)
(def nex-array-add-at rt/nex-array-add-at)
(def nex-array-set rt/nex-array-set)
(def nex-array-size rt/nex-array-size)
(def nex-array-empty? rt/nex-array-empty?)
(def nex-array-contains rt/nex-array-contains)
(def nex-array-index-of rt/nex-array-index-of)
(def nex-array-remove rt/nex-array-remove)
(def nex-array-reverse rt/nex-array-reverse)
(def nex-array-sort rt/nex-array-sort)
(def nex-array-slice rt/nex-array-slice)
(def nex-array-take rt/nex-array-take)
(def nex-array-drop rt/nex-array-drop)
(def nex-array-take-last rt/nex-array-take-last)
(def nex-array-drop-last rt/nex-array-drop-last)
(def nex-array-concat rt/nex-array-concat)
(defn nex-array-str [arr] (rt/nex-array-str nex-format-value arr))
(def nex-map rt/nex-map)
(def nex-map-from rt/nex-map-from)
(def nex-map? rt/nex-map?)
(def nex-map-get rt/nex-map-get)
(def nex-map-put rt/nex-map-put)
(def nex-map-size rt/nex-map-size)
(def nex-map-empty? rt/nex-map-empty?)
(def nex-map-contains-key rt/nex-map-contains-key)
(def nex-map-keys rt/nex-map-keys)
(def nex-map-values rt/nex-map-values)
(def nex-map-entries rt/nex-map-entries)
(def nex-map-remove rt/nex-map-remove)
(defn nex-map-str [m] (rt/nex-map-str nex-format-value m))
(def nex-set rt/nex-set)
(def nex-set-from rt/nex-set-from)
(def nex-set? rt/nex-set?)
(def nex-set-contains rt/nex-set-contains)
(def nex-set-size rt/nex-set-size)
(def nex-set-empty? rt/nex-set-empty?)
(def nex-set-union rt/nex-set-union)
(def nex-set-difference rt/nex-set-difference)
(def nex-set-intersection rt/nex-set-intersection)
(def nex-set-symmetric-difference rt/nex-set-symmetric-difference)
(def nex-set-to-array rt/nex-set-to-array)
(def nex-set-seq rt/nex-set-seq)
(defn nex-set-str [s] (rt/nex-set-str nex-format-value s))
(def nex-bitwise-left-shift rt/nex-bitwise-left-shift)
(def nex-bitwise-right-shift rt/nex-bitwise-right-shift)
(def nex-bitwise-logical-right-shift rt/nex-bitwise-logical-right-shift)
(def nex-bitwise-rotate-left rt/nex-bitwise-rotate-left)
(def nex-bitwise-rotate-right rt/nex-bitwise-rotate-right)
(def nex-bitwise-and rt/nex-bitwise-and)
(def nex-bitwise-or rt/nex-bitwise-or)
(def nex-bitwise-xor rt/nex-bitwise-xor)
(def nex-bitwise-not rt/nex-bitwise-not)
(def nex-bitwise-is-set rt/nex-bitwise-is-set)
(def nex-bitwise-set rt/nex-bitwise-set)
(def nex-bitwise-unset rt/nex-bitwise-unset)
(def nex-abs rt/nex-abs)
(def nex-round rt/nex-round)
(def nex-int-pow rt/nex-int-pow)
(def nex-integer? rt/nex-integer?)
(def ->nex-integer rt/->nex-integer)
(def nex-int->number rt/nex-int->number)
(def ->nex-real rt/->nex-real)
(def nex-numeric? rt/nex-numeric?)
(def nex-int-add rt/nex-int-add)
(def nex-int-sub rt/nex-int-sub)
(def nex-int-mul rt/nex-int-mul)
(def nex-int-neg rt/nex-int-neg)
(def nex-int-quot rt/nex-int-quot)
(def nex-int-div rt/nex-int-div)
(def nex-int-mod rt/nex-int-mod)
(def nex-real-rem rt/nex-real-rem)
(def nex-int-zero? rt/nex-int-zero?)
(def nex-numeric-compare rt/nex-numeric-compare)
(def nex-numeric-equals? rt/nex-numeric-equals?)
(def nex-numeric-lt rt/nex-numeric-lt)
(def nex-numeric-lte rt/nex-numeric-lte)
(def nex-numeric-gt rt/nex-numeric-gt)
(def nex-numeric-gte rt/nex-numeric-gte)
(def nex-console-print rt/nex-console-print)
(def nex-console-println rt/nex-console-println)
(def nex-console-error rt/nex-console-error)
(def nex-console-newline rt/nex-console-newline)
(def nex-console-flush rt/nex-console-flush)
(def nex-console-read-line rt/nex-console-read-line)
(def nex-parse-integer64-string rt/nex-parse-integer64-string)
(def nex-parse-integer rt/nex-parse-integer)
(def nex-parse-real rt/nex-parse-real)
(def nex-process-getenv rt/nex-process-getenv)
(def nex-process-setenv rt/nex-process-setenv)
(def nex-process-command-line rt/nex-process-command-line)
(def nex-process-is-self rt/nex-process-is-self)
(def nex-process-is-child rt/nex-process-is-child)
(def nex-process-set-working-directory rt/nex-process-set-working-directory)
(def nex-process-set-redirect-error-to-output rt/nex-process-set-redirect-error-to-output)
(def nex-process-start rt/nex-process-start)
(def nex-process-is-started rt/nex-process-is-started)
(def nex-process-is-alive rt/nex-process-is-alive)
(def nex-process-pid rt/nex-process-pid)
(def nex-process-wait rt/nex-process-wait)
(def nex-process-wait-timeout rt/nex-process-wait-timeout)
(def nex-process-exit-code rt/nex-process-exit-code)
(def nex-process-terminate rt/nex-process-terminate)
(def nex-process-kill rt/nex-process-kill)
(def nex-process-write rt/nex-process-write)
(def nex-process-write-line rt/nex-process-write-line)
(def nex-process-close-stdin rt/nex-process-close-stdin)
(def nex-process-read-line rt/nex-process-read-line)
(def nex-process-read-all rt/nex-process-read-all)
(def nex-process-read-error-line rt/nex-process-read-error-line)
(def nex-process-read-error-all rt/nex-process-read-error-all)
(def nex-process-to-string rt/nex-process-to-string)

;; ---------------------------------------------------------------------------
;; Engine hooks. The builtin library is engine-neutral; the pieces that need
;; an evaluator (invoking a user compare/equals/to_string or a Function value)
;; are injected here, following the *value-equals*/*value-hash* precedent in
;; nex.types.runtime. The interpreter registers its eval-node-backed versions
;; at load; defaults are inert so pure value code keeps working.
;; ---------------------------------------------------------------------------

(defonce ^:private engine-hooks
  (atom {:nex-object? (fn [_] false)
         :make-object (fn [class-name field-values & _]
                        {:class-name class-name :fields field-values})
         :object-equals-override (fn [_ _ _] nil)
         :call-object-method (fn [_ _ method _]
                               (throw (ex-info (str "No engine registered to invoke method: " method)
                                               {:method method})))
         :user-to-string (fn [_ _] nil)
         :add-output (fn [_ line] (rt/nex-console-println line))
         :is-parent? (fn [_ _ _] false)}))

(defn set-engine-hooks!
  "Register engine capabilities. Keys: :nex-object? :make-object
   :object-equals-override :call-object-method :user-to-string."
  [m]
  (swap! engine-hooks merge m)
  nil)

(defn nex-object? [v] ((:nex-object? @engine-hooks) v))
(defn make-object
  ([class-name field-values] (make-object class-name field-values nil))
  ([class-name field-values closure-env]
   ((:make-object @engine-hooks) class-name field-values closure-env)))
(defn object-equals-override [ctx a b] ((:object-equals-override @engine-hooks) ctx a b))
(defn- eval-call [ctx obj method args] ((:call-object-method @engine-hooks) ctx obj method args))
(defn- user-to-string [ctx value] ((:user-to-string @engine-hooks) ctx value))

(declare nex-format-value)
(declare call-builtin-method)

(def Precondition "Precondition")
(def Postcondition "Postcondition")
(def Loop-invariant "Loop invariant")
(def Class-invariant "Class invariant")
(def Assertion "Assertion")

(defn report-contract-violation
  "Throw a contract violation. Every clause names its assertion, so `label` is
   normally present; a bare `assert expr` has none, and reports its source line
   instead."
  ([contract-type label condition]
   (report-contract-violation contract-type label condition nil))
  ([contract-type label condition line]
   (throw (ex-info (cond
                     label (str contract-type " violation: " label)
                     line  (str contract-type " violation (line " line ")")
                     :else (str contract-type " violation"))
                   {:contract-type contract-type
                    :label label
                    :condition condition}))))

(defn nex-format-value [value]
  (value/nex-format-value nex-object? nex-map-str nex-array-str nex-set-str value))

(defn nex-clone-value [value]
  (value/nex-clone-value nex-object? make-object value))

(defn nex-map-entry-match? [m2 k1 v1]
  (value/nex-map-entry-match? nex-object? k1 v1 m2))

(defn nex-deep-equals? [a b]
  (value/nex-deep-equals? nex-object? a b))

(defn nex-structural-hash
  "A structural hash that agrees with the structural `equals` default
   (nex-deep-equals?): values that compare equal hash equal. This is the default
   `hash` an object inherits from `Any`. Object and Array hashing is order-
   sensitive over fields/elements; Set and Map hashing is order-insensitive,
   mirroring how those collections compare."
  [v]
  (cond
    (nex-object? v)
    (hash (into [(:class-name v)]
                (map (fn [k] [k (nex-structural-hash (get (:fields v) k))])
                     (sort (keys (:fields v))))))

    (nex-array? v)
    (hash (mapv nex-structural-hash
                (seq v)))

    (nex-set? v)
    (reduce + 0 (map nex-structural-hash (nex-set-seq v)))

    (nex-map? v)
    (reduce + 0 (map (fn [[k val]]
                       (hash [(nex-structural-hash k) (nex-structural-hash val)]))
                     (nex-map-entries v)))

    :else (rt/nex-hash-code v)))

(defn builtin-scalar-value?
  [v]
  (or (nil? v)
      (string? v)
      (nex-numeric? v)
      (boolean? v)
      (char? v)))

(defn membership-equals?
  "Element equality for linear-scan membership (Array contains/index_of). With a
   context, a user-defined `equals` override on the element's class is honoured;
   otherwise comparison is structural — the same rule the `=` operator uses."
  ([a b] (membership-equals? nil a b))
  ([ctx a b]
   (cond
     (and (builtin-scalar-value? a) (builtin-scalar-value? b)) (= a b)
     :else (let [overridden (object-equals-override ctx a b)]
             (if (some? overridden) overridden (nex-deep-equals? a b))))))

(defn nex-array-contains-value?
  ([arr elem] (nex-array-contains-value? nil arr elem))
  ([ctx arr elem]
   (boolean
    (some #(membership-equals? ctx % elem)
          (seq arr)))))

(defn nex-array-index-of-value
  ([arr elem] (nex-array-index-of-value nil arr elem))
  ([ctx arr elem]
   (loop [idx 0
          values (seq arr)]
     (cond
       (nil? values) -1
       (membership-equals? ctx (first values) elem) idx
       :else (recur (inc idx) (next values))))))

(defn nex-map-contains-key-value?
  [m key]
  ;; The map's own key lookup already uses Nex equality (structural by default,
  ;; honouring an `equals` override when the interpreter has bound it).
  (nex-map-contains-key m key))

(defn nex-set-contains-value?
  [s value]
  ;; The set's own membership already uses Nex equality (structural by default,
  ;; honouring an `equals` override when the interpreter has bound it).
  (nex-set-contains s value))

(defn sortable-builtin-scalar-value?
  [v]
  (or (string? v)
      (number? v)
      (boolean? v)
      (char? v)))

(defn nex-value-compare
  [ctx a b]
  (cond
    (and (sortable-builtin-scalar-value? a)
         (sortable-builtin-scalar-value? b))
    (nex-ordering-compare a b)

    (nex-object? a)
    (let [result (eval-call ctx a "compare" [b])]
      (if (number? result)
        result
        (throw (ex-info "Comparable.compare must return Integer"
                        {:left a :right b :result result}))))

    :else
    (throw (ex-info "Array.sort requires Comparable elements"
                    {:left a :right b}))))

(defn nex-array-sort-with-ctx
  ([ctx arr]
   (let [out (java.util.ArrayList. arr)]
             (.sort out (reify java.util.Comparator
                          (compare [_ a b]
                            (int (nex-value-compare ctx a b)))))
             out))
  ([ctx arr comparator]
   (let [compare-fn (fn [a b]
                      (let [result (if (fn? comparator)
                                     (comparator a b)
                                     (eval-call ctx comparator "call2" [a b]))]
                        (if (integer? result)
                          result
                          (throw (ex-info "Array.sort comparator must return Integer"
                                          {:left a :right b :result result})))))]
     (let [out (java.util.ArrayList. arr)]
               (.sort out (reify java.util.Comparator
                            (compare [_ a b]
                              (compare-fn a b))))
               out))))

(defn make-min-heap
  [comparator]
  {:nex-builtin-type :MinHeap
   :data (atom [])
   :comparator comparator})

(defn make-atomic-integer
  [initial]
  ;; 64-bit, matching Nex Integer (Int64). Previously AtomicInteger, which
  ;; silently truncated values above 2^31 (see NUMERIC_TOWER.md).
  {:nex-builtin-type :AtomicInteger
   :state (AtomicLong. (long initial))})

(defn make-atomic-integer64
  [initial]
  {:nex-builtin-type :AtomicInteger64
   :state (AtomicLong. (long initial))})

(defn make-atomic-boolean
  [initial]
  {:nex-builtin-type :AtomicBoolean
   :state (AtomicBoolean. (boolean initial))})

(defn make-atomic-reference
  [initial]
  {:nex-builtin-type :AtomicReference
   :state (AtomicReference. initial)})

(defn deep-equals-runtime?
  [a b]
  (value/nex-deep-equals? nex-object? a b))

(defn atomic-reference-cas!
  [atomic expected update]
  (loop []
            (let [^AtomicReference state (:state atomic)
                  current (.get state)]
              (if (deep-equals-runtime? current expected)
                (if (.compareAndSet state current update)
                  true
                  (recur))
                false))))

(defn heap-compare
  [ctx heap left right]
  (let [comparator (:comparator heap)]
    (if comparator
      (let [result (if (fn? comparator)
                     (comparator left right)
                     (eval-call ctx comparator "call2" [left right]))]
        (if (integer? result)
          result
          (throw (ex-info "Min_Heap comparator must return Integer"
                          {:left left :right right :result result}))))
      (nex-value-compare ctx left right))))

(defn heap-sift-up
  [ctx heap values idx]
  (loop [items values
         child idx]
    (if (zero? child)
      items
      (let [parent (quot (dec child) 2)
            child-value (nth items child)
            parent-value (nth items parent)]
        (if (neg? (heap-compare ctx heap child-value parent-value))
          (recur (-> items
                     (assoc child parent-value)
                     (assoc parent child-value))
                 parent)
          items)))))

(defn heap-sift-down
  [ctx heap values idx]
  (let [n (count values)]
    (loop [items values
           parent idx]
      (let [left (+ (* 2 parent) 1)
            right (+ left 1)]
        (if (>= left n)
          items
          (let [smallest-child (if (and (< right n)
                                        (neg? (heap-compare ctx
                                                            heap
                                                            (nth items right)
                                                            (nth items left))))
                                 right
                                 left)
                parent-value (nth items parent)
                child-value (nth items smallest-child)]
            (if (neg? (heap-compare ctx heap child-value parent-value))
              (recur (-> items
                         (assoc parent child-value)
                         (assoc smallest-child parent-value))
                     smallest-child)
              items)))))))

(defn heap-insert!
  [ctx heap value]
  (swap! (:data heap)
         (fn [items]
           (let [expanded (conj items value)]
             (heap-sift-up ctx heap expanded (dec (count expanded))))))
  nil)

(defn heap-peek
  [heap]
  (let [items @(:data heap)]
    (when (seq items)
      (first items))))

(defn heap-extract-min!
  [ctx heap]
  (let [items @(:data heap)]
    (when (seq items)
      (let [minimum (first items)
            last-value (peek items)
            remaining-count (dec (count items))
            replacement (if (zero? remaining-count)
                          []
                          (heap-sift-down ctx heap (assoc (pop items) 0 last-value) 0))]
        (reset! (:data heap) replacement)
        minimum))))

(defn nex-display-value [value]
  (value/nex-display-value nex-object? nex-format-value value))

(defn nex-ordering-compare [x y]
  (cond
    ;; Numbers first: Clojure `compare` throws on BigInt (JS Integer), and the
    ;; string fallback below would misorder them ("10" < "9"). nex-numeric-compare
    ;; handles Integer/Real in both representations.
    (and (nex-numeric? x) (nex-numeric? y)) (nex-numeric-compare x y)
    (= x y) 0
    :else
    (try
      (let [c (compare x y)]
        (cond
          (neg? c) -1
          (pos? c) 1
          :else 0))
      (catch Exception _
        (let [sx (str x)
              sy (str y)]
          (cond
            (= sx sy) 0
            (neg? (compare sx sy)) -1
            :else 1))))))

(defn concat-string-value
  "Convert a runtime value to the string form used by String concatenation.
   If a Nex object implements to_string (resolved through the engine hook),
   use it; otherwise the built-in Any/to_string formatting path."
  [ctx value]
  (cond
    (string? value) value
    :else (or (user-to-string ctx value)
              (call-builtin-method nil nil value "to_string" []))))

(def builtin-type-methods
  "Methods available on built-in types"
  (letfn [(nex-compare [x y]
            (nex-ordering-compare x y))]
    {:Any
   {"to_string"   ^{:returns "String" :signatures [{:params [] :return-type "String"}]}
                  (fn [v & _] (nex-format-value v))
    ;; Default equality is structural (deep, field-by-field). A class may
    ;; override `equals` to change this; the `=`/`/=` operators then honour the
    ;; override. Identity comparison remains available through `==`/`!=`.
    "equals"      ^{:returns "Boolean" :signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                  (fn [v other & _] (nex-deep-equals? v other))
    ;; Default hash is structural and consistent with the structural `equals`
    ;; above. A class that overrides `equals` should override `hash` too.
    "hash"        ^{:returns "Integer" :signatures [{:params [] :return-type "Integer"}]}
                  (fn [v & _] (->nex-integer (nex-structural-hash v)))
    "clone"       ^{:returns "Any" :signatures [{:params [] :return-type "Any"}]}
                  (fn [v & _] (nex-clone-value v))}

   :String
   {"length"      ^{:signatures [{:params [] :return-type "Integer"}]}
                  (fn [s & _] (->nex-integer (count s)))
    "index_of"    ^{:signatures [{:params [{:name "substr" :type "String"}] :return-type "Integer"}]}
                  (fn [s ch & _]
                    (let [idx (str/index-of s (str ch))]
                      (->nex-integer (if idx idx -1))))
    "substring"   ^{:signatures [{:params [{:name "start" :type "Integer"} {:name "end" :type "Integer"}] :return-type "String"}]}
                  (fn [s start end & _] (subs s (nex-int->number start) (nex-int->number end)))
    "to_upper"    ^{:signatures [{:params [] :return-type "String"}]}
                  (fn [s & _] (str/upper-case s))
    "to_lower"    ^{:signatures [{:params [] :return-type "String"}]}
                  (fn [s & _] (str/lower-case s))
    "to_integer"  ^{:signatures [{:params [] :return-type "Integer"}]}
                  (fn [s & _] (nex-parse-integer s))
    "to_integer64" ^{:signatures [{:params [] :return-type "Integer"}]}
                   (fn [s & _] (nex-parse-integer64-string s))
    "to_real"     ^{:signatures [{:params [] :return-type "Real"}]}
                  (fn [s & _] (Double/parseDouble (str/trim s)))
    "contains"    ^{:signatures [{:params [{:name "substr" :type "String"}] :return-type "Boolean"}]}
                  (fn [s substr & _] (str/includes? s substr))
    "starts_with" ^{:signatures [{:params [{:name "prefix" :type "String"}] :return-type "Boolean"}]}
                  (fn [s prefix & _] (str/starts-with? s prefix))
    "ends_with"   ^{:signatures [{:params [{:name "suffix" :type "String"}] :return-type "Boolean"}]}
                  (fn [s suffix & _] (str/ends-with? s suffix))
    "trim"        ^{:signatures [{:params [] :return-type "String"}]}
                  (fn [s & _] (str/trim s))
    "replace"     ^{:signatures [{:params [{:name "old" :type "String"} {:name "new" :type "String"}] :return-type "String"}]}
                  (fn [s old new & _] (str/replace s old new))
    "pad_end"     ^{:signatures [{:params [{:name "pad" :type "String"} {:name "count" :type "Integer"}] :return-type "String"}]}
                  (fn [s pad len & _] (let [len (nex-int->number len)] (if (>= (count s) len) s (str s (apply str (repeat (- len (count s)) pad))))))
    "pad_start"   ^{:signatures [{:params [{:name "pad" :type "String"} {:name "count" :type "Integer"}] :return-type "String"}]}
                  (fn [s pad len & _] (let [len (nex-int->number len)] (if (>= (count s) len) s (str (apply str (repeat (- len (count s)) pad)) s))))
    "replicate"   ^{:signatures [{:params [{:name "n" :type "Integer"}] :return-type "String"}]}
                  (fn [s n & _] (apply str (repeat (nex-int->number n) s)))
    "char_at"     ^{:signatures [{:params [{:name "index" :type "Integer"}] :return-type "Char"}]}
                  (fn [s idx & _] (get s (nex-int->number idx)))
    "chars"       ^{:signatures [{:params [] :return-type {:base-type "Array" :type-params ["Char"]}}]}
                  (fn [s & _]
                    (nex-array-from
                     (mapv #(get s %) (range (count s)))))
    "to_bytes"    ^{:signatures [{:params [] :return-type {:base-type "Array" :type-params ["Integer"]}}]}
                  (fn [s & _]
                    (nex-array-from
                             (mapv #(->nex-integer (bit-and (int %) 0xFF))
                                   (.getBytes ^String s StandardCharsets/UTF_8))))
    "split"       ^{:signatures [{:params [{:name "delimiter" :type "String"}]
                                  :return-type {:base-type "Array" :type-params ["String"]}}]}
                  (fn [s delim & _] (nex-array-from (str/split s (re-pattern delim))))
    "join"        ^{:signatures [{:params [{:name "parts" :type {:base-type "Array" :type-params ["String"]}}]
                                  :return-type "String"}]}
                  (fn [s arr & _] (str/join s arr))
    ;; String operator methods
    "plus"        (fn [s other & [ctx]]
                    (str s (if ctx
                             (concat-string-value ctx other)
                             (nex-format-value other))))
    "equals"      (fn [s other & _] (= s other))
    "not_equals"  (fn [s other & _] (not= s other))
    "less_than"   (fn [s other & _] (neg? (compare s other)))
    "less_than_or_equal" (fn [s other & _] (<= (compare s other) 0))
    "greater_than" (fn [s other & _] (pos? (compare s other)))
    "greater_than_or_equal" (fn [s other & _] (>= (compare s other) 0))
    "compare"     (fn [s other & _] (nex-compare s other))
    "hash"        (fn [s & _] (hash s))
    ;; A String iterates over its Chars, exactly as Array/Map/Set iterate over
    ;; their elements — see register-string-methods! for why this is declared
    ;; explicitly rather than through the universal "Any" fallback.
    "cursor"      ^{:signatures [{:params [] :return-type "Cursor"}]}
                  (fn [s & _]
                    {:nex-builtin-type :StringCursor
                     :source s
                     :index (atom 0)})}

   :Integer
   {"to_string"         ^{:signatures [{:params [] :return-type "String"}]}
                        (fn [n & _] (str n))
    ;; The typechecker has always accepted these three (they're registered
    ;; alongside every other Integer method), but the runtime table never
    ;; defined them — n.to_real() and kin failed with "Method not found",
    ;; not a type error. to_integer/to_integer64 are identity: an Integer is
    ;; already the language's one 64-bit integer type.
    "to_integer"        ^{:signatures [{:params [] :return-type "Integer"}]}
                        (fn [n & _] n)
    "to_integer64"      ^{:signatures [{:params [] :return-type "Integer"}]}
                        (fn [n & _] n)
    "to_real"           ^{:signatures [{:params [] :return-type "Real"}]}
                        (fn [n & _] (->nex-real n))
    "abs"               ^{:signatures [{:params [] :return-type "Integer"}]}
                        (fn [n & _] (if (neg? n) (nex-int-neg n) n))
    "min"               ^{:signatures [{:params [{:name "other" :type "Integer"}] :return-type "Integer"}]}
                        (fn [n other & _] (if (pos? (nex-numeric-compare n other)) other n))
    "max"               ^{:signatures [{:params [{:name "other" :type "Integer"}] :return-type "Integer"}]}
                        (fn [n other & _] (if (neg? (nex-numeric-compare n other)) other n))
    "pick"              ^{:signatures [{:params [] :return-type "Integer"}]}
                        (fn [n & _] (->nex-integer (rand-int (nex-int->number n))))
    "bitwise_left_shift" ^{:signatures [{:params [{:name "n" :type "Integer"}] :return-type "Integer"}]}
                         (fn [n shift & _] (nex-bitwise-left-shift n shift))
    "bitwise_right_shift" ^{:signatures [{:params [{:name "n" :type "Integer"}] :return-type "Integer"}]}
                          (fn [n shift & _] (nex-bitwise-right-shift n shift))
    "bitwise_logical_right_shift" ^{:signatures [{:params [{:name "n" :type "Integer"}] :return-type "Integer"}]}
                                  (fn [n shift & _] (nex-bitwise-logical-right-shift n shift))
    "bitwise_rotate_left" ^{:signatures [{:params [{:name "n" :type "Integer"}] :return-type "Integer"}]}
                          (fn [n shift & _] (nex-bitwise-rotate-left n shift))
    "bitwise_rotate_right" ^{:signatures [{:params [{:name "n" :type "Integer"}] :return-type "Integer"}]}
                           (fn [n shift & _] (nex-bitwise-rotate-right n shift))
    "bitwise_is_set"    ^{:signatures [{:params [{:name "n" :type "Integer"}] :return-type "Boolean"}]}
                        (fn [n idx & _] (nex-bitwise-is-set n idx))
    "bitwise_set"       ^{:signatures [{:params [{:name "n" :type "Integer"}] :return-type "Integer"}]}
                        (fn [n idx & _] (nex-bitwise-set n idx))
    "bitwise_unset"     ^{:signatures [{:params [{:name "n" :type "Integer"}] :return-type "Integer"}]}
                        (fn [n idx & _] (nex-bitwise-unset n idx))
    "bitwise_and"       ^{:signatures [{:params [{:name "x" :type "Integer"}] :return-type "Integer"}]}
                        (fn [n other & _] (nex-bitwise-and n other))
    "bitwise_or"        ^{:signatures [{:params [{:name "x" :type "Integer"}] :return-type "Integer"}]}
                        (fn [n other & _] (nex-bitwise-or n other))
    "bitwise_xor"       ^{:signatures [{:params [{:name "x" :type "Integer"}] :return-type "Integer"}]}
                        (fn [n other & _] (nex-bitwise-xor n other))
    "bitwise_not"       ^{:signatures [{:params [] :return-type "Integer"}]}
                        (fn [n & _] (nex-bitwise-not n))
    ;; Arithmetic operator methods (64-bit checked, matching the operators)
    "plus"              ^{:signatures [{:params [{:name "other" :type "Integer"}] :return-type "Integer"}]}
                        (fn [n other & _] (nex-int-add n other))
    "minus"             ^{:signatures [{:params [{:name "other" :type "Integer"}] :return-type "Integer"}]}
                        (fn [n other & _] (nex-int-sub n other))
    "times"             ^{:signatures [{:params [{:name "other" :type "Integer"}] :return-type "Integer"}]}
                        (fn [n other & _] (nex-int-mul n other))
    ;; divided_by is typed to return Real, so it is real division on both hosts.
    "divided_by"        ^{:signatures [{:params [{:name "other" :type "Integer"}] :return-type "Real"}]}
                        (fn [n other & _] (/ (double n) (double other)))
    ;; Comparison operator methods
    "equals"            ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                        (fn [n other & _] (nex-numeric-equals? n other))
    "not_equals"        ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                        (fn [n other & _] (not (nex-numeric-equals? n other)))
    "less_than"         ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                        (fn [n other & _] (neg? (nex-numeric-compare n other)))
    "less_than_or_equal" ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                         (fn [n other & _] (not (pos? (nex-numeric-compare n other))))
    "greater_than"      ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                        (fn [n other & _] (pos? (nex-numeric-compare n other)))
    "greater_than_or_equal" ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                            (fn [n other & _] (not (neg? (nex-numeric-compare n other))))
    "to_char"           ^{:signatures [{:params [] :return-type "Char"}]}
                        (fn [n & _] (char (int n)))
    "compare"           (fn [n other & _] (nex-compare n other))
    "hash"              (fn [n & _] (hash n))}

   :Real
   {"to_string"         ^{:signatures [{:params [] :return-type "String"}]}
                        (fn [n & _] (str n))
    "abs"               ^{:signatures [{:params [] :return-type "Real"}]}
                        (fn [n & _] (nex-abs n))
    "min"               ^{:signatures [{:params [{:name "other" :type "Real"}] :return-type "Real"}]}
                        (fn [n other & _] (min (->nex-real n) (->nex-real other)))
    "max"               ^{:signatures [{:params [{:name "other" :type "Real"}] :return-type "Real"}]}
                        (fn [n other & _] (max (->nex-real n) (->nex-real other)))
    "round"             ^{:signatures [{:params [] :return-type "Integer"}]}
                        (fn [n & _] (->nex-integer (nex-round n)))
    "to_fixed"          ^{:signatures [{:params [{:name "places" :type "Integer"}] :return-type "Real"}]}
                        (fn [n places & _]
                          (let [places (nex-int->number places)]
                            (double (.setScale (bigdec n) (int places) java.math.RoundingMode/HALF_UP))))
    ;; IEEE-754 inspection: with Real division now honestly IEEE, these let
    ;; callers detect the special values it can produce (see NUMERIC_TOWER.md).
    "is_nan"            ^{:signatures [{:params [] :return-type "Boolean"}]}
                        (fn [n & _] (Double/isNaN (double n)))
    "is_infinite"       ^{:signatures [{:params [] :return-type "Boolean"}]}
                        (fn [n & _] (Double/isInfinite (double n)))
    "is_finite"         ^{:signatures [{:params [] :return-type "Boolean"}]}
                        (fn [n & _] (and (not (Double/isNaN (double n)))
                                                 (not (Double/isInfinite (double n)))))
    ;; Arithmetic operator methods
    "plus"              ^{:signatures [{:params [{:name "other" :type "Real"}] :return-type "Real"}]}
                        (fn [n other & _] (+ n other))
    "minus"             ^{:signatures [{:params [{:name "other" :type "Real"}] :return-type "Real"}]}
                        (fn [n other & _] (- n other))
    "times"             ^{:signatures [{:params [{:name "other" :type "Real"}] :return-type "Real"}]}
                        (fn [n other & _] (* n other))
    ;; IEEE division (see the boxed-double note on the "/" operator).
    "divided_by"        ^{:signatures [{:params [{:name "other" :type "Real"}] :return-type "Real"}]}
                        (fn [n other & _] (/ (double n) (double other)))
    ;; Comparison operator methods
    "equals"            ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                        (fn [n other & _] (= n other))
    "not_equals"        ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                        (fn [n other & _] (not= n other))
    "less_than"         ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                        (fn [n other & _] (< n other))
    "less_than_or_equal" ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                         (fn [n other & _] (<= n other))
    "greater_than"      ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                        (fn [n other & _] (> n other))
    "greater_than_or_equal" ^{:signatures [{:params [{:name "other" :type "Any"}] :return-type "Boolean"}]}
                            (fn [n other & _] (>= n other))
    "compare"           (fn [n other & _] (nex-compare n other))
    "hash"              (fn [n & _] (hash n))}

   :Char
   {"to_string"   ^{:signatures [{:params [] :return-type "String"}]}
                  (fn [c & _] (str c))
    "to_upper"    ^{:signatures [{:params [] :return-type "String"}]}
                  (fn [c & _] (str/upper-case (str c)))
    "to_lower"    ^{:signatures [{:params [] :return-type "String"}]}
                  (fn [c & _] (str/lower-case (str c)))
    "to_integer"  ^{:signatures [{:params [] :return-type "Integer"}]}
                  (fn [c & _] (->nex-integer (int c)))
    "compare"     (fn [c other & _] (nex-compare c other))
    "hash"        (fn [c & _] (hash c))}

   :Boolean
   {"to_string"   (fn [b & _] (str b))
    ;; Boolean operator methods
    "and"         (fn [b other & _] (and b other))
    "or"          (fn [b other & _] (or b other))
    "not"         (fn [b & _] (not b))
    "equals"      (fn [b other & _] (= b other))
    "not_equals"  (fn [b other & _] (not= b other))
    "compare"     (fn [b other & _] (nex-compare b other))
    "hash"        (fn [b & _] (hash b))}

   :Array
   {"get"         ^{:returns :element :signatures [{:params [{:name "index" :type "Integer"}] :return-type "T"}]}
                  (fn [arr index & _] (nex-array-get arr index))
    "add"         ^{:returns "Void" :signatures [{:params [{:name "value" :type "T"}] :return-type "Void"}]}
                  (fn [arr value & _] (nex-array-add arr value))
    "add_at"      ^{:returns "Void" :signatures [{:params [{:name "index" :type "Integer"} {:name "value" :type "T"}] :return-type "Void"}]}
                  (fn [arr index value & _] (nex-array-add-at arr index value))
    "put"         ^{:returns "Void"} (fn [arr index value & _] (nex-array-set arr index value))
    "set"         ^{:returns "Void" :signatures [{:params [{:name "index" :type "Integer"} {:name "value" :type "T"}] :return-type "Void"}]}
                  (fn [arr index value & _] (nex-array-set arr index value))
    "length"      ^{:returns "Integer" :signatures [{:params [] :return-type "Integer"}]}
                  (fn [arr & _] (->nex-integer (nex-array-size arr)))
    "is_empty"    ^{:returns "Boolean" :signatures [{:params [] :return-type "Boolean"}]}
                  (fn [arr & _] (nex-array-empty? arr))
    ;; A trailing ctx is supplied by call-builtin-method so element membership
    ;; can honour a user-defined `equals` override (see object-equals-override).
    "contains"    ^{:returns "Boolean" :signatures [{:params [{:name "elem" :type "T"}] :return-type "Boolean"}]}
                  (fn [arr elem & rest] (nex-array-contains-value? (first rest) arr elem))
    "index_of"    ^{:returns "Integer" :signatures [{:params [{:name "elem" :type "T"}] :return-type "Integer"}]}
                  (fn [arr elem & rest]
                    (let [idx (nex-array-index-of-value (first rest) arr elem)]
                      (->nex-integer (if (>= idx 0) idx -1))))
    "remove"      ^{:returns "Void" :signatures [{:params [{:name "index" :type "Integer"}] :return-type "Void"}]}
                  (fn [arr idx & _] (nex-array-remove arr idx))
    "reverse"     ^{:returns :self :signatures [{:params [] :return-type {:base-type "Array" :type-params ["T"]}}]}
                  (fn [arr & _] (nex-array-reverse arr))
    "sort"        ^{:returns :self
                    :signatures [{:params [] :return-type {:base-type "Array" :type-params ["T"]}}
                                 {:params [{:name "compareFn" :type "Function"}]
                                  :return-type {:base-type "Array" :type-params ["T"]}}]}
                  (fn [arr & args]
                    (let [ctx (last args)
                          method-args (butlast args)]
                      (case (count method-args)
                        0 (nex-array-sort-with-ctx ctx arr)
                        1 (nex-array-sort-with-ctx ctx arr (first method-args))
                        (throw (ex-info "Method sort expects 0 or 1 arguments"
                                        {:target arr :method "sort" :actual (count method-args)})))))
    "slice"       ^{:returns :self
                    :signatures [{:params [{:name "start" :type "Integer"} {:name "end" :type "Integer"}]
                                  :return-type {:base-type "Array" :type-params ["T"]}}]}
                  (fn [arr start end & _] (nex-array-slice arr start end))
    "take"        ^{:returns :self
                    :signatures [{:params [{:name "n" :type "Integer"}]
                                  :return-type {:base-type "Array" :type-params ["T"]}}]}
                  (fn [arr n & _] (nex-array-take arr n))
    "drop"        ^{:returns :self
                    :signatures [{:params [{:name "n" :type "Integer"}]
                                  :return-type {:base-type "Array" :type-params ["T"]}}]}
                  (fn [arr n & _] (nex-array-drop arr n))
    "take_last"   ^{:returns :self
                    :signatures [{:params [{:name "n" :type "Integer"}]
                                  :return-type {:base-type "Array" :type-params ["T"]}}]}
                  (fn [arr n & _] (nex-array-take-last arr n))
    "drop_last"   ^{:returns :self
                    :signatures [{:params [{:name "n" :type "Integer"}]
                                  :return-type {:base-type "Array" :type-params ["T"]}}]}
                  (fn [arr n & _] (nex-array-drop-last arr n))
    "concat"      ^{:returns :self
                    :signatures [{:params [{:name "other" :type {:base-type "Array" :type-params ["T"]}}]
                                  :return-type {:base-type "Array" :type-params ["T"]}}]}
                  (fn [arr other & _] (nex-array-concat arr other))
    "to_string"   ^{:returns "String" :signatures [{:params [] :return-type "String"}]}
                  (fn [arr & _] (nex-array-str arr))
    "equals"      ^{:returns "Boolean"
                    :signatures [{:params [{:name "other" :type {:base-type "Array" :type-params ["T"]}}] :return-type "Boolean"}]}
                  (fn [arr other & _] (nex-deep-equals? arr other))
    "clone"       ^{:returns :self :signatures [{:params [] :return-type {:base-type "Array" :type-params ["T"]}}]}
                  (fn [arr & _] (nex-clone-value arr))
    "cursor"      ^{:returns "Cursor" :signatures [{:params [] :return-type "Cursor"}]}
                  (fn [arr & _]
                    {:nex-builtin-type :ArrayCursor
                     :source arr
                     :index (atom 0)})}

   :Map
   {"get"         ^{:returns :value :signatures [{:params [{:name "key" :type "K"}] :return-type "V"}]}
                  (fn [m key & _]
                    (let [v (nex-map-get m key)]
                      (if (nil? v)
                        (report-contract-violation Precondition "key_must_exist" "has_key")
                        v)))
    "try_get"      ^{:returns :value
                     :signatures [{:params [{:name "key" :type "K"} {:name "default" :type "V"}] :return-type "V"}]}
                   (fn [m key default & _]
                    (let [v (nex-map-get m key)]
                      (if (nil? v)
                        default
                        v)))
    "put"          ^{:returns "Void" :signatures [{:params [{:name "key" :type "K"} {:name "value" :type "V"}] :return-type "Void"}]}
                   (fn [m key val & _] (nex-map-put m key val))
    "set"          ^{:returns "Void" :signatures [{:params [{:name "key" :type "K"} {:name "value" :type "V"}] :return-type "Void"}]}
                   (fn [m key val & _] (nex-map-put m key val))
    "size"         ^{:returns "Integer" :signatures [{:params [] :return-type "Integer"}]}
                   (fn [m & _] (->nex-integer (nex-map-size m)))
    "is_empty"     ^{:returns "Boolean" :signatures [{:params [] :return-type "Boolean"}]}
                   (fn [m & _] (nex-map-empty? m))
    "contains_key" ^{:returns "Boolean" :signatures [{:params [{:name "key" :type "K"}] :return-type "Boolean"}]}
                   (fn [m key & _] (nex-map-contains-key-value? m key))
    "keys"         ^{:returns :array-of-element
                     :signatures [{:params [] :return-type {:base-type "Array" :type-params ["K"]}}]}
                   (fn [m & _] (nex-map-keys m))
    "values"       ^{:returns :array-of-value
                     :signatures [{:params [] :return-type {:base-type "Array" :type-params ["V"]}}]}
                   (fn [m & _] (nex-map-values m))
    "remove"       ^{:returns "Void" :signatures [{:params [{:name "key" :type "K"}] :return-type "Void"}]}
                   (fn [m key & _] (nex-map-remove m key))
    "to_string"    ^{:returns "String" :signatures [{:params [] :return-type "String"}]}
                   (fn [m & _] (nex-map-str m))
    "equals"       ^{:returns "Boolean"
                     :signatures [{:params [{:name "other" :type {:base-type "Map" :type-params ["K" "V"]}}] :return-type "Boolean"}]}
                   (fn [m other & _] (nex-deep-equals? m other))
    "clone"        ^{:returns :self :signatures [{:params [] :return-type {:base-type "Map" :type-params ["K" "V"]}}]}
                   (fn [m & _] (nex-clone-value m))
    "cursor"       ^{:returns "Cursor" :signatures [{:params [] :return-type "Cursor"}]}
                   (fn [m & _]
                     {:nex-builtin-type :MapCursor
                     :source m
                     :keys (atom (nex-map-keys m))
                     :index (atom 0)})}

   :Set
   {"contains"             ^{:returns "Boolean" :signatures [{:params [{:name "value" :type "T"}] :return-type "Boolean"}]}
                           (fn [s value & _] (nex-set-contains-value? s value))
    "union"                ^{:returns :self
                             :signatures [{:params [{:name "other" :type {:base-type "Set" :type-params ["T"]}}]
                                           :return-type {:base-type "Set" :type-params ["T"]}}]}
                           (fn [s other & _] (nex-set-union s other))
    "difference"           ^{:returns :self
                             :signatures [{:params [{:name "other" :type {:base-type "Set" :type-params ["T"]}}]
                                           :return-type {:base-type "Set" :type-params ["T"]}}]}
                           (fn [s other & _] (nex-set-difference s other))
    "intersection"         ^{:returns :self
                             :signatures [{:params [{:name "other" :type {:base-type "Set" :type-params ["T"]}}]
                                           :return-type {:base-type "Set" :type-params ["T"]}}]}
                           (fn [s other & _] (nex-set-intersection s other))
    "symmetric_difference" ^{:returns :self
                             :signatures [{:params [{:name "other" :type {:base-type "Set" :type-params ["T"]}}]
                                           :return-type {:base-type "Set" :type-params ["T"]}}]}
                           (fn [s other & _] (nex-set-symmetric-difference s other))
    "size"                 ^{:returns "Integer" :signatures [{:params [] :return-type "Integer"}]}
                           (fn [s & _] (->nex-integer (nex-set-size s)))
    "is_empty"             ^{:returns "Boolean" :signatures [{:params [] :return-type "Boolean"}]}
                           (fn [s & _] (nex-set-empty? s))
    "to_array"             ^{:returns :array-of-element
                             :signatures [{:params [] :return-type {:base-type "Array" :type-params ["T"]}}]}
                           (fn [s & _] (nex-set-to-array s))
    "to_string"            ^{:returns "String" :signatures [{:params [] :return-type "String"}]}
                           (fn [s & _] (nex-set-str s))
    "equals"               ^{:returns "Boolean"
                             :signatures [{:params [{:name "other" :type {:base-type "Set" :type-params ["T"]}}] :return-type "Boolean"}]}
                           (fn [s other & _] (nex-deep-equals? s other))
    "clone"                ^{:returns :self :signatures [{:params [] :return-type {:base-type "Set" :type-params ["T"]}}]}
                           (fn [s & _] (nex-clone-value s))
    "cursor"               ^{:returns "Cursor" :signatures [{:params [] :return-type "Cursor"}]}
                           (fn [s & _]
                             {:nex-builtin-type :SetCursor
                              :source s
                              :values (atom (vec (nex-set-seq s)))
                              :index (atom 0)})}

   :Min_Heap
   {"insert"          ^{:signatures [{:params [{:name "value" :type "T"}] :return-type "Void"}]}
                      (fn [heap value & [ctx]] (heap-insert! ctx heap value))
    "extract_min"     ^{:signatures [{:params [] :return-type "T"}]}
                      (fn [heap & [ctx]]
                        (or (heap-extract-min! ctx heap)
                            (throw (ex-info "Min_Heap is empty" {:heap heap}))))
    "try_extract_min" ^{:signatures [{:params [] :return-type {:base-type "T" :detachable true}}]}
                      (fn [heap & [ctx]] (heap-extract-min! ctx heap))
    "peek"            ^{:signatures [{:params [] :return-type "T"}]}
                      (fn [heap & _]
                        (or (heap-peek heap)
                            (throw (ex-info "Min_Heap is empty" {:heap heap}))))
    "try_peek"        ^{:signatures [{:params [] :return-type {:base-type "T" :detachable true}}]}
                      (fn [heap & _] (heap-peek heap))
    "size"            ^{:signatures [{:params [] :return-type "Integer"}]}
                      (fn [heap & _] (->nex-integer (count @(:data heap))))
    "is_empty"        ^{:signatures [{:params [] :return-type "Boolean"}]}
                      (fn [heap & _] (empty? @(:data heap)))}

   ;; Atomic_Integer and Atomic_Integer64 are both 64-bit (AtomicLong on the JVM,
   ;; a BigInt-holding atom on JS). On JS, Integer is a BigInt, so increment/add
   ;; must use the BigInt-safe primitives — `inc`/`dec`/`+` mix BigInt and number
   ;; and throw.
   :Atomic_Integer
   {"load"            ^{:signatures [{:params [] :return-type "Integer"}]}
                      (fn [atomic & _] (.get ^AtomicLong (:state atomic)))
    "store"           ^{:signatures [{:params [{:name "value" :type "Integer"}] :return-type "Void"}]}
                      (fn [atomic value & _]
                        (.set ^AtomicLong (:state atomic) (long value))
                        nil)
    "compare_and_set" ^{:signatures [{:params [{:name "expected" :type "Integer"}
                                                {:name "update" :type "Integer"}]
                                       :return-type "Boolean"}]}
                      (fn [atomic expected update & _]
                        (.compareAndSet ^AtomicLong (:state atomic) (long expected) (long update)))
    "get_and_add"     ^{:signatures [{:params [{:name "delta" :type "Integer"}] :return-type "Integer"}]}
                      (fn [atomic delta & _]
                        (.getAndAdd ^AtomicLong (:state atomic) (long delta)))
    "add_and_get"     ^{:signatures [{:params [{:name "delta" :type "Integer"}] :return-type "Integer"}]}
                      (fn [atomic delta & _]
                        (.addAndGet ^AtomicLong (:state atomic) (long delta)))
    "increment"       ^{:signatures [{:params [] :return-type "Integer"}]}
                      (fn [atomic & _]
                        (.incrementAndGet ^AtomicLong (:state atomic)))
    "decrement"       ^{:signatures [{:params [] :return-type "Integer"}]}
                      (fn [atomic & _]
                        (.decrementAndGet ^AtomicLong (:state atomic)))}

   :Atomic_Integer64
   {"load"            ^{:signatures [{:params [] :return-type "Integer"}]}
                      (fn [atomic & _] (.get ^AtomicLong (:state atomic)))
    "store"           ^{:signatures [{:params [{:name "value" :type "Integer"}] :return-type "Void"}]}
                      (fn [atomic value & _]
                        (.set ^AtomicLong (:state atomic) (long value))
                        nil)
    "compare_and_set" ^{:signatures [{:params [{:name "expected" :type "Integer"}
                                                {:name "update" :type "Integer"}]
                                       :return-type "Boolean"}]}
                      (fn [atomic expected update & _]
                        (.compareAndSet ^AtomicLong (:state atomic) (long expected) (long update)))
    "get_and_add"     ^{:signatures [{:params [{:name "delta" :type "Integer"}] :return-type "Integer"}]}
                      (fn [atomic delta & _]
                        (.getAndAdd ^AtomicLong (:state atomic) (long delta)))
    "add_and_get"     ^{:signatures [{:params [{:name "delta" :type "Integer"}] :return-type "Integer"}]}
                      (fn [atomic delta & _]
                        (.addAndGet ^AtomicLong (:state atomic) (long delta)))
    "increment"       ^{:signatures [{:params [] :return-type "Integer"}]}
                      (fn [atomic & _]
                        (.incrementAndGet ^AtomicLong (:state atomic)))
    "decrement"       ^{:signatures [{:params [] :return-type "Integer"}]}
                      (fn [atomic & _]
                        (.decrementAndGet ^AtomicLong (:state atomic)))}

   :Atomic_Boolean
   {"load"            ^{:signatures [{:params [] :return-type "Boolean"}]}
                      (fn [atomic & _] (.get ^AtomicBoolean (:state atomic)))
    "store"           ^{:signatures [{:params [{:name "value" :type "Boolean"}] :return-type "Void"}]}
                      (fn [atomic value & _]
                        (.set ^AtomicBoolean (:state atomic) (boolean value))
                        nil)
    "compare_and_set" ^{:signatures [{:params [{:name "expected" :type "Boolean"}
                                                {:name "update" :type "Boolean"}]
                                       :return-type "Boolean"}]}
                      (fn [atomic expected update & _]
                        (.compareAndSet ^AtomicBoolean (:state atomic)
                                                (boolean expected)
                                                (boolean update)))}

   :Atomic_Reference
   {"load"            ^{:signatures [{:params [] :return-type {:base-type "T" :detachable true}}]}
                      (fn [atomic & _] (.get ^AtomicReference (:state atomic)))
    "store"           ^{:signatures [{:params [{:name "value" :type {:base-type "T" :detachable true}}] :return-type "Void"}]}
                      (fn [atomic value & _]
                        (.set ^AtomicReference (:state atomic) value)
                        nil)
    "compare_and_set" ^{:signatures [{:params [{:name "expected" :type {:base-type "T" :detachable true}}
                                                {:name "update" :type {:base-type "T" :detachable true}}]
                                       :return-type "Boolean"}]}
                      (fn [atomic expected update & _]
                        (atomic-reference-cas! atomic expected update))}

   :Task
   {"await"    ^{:signatures [{:params [] :return-type "T"}]}
              (fn [t & [timeout]]
                  (let [result (if (some? timeout)
                                 (task-await t timeout)
                                 (task-await t))]
                    (if (= result task-timeout-signal) nil result)))
    "cancel"   ^{:signatures [{:params [] :return-type "Boolean"}]}
              (fn [t & _] (task-cancel t))
    "is_done"  ^{:signatures [{:params [] :return-type "Boolean"}]}
              (fn [t & _] (.isDone ^CompletableFuture (:future t)))
    "is_cancelled" ^{:signatures [{:params [] :return-type "Boolean"}]}
                   (fn [t & _] (task-cancelled? t))}

   :Channel
   {"send"      ^{:signatures [{:params [{:name "value" :type "T"}] :return-type "Void"}]}
               (fn [ch value & [timeout]]
                  (if (some? timeout)
                    (channel-send ch value timeout)
                    (channel-send ch value)))
    "try_send"  ^{:signatures [{:params [{:name "value" :type "T"}] :return-type "Boolean"}]}
               (fn [ch value & _] (channel-try-send ch value))
    "receive"   ^{:signatures [{:params [] :return-type "T"}]}
               (fn [ch & [timeout]]
                  (if (some? timeout)
                    (channel-receive ch timeout)
                    (channel-receive ch)))
    "try_receive" ^{:signatures [{:params [] :return-type {:base-type "T" :detachable true}}]}
                  (fn [ch & _] (channel-try-receive ch))
    "close"     ^{:signatures [{:params [] :return-type "Void"}]}
               (fn [ch & _] (channel-close ch))
    "is_closed" ^{:signatures [{:params [] :return-type "Boolean"}]}
               (fn [ch & _] (:closed? @(:state ch)))
    "capacity"  ^{:signatures [{:params [] :return-type "Integer"}]}
               (fn [ch & _] (:capacity @(:state ch)))
    "size"      ^{:signatures [{:params [] :return-type "Integer"}]}
               (fn [ch & _]
                  (count (:buffer @(:state ch))))}

   :Console
   {"print"        ^{:returns "Void" :signatures [{:params [{:name "msg" :type "String"}] :return-type "Void"}]}
                   (fn [_ msg & _] (nex-console-print (nex-display-value msg)) nil)
    "print_line"   ^{:returns "Void" :signatures [{:params [{:name "msg" :type "String"}] :return-type "Void"}]}
                   (fn [_ msg & _] (nex-console-println (nex-display-value msg)) nil)
    "read_line"    ^{:returns "String"
                     :signatures [{:params [] :return-type "String"}
                                  {:params [{:name "prompt" :type "String"}] :return-type "String"}]}
                   (fn [_ & args] (when (seq args) (nex-console-print (str (first args)))) (nex-console-read-line))
    "error"        ^{:returns "Void" :signatures [{:params [{:name "msg" :type "String"}] :return-type "Void"}]}
                   (fn [_ msg & _] (nex-console-error (nex-display-value msg)) nil)
    "new_line"     ^{:returns "Void" :signatures [{:params [] :return-type "Void"}]}
                   (fn [_ & _] (nex-console-newline) nil)
    "flush"        ^{:returns "Void" :signatures [{:params [] :return-type "Void"}]}
                   (fn [_ & _] (nex-console-flush) nil)
    "read_integer" ^{:returns "Integer" :signatures [{:params [] :return-type "Integer"}]}
                   (fn [_ & _] (nex-parse-integer (nex-console-read-line)))
    "read_real"    ^{:returns "Real" :signatures [{:params [] :return-type "Real"}]}
                   (fn [_ & _] (nex-parse-real (nex-console-read-line)))}

   :Process
   {"getenv"       ^{:returns "String" :signatures [{:params [{:name "name" :type "String"}] :return-type "String"}]}
                   (fn [proc name & _] (or (nex-process-getenv proc (str name)) ""))
    "setenv"       ^{:returns "Void"
                     :signatures [{:params [{:name "name" :type "String"} {:name "value" :type "String"}] :return-type "Void"}]}
                   (fn [proc name value & _] (nex-process-setenv proc (str name) (str value)) nil)
    "command_line" ^{:returns {:base-type "Array" :type-params ["String"]}
                     :signatures [{:params [] :return-type {:base-type "Array" :type-params ["String"]}}]}
                   (fn [proc & _] (nex-process-command-line proc))

    "is_self"      ^{:returns "Boolean" :signatures [{:params [] :return-type "Boolean"}]}
                   (fn [proc & _] (nex-process-is-self proc))
    "is_child"     ^{:returns "Boolean" :signatures [{:params [] :return-type "Boolean"}]}
                   (fn [proc & _] (nex-process-is-child proc))

    "set_working_directory"        ^{:returns "Void" :signatures [{:params [{:name "dir" :type "String"}] :return-type "Void"}]}
                                    (fn [proc dir & _] (nex-process-set-working-directory proc (str dir)))
    "set_redirect_error_to_output" ^{:returns "Void" :signatures [{:params [{:name "flag" :type "Boolean"}] :return-type "Void"}]}
                                    (fn [proc flag & _] (nex-process-set-redirect-error-to-output proc (boolean flag)))

    "start"        ^{:returns "Void" :signatures [{:params [] :return-type "Void"}]}
                   (fn [proc & _] (nex-process-start proc))
    "is_started"   ^{:returns "Boolean" :signatures [{:params [] :return-type "Boolean"}]}
                   (fn [proc & _] (nex-process-is-started proc))
    "is_alive"     ^{:returns "Boolean" :signatures [{:params [] :return-type "Boolean"}]}
                   (fn [proc & _] (nex-process-is-alive proc))
    "pid"          ^{:returns "Integer" :signatures [{:params [] :return-type "Integer"}]}
                   (fn [proc & _] (nex-process-pid proc))

    ;; wait() never actually returns nil (it blocks until exit), but the JVM
    ;; lowering's static return type is keyed by method name only, not arity
    ;; — see builtin-type-method-return-type — so both arities share the
    ;; nullable declaration here. The typechecker itself (typechecker.clj)
    ;; still gives wait() and wait(ms) their precise per-arity types.
    "wait"         ^{:returns {:base-type "Integer" :detachable true}
                     :signatures [{:params [] :return-type "Integer"}
                                  {:params [{:name "timeout_ms" :type "Integer"}]
                                   :return-type {:base-type "Integer" :detachable true}}]}
                   (fn [proc & [timeout]]
                     (if (some? timeout)
                       (nex-process-wait-timeout proc timeout)
                       (nex-process-wait proc)))
    "exit_code"    ^{:returns {:base-type "Integer" :detachable true}
                     :signatures [{:params [] :return-type {:base-type "Integer" :detachable true}}]}
                   (fn [proc & _] (nex-process-exit-code proc))

    "terminate"    ^{:returns "Void" :signatures [{:params [] :return-type "Void"}]}
                   (fn [proc & _] (nex-process-terminate proc))
    "kill"         ^{:returns "Void" :signatures [{:params [] :return-type "Void"}]}
                   (fn [proc & _] (nex-process-kill proc))

    "write"            ^{:returns "Void" :signatures [{:params [{:name "text" :type "String"}] :return-type "Void"}]}
                       (fn [proc text & _] (nex-process-write proc (str text)))
    "write_line"       ^{:returns "Void" :signatures [{:params [{:name "text" :type "String"}] :return-type "Void"}]}
                       (fn [proc text & _] (nex-process-write-line proc (str text)))
    "close_stdin"      ^{:returns "Void" :signatures [{:params [] :return-type "Void"}]}
                       (fn [proc & _] (nex-process-close-stdin proc))
    "read_line"        ^{:returns {:base-type "String" :detachable true}
                         :signatures [{:params [] :return-type {:base-type "String" :detachable true}}]}
                       (fn [proc & _] (nex-process-read-line proc))
    "read_all"         ^{:returns "String" :signatures [{:params [] :return-type "String"}]}
                       (fn [proc & _] (nex-process-read-all proc))
    "read_error_line"  ^{:returns {:base-type "String" :detachable true}
                         :signatures [{:params [] :return-type {:base-type "String" :detachable true}}]}
                       (fn [proc & _] (nex-process-read-error-line proc))
    "read_error_all"   ^{:returns "String" :signatures [{:params [] :return-type "String"}]}
                       (fn [proc & _] (nex-process-read-error-all proc))

    "to_string"    ^{:returns "String" :signatures [{:params [] :return-type "String"}]}
                   (fn [proc & _] (nex-process-to-string proc))}

   :ArrayCursor
   {"start"   (fn [c & _] (reset! (:index c) 0) nil)
    "item"    (fn [c & _]
                (let [arr (:source c)
                      idx @(:index c)]
                  (if (< idx (nex-array-size arr))
                    (nex-array-get arr idx)
                    (throw (ex-info "Cursor is at end" {:index idx})))))
    "next"    (fn [c & _]
                (let [arr (:source c)
                      idx @(:index c)]
                  (when (< idx (nex-array-size arr))
                    (swap! (:index c) inc))
                  nil))
    "at_end"  (fn [c & _]
                (>= @(:index c) (nex-array-size (:source c))))}

   :StringCursor
   {"start"   (fn [c & _] (reset! (:index c) 0) nil)
    "item"    (fn [c & _]
                (let [s (:source c)
                      idx @(:index c)]
                  (if (< idx (count s))
                    (get s idx)
                    (throw (ex-info "Cursor is at end" {:index idx})))))
    "next"    (fn [c & _]
                (let [s (:source c)
                      idx @(:index c)]
                  (when (< idx (count s))
                    (swap! (:index c) inc))
                  nil))
    "at_end"  (fn [c & _]
                (>= @(:index c) (count (:source c))))}

   :MapCursor
   {"start"   (fn [c & _]
                (reset! (:keys c) (nex-map-keys (:source c)))
                (reset! (:index c) 0)
                nil)
    "item"    (fn [c & _]
                (let [ks @(:keys c)
                      idx @(:index c)]
                  (if (< idx (count ks))
                    (let [k (nth ks idx)
                          v (nex-map-get (:source c) k)]
                      (nex-array-from [k v]))
                    (throw (ex-info "Cursor is at end" {:index idx})))))
    "next"    (fn [c & _]
                (let [ks @(:keys c)
                      idx @(:index c)]
                  (when (< idx (count ks))
                    (swap! (:index c) inc))
                  nil))
    "at_end"  (fn [c & _]
                (>= @(:index c) (count @(:keys c))))}

      :SetCursor
   {"start"   (fn [c & _]
                (reset! (:values c) (vec (nex-set-seq (:source c))))
                (reset! (:index c) 0)
                nil)
    "item"    (fn [c & _]
                (let [vals @(:values c)
                      idx @(:index c)]
                  (if (< idx (count vals))
                    (nth vals idx)
                    (throw (ex-info "Cursor is at end" {:index idx})))))
    "next"    (fn [c & _]
                (let [vals @(:values c)
                      idx @(:index c)]
                  (when (< idx (count vals))
                    (swap! (:index c) inc))
                  nil))
    "at_end"  (fn [c & _]
                (>= @(:index c) (count @(:values c))))}}))

(defn builtin-type-method-return-type
  "Static return type for a built-in method, consumed by the compiler's lowering
   pass. The return type is carried as `:returns` metadata on the method fn in
   `builtin-type-methods`, so built-in method names stay defined in a single
   place. The value is either a concrete Nex type or a marker keyword that the
   consumer resolves against the receiver's generic arguments:

   - `:element`          first type argument (`Array`/`Set` element, `Map` key)
   - `:value`            second type argument (`Map` value)
   - `:self`             the receiver collection type itself
   - `:array-of-element` `Array` of the first type argument
   - `:array-of-value`   `Array` of the second type argument"
  [type-name method-name]
  (:returns (meta (get-in builtin-type-methods [type-name method-name]))))

(def get-type-name typeinfo/get-type-name)

(defn call-builtin-method
  "Call a built-in method on a primitive value"
  ([target value method-name args]
   (call-builtin-method nil target value method-name args))
  ([ctx target value method-name args]
   (if-let [method-fn
            (or (when-let [type-name (get-type-name value)]
                  (when-let [methods (get builtin-type-methods type-name)]
                    (get methods method-name)))
                (get-in builtin-type-methods [:Any method-name]))]
     (if (and ctx
              (let [type-name (get-type-name value)]
                (or (and (= type-name :Array)
                         (contains? #{"sort" "contains" "index_of"} method-name))
                    (= type-name :Min_Heap))))
       (apply method-fn value (concat args [ctx]))
       (apply method-fn value args))
     (throw (ex-info (str "Method not found on type: " method-name)
                     {:target target :value value :method method-name})))))

;; ---------------------------------------------------------------------------
;; Free-function builtins (Stage D2). `builtins` maps a builtin function name
;; to (fn [ctx & args]); print, type_is, and http-server handler dispatch go
;; through the engine hooks (:add-output, :is-parent?, :call-object-method).
;; ---------------------------------------------------------------------------

(defn add-output
  "Record one line of program output through the engine hook (the interpreter
   accumulates it on the context; the default writes to the console)."
  [ctx line]
  ((:add-output @engine-hooks) ctx line))

(defn is-parent?
  "Class-hierarchy query through the engine hook."
  [ctx class-name parent-name]
  ((:is-parent? @engine-hooks) ctx class-name parent-name))

(defn print-output-value
  "Convert a value for built-in print/println output.
   Preserve existing formatting for non-objects, but respect user-defined
   to_string implementations on Nex objects."
  [ctx value]
  (if (nex-object? value)
    (concat-string-value ctx value)
    (nex-format-value value)))

(defn runtime-type-name [value]
  (typeinfo/runtime-type-name nex-object? get-type-name value))

(defn runtime-type-is? [ctx target-type value]
  (typeinfo/runtime-type-is? runtime-type-name is-parent? ctx target-type value))

(defn java-http-request
     [method url body timeout-ms]
     (http/java-http-request make-object method url body timeout-ms))

(defn make-http-server-handle
     [port]
     (http/make-http-server-handle port))

(defn start-http-server!
     [ctx handle]
     (http/start-http-server!
      make-object
      (fn [inner-ctx handler request-obj]
        (eval-call inner-ctx handler "call1" [request-obj]))
      ctx
      handle))

(defn resolve-imported-java-class
     "Resolve a Java class name using imports in the context."
     [ctx class-name]
     (let [imports @(:imports ctx)
           match (some (fn [{:keys [qualified-name source]}]
                         (when (and (nil? source)
                                    qualified-name
                                    (= class-name (last (str/split qualified-name #"\."))))
                           qualified-name))
                       imports)
           qualified (or match class-name)]
       (try
         (Class/forName qualified)
         (catch Exception _ nil))))

(defn- class-def-if-exists
  "Nex class-def lookup mirroring nex.interpreter/lookup-class-if-exists,
   duplicated here (rather than required) so this namespace stays free of a
   dependency on nex.interpreter (see the Stage D extraction note above)."
  [ctx class-name]
  (or (get @(:classes ctx) class-name)
      (get @(:specialized-classes ctx) class-name)))

(defn- class-java-interfaces
  "Reflected Class objects for the Java interfaces CLASS-NAME's `inherit`
   chain declares, walking through Nex parents (a Java interface parent is a
   leaf — see docs/proposals/java-interop.md)."
  [ctx class-name]
  (letfn [(walk [cn visited]
            (when-let [class-def (and (not (contains? visited cn))
                                      (class-def-if-exists ctx cn))]
              (let [visited' (conj visited cn)]
                (mapcat (fn [{:keys [parent]}]
                          (if (class-def-if-exists ctx parent)
                            (walk parent visited')
                            (when-let [^Class klass (resolve-imported-java-class ctx parent)]
                              (when (.isInterface klass) [klass]))))
                        (:parents class-def)))))]
    (distinct (walk class-name #{}))))

(defn- coerce-java-return
  "Narrow a Nex return value to the primitive type a Java interface method
   declares, so java.lang.reflect.Proxy's return-value check (which requires
   an exact wrapper match, e.g. Integer for `int`, not any boxed number)
   accepts it."
  [^Class return-type value]
  (cond
    (= return-type Void/TYPE) nil
    (= return-type Integer/TYPE) (int value)
    (= return-type Long/TYPE) (long value)
    (= return-type Double/TYPE) (double value)
    (= return-type Float/TYPE) (float value)
    (= return-type Boolean/TYPE) (boolean value)
    (= return-type Short/TYPE) (short value)
    (= return-type Byte/TYPE) (byte value)
    (= return-type Character/TYPE) (char value)
    :else value))

(defn- java-proxy-for-object
  "Wrap a Nex OBJ that implements one or more Java interfaces (via `inherit`)
   in a java.lang.reflect.Proxy, so it can be handed to a real Java API that
   expects one of them (Runnable, ActionListener, ...). InvocationHandler
   dispatch reuses the ordinary Nex method-call path (eval-call), so calling
   an interface method on the proxy runs exactly like a Nex-side call to the
   same method on the object. equals/hashCode/toString inherited from Object
   (declaring class Object, not the Nex-declared interface) get identity-based
   defaults instead of an eval-call, since the wrapped class is not required
   to define them.

   State mutated by a proxied call must be visible both (a) to the *next*
   proxied call, and (b) to an ordinary Nex read of the same object
   afterwards (`counter.count`) — and NexObject fields are an immutable map,
   propagated by write-back-by-convention (nex.interpreter/write-back-target!)
   rather than a mutable cell. eval-call's dispatch target,
   `{:type :literal :value obj}`, names no slot that convention can write
   back to; write-back-target! now treats that shape as a request to
   propagate the mutation to every *other* alias of the same identity in the
   current env chain instead (env-replace-object-aliases!), which is exactly
   what makes (b) work. (a) still needs a slot of its own to re-read from on
   the next call — a bare Clojure closure over `obj` would keep re-dispatching
   against the pre-call identity forever, since nothing ever tells the
   closure a new one exists. So OBJ is registered here under a private,
   unique binding in the *current* env (the one live when this object
   crossed into a Java call — e.g. inside the `with \"java\"` block that
   called `addActionListener`), and each dispatch re-reads that binding first:
   env-replace-object-aliases! keeps it current the same way it keeps
   `counter` current, since both are just bindings in the same map."
  [ctx obj]
  (let [interfaces (class-java-interfaces ctx (:class-name obj))]
    (if (empty? interfaces)
      obj
      (let [env (:current-env ctx)
            slot (str "__java_proxy_target_" (gensym) "__")
            _ (swap! (:bindings env) assoc slot obj)]
        (java.lang.reflect.Proxy/newProxyInstance
         (.getContextClassLoader (Thread/currentThread))
         (into-array Class interfaces)
         (reify java.lang.reflect.InvocationHandler
           (invoke [_ proxy method args]
             (let [^java.lang.reflect.Method method method
                   method-name (.getName method)
                   arg-values (vec (or args []))
                   current-obj (get @(:bindings env) slot obj)]
               (if (= (.getDeclaringClass method) Object)
                 (case method-name
                   "hashCode" (System/identityHashCode current-obj)
                   "equals" (identical? current-obj (first arg-values))
                   "toString" (str "NexProxy<" (:class-name current-obj) ">")
                   nil)
                 (coerce-java-return (.getReturnType method)
                                     (eval-call ctx current-obj method-name arg-values)))))))))))

(defn java-arg
  "Convert one argument for a reflective Java call: a Nex object implementing
   a Java interface crosses the boundary as a real Proxy for it; everything
   else passes through unchanged."
  [ctx v]
  (if (nex-object? v)
    (java-proxy-for-object ctx v)
    v))

(defn java-args
  "java-arg over a whole argument list — for every interpreter call site that
   marshals arguments into a reflective Java call (static methods, `new`,
   constructors, instance methods alike)."
  [ctx arg-values]
  (mapv #(java-arg ctx %) arg-values))

(defn java-create-object
     "Create a Java object via reflection."
     [ctx class-name arg-values]
     (let [klass (resolve-imported-java-class ctx class-name)]
       (when-not klass
         (throw (ex-info (str "Undefined class: " class-name)
                         {:class-name class-name})))
       (clojure.lang.Reflector/invokeConstructor klass (to-array (java-args ctx arg-values)))))

(defn java-call-method
     "Call a Java method via reflection."
     [ctx target method-name arg-values]
     (clojure.lang.Reflector/invokeInstanceMethod target method-name (to-array (java-args ctx arg-values))))

(def builtins
  {"print"
   (fn [ctx & args]
     (let [output (str/join " " (map #(print-output-value ctx %) args))]
       (add-output ctx output)
       nil))

   "println"
   (fn [ctx & args]
     (let [output (str/join " " (map #(print-output-value ctx %) args))]
       (add-output ctx output)
       nil))

   "type_of"
   (fn [ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "type_of expects exactly 1 argument"
                       {:function "type_of" :expected 1 :actual (count args)})))
     (runtime-type-name (first args)))

   "type_is"
   (fn [ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "type_is expects exactly 2 arguments"
                       {:function "type_is" :expected 2 :actual (count args)})))
     (let [[target-type value] args]
       (runtime-type-is? ctx target-type value)))

   "await_all"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "await_all expects exactly 1 argument"
                       {:function "await_all" :expected 1 :actual (count args)})))
     (let [tasks (first args)]
       (when-not (nex-array? tasks)
         (throw (ex-info "await_all requires an array of tasks"
                         {:function "await_all" :actual-type (runtime-type-name tasks)})))
       (doseq [task tasks]
         (when-not (= (:nex-builtin-type task) :Task)
           (throw (ex-info "await_all requires an array of tasks"
                           {:function "await_all" :actual-type (runtime-type-name task)}))))
       (await-all-tasks tasks)))

   "await_any"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "await_any expects exactly 1 argument"
                       {:function "await_any" :expected 1 :actual (count args)})))
     (let [tasks (first args)]
       (when-not (nex-array? tasks)
         (throw (ex-info "await_any requires an array of tasks"
                         {:function "await_any" :actual-type (runtime-type-name tasks)})))
       (doseq [task tasks]
         (when-not (= (:nex-builtin-type task) :Task)
           (throw (ex-info "await_any requires an array of tasks"
                           {:function "await_any" :actual-type (runtime-type-name task)}))))
       (await-any-task tasks)))

   "sleep"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "sleep expects exactly 1 argument"
                       {:function "sleep" :expected 1 :actual (count args)})))
     (do
               (Thread/sleep (long (first args)))
               nil))

   "hint_spin"
   (fn [_ctx & args]
     (when (not= (count args) 0)
       (throw (ex-info "hint_spin expects exactly 0 arguments"
                       {:function "hint_spin" :expected 0 :actual (count args)})))
     (Thread/onSpinWait)
     nil)

   "exit"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "exit expects exactly 1 argument"
                       {:function "exit" :expected 1 :actual (count args)})))
     (System/exit (int (first args))))

   "random_real"
   (fn [_ctx & args]
     (when (not= (count args) 0)
       (throw (ex-info "random_real expects exactly 0 arguments"
                       {:function "random_real" :expected 0 :actual (count args)})))
     (rand))

   "http_get"
   (fn [_ctx & args]
     (when-not (or (= (count args) 1) (= (count args) 2))
       (throw (ex-info "http_get expects 1 or 2 arguments"
                       {:function "http_get" :expected "1 or 2" :actual (count args)})))
     (let [[url timeout-ms] args]
       (java-http-request "GET" (str url) nil timeout-ms)))

   "http_post"
   (fn [_ctx & args]
     (when-not (or (= (count args) 2) (= (count args) 3))
       (throw (ex-info "http_post expects 2 or 3 arguments"
                       {:function "http_post" :expected "2 or 3" :actual (count args)})))
     (let [[url body timeout-ms] args]
       (java-http-request "POST" (str url) (str body) timeout-ms)))

   "json_parse"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "json_parse expects exactly 1 argument"
                       {:function "json_parse" :expected 1 :actual (count args)})))
     (json-types/nex-json-parse (first args)))

   "json_stringify"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "json_stringify expects exactly 1 argument"
                       {:function "json_stringify" :expected 1 :actual (count args)})))
     (json-types/nex-json-stringify (first args)))

   "regex_validate"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "regex_validate expects exactly 2 arguments" {:function "regex_validate"})))
     (regex-types/regex-validate (first args) (second args)))

   "regex_matches"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "regex_matches expects exactly 3 arguments" {:function "regex_matches"})))
     (apply regex-types/regex-matches? args))

   "regex_find"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "regex_find expects exactly 3 arguments" {:function "regex_find"})))
     (apply regex-types/regex-find args))

   "regex_find_all"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "regex_find_all expects exactly 3 arguments" {:function "regex_find_all"})))
     (apply regex-types/regex-find-all args))

   "regex_replace"
   (fn [_ctx & args]
     (when (not= (count args) 4)
       (throw (ex-info "regex_replace expects exactly 4 arguments" {:function "regex_replace"})))
     (apply regex-types/regex-replace args))

   "regex_split"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "regex_split expects exactly 3 arguments" {:function "regex_split"})))
     (apply regex-types/regex-split args))

   "datetime_now"
   (fn [_ctx & args]
     (when (not= (count args) 0)
       (throw (ex-info "datetime_now expects exactly 0 arguments" {:function "datetime_now"})))
     (dt/datetime-now))

   "datetime_from_epoch_millis"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_from_epoch_millis expects exactly 1 argument" {:function "datetime_from_epoch_millis"})))
     (dt/datetime-from-epoch-millis (first args)))

   "datetime_parse_iso"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_parse_iso expects exactly 1 argument" {:function "datetime_parse_iso"})))
     (dt/datetime-parse-iso (first args)))

   "datetime_make"
   (fn [_ctx & args]
     (when (not= (count args) 6)
       (throw (ex-info "datetime_make expects exactly 6 arguments" {:function "datetime_make"})))
     (apply dt/datetime-make args))

   "datetime_year"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_year expects exactly 1 argument" {:function "datetime_year"})))
     (dt/datetime-year (first args)))

   "datetime_month"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_month expects exactly 1 argument" {:function "datetime_month"})))
     (dt/datetime-month (first args)))

   "datetime_day"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_day expects exactly 1 argument" {:function "datetime_day"})))
     (dt/datetime-day (first args)))

   "datetime_weekday"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_weekday expects exactly 1 argument" {:function "datetime_weekday"})))
     (dt/datetime-weekday (first args)))

   "datetime_day_of_year"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_day_of_year expects exactly 1 argument" {:function "datetime_day_of_year"})))
     (dt/datetime-day-of-year (first args)))

   "datetime_hour"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_hour expects exactly 1 argument" {:function "datetime_hour"})))
     (dt/datetime-hour (first args)))

   "datetime_minute"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_minute expects exactly 1 argument" {:function "datetime_minute"})))
     (dt/datetime-minute (first args)))

   "datetime_second"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_second expects exactly 1 argument" {:function "datetime_second"})))
     (dt/datetime-second (first args)))

   "datetime_epoch_millis"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_epoch_millis expects exactly 1 argument" {:function "datetime_epoch_millis"})))
     (dt/datetime-epoch-millis (first args)))

   "datetime_add_millis"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "datetime_add_millis expects exactly 2 arguments" {:function "datetime_add_millis"})))
     (apply dt/datetime-add-millis args))

   "datetime_diff_millis"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "datetime_diff_millis expects exactly 2 arguments" {:function "datetime_diff_millis"})))
     (apply dt/datetime-diff-millis args))

   "datetime_truncate_to_day"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_truncate_to_day expects exactly 1 argument" {:function "datetime_truncate_to_day"})))
     (dt/datetime-truncate-to-day (first args)))

   "datetime_truncate_to_hour"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_truncate_to_hour expects exactly 1 argument" {:function "datetime_truncate_to_hour"})))
     (dt/datetime-truncate-to-hour (first args)))

   "datetime_format_iso"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "datetime_format_iso expects exactly 1 argument" {:function "datetime_format_iso"})))
     (dt/datetime-format-iso (first args)))

   "path_exists"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_exists expects exactly 1 argument" {:function "path_exists"})))
     (rt/path-exists? (str (first args))))

   "path_is_file"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_is_file expects exactly 1 argument" {:function "path_is_file"})))
     (rt/path-is-file? (str (first args))))

   "path_is_directory"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_is_directory expects exactly 1 argument" {:function "path_is_directory"})))
     (rt/path-is-directory? (str (first args))))

   "path_name"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_name expects exactly 1 argument" {:function "path_name"})))
     (rt/path-name (str (first args))))

   "path_extension"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_extension expects exactly 1 argument" {:function "path_extension"})))
     (rt/path-extension (str (first args))))

   "path_name_without_extension"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_name_without_extension expects exactly 1 argument" {:function "path_name_without_extension"})))
     (rt/path-name-without-extension (str (first args))))

   "path_absolute"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_absolute expects exactly 1 argument" {:function "path_absolute"})))
     (str (rt/path-absolute (str (first args)))))

   "path_normalize"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_normalize expects exactly 1 argument" {:function "path_normalize"})))
     (str (rt/path-normalize (str (first args)))))

   "path_size"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_size expects exactly 1 argument" {:function "path_size"})))
     (rt/path-size (str (first args))))

   "path_modified_time"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_modified_time expects exactly 1 argument" {:function "path_modified_time"})))
     (rt/path-modified-time (str (first args))))

   "path_parent"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_parent expects exactly 1 argument" {:function "path_parent"})))
     (rt/path-parent (str (first args))))

   "path_child"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "path_child expects exactly 2 arguments" {:function "path_child"})))
     (rt/path-child (str (first args)) (str (second args))))

   "path_create_file"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_create_file expects exactly 1 argument" {:function "path_create_file"})))
     (rt/path-create-file (str (first args))))

   "path_create_directory"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_create_directory expects exactly 1 argument" {:function "path_create_directory"})))
     (rt/path-create-directory (str (first args))))

   "path_create_directories"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_create_directories expects exactly 1 argument" {:function "path_create_directories"})))
     (rt/path-create-directories (str (first args))))

   "path_delete"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_delete expects exactly 1 argument" {:function "path_delete"})))
     (rt/path-delete (str (first args))))

   "path_delete_tree"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_delete_tree expects exactly 1 argument" {:function "path_delete_tree"})))
     (rt/path-delete-tree (str (first args))))

   "path_copy"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "path_copy expects exactly 2 arguments" {:function "path_copy"})))
     (rt/path-copy (str (first args)) (str (second args))))

   "path_move"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "path_move expects exactly 2 arguments" {:function "path_move"})))
     (rt/path-move (str (first args)) (str (second args))))

   "path_read_text"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_read_text expects exactly 1 argument" {:function "path_read_text"})))
     (rt/path-read-text (str (first args))))

   "path_write_text"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "path_write_text expects exactly 2 arguments" {:function "path_write_text"})))
     (rt/path-write-text (str (first args)) (str (second args))))

   "path_append_text"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "path_append_text expects exactly 2 arguments" {:function "path_append_text"})))
     (rt/path-append-text (str (first args)) (str (second args))))

   "path_list"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "path_list expects exactly 1 argument" {:function "path_list"})))
     (rt/path-list (str (first args))))

   "text_file_open_read"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "text_file_open_read expects exactly 1 argument" {:function "text_file_open_read"})))
     (rt/text-file-open-read (str (first args))))

   "text_file_open_write"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "text_file_open_write expects exactly 1 argument" {:function "text_file_open_write"})))
     (rt/text-file-open-write (str (first args))))

   "text_file_open_append"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "text_file_open_append expects exactly 1 argument" {:function "text_file_open_append"})))
     (rt/text-file-open-append (str (first args))))

   "text_file_read_line"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "text_file_read_line expects exactly 1 argument" {:function "text_file_read_line"})))
     (rt/text-file-read-line (first args)))

   "text_file_write"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "text_file_write expects exactly 2 arguments" {:function "text_file_write"})))
     (rt/text-file-write (first args) (str (second args))))

   "text_file_close"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "text_file_close expects exactly 1 argument" {:function "text_file_close"})))
     (rt/text-file-close (first args)))

   "binary_file_open_read"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_open_read expects exactly 1 argument" {:function "binary_file_open_read"})))
     (rt/binary-file-open-read (str (first args))))

   "binary_file_open_write"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_open_write expects exactly 1 argument" {:function "binary_file_open_write"})))
     (rt/binary-file-open-write (str (first args))))

   "binary_file_open_append"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_open_append expects exactly 1 argument" {:function "binary_file_open_append"})))
     (rt/binary-file-open-append (str (first args))))

   "binary_file_read_all"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_read_all expects exactly 1 argument" {:function "binary_file_read_all"})))
     (rt/binary-file-read-all (first args)))

   "binary_file_read"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "binary_file_read expects exactly 2 arguments" {:function "binary_file_read"})))
     (rt/binary-file-read (first args) (second args)))

   "binary_file_write"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "binary_file_write expects exactly 2 arguments" {:function "binary_file_write"})))
     (rt/binary-file-write (first args) (second args)))

   "binary_file_position"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_position expects exactly 1 argument" {:function "binary_file_position"})))
     (rt/binary-file-position (first args)))

   "binary_file_seek"
   (fn [_ctx & args]
     (when (not= (count args) 2)
       (throw (ex-info "binary_file_seek expects exactly 2 arguments" {:function "binary_file_seek"})))
     (rt/binary-file-seek (first args) (second args)))

   "binary_file_close"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "binary_file_close expects exactly 1 argument" {:function "binary_file_close"})))
     (rt/binary-file-close (first args)))

   "http_server_create"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "http_server_create expects exactly 1 argument"
                       {:function "http_server_create" :expected 1 :actual (count args)})))
     (make-http-server-handle (int (first args))))

   "http_server_get"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "http_server_get expects exactly 3 arguments"
                       {:function "http_server_get" :expected 3 :actual (count args)})))
     (let [[handle path handler] args]
       (do
                 (swap! (get-in handle [:routes "GET"]) conj {:path-pattern (str path)
                                                              :handler handler})
                 nil)))

   "http_server_post"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "http_server_post expects exactly 3 arguments"
                       {:function "http_server_post" :expected 3 :actual (count args)})))
     (let [[handle path handler] args]
       (do
                 (swap! (get-in handle [:routes "POST"]) conj {:path-pattern (str path)
                                                               :handler handler})
                 nil)))

   "http_server_put"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "http_server_put expects exactly 3 arguments"
                       {:function "http_server_put" :expected 3 :actual (count args)})))
     (let [[handle path handler] args]
       (do
                 (swap! (get-in handle [:routes "PUT"]) conj {:path-pattern (str path)
                                                              :handler handler})
                 nil)))

   "http_server_delete"
   (fn [_ctx & args]
     (when (not= (count args) 3)
       (throw (ex-info "http_server_delete expects exactly 3 arguments"
                       {:function "http_server_delete" :expected 3 :actual (count args)})))
     (let [[handle path handler] args]
       (do
                 (swap! (get-in handle [:routes "DELETE"]) conj {:path-pattern (str path)
                                                                 :handler handler})
                 nil)))

   "http_server_start"
   (fn [ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "http_server_start expects exactly 1 argument"
                       {:function "http_server_start" :expected 1 :actual (count args)})))
     (start-http-server! ctx (first args)))

   "http_server_stop"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "http_server_stop expects exactly 1 argument"
                       {:function "http_server_stop" :expected 1 :actual (count args)})))
     (let [handle (first args)
                   server @(:server handle)]
               (when server
                 (.stop ^com.sun.net.httpserver.HttpServer server 0)
                 (reset! (:server handle) nil))
               nil))

   "http_server_is_running"
   (fn [_ctx & args]
     (when (not= (count args) 1)
       (throw (ex-info "http_server_is_running expects exactly 1 argument"
                       {:function "http_server_is_running" :expected 1 :actual (count args)})))
     (some? @(:server (first args))))

   })
