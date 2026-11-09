(ns nex.types.concurrency
  "Tasks and channels at the value level: the record representations and the
   blocking/async operations on them, shared by the interpreter and the
   compiled backends. Extracted from nex.interpreter (backend-alignment
   Stage D); scheduling (spawn) stays with each engine."
  (:require [nex.types.runtime :as rt])
  (:import [java.util.concurrent CompletableFuture ExecutionException ExecutorService Executors
            ThreadFactory TimeUnit TimeoutException CancellationException]
           [java.util.concurrent.atomic AtomicLong]))

(def nex-array-from rt/nex-array-from)

(def channel-closed-signal ::channel-closed)

(def channel-timeout-signal ::channel-timeout)

(def task-timeout-signal ::task-timeout)

(defn current-time-ms []
  (System/currentTimeMillis))

(defn timeout-ms
  [v]
  (let [n (cond
            (integer? v) v
            (number? v) (long v)
            :else nil)]
    (when (or (nil? n) (neg? n))
      (throw (ex-info "Timeout must be a non-negative Integer" {:timeout v})))
    n))

(defn queue-empty [] clojure.lang.PersistentQueue/EMPTY)

(defn queue-conj [q x]
  (conj (or q (queue-empty)) x))

(defn queue-pop [q]
  [(peek q) (pop q)])

(defn- daemon-thread-pool
  "Unbounded pool of daemon threads, so tasks still running when the program
   ends don't keep the JVM alive."
  ^ExecutorService []
  (let [n (AtomicLong.)]
    (Executors/newCachedThreadPool
     (reify ThreadFactory
       (newThread [_ r]
         (doto (Thread. ^Runnable r (str "nex-task-" (.incrementAndGet n)))
           (.setDaemon true)))))))

(defn- virtual-thread-executor
  "One virtual thread per task (Java 21+), or nil on an older JVM. Looked up
   reflectively because Nex still runs on Java 17."
  ^ExecutorService []
  (try
    (.invoke (.getMethod Executors "newVirtualThreadPerTaskExecutor" (make-array Class 0))
             nil (object-array 0))
    (catch Exception _ nil)))

(defonce ^{:doc "Executor that runs spawned tasks. Tasks often block (channels, sleep, IO),
   so they must not share the JVM's common pool: it has about one thread per
   core, and a few blocked tasks there stall every other task."}
  task-executor
  (delay (or (virtual-thread-executor) (daemon-thread-pool))))

(defn run-task-async
  "A CompletableFuture running F (a no-arg fn) on the task executor."
  ^CompletableFuture [f]
  (CompletableFuture/supplyAsync
   (reify java.util.function.Supplier
     (get [_] (f)))
   ^ExecutorService @task-executor))

(defn make-task [future]
  {:nex-builtin-type :Task
   :future future})

;; Promise helpers for the cljs Task/await machinery (make-task, await-all-tasks).

(defn make-channel
  ([] (make-channel 0))
  ([capacity]
   {:nex-builtin-type :Channel
    :lock (Object.)
    :state (atom {:closed? false
                  :capacity capacity
                  :buffer (queue-empty)
                  :senders (queue-empty)
                  :receivers (queue-empty)})}))

(defn task-await
  ([task]
   (task-await task nil))
  ([task timeout]
   (try
     (if (nil? timeout)
       (.get ^CompletableFuture (:future task))
       (.get ^CompletableFuture (:future task) (timeout-ms timeout) TimeUnit/MILLISECONDS))
     (catch TimeoutException _ task-timeout-signal)
     (catch CancellationException _
       (throw (ex-info "Task cancelled" {:task task})))
     (catch ExecutionException e
       (throw (or (.getCause e) e)))
     (catch InterruptedException e
       (.interrupt (Thread/currentThread))
       (throw e)))))

(defn task-done? [task]
  (.isDone ^CompletableFuture (:future task)))

(defn await-all-tasks
  [tasks]
  (nex-array-from (map task-await tasks)))

(defn await-any-task
  [tasks]
  (when (empty? tasks)
    (throw (ex-info "await_any requires at least one task" {})))
  (loop []
    (if-let [ready-task (some #(when (task-done? %) %) tasks)]
      (task-await ready-task)
      (do
        (Thread/sleep 1)
        (recur)))))

(defn task-cancel [task]
  (.cancel ^CompletableFuture (:future task) true))

(defn task-cancelled? [task]
  (.isCancelled ^CompletableFuture (:future task)))

(defn queue-remove-first
  [q pred]
  (reduce (fn [acc item]
            (let [{:keys [removed out]} acc]
              (if (and (not removed) (pred item))
                {:removed true :out out}
                {:removed removed :out (queue-conj out item)})))
          {:removed false :out (queue-empty)}
          q))

(defn channel-send
  ([ch value]
   (channel-send ch value nil))
  ([ch value timeout]
   (let [ack (promise)
         timed? (some? timeout)
         deliver-now
         (locking (:lock ch)
           (let [{:keys [closed? receivers capacity buffer]} @(:state ch)]
             (when closed?
               (throw (ex-info "Cannot send on a closed channel" {:channel ch :builtin-kind "Channel_Closed"})))
             (cond
               (seq receivers)
               (let [[receiver rest-receivers] (queue-pop receivers)]
                 (swap! (:state ch) assoc :receivers rest-receivers)
                 [:receiver receiver])
               (< (count buffer) capacity)
               (do
                 (swap! (:state ch) update :buffer queue-conj value)
                 [:buffered])
               :else
               (do
                 (swap! (:state ch) update :senders queue-conj {:value value :ack ack})
                 [:wait ack]))))]
     (case (first deliver-now)
       :buffered (if timed? true nil)
       :receiver (do (deliver (second deliver-now) value) (if timed? true nil))
       :wait (let [result (if timed?
                            (deref (second deliver-now) (timeout-ms timeout) channel-timeout-signal)
                            @(second deliver-now))]
               (cond
                 (= result channel-closed-signal)
                 (throw (ex-info "Cannot send on a closed channel" {:channel ch :builtin-kind "Channel_Closed"}))

                 (= result channel-timeout-signal)
                 (do
                   (locking (:lock ch)
                     (swap! (:state ch) update :senders
                            (fn [q] (:out (queue-remove-first q #(identical? (:ack %) ack))))))
                   false)

                 :else (if timed? true nil)))))))

(defn channel-try-send [ch value]
  (locking (:lock ch)
    (let [{:keys [closed? receivers capacity buffer]} @(:state ch)]
      (when closed?
        (throw (ex-info "Cannot send on a closed channel" {:channel ch :builtin-kind "Channel_Closed"})))
      (cond
        (seq receivers)
        (let [[receiver rest-receivers] (queue-pop receivers)]
          (swap! (:state ch) assoc :receivers rest-receivers)
          (deliver receiver value)
          true)

        (< (count buffer) capacity)
        (do
          (swap! (:state ch) update :buffer queue-conj value)
          true)

        :else false))))

(defn channel-receive
  ([ch]
   (channel-receive ch nil))
  ([ch timeout]
   (let [out (promise)
         timed? (some? timeout)
         ready
         (locking (:lock ch)
           (let [{:keys [closed? senders buffer capacity]} @(:state ch)]
             (cond
               (seq buffer)
               (let [[value rest-buffer] (queue-pop buffer)
                     promote (when (and (pos? capacity) (seq senders))
                               (let [[sender rest-senders] (queue-pop senders)]
                                 (swap! (:state ch) assoc
                                        :senders rest-senders
                                        :buffer (queue-conj rest-buffer (:value sender)))
                                 sender))]
                 (when-not promote
                   (swap! (:state ch) assoc :buffer rest-buffer))
                 [:buffer value promote])
               (seq senders)
               (let [[sender rest-senders] (queue-pop senders)]
                 (swap! (:state ch) assoc :senders rest-senders)
                 [:sender sender])
               closed?
               [:closed]
               :else
               (do
                 (swap! (:state ch) update :receivers queue-conj out)
                 [:wait out]))))]
     (case (first ready)
       :buffer (let [[_ value promoted] ready]
                 (when promoted
                   (deliver (:ack promoted) true))
                 value)
       :sender (let [{:keys [value ack]} (second ready)]
                 (deliver ack true)
                 value)
       :closed (throw (ex-info "Cannot receive from a closed channel" {:channel ch :builtin-kind "Channel_Closed"}))
       :wait (let [result (if timed?
                            (deref (second ready) (timeout-ms timeout) channel-timeout-signal)
                            @(second ready))]
               (cond
                 (= result channel-closed-signal)
                 (throw (ex-info "Cannot receive from a closed channel" {:channel ch :builtin-kind "Channel_Closed"}))

                 (= result channel-timeout-signal)
                 (do
                   (locking (:lock ch)
                     (swap! (:state ch) update :receivers
                            (fn [q] (:out (queue-remove-first q #(identical? % out))))))
                   nil)

                 :else result))))))

(defn channel-try-receive [ch]
  (locking (:lock ch)
    (let [{:keys [senders buffer capacity]} @(:state ch)]
      (cond
        (seq buffer)
        (let [[value rest-buffer] (queue-pop buffer)
              promoted (when (and (pos? capacity) (seq senders))
                         (let [[sender rest-senders] (queue-pop senders)]
                           (swap! (:state ch) assoc
                                  :senders rest-senders
                                  :buffer (queue-conj rest-buffer (:value sender)))
                           sender))]
          (when-not promoted
            (swap! (:state ch) assoc :buffer rest-buffer))
          (when promoted
            (deliver (:ack promoted) true))
          value)

        (seq senders)
        (let [[sender rest-senders] (queue-pop senders)]
          (swap! (:state ch) assoc :senders rest-senders)
          (deliver (:ack sender) true)
          (:value sender))

        :else nil))))

(defn channel-close [ch]
  (locking (:lock ch)
    (let [{:keys [closed? senders receivers buffer]} @(:state ch)]
      (when-not closed?
        (swap! (:state ch) assoc :closed? true :senders (queue-empty)
               :receivers (if (seq buffer) receivers (queue-empty)))
        (doseq [{:keys [ack]} senders]
          (deliver ack channel-closed-signal))
        (when-not (seq buffer)
          (doseq [receiver receivers]
            (deliver receiver channel-closed-signal)))))
    nil))

;;
;; Forward declarations
;;
