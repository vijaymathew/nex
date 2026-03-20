(ns user
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.debugger :as dbg]
            [nex.repl :as repl]))

(def default-iterations 25)
(def default-warmup 5)

(defn parse-args
  [args]
  (loop [opts {:iterations default-iterations
               :warmup default-warmup}
         remaining args]
    (if-let [arg (first remaining)]
      (case arg
        "--iterations" (recur (assoc opts :iterations (Long/parseLong (second remaining)))
                              (nnext remaining))
        "--warmup" (recur (assoc opts :warmup (Long/parseLong (second remaining)))
                          (nnext remaining))
        "--help" (assoc opts :help true)
        (throw (ex-info (str "Unknown arg: " arg) {:arg arg})))
      opts)))

(defn usage []
  (println "Usage: clojure -M:test test/scripts/run_compiled_repl_perf.clj [--iterations N] [--warmup N]"))

(defn now-ns [] (System/nanoTime))

(defn ms
  [nanos]
  (/ nanos 1e6))

(defn percentile
  [sorted-samples p]
  (let [n (count sorted-samples)]
    (if (zero? n)
      0.0
      (nth sorted-samples
           (min (dec n)
                (max 0 (int (Math/floor (* p (dec n))))))))))

(defn stats
  [samples]
  (let [sorted (vec (sort samples))
        count' (count samples)
        total (reduce + 0.0 samples)]
    {:count count'
     :min (first sorted)
     :p50 (percentile sorted 0.50)
     :p95 (percentile sorted 0.95)
     :max (last sorted)
     :mean (/ total count')}))

(defn eval*!
  [ctx source]
  (let [output (with-out-str
                 (repl/eval-code ctx source "perf-harness"))]
    (when (str/includes? output "Error:")
      (throw (ex-info "Benchmark eval failed" {:source source
                                               :output output})))
    output))

(defn with-repl-env
  [backend f]
  (binding [repl/*type-checking-enabled* (atom false)
            repl/*repl-var-types* (atom {})
            repl/*repl-backend* (atom backend)
            repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
    (dbg/set-enabled! false)
    (dbg/reset-run-state!)
    (let [ctx (repl/init-repl-context)]
      (f ctx))))

(defn measure-thunk-ms
  [f]
  (let [t0 (now-ns)]
    (f)
    (ms (- (now-ns) t0))))

(defn benchmark-fresh
  [backend source iterations]
  (repeatedly iterations
              (fn []
                (with-repl-env backend
                  (fn [ctx]
                    (measure-thunk-ms (fn []
                                        (eval*! ctx source))))))))

(defn benchmark-steady
  [backend {:keys [setup measure]} warmup iterations]
  (with-repl-env backend
    (fn [ctx]
      (doseq [source setup]
        (eval*! ctx source))
      (dotimes [_ warmup]
        (eval*! ctx measure))
      (vec
       (repeatedly iterations
                   (fn []
                     (measure-thunk-ms (fn []
                                         (eval*! ctx measure)))))))))

(defn benchmark-deopt-cycle
  [backend {:keys [setup cycle]} warmup iterations]
  (with-repl-env backend
    (fn [ctx]
      (doseq [source setup]
        (eval*! ctx source))
      (dotimes [_ warmup]
        (doseq [source cycle]
          (eval*! ctx source)))
      (vec
       (repeatedly iterations
                   (fn []
                     (measure-thunk-ms
                      (fn []
                        (doseq [source cycle]
                          (eval*! ctx source))))))))))

(def workloads
  {:startup
   {:kind :fresh
    :source "let x: Integer := 1"}

   :steady-arithmetic
   {:kind :steady
    :setup ["let x: Integer := 40"
            "let y: Integer := 2"]
    :measure "x + y"}

   :steady-function-call
   {:kind :steady
    :setup ["function inc(n: Integer): Integer
do
  result := n + 1
end"]
    :measure "inc(41)"}

   :steady-object-method
   {:kind :steady
    :setup ["class Counter
feature
  value: Integer

  read_plus(n: Integer): Integer
  do
    result := value + n
  end
end"
            "let c: Counter := create Counter"
            "c.value := 41"]
    :measure "c.read_plus(1)"}

   :deopt-reopt-cycle
   {:kind :cycle
    :setup ["let x: Integer := 40"]
    :cycle ["with \"javascript\" do
  print(1)
end"
            "x + 2"]}})

(def thresholds
  {:startup
   {:p50 {:ratio-max 12.0 :abs-extra-ms 250.0 :abs-max-ms 750.0}
    :p95 {:ratio-max 15.0 :abs-extra-ms 400.0 :abs-max-ms 1000.0}}
   :steady
   {:p50 {:ratio-max 2.0 :abs-extra-ms 5.0}
    :p95 {:ratio-max 3.0 :abs-extra-ms 10.0}}
   :deopt
   {:p50 {:ratio-max 3.0 :abs-extra-ms 15.0}
    :p95 {:ratio-max 4.0 :abs-extra-ms 30.0}}})

(defn threshold-limit
  [baseline {:keys [ratio-max abs-extra-ms abs-max-ms]}]
  (cond-> (max (* baseline ratio-max)
               (+ baseline abs-extra-ms))
    abs-max-ms (min abs-max-ms)))

(defn threshold-check
  [label compiled interpreter class]
  (let [cfg (get thresholds class)
        p50-limit (threshold-limit (:p50 interpreter) (:p50 cfg))
        p95-limit (threshold-limit (:p95 interpreter) (:p95 cfg))
        pass? (and (<= (:p50 compiled) p50-limit)
                   (<= (:p95 compiled) p95-limit))]
    {:label label
     :class class
     :pass? pass?
     :compiled {:p50 (:p50 compiled) :p95 (:p95 compiled)}
     :interpreter {:p50 (:p50 interpreter) :p95 (:p95 interpreter)}
     :limits {:p50 p50-limit :p95 p95-limit}}))

(defn render-stats-table
  [results]
  (println)
  (println "Workload                     Backend       p50 ms   p95 ms   mean ms")
  (println "---------------------------  ------------  -------  -------  -------")
  (doseq [[workload backend stats] results]
    (println (format "%-27s  %-12s  %7.3f  %7.3f  %7.3f"
                     (name workload)
                     (name backend)
                     (:p50 stats)
                     (:p95 stats)
                     (:mean stats)))))

(defn render-thresholds
  [checks]
  (println)
  (println "Threshold checks")
  (println "----------------")
  (doseq [{:keys [label pass? compiled interpreter limits]} checks]
    (println (format "%-24s %s" label (if pass? "PASS" "WARN")))
    (println (format "  compiled    p50=%7.3f ms  p95=%7.3f ms" (:p50 compiled) (:p95 compiled)))
    (println (format "  interpreter p50=%7.3f ms  p95=%7.3f ms" (:p50 interpreter) (:p95 interpreter)))
    (println (format "  limits      p50<=%7.3f ms  p95<=%7.3f ms" (:p50 limits) (:p95 limits)))))

(defn run-workload
  [backend {:keys [kind] :as workload} warmup iterations]
  (stats
   (vec
    (case kind
      :fresh (benchmark-fresh backend (:source workload) iterations)
      :steady (benchmark-steady backend workload warmup iterations)
      :cycle (benchmark-deopt-cycle backend workload warmup iterations)))))

(defn -main
  [& raw-args]
  (let [{:keys [iterations warmup help] :as opts} (parse-args raw-args)]
    (when help
      (usage)
      (System/exit 0))
    (println "Compiled REPL performance harness")
    (println (format "iterations=%d warmup=%d" iterations warmup))
    (let [results (into {}
                        (for [[workload-name workload] workloads]
                          [workload-name
                           {:interpreter (run-workload :interpreter workload warmup iterations)
                            :compiled (run-workload :compiled workload warmup iterations)}]))
          flat-results (mapcat (fn [[workload-name {:keys [interpreter compiled]}]]
                                 [[workload-name :interpreter interpreter]
                                  [workload-name :compiled compiled]])
                               results)
          checks [(threshold-check "startup overhead"
                                   (get-in results [:startup :compiled])
                                   (get-in results [:startup :interpreter])
                                   :startup)
                  (threshold-check "steady arithmetic"
                                   (get-in results [:steady-arithmetic :compiled])
                                   (get-in results [:steady-arithmetic :interpreter])
                                   :steady)
                  (threshold-check "steady function"
                                   (get-in results [:steady-function-call :compiled])
                                   (get-in results [:steady-function-call :interpreter])
                                   :steady)
                  (threshold-check "steady object method"
                                   (get-in results [:steady-object-method :compiled])
                                   (get-in results [:steady-object-method :interpreter])
                                   :steady)
                  (threshold-check "deopt/reopt cycle"
                                   (get-in results [:deopt-reopt-cycle :compiled])
                                   (get-in results [:deopt-reopt-cycle :interpreter])
                                   :deopt)]
          failures (remove :pass? checks)]
      (render-stats-table flat-results)
      (render-thresholds checks)
      (println)
      (println "Raw stats")
      (println "---------")
      (pprint results)
      (when (seq failures)
        (println)
        (println (format "Performance thresholds failed for %d workload(s)." (count failures)))
        (System/exit 1)))))

(apply -main *command-line-args*)
