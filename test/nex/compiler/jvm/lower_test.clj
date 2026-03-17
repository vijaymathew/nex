(ns nex.compiler.jvm.lower-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.ir :as ir]
            [nex.lower :as lower]
            [nex.parser :as p]))

(deftest make-lowering-env-test
  (testing "initial lowering env shape is stable"
    (let [env (lower/make-lowering-env)]
      (is (= {} (:locals env)))
      (is (= true (:top-level? env)))
      (is (= true (:repl? env)))
      (is (= 0 (:state-slot env)))
      (is (= 1 (:next-slot env)))
      (is (= [] (:classes env)))
      (is (= [] (:imports env)))
      (is (= {} (:var-types env))))))

(deftest lower-literals-and-binary-test
  (testing "binary arithmetic lowers to typed IR"
    (let [program (p/ast "let x := 1 + 2")
          {:keys [unit env]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0001"})
          stmt (first (:body unit))]
      (is (= :set-local (:op stmt)))
      (is (= 1 (:slot stmt)))
      (is (= "Integer" (:nex-type stmt)))
      (is (= :int (:jvm-type stmt)))
      (is (= :binary (-> stmt :expr :op)))
      (is (= :add (-> stmt :expr :operator)))
      (is (= :const (-> stmt :expr :left :op)))
      (is (= 1 (-> stmt :expr :left :value)))
      (is (= 2 (-> stmt :expr :right :value)))
      (is (= "Integer" (get-in env [:var-types "x"])))
      (is (= 2 (:next-slot env))))))

(deftest lower-final-expression-to-return-test
  (testing "final repl expression lowers to a return"
    (let [program (p/ast "42")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0042"})
          stmt (first (:body unit))]
      (is (= 1 (count (:body unit))))
      (is (= :return (:op stmt)))
      (is (= :const (-> stmt :expr :op)))
      (is (= 42 (-> stmt :expr :value)))
      (is (= :int (-> stmt :expr :jvm-type)))
      (is (= (ir/object-jvm-type "java/lang/Object") (:jvm-type stmt))))))

(deftest lower-top-level-identifier-and-assign-test
  (testing "top-level identifiers and assignments lower through REPL state ops"
    (let [program (p/ast "x := x + 1")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0002"
                                                         :var-types {"x" "Integer"}})
          stmt (first (:body unit))]
      (is (= :top-set (:op stmt)))
      (is (= "x" (:name stmt)))
      (is (= :binary (-> stmt :expr :op)))
      (is (= :top-get (-> stmt :expr :left :op)))
      (is (= "x" (-> stmt :expr :left :name)))
      (is (= 1 (-> stmt :expr :right :value))))))

(deftest lower-identifier-expression-test
  (testing "identifier-only expressions lower as top-level gets when not local"
    (let [expr {:type :identifier :name "score"}
          env (lower/make-lowering-env {:var-types {"score" "Integer"}})
          lowered (lower/lower-expression env expr)]
      (is (= :top-get (:op lowered)))
      (is (= "score" (:name lowered)))
      (is (= "Integer" (:nex-type lowered)))
      (is (= :int (:jvm-type lowered))))))
