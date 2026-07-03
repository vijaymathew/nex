(ns nex.types.concurrency
  "Tasks and channels at the value level: the record representations and the
   blocking/async operations on them, shared by the interpreter and the
   compiled backends. Extracted from nex.interpreter (backend-alignment
   Stage D); scheduling (spawn) stays with each engine."
  (:require [nex.types.runtime :as rt])
  #?(:clj (:import [java.util.concurrent CompletableFuture ExecutionException TimeUnit TimeoutException CancellationException])))

(def nex-array-from rt/nex-array-from)

#?(:clj
   (def channel-closed-signal ::channel-closed))

#?(:cljs
   (def channel-closed-signal ::channel-closed))

(def channel-timeout-signal ::channel-timeout)

(def task-timeout-signal ::task-timeout)

(defn current-time-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn timeout-ms
  [v]
  (let [n (cond
            (integer? v) v
            (number? v) (long v)
            :else nil)]
    (when (or (nil? n) (neg? n))
      (throw (ex-info "Timeout must be a non-negative Integer" {:timeout v})))
    n))

#?(:clj
   (defn queue-empty [] clojure.lang.PersistentQueue/EMPTY))

#?(:clj
   (defn queue-conj [q x]
     (conj (or q (queue-empty)) x)))

#?(:clj
   (defn queue-pop [q]
     [(peek q) (pop q)]))

#?(:clj
   (defn make-task [future]
     {:nex-builtin-type :Task
      :future future}))

;; Promise helpers for the cljs Task/await machinery (make-task, await-all-tasks).
#?(:cljs
   (defn promise? [v]
     (instance? js/Promise v)))

#?(:cljs
   (defn ->promise [v]
     (if (promise? v) v (js/Promise.resolve v))))

#?(:cljs
   (defn promise-all [values]
     (.then (js/Promise.all (to-array (map ->promise values)))
            (fn [arr] (vec (array-seq arr))))))

#?(:cljs
   (defn make-task [promise]
     (let [done? (atom false)
           cancelled? (atom false)
           cancel-reject (atom nil)
           cancel-promise (js/Promise.
                           (fn [_resolve reject]
                             (reset! cancel-reject reject)))
           wrapped (.then (.race js/Promise (to-array [(->promise promise) cancel-promise]))
                          (fn [value]
                            (reset! done? true)
                            value)
                          (fn [err]
                            (reset! done? true)
                            (js/Promise.reject err)))]
       {:nex-builtin-type :Task
        :promise wrapped
        :done? done?
        :cancelled? cancelled?
        :cancel! (fn []
                   (if @done?
                     false
                     (do
                       (reset! cancelled? true)
                       (reset! done? true)
                       (when-let [reject @cancel-reject]
                         (reject (ex-info "Task cancelled" {:task :cancelled})))
                       true)))})))

#?(:clj
   (defn make-channel
     ([] (make-channel 0))
     ([capacity]
     {:nex-builtin-type :Channel
      :lock (Object.)
      :state (atom {:closed? false
                    :capacity capacity
                    :buffer (queue-empty)
                    :senders (queue-empty)
                    :receivers (queue-empty)})})))

#?(:cljs
   (defn make-channel
     ([] (make-channel 0))
     ([capacity]
     {:nex-builtin-type :Channel
      :state (atom {:closed? false
                    :capacity capacity
                    :buffer []
                    :senders []
                    :receivers []})})))

#?(:clj
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
          (throw e))))))

#?(:cljs
   (defn task-await
     ([task]
      (:promise task))
     ([task timeout]
      (.then (.race js/Promise
                    (to-array [(:promise task)
                               (js/Promise.
                                (fn [resolve _reject]
                                  (js/setTimeout #(resolve task-timeout-signal) (timeout-ms timeout))))]))
             identity))))

#?(:clj
   (defn task-done? [task]
     (.isDone ^CompletableFuture (:future task))))

#?(:cljs
   (defn task-done? [task]
     @(:done? task)))

#?(:clj
   (defn await-all-tasks
     [tasks]
     (nex-array-from (map task-await tasks))))

#?(:cljs
   (defn await-all-tasks
     [tasks]
     (.then (promise-all (map task-await tasks))
            (fn [results]
              (nex-array-from results)))))

#?(:clj
   (defn await-any-task
     [tasks]
     (when (empty? tasks)
       (throw (ex-info "await_any requires at least one task" {})))
     (loop []
       (if-let [ready-task (some #(when (task-done? %) %) tasks)]
         (task-await ready-task)
         (do
           (Thread/sleep 1)
           (recur))))))

#?(:cljs
   (defn await-any-task
     [tasks]
     (when (empty? tasks)
       (throw (ex-info "await_any requires at least one task" {})))
     (.race js/Promise (to-array (map task-await tasks)))))

#?(:clj
   (defn task-cancel [task]
     (.cancel ^CompletableFuture (:future task) true)))

#?(:cljs
   (defn task-cancel [task]
     ((:cancel! task))))

#?(:clj
   (defn task-cancelled? [task]
     (.isCancelled ^CompletableFuture (:future task))))

#?(:cljs
   (defn task-cancelled? [task]
     @(:cancelled? task)))

#?(:clj
   (defn queue-remove-first
     [q pred]
     (reduce (fn [acc item]
               (let [{:keys [removed out]} acc]
                 (if (and (not removed) (pred item))
                   {:removed true :out out}
                   {:removed removed :out (queue-conj out item)})))
             {:removed false :out (queue-empty)}
             q)))

#?(:clj
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
                  (throw (ex-info "Cannot send on a closed channel" {:channel ch})))
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
                    (throw (ex-info "Cannot send on a closed channel" {:channel ch}))

                    (= result channel-timeout-signal)
                    (do
                      (locking (:lock ch)
                        (swap! (:state ch) update :senders
                               (fn [q] (:out (queue-remove-first q #(identical? (:ack %) ack))))))
                      false)

                    :else (if timed? true nil))))))))

#?(:clj
   (defn channel-try-send [ch value]
     (locking (:lock ch)
       (let [{:keys [closed? receivers capacity buffer]} @(:state ch)]
         (when closed?
           (throw (ex-info "Cannot send on a closed channel" {:channel ch})))
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

           :else false)))))

#?(:clj
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
          :closed (throw (ex-info "Cannot receive from a closed channel" {:channel ch}))
          :wait (let [result (if timed?
                               (deref (second ready) (timeout-ms timeout) channel-timeout-signal)
                               @(second ready))]
                  (cond
                    (= result channel-closed-signal)
                    (throw (ex-info "Cannot receive from a closed channel" {:channel ch}))

                    (= result channel-timeout-signal)
                    (do
                      (locking (:lock ch)
                        (swap! (:state ch) update :receivers
                               (fn [q] (:out (queue-remove-first q #(identical? % out))))))
                      nil)

                    :else result)))))))

#?(:clj
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

           :else nil)))))

#?(:clj
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
       nil)))

#?(:cljs
   (defn channel-send
     ([ch value]
      (channel-send ch value nil))
     ([ch value timeout]
      (js/Promise.
       (fn [resolve reject]
         (let [timed? (some? timeout)
               finish (fn [v] (resolve v))
               {:keys [closed? receivers capacity buffer]} @(:state ch)]
           (cond
             closed?
             (reject (ex-info "Cannot send on a closed channel" {:channel ch}))

             (seq receivers)
             (let [receiver (first receivers)]
               (swap! (:state ch) update :receivers #(vec (rest %)))
               ((:resolve receiver) value)
               (finish (when timed? true)))

             (< (count buffer) capacity)
             (do
               (swap! (:state ch) update :buffer conj value)
               (finish (when timed? true)))

             :else
             (let [id (str (gensym "__send"))
                   timer-id (atom nil)
                   entry {:id id
                          :value value
                          :resolve (fn [_]
                                     (when-let [timer @timer-id] (js/clearTimeout timer))
                                     (finish (when timed? true)))
                          :reject (fn [err]
                                    (when-let [timer @timer-id] (js/clearTimeout timer))
                                    (reject err))}]
               (swap! (:state ch) update :senders conj entry)
               (when timed?
                 (reset! timer-id
                         (js/setTimeout
                          (fn []
                            (swap! (:state ch) update :senders
                                   (fn [senders]
                                     (vec (remove #(= (:id %) id) senders))))
                            (finish false))
                          (timeout-ms timeout))))))))))))

#?(:cljs
   (defn channel-try-send [ch value]
     (let [{:keys [closed? receivers capacity buffer]} @(:state ch)]
       (cond
         closed?
         (throw (ex-info "Cannot send on a closed channel" {:channel ch}))

         (seq receivers)
         (let [receiver (first receivers)]
           (swap! (:state ch) update :receivers #(vec (rest %)))
           ((:resolve receiver) value)
           true)

         (< (count buffer) capacity)
         (do
           (swap! (:state ch) update :buffer conj value)
           true)

         :else false))))

#?(:cljs
   (defn channel-receive
     ([ch]
      (channel-receive ch nil))
     ([ch timeout]
      (js/Promise.
       (fn [resolve reject]
         (let [timed? (some? timeout)
               {:keys [closed? senders buffer capacity]} @(:state ch)]
           (cond
             (seq buffer)
             (let [buffered-value (first buffer)]
               (swap! (:state ch) update :buffer #(vec (rest %)))
               (when (and (pos? capacity) (seq (:senders @(:state ch))))
                 (let [{sender-value :value sender-resolve :resolve} (first (:senders @(:state ch)))]
                   (swap! (:state ch)
                          (fn [state]
                            (-> state
                                (update :senders #(vec (rest %)))
                                (update :buffer conj sender-value))))
                   (sender-resolve nil)))
               (resolve buffered-value))

             (seq senders)
             (let [{:keys [value] :as sender} (first senders)
                   ack-resolve (:resolve sender)]
               (swap! (:state ch) update :senders #(vec (rest %)))
               (ack-resolve nil)
               (resolve value))

             closed?
             (reject (ex-info "Cannot receive from a closed channel" {:channel ch}))

             :else
             (let [id (str (gensym "__recv"))
                   timer-id (atom nil)
                   entry {:id id
                          :resolve (fn [value]
                                     (when-let [timer @timer-id] (js/clearTimeout timer))
                                     (resolve value))
                          :reject (fn [err]
                                    (when-let [timer @timer-id] (js/clearTimeout timer))
                                    (reject err))}]
               (swap! (:state ch) update :receivers conj entry)
               (when timed?
                 (reset! timer-id
                         (js/setTimeout
                          (fn []
                            (swap! (:state ch) update :receivers
                                   (fn [receivers]
                                     (vec (remove #(= (:id %) id) receivers))))
                            (resolve nil))
                          (timeout-ms timeout))))))))))))

#?(:cljs
   (defn channel-try-receive [ch]
     (let [{:keys [senders buffer capacity]} @(:state ch)]
       (cond
         (seq buffer)
         (let [buffered-value (first buffer)]
           (swap! (:state ch) update :buffer #(vec (rest %)))
           (when (and (pos? capacity) (seq (:senders @(:state ch))))
             (let [{sender-value :value sender-resolve :resolve} (first (:senders @(:state ch)))]
               (swap! (:state ch)
                      (fn [state]
                        (-> state
                            (update :senders #(vec (rest %)))
                            (update :buffer conj sender-value))))
               (sender-resolve nil)))
           buffered-value)

         (seq senders)
         (let [{:keys [value] :as sender} (first senders)
               ack-resolve (:resolve sender)]
           (swap! (:state ch) update :senders #(vec (rest %)))
           (ack-resolve nil)
           value)

         :else nil))))

#?(:cljs
   (defn channel-close [ch]
     (let [{:keys [closed? senders receivers buffer]} @(:state ch)]
       (when-not closed?
         (swap! (:state ch) assoc :closed? true :senders [] :receivers (if (seq buffer) receivers []))
         (doseq [{:keys [reject]} senders]
           (reject (ex-info "Cannot send on a closed channel" {:channel ch})))
         (when-not (seq buffer)
           (doseq [{:keys [reject]} receivers]
             (reject (ex-info "Cannot receive from a closed channel" {:channel ch})))))
       nil)))

;;
;; Forward declarations
;;
