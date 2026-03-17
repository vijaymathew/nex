(ns nex.compiler.jvm.emit-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.emit :as emit]
            [nex.compiler.jvm.runtime :as runtime]
            [nex.ir :as ir]))

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
                         :body []
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
