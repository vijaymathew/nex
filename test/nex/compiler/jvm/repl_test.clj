(ns nex.compiler.jvm.repl-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
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
            globals-after-let @(:bindings (:globals ctx0))
            session @repl/*compiled-repl-session*
            ctx1 ctx0
            output (with-out-str
                     (repl/eval-code ctx1 "x + 2"))]
        (is (= :compiled @repl/*repl-backend*))
        (is (str/includes? let-output "40"))
        (is (= {} globals-after-let))
        (is (= 40 (runtime/state-get-value (:state session) "x")))
        (is (= "Integer" (runtime/state-get-type (:state session) "x")))
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

(deftest repl-compiled-backend-direct-cooperating-functions-batch-test
  (testing "compiled backend can handle batches with cooperating function definitions and multiple assignments"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "function add1(n: Integer): Integer
do
  result := n + 1
end

function add2(n: Integer): Integer
do
  result := add1(n) + 1
end

let x: Integer := add2(1)
x := x + 1
x"))
            session @repl/*compiled-repl-session*]
        (is (contains? @(:function-asts session) "add1"))
        (is (contains? @(:function-asts session) "add2"))
        (is (some? (runtime/state-get-fn (:state session) "add1")))
        (is (some? (runtime/state-get-fn (:state session) "add2")))
        (is (= 4 (runtime/state-get-value (:state session) "x")))
        (is (= "Integer" (runtime/state-get-type (:state session) "x")))
        (is (str/includes? output "4"))))))

(deftest compiled-repl-normalizes-calls-only-programs-test
  (testing "compiled helper normalizes legacy :calls-only programs into the same path"
    (let [session (compiled-repl/make-session)
          _ (runtime/state-set-value! (:state session) "x" (int 41))
          _ (runtime/state-set-type! (:state session) "x" "Integer")
          ast {:type :program
               :imports []
               :interns []
               :classes []
               :functions []
               :statements []
               :calls [{:type :call
                        :target nil
                        :method "x"
                        :args []
                        :has-parens false}]}
          result (compiled-repl/compile-and-eval! session ast)]
      (is (:compiled? result))
      (is (= 41 (:result result))))))

(deftest repl-compiled-backend-builtin-print-test
  (testing "compiled backend lowers builtin print through call-runtime and preserves REPL output"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "print(1)"))]
        (is (str/includes? output "1"))))))

(deftest repl-compiled-backend-builtin-type-of-test
  (testing "compiled backend lowers builtin type_of through call-runtime"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "type_of(1)"))]
        (is (str/includes? output "String"))
        (is (str/includes? output "\"Integer\""))))))

(deftest repl-compiled-backend-direct-assignment-test
  (testing "compiled backend can update canonical top-level state via assignment"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str (repl/eval-code ctx0 "let x: Integer := 40"))
            assign-output (with-out-str (repl/eval-code ctx0 "x := x + 2"))
            read-output (with-out-str (repl/eval-code ctx0 "x"))
            session @repl/*compiled-repl-session*]
        (is (str/includes? assign-output "42"))
        (is (str/includes? read-output "42"))
        (is (= 42 (runtime/state-get-value (:state session) "x")))))))

(deftest repl-compiled-backend-status-command-test
  (testing "backend status command reports the current backend"
    (binding [repl/*repl-backend* (atom :compiled)]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/handle-command ctx ":backend status"))]
        (is (str/includes? output "COMPILED"))))))

(deftest compiled-session-stores-deferred-class-metadata-test
  (testing "compiled session keeps deferred and parent metadata as canonical class state"
    (let [session (compiled-repl/make-session)
          deferred-result (compiled-repl/compile-and-eval! session
                                                           (p/ast "deferred class Shape
feature
  area(): Real do end
end"))
          child-result (compiled-repl/compile-and-eval! session
                                                        (p/ast "class Square inherit Shape
feature
  side: Real

  area(): Real
  do
    result := side * side
  end
end"))
          shape-meta (get @(:compiled-classes session) "Shape")
          square-meta (get @(:compiled-classes session) "Square")
          square-area (some #(when (= "area" (:name %)) %) (:methods square-meta))
          shape-area (some #(when (= "area" (:name %)) %) (:methods shape-meta))]
      (is (:compiled? deferred-result))
      (is (:compiled? child-result))
      (is (true? (:deferred? shape-meta)))
      (is (= [] (:parents shape-meta)))
      (is (false? (:deferred? square-meta)))
      (is (= "Shape" (get-in square-meta [:parents 0 :nex-name])))
      (is (string? (get-in square-meta [:parents 0 :jvm-name])))
      (is (= "_parent_Shape" (get-in square-meta [:composition-fields 0 :name])))
      (is (true? (:deferred? shape-area)))
      (is (false? (:override? shape-area)))
      (is (false? (:deferred? square-area)))
      (is (true? (:override? square-area))))))

(deftest compiled-repl-deferred-class-create-deopts-test
  (testing "compiled helper declines direct instantiation of deferred classes"
    (let [session (compiled-repl/make-session)
          _ (compiled-repl/compile-and-eval! session
                                             (p/ast "deferred class Shape
feature
  area(): Real do end
end"))
          result (compiled-repl/compile-and-eval! session
                                                  (p/ast "let s := create Shape"))]
      (is (nil? result)))))

(deftest repl-compiled-backend-deferred-class-metadata-survives-deopt-sync-test
  (testing "compiled deferred-parent class metadata survives explicit interpreter deopt/sync and later compiled dispatch"
    (let [session (compiled-repl/make-session)
          _ (compiled-repl/compile-and-eval! session
                                             (p/ast "deferred class Shape
feature
  area(): Real do end
end"))
          _ (compiled-repl/compile-and-eval! session
                                             (p/ast "class Square inherit Shape
create
  with_side(v: Real) do
    this.side := v
  end
feature
  side: Real

  area(): Real
  do
    result := side * side
  end
end"))
          ctx0 (interp/make-context)
          {:keys [ctx var-types]} (compiled-repl/sync-session->interpreter! session ctx0)
          loop-ast (p/ast "from
  let i: Integer := 0
until
  i > 0
do
  i := i + 1
end")
          _ (doseq [stmt (:statements loop-ast)]
              (interp/eval-node ctx stmt))
          _ (compiled-repl/sync-interpreter->session! session ctx var-types loop-ast)
          assign-result (compiled-repl/compile-and-eval! session
                                                         (p/ast "let s: Shape := create Square.with_side(4.0)"))
          call-result (compiled-repl/compile-and-eval! session
                                                       (p/ast "s.area()"))
          shape-meta (get @(:compiled-classes session) "Shape")
          square-meta (get @(:compiled-classes session) "Square")]
        (is (true? (:deferred? shape-meta)))
        (is (= "Shape" (get-in square-meta [:parents 0 :nex-name])))
        (is (= "Shape" (runtime/state-get-type (:state session) "s")))
        (is (:compiled? assign-result))
        (is (:compiled? call-result))
        (is (= 16.0 (:result call-result))))))
