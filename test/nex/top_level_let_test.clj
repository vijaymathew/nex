(ns nex.top-level-let-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [nex.generator.java :as java]
            [nex.generator.javascript :as js]))

(def mixed-top-level-code
  "function double(n: Integer): Integer
do
  result := n * 2
end

let inc := fn (n: Integer): Integer do
  result := n + 1
end")

(deftest parser-allows-top-level-let-after-function
  (testing "Program may mix top-level function definitions and let statements"
    (let [ast (p/ast mixed-top-level-code)]
      (is (= :program (:type ast)))
      (is (= 1 (count (:functions ast))))
      (is (= 1 (count (:statements ast))))
      (is (= :let (-> ast :statements first :type))))))

(deftest interpreter-executes-top-level-let-after-function
  (testing "Top-level let after function definition is evaluated"
    (let [ctx (interp/make-context)
          ast (p/ast mixed-top-level-code)
          _ (interp/eval-node ctx ast)
          result (interp/eval-node ctx {:type :call
                                        :target nil
                                        :method "inc"
                                        :args [{:type :integer :value 41}]})]
      (is (= 42 result)))))

(deftest typechecker-checks-top-level-statements-in-order
  (testing "Typechecker handles typed top-level let that references top-level function"
    (let [ast (p/ast "function double(n: Integer): Integer
do
  result := n * 2
end

let y: Integer := double(5)")
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest generators-emit-top-level-statements-in-main
  (testing "Java and JavaScript generators execute top-level statements in Main/main.js"
    (let [code "function double(n: Integer): Integer
do
  result := n * 2
end

let y: Integer := double(5)
print(y)"
          tmp (java.io.File/createTempFile "nex-top-level-let-" ".nex")]
      (try
        (spit tmp code)
        (let [java-files (java/translate-file (.getPath tmp) nil {})
              js-files (js/translate-file (.getPath tmp) nil {})
              main-java (get java-files "Main.java")
              main-js (get js-files "main.js")]
          (is (str/includes? main-java "int y ="))
          (is (str/includes? main-java "NexGlobals.double.call1(5)"))
      (is (str/includes? main-js "let y = await NexGlobals.double.call1(5);"))
          (is (str/includes? main-js "console.log(y)")))
        (finally
          (.delete tmp))))))
