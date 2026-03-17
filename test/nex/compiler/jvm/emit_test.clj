(ns nex.compiler.jvm.emit-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.emit :as emit]
            [nex.compiler.jvm.runtime :as runtime]
            [nex.ir :as ir]
            [nex.lower :as lower]
            [nex.parser :as p]))

(deftest minimal-class-spec-test
  (testing "minimal class spec for a repl cell is stable"
    (let [unit (ir/unit {:name "nex/repl/Cell_0001"
                         :kind :repl-cell
                         :functions []
                         :body []
                         :result-jvm-type (ir/object-jvm-type "java/lang/Object")})
          spec (emit/minimal-class-spec unit)]
      (is (= "nex/repl/Cell_0001" (:internal-name spec)))
      (is (= "nex.repl.Cell_0001" (:binary-name spec)))
      (is (= "java/lang/Object" (:super-name spec)))
      (is (= 2 (count (:methods spec))))
      (is (= "eval" (-> spec :methods second :name))))))

(deftest compile-trivial-repl-cell-test
  (testing "emitted repl cell class loads and its eval method returns nil"
    (let [unit (ir/unit {:name "nex/repl/Cell_0001"
                         :kind :repl-cell
                         :functions []
                         :body [(ir/return-node
                                 (ir/const-node nil "Any"
                                                (ir/object-jvm-type "java/lang/Object"))
                                 "Any"
                                 (ir/object-jvm-type "java/lang/Object"))]
                         :result-jvm-type (ir/object-jvm-type "java/lang/Object")})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0001" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (bytes? bytecode))
      (is (= "nex.repl.Cell_0001" (.getName cls)))
      (is (nil? result)))))

(deftest compile-constant-return-repl-cell-test
  (testing "emitted repl cell can return a boxed constant"
    (let [unit (ir/unit {:name "nex/repl/Cell_0002"
                         :kind :repl-cell
                         :functions []
                         :body [(ir/return-node
                                 (ir/const-node 42 "Integer" :int)
                                 "Any"
                                 (ir/object-jvm-type "java/lang/Object"))]
                         :result-jvm-type (ir/object-jvm-type "java/lang/Object")})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0002" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (= 42 result))
      (is (instance? Integer result)))))

(deftest compile-pop-then-return-test
  (testing "pop discards intermediate constants before returning"
    (let [unit (ir/unit {:name "nex/repl/Cell_0003"
                         :kind :repl-cell
                         :functions []
                         :body [(ir/pop-node (ir/const-node 7 "Integer" :int))
                                (ir/return-node
                                 (ir/const-node "done" "String"
                                                (ir/object-jvm-type "java/lang/String"))
                                 "Any"
                                 (ir/object-jvm-type "java/lang/Object"))]
                         :result-jvm-type (ir/object-jvm-type "java/lang/Object")})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0003" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (= "done" result)))))

(deftest lower-and-compile-repl-expression-smoke-test
  (testing "parsed repl expression lowers and compiles end-to-end"
    (let [program (p/ast "42")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0042"})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0042" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (= 42 result)))))

(deftest lower-and-compile-let-expression-smoke-test
  (testing "compiled repl cells support local let bindings end-to-end"
    (let [program (p/ast "let x := 40\nx")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0043"})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0043" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (= 40 result)))))

(deftest lower-and-compile-let-plus-expression-smoke-test
  (testing "compiled repl cells support arithmetic over local lets end-to-end"
    (let [program (p/ast "let x := 40\nx + 2")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0044"})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0044" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (= 42 result)))))

(deftest compile-top-set-and-top-get-smoke-test
  (testing "compiled repl cells can persist top-level values through NexReplState"
    (let [state-loader (loader/make-loader)
          state (runtime/make-repl-state state-loader)
          unit-a (-> (p/ast "score := 40")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0050"
                                             :var-types {"score" "Integer"}})
                     :unit)
          unit-b (-> (p/ast "score")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0051"
                                             :var-types {"score" "Integer"}})
                     :unit)
          class-a (loader/define-class! state-loader
                                        "nex.repl.Cell_0050"
                                        (emit/compile-unit->bytes unit-a))
          class-b (loader/define-class! state-loader
                                        "nex.repl.Cell_0051"
                                        (emit/compile-unit->bytes unit-b))
          eval-a (.getMethod class-a "eval" (into-array Class [(class state)]))
          eval-b (.getMethod class-b "eval" (into-array Class [(class state)]))]
      (.invoke eval-a nil (object-array [state]))
      (is (= 40 (runtime/state-get-value state "score")))
      (is (= 40 (.invoke eval-b nil (object-array [state])))))))

(deftest compile-multi-cell-repl-state-smoke-test
  (testing "compiled cells share top-level state across multiple evaluations"
    (let [state-loader (loader/make-loader)
          state (runtime/make-repl-state state-loader)
          unit-a (-> (p/ast "x := 40")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0052"
                                             :var-types {"x" "Integer"}})
                     :unit)
          unit-b (-> (p/ast "x + 2")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0053"
                                             :var-types {"x" "Integer"}})
                     :unit)
          class-a (loader/define-class! state-loader
                                        "nex.repl.Cell_0052"
                                        (emit/compile-unit->bytes unit-a))
          class-b (loader/define-class! state-loader
                                        "nex.repl.Cell_0053"
                                        (emit/compile-unit->bytes unit-b))
          eval-a (.getMethod class-a "eval" (into-array Class [(class state)]))
          eval-b (.getMethod class-b "eval" (into-array Class [(class state)]))]
      (.invoke eval-a nil (object-array [state]))
      (is (= 40 (runtime/state-get-value state "x")))
      (is (= 42 (.invoke eval-b nil (object-array [state])))))))

(deftest compile-top-level-state-with-if-smoke-test
  (testing "compiled cells support compare and if over shared top-level state"
    (let [state-loader (loader/make-loader)
          state (runtime/make-repl-state state-loader)
          unit-a (-> (p/ast "x := 40")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0054"
                                             :var-types {"x" "Integer"}})
                     :unit)
          unit-b (-> (p/ast "if x > 0 then x + 2 else 0 end")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0055"
                                             :var-types {"x" "Integer"}})
                     :unit)
          class-a (loader/define-class! state-loader
                                        "nex.repl.Cell_0054"
                                        (emit/compile-unit->bytes unit-a))
          class-b (loader/define-class! state-loader
                                        "nex.repl.Cell_0055"
                                        (emit/compile-unit->bytes unit-b))
          eval-a (.getMethod class-a "eval" (into-array Class [(class state)]))
          eval-b (.getMethod class-b "eval" (into-array Class [(class state)]))]
      (.invoke eval-a nil (object-array [state]))
      (is (= 42 (.invoke eval-b nil (object-array [state])))))))
