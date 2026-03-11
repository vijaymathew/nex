(ns nex.browser-spawn-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [clojure.string :as str]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]))

(defn- make-channel
  ([] ((deref (var interp/make-channel))))
  ([capacity] ((deref (var interp/make-channel)) capacity)))

(defn- make-task [promise]
  ((deref (var interp/make-task)) promise))

(defn- task-await
  ([task] ((deref (var interp/task-await)) task))
  ([task timeout-ms] ((deref (var interp/task-await)) task timeout-ms)))

(defn- task-cancel [task]
  ((deref (var interp/task-cancel)) task))

(defn- task-cancelled? [task]
  ((deref (var interp/task-cancelled?)) task))

(defn- await-any-task [tasks]
  ((deref (var interp/await-any-task)) tasks))

(defn- await-all-tasks [tasks]
  ((deref (var interp/await-all-tasks)) tasks))

(defn- channel-send
  ([ch value] ((deref (var interp/channel-send)) ch value))
  ([ch value timeout-ms] ((deref (var interp/channel-send)) ch value timeout-ms)))

(defn- channel-receive
  ([ch] ((deref (var interp/channel-receive)) ch))
  ([ch timeout-ms] ((deref (var interp/channel-receive)) ch timeout-ms)))

(defn- channel-close [ch]
  ((deref (var interp/channel-close)) ch))

(defn- settle!
  [promise done on-success]
  (.then promise
         (fn [value]
           (on-success value)
           (done))
         (fn [err]
           (is false (str "Unexpected rejection: " err))
           (done))))

(deftest browser-spawn-await-smoke-test
  (async done
    (testing "Promise-backed browser tasks resolve through await"
      (let [task (make-task (js/Promise.resolve 42))]
        (settle!
         (task-await task)
         done
         (fn [result]
           (is (= 42 result))))))))

(deftest browser-channel-send-receive-smoke-test
  (async done
    (testing "browser channels rendezvous through Promise-based send/receive"
      (let [ch (make-channel)]
        (settle!
         (.then (channel-send ch 7)
                (fn [_]
                  (.then (channel-receive ch)
                         (fn [received]
                           (channel-close ch)
                           {:received received
                            :closed? (:closed? @(:state ch))}))))
         done
         (fn [{:keys [received closed?]}]
           (is (= 7 received))
           (is (true? closed?))))))))

(deftest browser-spawn-failure-smoke-test
  (async done
    (testing "task failure is re-raised through await in the browser runtime"
      (let [task (make-task (js/Promise.reject (js/Error. "boom")))]
        (.then (task-await task)
               (fn [_]
                 (is false "await should have rejected")
                 (done))
               (fn [err]
                 (is (str/includes? (or (.-message err) (str err)) "boom"))
                 (done)))))))

(deftest browser-task-cancel-timeout-smoke-test
  (async done
    (testing "task cancel state and timed await work in the browser runtime"
      (let [task (make-task (js/Promise. (fn [resolve _reject]
                                           (js/setTimeout #(resolve 1) 20))))]
        (settle!
         (.then (task-await task 1)
                (fn [awaited]
                  {:awaited awaited
                   :cancelled (task-cancel task)
                   :is-cancelled (task-cancelled? task)}))
         done
         (fn [{:keys [awaited cancelled is-cancelled]}]
           (is (nil? awaited))
           (is (true? cancelled))
           (is (true? is-cancelled))))))))

(deftest browser-await-any-all-smoke-test
  (async done
    (testing "browser task helpers support await_any and await_all semantics"
      (let [slow (make-task (js/Promise. (fn [resolve _reject]
                                           (js/setTimeout #(resolve 10) 10))))
            fast (make-task (js/Promise.resolve 20))]
        (settle!
         (.then (await-any-task [slow fast])
                (fn [first-result]
                  (.then (await-all-tasks [slow fast])
                         (fn [all-results]
                           {:first-result first-result
                            :all-results all-results}))))
         done
         (fn [{:keys [first-result all-results]}]
           (is (= 20 first-result))
           (is (= [10 20] all-results))))))))

(deftest browser-typecheck-spawn-generic-let-smoke-test
  (testing "CLJS typechecker accepts Task[T] spawn let declarations"
    (let [ast {:type :program
               :imports []
               :interns []
               :classes []
               :functions []
               :calls []
               :statements [{:type :let
                             :name "t"
                             :var-type {:base-type "Task" :type-args ["Integer"]}
                             :value {:type :spawn
                                     :body [{:type :assign
                                             :target "result"
                                             :value {:type :binary
                                                     :operator "+"
                                                     :left {:type :integer :value 10}
                                                     :right {:type :integer :value 2}}}]}}]}
          result (tc/type-check ast {:var-types {}})]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest browser-interpreter-spawn-await-sequence-smoke-test
  (async done
    (testing "browser interpreter can bind a spawned task and await it later"
      (let [ctx (interp/make-context)
            let-node {:type :let
                      :name "t"
                      :var-type {:base-type "Task" :type-args ["Integer"]}
                      :value {:type :spawn
                              :body [{:type :assign
                                      :target "result"
                                      :value {:type :binary
                                              :operator "+"
                                              :left {:type :integer :value 10}
                                              :right {:type :integer :value 2}}}]}}
            await-node {:type :call
                        :target "t"
                        :method "await"
                        :args []
                        :has-parens false}]
        (.then (interp/eval-node-async ctx let-node)
               (fn [_]
                 (.then (interp/eval-node-async ctx await-node)
                        (fn [result]
                          (is (= 12 result))
                          (done))
                        (fn [err]
                          (is false (str "Unexpected rejection in await sequence: " err))
                          (done))))
               (fn [err]
                 (is false (str "Unexpected rejection in let sequence: " err))
                 (done)))))))

(deftest browser-interpreter-print-awaited-task-smoke-test
  (async done
    (testing "browser interpreter can await a task inside print arguments"
      (let [ctx (interp/make-context)
            let-node {:type :let
                      :name "t"
                      :var-type {:base-type "Task" :type-args ["Integer"]}
                      :value {:type :spawn
                              :body [{:type :assign
                                      :target "result"
                                      :value {:type :binary
                                              :operator "+"
                                              :left {:type :integer :value 10}
                                              :right {:type :integer :value 2}}}]}}
            print-node {:type :call
                        :target nil
                        :method "print"
                        :args [{:type :call
                                :target "t"
                                :method "await"
                                :args []
                                :has-parens false}]
                        :has-parens true}]
        (reset! (:output ctx) [])
        (.then (interp/eval-node-async ctx let-node)
               (fn [_]
                 (.then (interp/eval-node-async ctx print-node)
                        (fn [_]
                          (is (= ["12"] @(:output ctx)))
                          (done))
                        (fn [err]
                          (is false (str "Unexpected rejection in print/await sequence: " err))
                          (done))))
               (fn [err]
                 (is false (str "Unexpected rejection in let sequence: " err))
                 (done)))))))
