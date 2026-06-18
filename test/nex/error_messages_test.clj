(ns nex.error-messages-test
  "Runtime errors surfaced to a Nex programmer must not leak interpreter
   internals (Clojure/JVM exception wording such as \"long overflow\", \"For
   input string\", or \"class java.lang.String cannot be cast to ...\").
   `interp/nex-error-message` translates host exceptions to Nex-level wording,
   and it is applied at every boundary where an error reaches the user: the REPL
   and file-runner display, and the `exception` value bound inside `rescue`."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]))

(defn- thrown [f]
  (try (f) nil (catch Throwable t t)))

(deftest translates-host-exceptions
  (testing "integer overflow does not leak \"long overflow\""
    (is (= "Arithmetic overflow"
           (interp/nex-error-message (thrown #(+ Long/MAX_VALUE 1))))))
  (testing "number parsing does not leak \"For input string\""
    (is (= "Not a valid number"
           (interp/nex-error-message (thrown #(Long/parseLong "abc"))))))
  (testing "a class cast does not leak java.lang class names"
    (let [m (interp/nex-error-message (thrown #(- "a" 1)))]
      (is (= "Type error: a value was not of the expected type" m))
      (is (not (re-find #"java\.lang|clojure\.lang|cannot be cast" m)))))
  (testing "division by zero is uniform"
    (is (= "Division by zero"
           (interp/nex-error-message (thrown #(/ 1 0)))))))

(deftest preserves-interpreter-level-messages
  (testing "the interpreter's own ex-info messages pass through unchanged"
    (is (= "Method not found on type: foo"
           (interp/nex-error-message (ex-info "Method not found on type: foo" {}))))
    (is (= "Precondition violation: key_must_exist"
           (interp/nex-error-message (ex-info "Precondition violation: key_must_exist" {}))))))

(defn- run [body]
  (let [ast (p/ast (str "class T\n  feature\n    d() do\n      " body "\n    end\nend"))
        ctx (interp/make-context)
        class-def (first (:classes ast))]
    (interp/register-class ctx class-def)
    (let [method-def (-> class-def :body first :members first)
          env (interp/make-env (:globals ctx))
          c (assoc ctx :current-env env)]
      (doseq [s (:body method-def)] (interp/eval-node c s))
      @(:output c))))

(deftest rescue-exposes-clean-message
  (testing "the exception value inside rescue carries the clean message, not host wording"
    ;; `exception` is the clean message string; `print` renders a string in quotes.
    (is (= ["\"Arithmetic overflow\""]
           (run (str "do\n  let a: Integer := 9223372036854775807\n  let b: Integer := a + 1\n"
                     "rescue\n  print(exception)\nend"))))
    (is (= ["\"Not a valid number\""]
           (run "do\n  print(\"abc\".to_integer)\nrescue\n  print(exception)\nend")))))
