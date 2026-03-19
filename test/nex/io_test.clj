(ns nex.io-test
  "Tests for built-in Console and Process types"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [nex.generator.javascript :as js]))

;; ============================================================================
;; CONSOLE INTERPRETER TESTS
;; ============================================================================

(deftest console-create-test
  (testing "Create Console returns tagged map"
    (let [code "class Main
  feature
    demo() do
      let io: Console := create Console
    end
end"
          ast (p/ast code)
          ctx (interp/interpret ast)
          ;; Verify it parses and interprets without error
          output @(:output ctx)]
      (is (empty? output)))))

(deftest console-print-test
  (testing "Console.print outputs to stdout"
    (let [printed (with-out-str
                    (interp/nex-console-print "hello"))]
      (is (= "hello" printed)))
    ;; Also test through builtin method dispatch
    (let [console {:nex-builtin-type :Console}
          printed (with-out-str
                    (interp/call-builtin-method "io" console "print" ["hello"]))]
      (is (= "hello" printed)))))

(deftest console-println-test
  (testing "Console.print_line outputs with newline"
    (let [printed (with-out-str
                    (interp/nex-console-println "hello"))]
      (is (= "hello\n" printed)))))

(deftest console-error-test
  (testing "Console.error outputs to stderr"
    (let [err-output (java.io.StringWriter.)
          _ (binding [*err* err-output]
              (interp/nex-console-error "oops"))]
      (is (str/includes? (str err-output) "oops")))))

(deftest console-newline-test
  (testing "Console.new_line outputs empty line"
    (let [printed (with-out-str
                    (interp/nex-console-newline))]
      (is (= "\n" printed)))))

(deftest console-type-detection-test
  (testing "Console type detection"
    (is (interp/nex-console? {:nex-builtin-type :Console}))
    (is (not (interp/nex-console? {:nex-builtin-type :Process})))
    (is (not (interp/nex-console? "hello")))))

(deftest console-get-type-name-test
  (testing "get-type-name returns :Console for console values"
    (is (= :Console (interp/get-type-name {:nex-builtin-type :Console})))))

;; ============================================================================
;; TYPECHECKER TESTS
;; ============================================================================

(deftest typechecker-console-create-test
  (testing "Typechecker accepts create Console"
    (let [code "class Main
  feature
    demo() do
      let io: Console := create Console
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result)))))

(deftest typechecker-console-methods-test
  (testing "Typechecker accepts Console method calls"
    (let [code "class Main
  feature
    demo() do
      let io: Console := create Console
      io.print(\"hello\")
      io.print_line(\"world\")
      io.error(\"oops\")
      io.new_line()
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result)))))

(deftest typechecker-console-return-types-test
  (testing "Console methods return correct types"
    (let [code "class Main
  feature
    demo() do
      let io: Console := create Console
      let s: String := io.read_line()
      let n: Integer := io.read_integer()
      let r: Real := io.read_real()
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result)))))

;; ============================================================================
;; JAVASCRIPT GENERATOR TESTS
;; ============================================================================

(deftest js-console-create-test
  (testing "JS generation for create Console"
    (let [code "class Main
  feature
    demo() do
      let io: Console := create Console
    end
end"
          js-code (js/translate code)]
      (is (str/includes? js-code "({_type: 'Console'})")))))

(deftest js-console-methods-test
  (testing "JS generation for Console methods"
    (let [code "class Main
  feature
    demo() do
      let io: Console := create Console
      io.print(\"hello\")
      io.print_line(\"world\")
      io.error(\"oops\")
      io.new_line()
    end
end"
          js-code (js/translate code)]
      ;; Console methods lower to direct JS runtime calls when target type is known.
      (is (str/includes? js-code "process.stdout.write(String(\"hello\"))"))
      (is (str/includes? js-code "console.log(\"world\")"))
      (is (str/includes? js-code "console.error(\"oops\")"))
      (is (str/includes? js-code "console.log()")))))

;; ============================================================================
;; PROCESS INTERPRETER TESTS
;; ============================================================================

(deftest process-create-test
  (testing "Create Process returns tagged map"
    (let [result {:nex-builtin-type :Process}]
      (is (interp/nex-process? result))
      (is (not (interp/nex-process? {:nex-builtin-type :Console})))
      (is (not (interp/nex-process? "hello"))))))

(deftest process-get-type-name-test
  (testing "get-type-name returns :Process for process values"
    (is (= :Process (interp/get-type-name {:nex-builtin-type :Process})))))

(deftest process-getenv-test
  (testing "Process.getenv reads environment variables"
    (let [proc {:nex-builtin-type :Process}
          home (interp/call-builtin-method "p" proc "getenv" ["HOME"])]
      (is (string? home))
      (is (not (empty? home))))))

(deftest process-getenv-missing-test
  (testing "Process.getenv returns empty string for missing var"
    (let [proc {:nex-builtin-type :Process}
          val (interp/call-builtin-method "p" proc "getenv" ["NEX_NONEXISTENT_VAR_12345"])]
      (is (= "" val)))))

(deftest process-command-line-test
  (testing "Process.command_line returns an array"
    (let [proc {:nex-builtin-type :Process}
          args (interp/call-builtin-method "p" proc "command_line" [])]
      (is (interp/nex-array? args)))))

;; ============================================================================
;; PROCESS TYPECHECKER TESTS
;; ============================================================================

(deftest typechecker-process-create-test
  (testing "Typechecker accepts create Process"
    (let [code "class Main
  feature
    demo() do
      let p: Process := create Process
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result)))))

(deftest typechecker-process-methods-test
  (testing "Typechecker accepts Process method calls"
    (let [code "class Main
  feature
    demo() do
      let p: Process := create Process
      let home: String := p.getenv(\"HOME\")
      p.setenv(\"MY_VAR\", \"value\")
      let args: Array [String] := p.command_line()
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result)))))

(deftest js-process-create-test
  (testing "JS generation for create Process"
    (let [code "class Main
  feature
    demo() do
      let p: Process := create Process
    end
end"
          js-code (js/translate code)]
      (is (str/includes? js-code "({_type: 'Process'})")))))
