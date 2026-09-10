(ns nex.compiler.jvm.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.runtime :as rt]))

(deftest repl-state-test
  (testing "repl state stores values, types, functions, and names"
    (let [state (rt/make-repl-state (loader/make-loader))]
      (is (nil? (rt/state-get-value state "x")))
      (is (= 42 (rt/state-set-value! state "x" 42)))
      (is (= 42 (rt/state-get-value state "x")))
      (is (= "Integer" (rt/state-set-type! state "x" "Integer")))
      (is (= "Integer" (rt/state-get-type state "x")))
      (is (= :f (rt/state-set-fn! state "f" :f)))
      (is (= :f (rt/state-get-fn state "f")))
      (is (= "nex/repl/Cell_0001" (rt/next-class-name! state "Cell")))
      (is (= "nex/repl/Fns_0002" (rt/next-class-name! state "Fns"))))))

(deftest exception-value-test
  (testing "a nex-raised value passes through unchanged"
    (is (= "boom" (rt/exception-value (rt/make-raised-exception "boom")))))
  (testing "a host throwable with a message surfaces the message"
    (is (= "/ by zero" (rt/exception-value (ArithmeticException. "/ by zero")))))
  (testing "a host throwable with no message still binds a real string, never nil
            (regression: StackOverflowError.getMessage is null, so
            `exception.to_string` in a rescue block rendered \"nil\")"
    (let [v (rt/exception-value (StackOverflowError.))]
      (is (some? v))
      (is (= "java.lang.StackOverflowError" v))))
  (testing "a blank message also falls back to the throwable's toString"
    (is (= "java.lang.RuntimeException" (rt/exception-value (RuntimeException. "  "))))))
