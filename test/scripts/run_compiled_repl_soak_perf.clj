(ns user
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.debugger :as dbg]
            [nex.interpreter :as interp]
            [nex.repl :as repl]))

(def default-iterations 8)
(def default-warmup 2)

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
  (println "Usage: clojure -M:test test/scripts/run_compiled_repl_soak_perf.clj [--iterations N] [--warmup N]"))

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

(defn delete-tree!
  [root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (.delete f)))

(defn eval*!
  [ctx {:keys [code load-path source-id]}]
  (let [output (with-out-str
                 (if load-path
                   (repl/load-file-into-repl ctx load-path)
                   (repl/eval-code ctx code (or source-id "perf-soak"))))]
    (when (str/includes? output "Error:")
      (throw (ex-info "Benchmark eval failed"
                      {:step {:code code :load-path load-path :source-id source-id}
                       :output output})))
    output))

(defn with-repl-env
  [backend f]
  (binding [repl/*type-checking-enabled* (atom true)
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

(defn run-session!
  [backend steps]
  (with-repl-env backend
    (fn [ctx]
      (doseq [step steps]
        (eval*! ctx step)))))

(defn benchmark-session
  [backend {:keys [prepare cleanup steps-fn]} warmup iterations]
  (let [env (if prepare (prepare) {})]
    (try
      (let [steps (steps-fn env)]
        (dotimes [_ warmup]
          (run-session! backend steps))
        (vec
         (repeatedly iterations
                     (fn []
                       (measure-thunk-ms
                        (fn []
                          (run-session! backend steps)))))))
      (finally
        (when cleanup
          (cleanup env))))))

(defn make-temp-dir
  [prefix]
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str prefix "-" (System/nanoTime)))
    (.mkdirs)))

(def workloads
  {:mixed-progressive
   {:class :soak
    :prepare (fn [] {})
    :steps-fn (fn [_]
                [{:code "let x: Integer := 10"}
                 {:code "let y: Real := 20.5"}
                 {:code "x + y"}
                 {:code "let inc := fn (n: Integer): Integer do
  result := n + 1
end"}
                 {:code "inc(41)"}
                 {:code "function choose(flag: Boolean): String
do
  if flag then
    result := \"yes\"
  else
    result := \"no\"
  end
end"}
                 {:code "choose(false)"}
                 {:code "intern io/Path"}
                 {:code (str "let root: Path := create Path.make(\""
                             (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir")))
                             "\")")}
                 {:code "class Point
create
  make(px, py: Integer) do
    this.x := px
    this.y := py
  end
feature
  x: Integer
  y: Integer

  move(dx, dy: Integer)
  do
    x := x + dx
    y := y + dy
  end
end"}
                 {:code "let pt: Point := create Point.make(1, 2)"}
                 {:code "pt.move(3, 4)"}
                 {:code "pt.x + pt.y"}])}

   :load-object-deopt
   {:class :module-soak
    :prepare (fn []
               (let [tmp-dir (make-temp-dir "nex-perf-load-object")
                     counter-file (io/file tmp-dir "Counter.nex")
                     main-file (io/file tmp-dir "main.nex")]
                 (spit counter-file "class Counter
create
  make(v: Integer) do
    this.value := v
  end
feature
  value: Integer

  add(delta: Integer)
  do
    value := value + delta
  end
end")
                 (spit main-file "intern Counter")
                 {:tmp-dir tmp-dir
                  :main-file main-file}))
    :cleanup (fn [{:keys [tmp-dir]}]
               (when (and tmp-dir (.exists tmp-dir))
                 (delete-tree! tmp-dir)))
    :steps-fn (fn [{:keys [tmp-dir main-file]}]
                [{:load-path (.getPath main-file)}
                 {:code "let c: Counter := create Counter.make(1)"}
                 {:code "do
  c.add(1)
end"}
                 {:code "c.value"}
                 {:code "intern io/Path"}
                 {:code (str "let object_root: Path := create Path.make(\""
                             (.getAbsolutePath tmp-dir)
                             "\")")}
                 {:code "do
  c.add(3)
end"}
                 {:code "c.value"}
                 {:code "let delta: Integer := 2"}
                 {:code "do
  c.add(delta)
end"}
                 {:code "c.value"}])}

   :import-intern-deopt
   {:class :module-soak
    :prepare (fn []
               (let [tmp-dir (make-temp-dir "nex-perf-import-intern")
                     counter-file (io/file tmp-dir "Counter.nex")]
                 (spit counter-file "class Counter
create
  make(v: Integer) do
    this.value := v
  end
feature
  value: Integer

  add(delta: Integer)
  do
    value := value + delta
  end
end")
                 {:tmp-dir tmp-dir
                  :counter-file counter-file}))
    :cleanup (fn [{:keys [tmp-dir]}]
               (when (and tmp-dir (.exists tmp-dir))
                 (delete-tree! tmp-dir)))
    :steps-fn (fn [{:keys [tmp-dir counter-file]}]
                [{:code "import java.lang.StringBuilder"}
                 {:code "intern Counter"
                  :source-id (.getPath counter-file)}
                 {:code "let sb: StringBuilder := create StringBuilder"}
                 {:code "do
  sb.append(\"ab\")
end"}
                 {:code "let c: Counter := create Counter.make(1)"}
                 {:code "do
  c.add(2)
end"}
                 {:code "sb.length()"}
                 {:code "c.value"}
                 {:code "intern io/Path"}
                 {:code (str "let import_root: Path := create Path.make(\""
                             (.getAbsolutePath tmp-dir)
                             "\")")}
                 {:code "do
  sb.append(\"c\")
end"}
                 {:code "do
  c.add(3)
end"}
                 {:code "sb.length()"}
                 {:code "c.value"}])}

   :load-closure-concurrency
   {:class :module-soak
    :prepare (fn []
               (let [tmp-dir (make-temp-dir "nex-perf-load-closure")
                     counter-file (io/file tmp-dir "Counter.nex")
                     main-file (io/file tmp-dir "main.nex")]
                 (spit counter-file "class Counter
create
  make(v: Integer) do
    this.value := v
  end
feature
  value: Integer

  add(delta: Integer)
  do
    value := value + delta
  end
end")
                 (spit main-file "intern Counter

function loaded_add(n: Integer): Integer
do
  result := n + 10
end")
                 {:tmp-dir tmp-dir
                  :main-file main-file}))
    :cleanup (fn [{:keys [tmp-dir]}]
               (when (and tmp-dir (.exists tmp-dir))
                 (delete-tree! tmp-dir)))
    :steps-fn (fn [{:keys [tmp-dir main-file]}]
                [{:load-path (.getPath main-file)}
                 {:code "let add_base: Function := fn (n: Integer): Integer do
  result := loaded_add(n) + 5
end"}
                 {:code "add_base(1)"}
                 {:code "let ch: Channel[Integer] := create Channel.with_capacity(1)"}
                 {:code "ch.send(add_base(2))"}
                 {:code "select
  when ch.receive() as msg then
    print(msg)
  timeout 100 then
    print(0)
end"}
                 {:code "let worker: Task[Integer] := spawn do
  result := loaded_add(8)
end"}
                 {:code "await_any([worker])"}
                 {:code "intern io/Path"}
                 {:code (str "let mixed_root: Path := create Path.make(\""
                             (.getAbsolutePath tmp-dir)
                             "\")")}
                 {:code "let c: Counter := create Counter.make(1)"}
                 {:code "add_base(4)"}
                 {:code "do
  c.add(add_base(3))
end"}
                 {:code "c.value"}
                 {:code "type_of(worker)"}])}})

(def thresholds
  {:soak
   {:p50 {:ratio-max 3.0 :abs-extra-ms 40.0}
    :p95 {:ratio-max 4.0 :abs-extra-ms 80.0}}
   :module-soak
   {:p50 {:ratio-max 4.0 :abs-extra-ms 75.0}
    :p95 {:ratio-max 6.0 :abs-extra-ms 150.0}}})

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
  (println "Scenario                     Backend       p50 ms   p95 ms   mean ms")
  (println "---------------------------  ------------  -------  -------  -------")
  (doseq [[scenario backend stats] results]
    (println (format "%-27s  %-12s  %7.3f  %7.3f  %7.3f"
                     (name scenario)
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
  [backend workload warmup iterations]
  (stats (benchmark-session backend workload warmup iterations)))

(defn -main
  [& raw-args]
  (let [{:keys [iterations warmup help]} (parse-args raw-args)]
    (when help
      (usage)
      (System/exit 0))
    (try
      (println "Compiled REPL soak performance harness")
      (println (format "iterations=%d warmup=%d" iterations warmup))
      (let [results (into {}
                          (for [[scenario-name workload] workloads]
                            [scenario-name
                             {:class (:class workload)
                              :interpreter (run-workload :interpreter workload warmup iterations)
                              :compiled (run-workload :compiled workload warmup iterations)}]))
            flat-results (mapcat (fn [[scenario-name {:keys [interpreter compiled]}]]
                                   [[scenario-name :interpreter interpreter]
                                    [scenario-name :compiled compiled]])
                                 results)
            checks (mapv (fn [[scenario-name {:keys [class interpreter compiled]}]]
                           (threshold-check (name scenario-name) compiled interpreter class))
                         results)
            failures (remove :pass? checks)]
        (render-stats-table flat-results)
        (render-thresholds checks)
        (println)
        (println "Raw stats")
        (println "---------")
        (pprint results)
        (when (seq failures)
          (println)
          (println (format "Performance thresholds failed for %d scenario(s)." (count failures)))
          (System/exit 1)))
      (finally
        (interp/shutdown-runtime!)))))

(apply -main *command-line-args*)
