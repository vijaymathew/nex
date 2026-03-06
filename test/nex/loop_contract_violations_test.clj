(ns nex.loop-contract-violations-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(defn- execute-first-method-body
  [code]
  (let [ast (p/ast code)
        class-def (first (:classes ast))
        method-def (-> class-def :body first :members first)
        ctx (interp/make-context)
        _ (interp/register-class ctx class-def)
        env (interp/make-env (:globals ctx))
        ctx' (assoc ctx :current-env env)]
    (doseq [stmt (:body method-def)]
      (interp/eval-node ctx' stmt))
    @(:output ctx')))

(deftest loop-invariant-violation-test
  (testing "Loop invariant violations fail execution"
    (let [code "class Test
  feature
    demo() do
      from
        let x := 10
      invariant
        must_be_large: x > 5
      until
        x = 0
      do
        x := x - 1
      end
    end
end"]
      (is (thrown-with-msg?
            Exception
            #"Loop invariant violation"
            (execute-first-method-body code))))))

(deftest loop-variant-must-decrease-test
  (testing "Loop variant must strictly decrease"
    (let [code "class Test
  feature
    demo() do
      from
        let i := 0
      variant
        5
      until
        i > 3
      do
        i := i + 1
      end
    end
end"]
      (is (thrown-with-msg?
            Exception
            #"Loop variant must decrease"
            (execute-first-method-body code))))))

(deftest valid-loop-contracts-pass-test
  (testing "Valid loop with invariant+variant runs successfully"
    (let [code "class Test
  feature
    demo() do
      from
        let x := 5
      invariant
        positive: x >= 0
      variant
        x
      until
        x = 0
      do
        print(x)
        x := x - 1
      end
    end
end"]
      (is (= ["5" "4" "3" "2" "1"]
             (execute-first-method-body code))))))
