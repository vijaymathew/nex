(ns nex.predeclared-test
  "The predeclared names `console` and `program` (nex.predeclared), and the
   Program type behind `program`. Behaviour is asserted on both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.predeclared :as predeclared]
            [nex.repl :as repl]))

(defn- run-backend
  [code interpret? args]
  (let [f (java.io.File/createTempFile "predeclared" ".nex")]
    (try
      (spit f code)
      (str/split-lines
       (str/trim-newline
        (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?
                                                 :program-args args}))))
      (finally (.delete f)))))

(defn- both
  "Printed output lines of CODE (run with program arguments ARGS), asserted
   identical on both backends."
  ([code] (both code []))
  ([code args]
   (let [compiled (run-backend code false args)
         interpreted (run-backend code true args)]
     (is (= interpreted compiled) "compiled and interpreted output must agree")
     compiled)))

(defn- type-error
  "The type-checking failure message for CODE, or nil if it checks."
  [code]
  (let [f (java.io.File/createTempFile "predeclared" ".nex")]
    (try
      (spit f code)
      (with-out-str (e/eval-file (.getPath f)))
      nil
      (catch clojure.lang.ExceptionInfo ex
        (ex-message ex))
      (finally (.delete f)))))

(defn- lines [& ls] (str/join "\n" ls))

(deftest console-prints-without-quotes
  (is (= ["hello" "1.5"]
         (both (lines "console.print_line(\"hello\")"
                      "console.print_line(1.5.to_string)")))))

(deftest program-knows-its-arguments-and-environment
  (is (= ["[\"--fast\", \"map.txt\"]" "true" "false" "" "true" "true"]
         (both (lines "console.print_line(program.arguments.to_string)"
                      "console.print_line(program.has_env(\"PATH\").to_string)"
                      "console.print_line(program.has_env(\"NEX_SURELY_NOT_SET\").to_string)"
                      "console.print_line(program.env(\"NEX_SURELY_NOT_SET\"))"
                      "console.print_line((program.pid > 0).to_string)"
                      "console.print_line((program.working_directory.length > 0).to_string)")
               ["--fast" "map.txt"]))))

(deftest visible-in-functions-and-classes
  (is (= ["from a function" "from a class" "2 arguments"]
         (both (lines "class Greeter"
                      "feature"
                      "  greet() do console.print_line(\"from a class\") end"
                      "end"
                      "function hello() do"
                      "  console.print_line(\"from a function\")"
                      "end"
                      "function count(): Integer do"
                      "  result := program.arguments.length"
                      "end"
                      "hello()"
                      "(create Greeter).greet()"
                      "console.print_line(count().to_string + \" arguments\")")
               ["a" "b"]))))

(deftest shadowed-by-fields-parameters-and-locals
  (is (= ["field" "parameter" "local" "still a Console"]
         (both (lines "class Box"
                      "create"
                      "  make() do console := \"field\" end"
                      "feature"
                      "  console: String"
                      "  show() do print_it(console) end"
                      "  print_it(s: String) do (create Console).print_line(s) end"
                      "end"
                      "function shout(console: String) do"
                      "  (create Console).print_line(console)"
                      "end"
                      "function local_one() do"
                      "  let console := \"local\""
                      "  (create Console).print_line(console)"
                      "end"
                      "(create Box.make()).show()"
                      "shout(\"parameter\")"
                      "local_one()"
                      "console.print_line(\"still a Console\")")))))

(deftest a-program-declaring-the-name-itself-keeps-its-own
  (testing "a top-level let"
    (is (= ["42" "true"]
           (both (lines "let console := 42"
                        "print(console)"
                        "print(program.pid > 0)")))))
  (testing "a function of that name"
    (is (= ["\"mine\""]
           (both (lines "function program(): String do result := \"mine\" end"
                        "print(program())")))))
  (testing "its own class of the type's name"
    (is (= ["\"own\""]
           (both (lines "class Program"
                        "feature"
                        "  name(): String do result := \"own\" end"
                        "end"
                        "print((create Program).name())"))))))

(deftest read-only
  (is (str/includes? (str (type-error "console := create Console"))
                     "Cannot assign to console: it is predeclared, and read-only"))
  (is (str/includes? (str (type-error (lines "function f() do"
                                             "  program := create Program"
                                             "end"
                                             "f()")))
                     "read-only"))
  (testing "a nested let is a new variable, which may be assigned"
    (is (nil? (type-error (lines "function f() do"
                                 "  let console := 1"
                                 "  console := 2"
                                 "end"
                                 "f()"))))))

(deftest create-program-takes-nothing
  (is (str/includes? (str (type-error "let p := create Program.self"))
                     "create Program takes no constructor or arguments")))

(deftest prelude-statements
  (let [ast (p/ast "let console := 1\nprint(console)")]
    (is (= ["program"] (map :name (predeclared/statements ast))))
    (is (every? :predeclared (predeclared/statements ast)))
    (is (= ["program" "console"] (map :name (take 2 (:statements (predeclared/add ast))))))))

(deftest repl-sessions-have-them
  (let [ctx (repl/session-context)
        out (with-out-str (repl/eval-code ctx "console.print_line(\"from the REPL\")"))]
    (is (str/includes? out "from the REPL"))))
