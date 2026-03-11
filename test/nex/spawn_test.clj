(ns nex.spawn-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- execute-method-output [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [c (:classes ast)]
            (interp/register-class ctx c))
        class-def (last (:classes ast))
        method-def (-> class-def :body first :members first)
        method-env (interp/make-env (:globals ctx))
        obj (interp/make-object (:name class-def) {})
        _ (interp/env-define method-env "obj" obj)
        ctx-with-env (assoc ctx :current-env method-env)]
    (interp/eval-node ctx-with-env {:type :call
                                    :target "obj"
                                    :method (:name method-def)
                                    :args []})
    @(:output ctx-with-env)))

(defn- execute-method [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [c (:classes ast)]
            (interp/register-class ctx c))
        class-def (last (:classes ast))
        method-def (-> class-def :body first :members first)
        method-env (interp/make-env (:globals ctx))
        obj (interp/make-object (:name class-def) {})
        _ (interp/env-define method-env "obj" obj)
        ctx-with-env (assoc ctx :current-env method-env)]
    (interp/eval-node ctx-with-env {:type :call
                                    :target "obj"
                                    :method (:name method-def)
                                    :args []})))

(deftest spawn-typechecks-with-and-without-result
  (testing "spawn infers Task[T] when result is assigned and Task otherwise"
    (let [typed-code "class Test
  feature
    demo() do
      let t: Task[Integer] := spawn do
        result := 1
      end
    end
end"
          plain-code "class Test
  feature
    demo() do
      let t: Task := spawn do
        print(1)
      end
    end
end"]
      (is (:success (tc/type-check (p/ast typed-code))))
      (is (:success (tc/type-check (p/ast plain-code)))))))

(deftest spawn-await-runtime
  (testing "spawn returns a task whose await produces the computed result"
    (let [code "class Test
  feature
    demo() do
      let t: Task[Integer] := spawn do
        result := 40 + 2
      end
      print(t.await)
      print(t.is_done)
    end
end"
          output (execute-method-output code)]
      (is (= ["42" "true"] output)))))

(deftest channel-send-receive-runtime
  (testing "unbuffered channels rendezvous between spawn and caller"
    (let [code "class Test
  feature
    demo() do
      let ch: Channel[Integer] := create Channel[Integer]
      spawn do
        ch.send(42)
      end
      print(ch.receive)
      ch.close
      print(ch.is_closed)
    end
end"
          output (execute-method-output code)]
      (is (= ["42" "true"] output)))))

(deftest spawn-await-reraises-task-failure
  (testing "task failure is re-raised on await"
    (let [code "class Test
  feature
    demo() do
      let t: Task := spawn do
        raise(\"boom\")
      end
      t.await
    end
end"]
      (is (thrown-with-msg? Exception #"boom" (execute-method code))))))

(deftest task-cancel-and-timeout-runtime
  (testing "tasks support cancellation state and timed await"
    (let [code "class Test
  feature
    demo() do
      let t1: Task[Integer] := spawn do
        sleep(20)
        result := 1
      end
      print(t1.await(1))
      let t2: Task := spawn do
        sleep(20)
      end
      print(t2.cancel)
      print(t2.is_cancelled)
    end
end"
          output (execute-method-output code)]
      (is (= ["nil" "true" "true"] output)))))

(deftest await-any-and-await-all-runtime
  (testing "await_any returns the first completed task result and await_all collects all results"
    (let [code "class Test
  feature
    demo() do
      let slow: Task[Integer] := spawn do
        sleep(10)
        result := 10
      end
      let fast: Task[Integer] := spawn do
        result := 20
      end
      print(await_any([slow, fast]))
      print(await_all([slow, fast]))
    end
end"
          output (execute-method-output code)]
      (is (= ["20" "[10, 20]"] output)))))

(deftest channel-close-prevents-further-receive
  (testing "receiving from a closed empty channel fails"
    (let [code "class Test
  feature
    demo() do
      let ch: Channel[Integer] := create Channel[Integer]
      ch.close
      ch.receive
    end
end"]
      (is (thrown-with-msg? Exception #"closed channel" (execute-method code))))))

(deftest buffered-channel-runtime
  (testing "buffered channels queue values and report size/capacity"
    (let [code "class Test
  feature
    demo() do
      let ch: Channel[Integer] := create Channel[Integer].with_capacity(2)
      ch.send(10)
      ch.send(20)
      print(ch.capacity)
      print(ch.size)
      ch.close
      print(ch.receive)
      print(ch.receive)
      print(ch.is_closed)
      print(ch.size)
    end
end"
          output (execute-method-output code)]
      (is (= ["2" "2" "10" "20" "true" "0"] output)))))

(deftest channel-try-methods-and-select-runtime
  (testing "try_send/try_receive and select work with buffered channels"
    (let [code "class Test
  feature
    demo() do
      let ch: Channel[Integer] := create Channel[Integer].with_capacity(1)
      print(ch.try_send(7))
      print(ch.try_send(8))
      print(ch.try_receive)
      select
        when ch.send(9) then
          print(\"sent\")
        else
          print(\"blocked\")
      end
      select
        when ch.receive as value then
          print(value)
        else
          print(\"none\")
      end
    end
end"
          output (execute-method-output code)]
      (is (= ["true" "false" "7" "\"sent\"" "9"] output)))))

(deftest channel-timeout-and-select-timeout-runtime
  (testing "channel send/receive time out and select timeout fires"
    (let [code "class Test
  feature
    demo() do
      let ch1: Channel[Integer] := create Channel[Integer]
      print(ch1.receive(1))
      let ch2: Channel[Integer] := create Channel[Integer].with_capacity(1)
      ch2.send(1)
      print(ch2.send(2, 1))
      select
        when ch1.receive as value then
          print(value)
        timeout 1 then
          print(\"timeout\")
      end
    end
end"
          output (execute-method-output code)]
      (is (= ["nil" "false" "\"timeout\""] output)))))

(deftest task-select-runtime
  (testing "select can probe task completion as well as channel readiness"
    (let [code "class Test
  feature
    demo() do
      let t: Task[Integer] := spawn do
        result := 42
      end
      select
        when t.await as value then
          print(value)
        timeout 50 then
          print(\"timeout\")
      end
    end
end"
          output (execute-method-output code)]
      (is (= ["42"] output)))))
