(ns nex.io-test
  "Tests for built-in Console and File IO types"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [nex.generator.java :as java]
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
    (is (not (interp/nex-console? {:nex-builtin-type :File})))
    (is (not (interp/nex-console? "hello")))))

(deftest console-get-type-name-test
  (testing "get-type-name returns :Console for console values"
    (is (= :Console (interp/get-type-name {:nex-builtin-type :Console})))))

;; ============================================================================
;; FILE INTERPRETER TESTS
;; ============================================================================

(def test-file-path "/tmp/nex-io-test.txt")

(defn cleanup-test-file [f]
  (f)
  (when (.exists (java.io.File. test-file-path))
    (.delete (java.io.File. test-file-path))))

(use-fixtures :each cleanup-test-file)

(deftest file-create-test
  (testing "Create File.open returns tagged map with path"
    (let [result {:nex-builtin-type :File :path "/tmp/test.txt"}]
      (is (interp/nex-file? result))
      (is (= "/tmp/test.txt" (:path result))))))

(deftest file-write-and-read-test
  (testing "File write and read operations"
    (interp/nex-file-write test-file-path "hello world")
    (is (= "hello world" (interp/nex-file-read test-file-path)))))

(deftest file-append-test
  (testing "File append operation"
    (interp/nex-file-write test-file-path "hello")
    (interp/nex-file-append test-file-path " world")
    (is (= "hello world" (interp/nex-file-read test-file-path)))))

(deftest file-exists-test
  (testing "File exists check"
    (is (not (interp/nex-file-exists? test-file-path)))
    (interp/nex-file-write test-file-path "test")
    (is (interp/nex-file-exists? test-file-path))))

(deftest file-delete-test
  (testing "File delete operation"
    (interp/nex-file-write test-file-path "test")
    (is (interp/nex-file-exists? test-file-path))
    (interp/nex-file-delete test-file-path)
    (is (not (interp/nex-file-exists? test-file-path)))))

(deftest file-lines-test
  (testing "File lines operation returns array of lines"
    (interp/nex-file-write test-file-path "line1\nline2\nline3")
    (let [lines (interp/nex-file-lines test-file-path)]
      (is (interp/nex-array? lines))
      (is (= 3 (interp/nex-array-size lines)))
      (is (= "line1" (interp/nex-array-get lines 0)))
      (is (= "line2" (interp/nex-array-get lines 1)))
      (is (= "line3" (interp/nex-array-get lines 2))))))

(deftest file-type-detection-test
  (testing "File type detection"
    (is (interp/nex-file? {:nex-builtin-type :File :path "/tmp/test.txt"}))
    (is (not (interp/nex-file? {:nex-builtin-type :Console})))
    (is (not (interp/nex-file? "hello")))))

(deftest file-get-type-name-test
  (testing "get-type-name returns :File for file values"
    (is (= :File (interp/get-type-name {:nex-builtin-type :File :path "/tmp/test.txt"})))))

(deftest file-builtin-methods-test
  (testing "File methods through builtin-type-methods dispatch"
    (let [file-obj {:nex-builtin-type :File :path test-file-path}]
      ;; write
      (interp/call-builtin-method "f" file-obj "write" ["hello"])
      (is (= "hello" (interp/call-builtin-method "f" file-obj "read" [])))
      ;; exists
      (is (interp/call-builtin-method "f" file-obj "exists" []))
      ;; append
      (interp/call-builtin-method "f" file-obj "append" [" world"])
      (is (= "hello world" (interp/call-builtin-method "f" file-obj "read" [])))
      ;; lines
      (interp/call-builtin-method "f" file-obj "write" ["a\nb\nc"])
      (let [lines (interp/call-builtin-method "f" file-obj "lines" [])]
        (is (interp/nex-array? lines))
        (is (= 3 (interp/nex-array-size lines))))
      ;; delete
      (interp/call-builtin-method "f" file-obj "delete" [])
      (is (not (interp/call-builtin-method "f" file-obj "exists" [])))
      ;; close (no-op)
      (is (nil? (interp/call-builtin-method "f" file-obj "close" []))))))

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

(deftest typechecker-file-create-test
  (testing "Typechecker accepts create File.open(path)"
    (let [code "class Main
  feature
    demo() do
      let f: File := create File.open(\"data.txt\")
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

(deftest typechecker-file-methods-test
  (testing "Typechecker accepts File method calls"
    (let [code "class Main
  feature
    demo() do
      let f: File := create File.open(\"data.txt\")
      f.write(\"content\")
      f.append(\"more\")
      f.close()
      f.delete()
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

(deftest typechecker-file-return-types-test
  (testing "File methods return correct types"
    (let [code "class Main
  feature
    demo() do
      let f: File := create File.open(\"data.txt\")
      let content: String := f.read()
      let exists: Boolean := f.exists()
      let all_lines: Array [String] := f.lines()
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result)))))

;; ============================================================================
;; JAVA GENERATOR TESTS
;; ============================================================================

(deftest java-console-create-test
  (testing "Java generation for create Console"
    (let [code "class Main
  feature
    demo() do
      let io: Console := create Console
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "new Object() /* Console */")))))

(deftest java-file-create-test
  (testing "Java generation for create File.open"
    (let [code "class Main
  feature
    demo() do
      let f: File := create File.open(\"data.txt\")
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "new java.io.File(\"data.txt\")")))))

(deftest java-console-methods-test
  (testing "Java generation for Console methods"
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
          java-code (java/translate code)]
      ;; Console methods lower to direct Java stdio calls when target type is known.
      (is (str/includes? java-code "System.out.print(\"hello\")"))
      (is (str/includes? java-code "System.out.println(\"world\")"))
      (is (str/includes? java-code "System.err.println(\"oops\")"))
      (is (str/includes? java-code "System.out.println()")))))

(deftest java-file-methods-test
  (testing "Java generation for File methods"
    (let [code "class Main
  feature
    demo() do
      let f: File := create File.open(\"data.txt\")
      f.write(\"content\")
      let s: String := f.read()
      f.delete()
    end
end"
          java-code (java/translate code)]
      ;; File methods lower to Java IO helpers when target type is known.
      (is (str/includes? java-code "java.nio.file.Files.writeString(f.toPath(), \"content\")"))
      (is (str/includes? java-code "java.nio.file.Files.readString(f.toPath())"))
      (is (str/includes? java-code "f.delete()")))))

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

(deftest js-file-create-test
  (testing "JS generation for create File.open"
    (let [code "class Main
  feature
    demo() do
      let f: File := create File.open(\"data.txt\")
    end
end"
          js-code (js/translate code)]
      (is (str/includes? js-code "({_type: 'File', path: \"data.txt\"})")))))

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

(deftest js-file-methods-test
  (testing "JS generation for File methods"
    (let [code "class Main
  feature
    demo() do
      let f: File := create File.open(\"data.txt\")
      f.write(\"content\")
      let s: String := f.read()
      f.delete()
    end
end"
          js-code (js/translate code)]
      ;; File methods lower to Node fs helpers when target type is known.
      (is (str/includes? js-code "require('fs').writeFileSync(f.path, \"content\", 'utf8')"))
      (is (str/includes? js-code "require('fs').readFileSync(f.path, 'utf8')"))
      (is (str/includes? js-code "require('fs').unlinkSync(f.path)")))))

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

;; ============================================================================
;; PROCESS GENERATOR TESTS
;; ============================================================================

(deftest java-process-create-test
  (testing "Java generation for create Process"
    (let [code "class Main
  feature
    demo() do
      let p: Process := create Process
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "new Object() /* Process */")))))

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
