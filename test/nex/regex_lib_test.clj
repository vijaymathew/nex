(ns nex.regex-lib-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.repl :as repl]))

(deftest regex-library-runtime-test
  (testing "Regex library works through the JVM interpreter"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern text/Regex")
                   (repl/eval-code ctx "let rx: Regex := create Regex.compile_with_flags(\"[a-z]+\", \"i\")")
                   (repl/eval-code ctx "print(rx.matches(\"Nex\"))")
                   (repl/eval-code ctx "print(rx.find(\"123 Nex 456\"))")
                   (repl/eval-code ctx "print(rx.find_all(\"one two THREE\"))")
                   (repl/eval-code ctx "print(rx.replace(\"v1 v2\", \"#\"))")
                   (repl/eval-code ctx "let comma: Regex := create Regex.compile(\",\")")
                   (repl/eval-code ctx "print(comma.split(\"a,b,c\"))"))]
      (is (.contains output "true"))
      (is (.contains output "\"Nex\""))
      (is (.contains output "[\"one\", \"two\", \"THREE\"]"))
      (is (.contains output "\"#1 #2\""))
      (is (.contains output "[\"a\", \"b\", \"c\"]")))))
