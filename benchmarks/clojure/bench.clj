;; The benchmarks in Clojure, one per name: `clojure -M bench.clj <name>`.
;; Each does the same work as benchmarks/nex/<name>.nex and prints the same
;; result, then the work's own time in microseconds. Written as
;; performance-minded Clojure usually is: type hints, loop/recur on primitive
;; longs, protocols and records for method calls, a transient map. Arithmetic
;; is checked for overflow, as Nex's is.
(set! *warn-on-reflection* true)

(defn fib ^long [^long n]
  (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))

(defn add ^long [^long a ^long b] (+ a b))

(defprotocol Step (step [this x]))
(defrecord Counter [^long bias]
  Step (step [_ x] (+ (long x) bias)))

(defprotocol Shape (value [this x]))
(defrecord S1 [] Shape (value [_ x] (+ (long x) 1)))
(defrecord S2 [] Shape (value [_ x] (+ (long x) 2)))
(defrecord S3 [] Shape (value [_ x] (+ (long x) 3)))
(defrecord S4 [] Shape (value [_ x] (+ (long x) 4)))

(defn randoms [^long n]
  (loop [i 0 seed 12345 xs (transient [])]
    (if (< i n)
      (let [seed (rem (+ (* seed 1103515245) 12345) 2147483648)]
        (recur (inc i) seed (conj! xs seed)))
      (persistent! xs))))

(defn sum-mod ^long [xs]
  (reduce (fn [^long total ^long y] (rem (+ total y) 1000003)) 0 xs))

(defn poly ^long [shapes ^long n]
  (let [^clojure.lang.IPersistentVector shapes shapes
        k (count shapes)]
    (loop [i 0 total 0]
      (if (< i n)
        (recur (inc i) (rem (+ total (long (value (nth shapes (rem i k)) i))) 1000003))
        total))))

(defn run ^long [name]
  (case name
    "startup" 0
    "fib" (fib 35)
    "loop" (loop [i 0 acc 0]
             (if (< i 50000000) (recur (inc i) (rem (+ acc (* i i)) 1000003)) acc))
    "sort" (sum-mod (sort (fn [a b] (compare a b)) (randoms 2000000)))
    "sort_natural" (sum-mod (sort (randoms 2000000)))
    "map" (let [n 500000
                m (persistent! (reduce (fn [m i] (assoc! m (str "k" i) i)) (transient {}) (range n)))]
            (loop [i 0 total 0]
              (if (< i n) (recur (inc i) (rem (+ total (long (get m (str "k" i)))) 1000003)) total)))
    "concat" (loop [i 0 total 0]
               (if (< i 5000000) (recur (inc i) (+ total (long (.length ^String (str "k" i))))) total))
    "call_function" (loop [i 0 total 0]
                      (if (< i 20000000) (recur (inc i) (rem (add total i) 1000003)) total))
    "call_method" (let [c (->Counter 3)]
                    (loop [i 0 total 0]
                      (if (< i 20000000) (recur (inc i) (rem (+ total (long (step c i))) 1000003)) total)))
    "call_poly2" (poly [(->S1) (->S2)] 20000000)
    "call_poly4" (poly [(->S1) (->S2) (->S3) (->S4)] 20000000)
    "char_at" (let [^String s (apply str (repeat 1000 "abcdefghi"))
                    n (.length s)]
                (loop [p 0 total 0]
                  (if (< p 10000)
                    (recur (inc p) (long (loop [i 0 total total]
                                           (if (< i n) (recur (inc i) (+ total (int (.charAt s i)))) total))))
                    total)))
    ;; Java has no constant-time indexing by code point, so take the code points once.
    "char_at_wide" (let [^String s (apply str (repeat 1000 "abcdefgh\uD83D\uDE00"))
                         ^ints cps (.toArray ^java.util.stream.IntStream (.codePoints s))
                         n (alength cps)]
                     (loop [p 0 total 0]
                       (if (< p 10000)
                         (recur (inc p) (long (loop [i 0 total total]
                                                (if (< i n) (recur (inc i) (+ total (aget cps i))) total))))
                         total)))
    "call_closure" (let [f (fn ^long [^long x] (+ x 1))]
                     (loop [i 0 total 0]
                       (if (< i 20000000) (recur (inc i) (rem (+ total (long (f i))) 1000003)) total)))))

(let [t0 (System/nanoTime)
      r (run (first *command-line-args*))
      t1 (System/nanoTime)]
  (println r)
  (println (quot (- t1 t0) 1000)))
