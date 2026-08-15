(ns nex.sexpr-lib-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.repl :as repl]))

(deftest sexpr-library-runtime
  (testing "Sexpr parses atoms, strings, and nested lists, and round-trips through sexpr_to_string"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern data/Sexpr")
                   (repl/eval-code ctx "print(sexpr_to_string(parse_sexpr_text(\"42\")))")
                   (repl/eval-code ctx "print(sexpr_to_string(parse_sexpr_text(\"-3.14\")))")
                   (repl/eval-code ctx "print(sexpr_to_string(parse_sexpr_text(\"hello\")))")
                   (repl/eval-code ctx "print(sexpr_to_string(parse_sexpr_text(\"(+ 1 (foo \\\"bar\\\" 2.5) -3)\")))")
                   (repl/eval-code ctx "let e: Sexpr := parse_sexpr_text(\"(a b c)\")")
                   (repl/eval-code ctx "match e of when List(items) then print(items.length) else print(-1) end"))]
      (is (str/includes? output "\"42\""))
      (is (str/includes? output "\"-3.14\""))
      (is (str/includes? output "\"hello\""))
      (is (str/includes? output "\"(+ 1 (foo \"bar\" 2.5) -3)\""))
      (is (str/includes? output "3"))))

  (testing "Number tokens are classified as Int or Float"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern data/Sexpr")
                   (repl/eval-code ctx "match parse_sexpr_text(\"42\") of when Int then print(\"int\") when Float then print(\"float\") else print(\"other\") end")
                   (repl/eval-code ctx "match parse_sexpr_text(\"3.14\") of when Int then print(\"int\") when Float then print(\"float\") else print(\"other\") end"))]
      (is (str/includes? output "\"int\""))
      (is (str/includes? output "\"float\""))))

  (testing "Malformed input reports a parse error instead of silently succeeding"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "intern data/Sexpr")
                   (repl/eval-code ctx "print(sexpr_to_string(parse_sexpr_text(\"(a b\")))"))]
      (is (str/includes? output "unterminated list")))))
