(ns nex.compiler.jvm.builtin-smoke-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.compiler.jvm.runtime :as runtime]
            [nex.parser :as parser]))

(deftest compiled-builtin-target-call-smoke-test
  (testing "compiled helper supports builtin-style feature calls on existing runtime-backed values"
    (let [session (compiled-repl/make-session)
          _ (runtime/state-set-value! (:state session) "numbers" (java.util.ArrayList. [1 2 3 4]))
          _ (runtime/state-set-type! (:state session) "numbers" {:base-type "Array" :type-params ["Integer"]})
          _ (runtime/state-set-value! (:state session) "m" (doto (java.util.HashMap.) (.put "a" 1)))
          _ (runtime/state-set-type! (:state session) "m" {:base-type "Map" :type-params ["String" "Integer"]})
          _ (runtime/state-set-value! (:state session) "s" (doto (java.util.LinkedHashSet.) (.add 2)))
          _ (runtime/state-set-type! (:state session) "s" {:base-type "Set" :type-params ["Integer"]})
          result (compiled-repl/compile-and-eval!
                  session
                  (parser/ast
                   (str "print(numbers.length)\n"
                        "print(m.contains_key(\"a\"))\n"
                        "print(s.contains(2))\n"
                        "type_of(numbers.reverse)")))]
      (is (:compiled? result))
      (is (= ["4" "true" "true"] (:output result)))
      (is (= "Array" (:result result))))))

(deftest compiled-legacy-calls-only-builtins-smoke-test
  (testing "legacy calls-only programs can include builtin and builtin-style calls on the compiled path"
    (let [session (compiled-repl/make-session)
          _ (runtime/state-set-value! (:state session) "numbers" (java.util.ArrayList. [1 2 3]))
          _ (runtime/state-set-type! (:state session) "numbers" {:base-type "Array" :type-params ["Integer"]})
          ast {:type :program
               :imports []
               :interns []
               :classes []
               :functions []
               :statements []
               :calls [{:type :call
                        :target nil
                        :method "print"
                        :args [{:type :call
                                :target {:type :identifier :name "numbers"}
                                :method "length"
                                :args []
                                :has-parens false}]
                        :has-parens true}
                       {:type :call
                        :target nil
                        :method "type_of"
                        :args [{:type :call
                                :target {:type :identifier :name "numbers"}
                                :method "slice"
                                :args [{:type :integer :value 0}
                                       {:type :integer :value 2}]
                                :has-parens true}]
                        :has-parens true}]}
          result (compiled-repl/compile-and-eval! session ast)]
      (is (:compiled? result))
      (is (= ["3"] (:output result)))
      (is (= "Array" (:result result))))))
