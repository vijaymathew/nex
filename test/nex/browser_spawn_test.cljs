(ns nex.browser-spawn-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [clojure.string :as str]
            [nex.interpreter :as interp]))

(defn- int-node [n]
  {:type :integer :value n})

(defn- string-node [s]
  {:type :string :value s})

(defn- call-node
  ([target method]
   (call-node target method []))
  ([target method args]
   {:type :call
    :target target
    :method method
    :args args
    :has-parens true}))

(defn- spawn-node [body]
  {:type :spawn
   :body body})

(defn- channel-create-node []
  {:type :create
   :class-name "Channel"
   :generic-args ["Integer"]
   :constructor nil
   :args []})

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
    (testing "spawn returns a Task whose await resolves in the CLJS interpreter"
      (let [ctx (interp/make-context)
            program (spawn-node [{:type :assign
                                  :target "result"
                                  :value (int-node 42)}])]
        (settle!
         (.then (interp/eval-node-async ctx program)
                (fn [task]
                  (interp/env-define (:current-env ctx) "task" task)
                  (interp/eval-node-async ctx (call-node "task" "await"))))
         done
         (fn [result]
           (is (= 42 result))))))))

(deftest browser-channel-send-receive-smoke-test
  (async done
    (testing "spawn + Channel rendezvous in the CLJS interpreter"
      (let [ctx (interp/make-context)]
        (settle!
         (.then (interp/eval-node-async ctx (channel-create-node))
                (fn [ch]
                  (interp/env-define (:current-env ctx) "ch" ch)
                  (.then (interp/eval-node-async
                          ctx
                          (spawn-node [(call-node "ch" "send" [(int-node 7)])]))
                         (fn [_]
                           (.then (interp/eval-node-async ctx (call-node "ch" "receive"))
                                  (fn [received]
                                    (.then (interp/eval-node-async ctx (call-node "ch" "close"))
                                           (fn [_]
                                             (.then (interp/eval-node-async ctx (call-node "ch" "is_closed"))
                                                    (fn [closed?]
                                                      {:received received
                                                       :closed? closed?}))))))))))
         done
         (fn [{:keys [received closed?]}]
           (is (= 7 received))
           (is (true? closed?))))))))

(deftest browser-spawn-failure-smoke-test
  (async done
    (testing "task failure is re-raised through await in the CLJS interpreter"
      (let [ctx (interp/make-context)
            program (spawn-node [{:type :raise
                                  :value (string-node "boom")}])]
        (.then (interp/eval-node-async ctx program)
               (fn [task]
                 (interp/env-define (:current-env ctx) "task" task)
                 (.then (interp/eval-node-async ctx (call-node "task" "await"))
                        (fn [_]
                          (is false "await should have rejected")
                          (done))
                        (fn [err]
                          (is (str/includes? (or (.-message err) (str err)) "boom"))
                          (done))))
               (fn [err]
                 (is false (str "spawn should succeed and return a task: " err))
                 (done)))))))
