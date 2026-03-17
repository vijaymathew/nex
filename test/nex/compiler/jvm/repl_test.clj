(ns nex.compiler.jvm.repl-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.compiler.jvm.runtime :as runtime]
            [nex.repl :as repl]))

(deftest repl-compiled-backend-command-and-direct-let-test
  (testing "experimental compiled backend can directly execute top-level let cells"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :interpreter)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str
                (repl/handle-command ctx0 ":backend compiled"))
            let-output (with-out-str
                         (repl/eval-code ctx0 "let x: Integer := 40"))
            ctx1 ctx0
            output (with-out-str
                     (repl/eval-code ctx1 "x + 2"))]
        (is (= :compiled @repl/*repl-backend*))
        (is (str/includes? let-output "40"))
        (is (str/includes? output "42"))))))

(deftest repl-compiled-backend-syncs-existing-interpreter-state-test
  (testing "switching to compiled syncs existing interpreter state into the compiled session"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :interpreter)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            ctx1 (binding [*out* (java.io.StringWriter.)]
                   (repl/eval-code ctx0 "let x: Integer := 40"))
            _ (with-out-str
                (repl/handle-command ctx1 ":backend compiled"))
            output (with-out-str
                     (repl/eval-code ctx1 "x + 2"))]
        (is (str/includes? output "42"))))))

(deftest repl-compiled-backend-deopt-sync-function-definition-test
  (testing "compiled backend deopts for function definitions and syncs them back for later compiled calls"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            ctx1 (binding [*out* (java.io.StringWriter.)]
                   (repl/eval-code ctx0 "function inc(n: Integer): Integer do result := n + 1 end"))
            output (with-out-str
                     (repl/eval-code ctx1 "inc(40)"))]
        (is (str/includes? output "41"))))))

(deftest repl-compiled-backend-direct-function-definition-test
  (testing "compiled backend can register top-level function definitions without deopting"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            def-output (with-out-str
                         (repl/eval-code ctx0 "function inc(n: Integer): Integer do result := n + 1 end"))
            globals-after-def @(:bindings (:globals ctx0))
            call-output (with-out-str
                          (repl/eval-code ctx0 "inc(40)"))
            session @repl/*compiled-repl-session*]
        (is (= {} globals-after-def))
        (is (contains? @(:function-asts session) "inc"))
        (is (some? (runtime/state-get-fn (:state session) "inc")))
        (is (str/blank? def-output))
        (is (str/includes? call-output "41"))))))

(deftest repl-compiled-backend-direct-function-declarations-test
  (testing "compiled backend can register forward declarations so mutual-recursion setup does not deopt"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            decl1-output (with-out-str
                           (repl/eval-code ctx0 "function is_even(n: Integer): Boolean"))
            decl2-output (with-out-str
                           (repl/eval-code ctx0 "function is_odd(n: Integer): Boolean"))
            def1-output (with-out-str
                          (repl/eval-code ctx0 "function is_even(n: Integer): Boolean
do
  if n = 0 then
    result := true
  else
    result := is_odd(n - 1)
  end
end"))
            def2-output (with-out-str
                          (repl/eval-code ctx0 "function is_odd(n: Integer): Boolean
do
  if n = 0 then
    result := false
  else
    result := is_even(n - 1)
  end
end"))
            call-output (with-out-str
                          (repl/eval-code ctx0 "is_even(4)"))
            session @repl/*compiled-repl-session*]
        (is (contains? @(:function-asts session) "is_even"))
        (is (contains? @(:function-asts session) "is_odd"))
        (is (some? (runtime/state-get-fn (:state session) "is_even")))
        (is (some? (runtime/state-get-fn (:state session) "is_odd")))
        (is (str/blank? decl1-output))
        (is (str/blank? decl2-output))
        (is (not (str/includes? def1-output "Type checking failed")))
        (is (not (str/includes? def2-output "Type checking failed")))
        (is (str/includes? call-output "true"))))))

(deftest repl-compiled-backend-direct-mixed-batch-test
  (testing "compiled backend can handle imports/intern-free mixed batches of functions and statements"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "function inc(n: Integer): Integer
do
  result := n + 1
end

let x: Integer := inc(40)
x + 1"))
            session @repl/*compiled-repl-session*]
        (is (contains? @(:function-asts session) "inc"))
        (is (some? (runtime/state-get-fn (:state session) "inc")))
        (is (= 41 (runtime/state-get-value (:state session) "x")))
        (is (= "Integer" (runtime/state-get-type (:state session) "x")))
        (is (not (str/includes? output "Type checking failed")))
        (is (str/includes? output "42"))))))

(deftest repl-compiled-backend-direct-assignment-test
  (testing "compiled backend can update canonical top-level state via assignment"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str (repl/eval-code ctx0 "let x: Integer := 40"))
            assign-output (with-out-str (repl/eval-code ctx0 "x := x + 2"))
            read-output (with-out-str (repl/eval-code ctx0 "x"))]
        (is (str/includes? assign-output "42"))
        (is (str/includes? read-output "42"))))))

(deftest repl-compiled-backend-status-command-test
  (testing "backend status command reports the current backend"
    (binding [repl/*repl-backend* (atom :compiled)]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/handle-command ctx ":backend status"))]
        (is (str/includes? output "COMPILED"))))))
