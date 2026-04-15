(ns nex.compiler.jvm.repl-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.debugger :as dbg]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.compiler.jvm.runtime :as runtime]
            [nex.repl :as repl])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(defn- start-test-http-server []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server
     "/hello"
     (proxy [HttpHandler] []
       (handle [^HttpExchange exchange]
         (let [method (.getRequestMethod exchange)
               body (slurp (.getRequestBody exchange))
               text (if (= method "POST")
                      (str "posted:" body)
                      "hello")
               bytes (.getBytes text StandardCharsets/UTF_8)
               headers (.getResponseHeaders exchange)]
           (.add headers "X-Nex" "ok")
           (.sendResponseHeaders exchange 200 (long (alength bytes)))
           (with-open [os (.getResponseBody exchange)]
             (.write os bytes))))))
    (.start server)
    server))

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
        (is (= {"x" 40} globals-after-let))
        (is (= 40 (runtime/state-get-value (:state session) "x")))
        (is (= "Integer" (runtime/state-get-type (:state session) "x")))
        (is (str/includes? output "42"))))))

(deftest repl-compiled-backend-typed-map-let-displays-binding-type-test
  (testing "compiled backend shows the declared binding type for top-level typed map lets"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx (repl/init-repl-context)
            let-output (with-out-str
                         (repl/eval-code ctx "let capitals: Map[String, String] := {\"France\": \"Paris\", \"Japan\": \"Tokyo\"}"))
            var-output (with-out-str
                         (repl/eval-code ctx "capitals"))]
        (is (str/includes? let-output "Map[String, String]"))
        (is (not (str/includes? let-output "Any {")))
        (is (str/includes? var-output "Map[String, String]"))))))

(deftest repl-compiled-backend-private-field-is-not-publicly-readable-test
  (testing "compiled backend rejects top-level access to private fields while keeping public methods callable"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx "class Counter
  create
    make(start: Integer) do
      count := start
    end
  feature
    increment() do
      count := count + 1
    end
    current(): Integer do
      result := count
    end
  private feature
    count: Integer
end"))
            _ (with-out-str (repl/eval-code ctx "let c := create Counter.make(10)"))
            _ (with-out-str (repl/eval-code ctx "c.increment"))
            current-output (with-out-str (repl/eval-code ctx "c.current"))
            private-output (with-out-str (repl/eval-code ctx "c.count"))]
        (is (str/includes? current-output "11"))
        (is (str/includes? private-output "Error:"))
        (is (not (str/includes? private-output "Integer 11")))))))

(deftest repl-compiled-backend-public-field-is-not-publicly-writable-test
  (testing "compiled backend rejects top-level writes to public fields"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx "class Counter
  feature
    value: Integer

    set_to(v: Integer): Integer
    do
      this.value := v
      result := this.value
    end

    current(): Integer
    do
      result := this.value
    end
end"))
            _ (with-out-str (repl/eval-code ctx "let c := create Counter"))
            assign-output (with-out-str (repl/eval-code ctx "c.value := 9"))
            current-output (with-out-str (repl/eval-code ctx "c.current"))]
        (is (str/includes? assign-output "Error:"))
        (is (str/includes? assign-output "Cannot assign to field value outside of class Counter"))
        (is (str/includes? current-output "0"))))))

(deftest repl-compiled-backend-allows-same-class-write-to-other-instance-test
  (testing "compiled backend allows a class to assign its own field on another instance of the same class"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx "class Counter
  feature
    value: Integer
    sync_to(other: Counter, v: Integer) do
      other.value := v
    end
    current(): Integer
    do
      result := value
    end
  end"))
            _ (with-out-str (repl/eval-code ctx "let a := create Counter"))
            _ (with-out-str (repl/eval-code ctx "let b := create Counter"))
            sync-output (with-out-str (repl/eval-code ctx "a.sync_to(b, 9)"))
            current-output (with-out-str (repl/eval-code ctx "b.current"))]
        (is (not (str/includes? sync-output "Error:")) sync-output)
        (is (str/includes? current-output "9"))))))

(deftest repl-compiled-backend-rejects-multiple-inheritance-parent-field-writes-test
  (testing "compiled backend rejects direct writes to either parent field from a multiply-inheriting child"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx "class A
  feature
    x: Integer
end

class B
  feature
    y: Integer
end"))
            bad-left-output (with-out-str
                              (repl/eval-code ctx "class CX
  inherit A, B
  feature
    break_x() do
      this.x := 1
    end
end"))
            bad-right-output (with-out-str
                               (repl/eval-code ctx "class CY
  inherit A, B
  feature
    break_y() do
      this.y := 2
    end
end"))]
        (is (str/includes? bad-left-output "Error:"))
        (is (str/includes? bad-left-output "Cannot assign to field x outside of class A"))
        (is (str/includes? bad-right-output "Error:"))
        (is (str/includes? bad-right-output "Cannot assign to field y outside of class B"))))))

(deftest repl-compiled-backend-self-inheritance-reports-error-test
  (testing "compiled-default REPL reports self-inheritance as a user error instead of crashing later"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "class C inherit C end"))]
        (is (str/includes? output "cannot inherit from itself"))
        (is (not (str/includes? output "StackOverflowError")))))))

(deftest repl-compiled-backend-parent-qualified-zero-arg-method-typechecks-test
  (testing "compiled-default REPL typechecks parent-qualified zero-arg methods without parentheses"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx0 "class A
feature
  MAX = 10
  x: Integer
  p(y: Integer): Integer do result := x + y end
create
  make(x: Integer) do this.x := x end
end"))
            _ (with-out-str
                (repl/eval-code ctx0 "class B
feature
  p(): Integer do result := 100 end
end"))
            class-output (with-out-str
                           (repl/eval-code ctx0 "class C inherit A, B
feature
  p(): Integer do result := A.MAX + A.p(20) + B.p end
create
  make(x: Integer) do
    A.make(x)
  end
end"))
            _ (with-out-str (repl/eval-code ctx0 "let c := create C.make(2)"))
            call-output (with-out-str (repl/eval-code ctx0 "c.p"))]
        (is (not (str/includes? class-output "Type error:")) class-output)
        (is (not (str/includes? class-output "Error:")) class-output)
        (is (str/includes? call-output "132"))))))

(deftest repl-compiled-backend-static-type-zero-arg-method-mismatch-reports-arity-test
  (testing "compiled-default REPL reports method arity mismatch instead of undefined field for zero-arg query syntax"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx0 "class A
feature
  x: Integer
  p(y: Integer): Integer do result := x + y end
create
  make(x: Integer) do this.x := x end
end"))
            _ (with-out-str
                (repl/eval-code ctx0 "class B
feature
  p(): Integer do result := 100 end
end"))
            _ (with-out-str
                (repl/eval-code ctx0 "class C inherit A, B
feature
  p(): Integer do result := B.p end
create
  make(x: Integer) do
    A.make(x)
  end
end"))
            _ (with-out-str (repl/eval-code ctx0 "let c1: A := create C.make(20)"))
            output (with-out-str (repl/eval-code ctx0 "c1.p"))]
        (is (str/includes? output "requires 1 argument(s); zero-argument access is invalid") output)
        (is (not (str/includes? output "Undefined field: p")) output)))))

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

(deftest repl-compiled-backend-debugger-routes-to-interpreter-test
  (testing "compiled backend falls back to the interpreter path when the debugger is enabled"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (try
          (dbg/set-enabled! true)
          (let [output (with-redefs [compiled-repl/compile-and-eval!
                                     (fn [& _]
                                       (throw (ex-info "compiled path should not be used while debugger is enabled" {})))]
                         (with-out-str
                           (repl/eval-code ctx0 "let x: Integer := 40")))
                session @repl/*compiled-repl-session*]
            (is (not (str/includes? output "Error:")))
            (is (= 40 (get @(:bindings (:globals ctx0)) "x")))
            (is (= 40 (runtime/state-get-value (:state session) "x")))
            (is (= "Integer" (get @repl/*repl-var-types* "x"))))
          (finally
            (dbg/set-enabled! false)
            (dbg/reset-run-state!)))))))

(deftest repl-compiled-backend-mixed-numeric-arithmetic-test
  (testing "compiled backend widens mixed numeric arithmetic instead of deopting or failing"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str (repl/eval-code ctx0 "let x := 10"))
            _ (with-out-str (repl/eval-code ctx0 "let y := 20.323"))
            output (with-out-str (repl/eval-code ctx0 "x + y"))]
        (is (not (str/includes? output "Error:")))
        (is (str/includes? output "30.323"))))))

(deftest repl-compiled-backend-builtin-numeric-method-compare-test
  (testing "compiled backend preserves builtin numeric receiver types for later comparisons"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str (repl/eval-code ctx0 "let n := -5"))
            int-output (with-out-str (repl/eval-code ctx0 "n.abs = 5"))
            real-output (with-out-str (repl/eval-code ctx0 "n.abs = 5.0"))]
        (is (not (str/includes? int-output "Error:")))
        (is (str/includes? int-output "true"))
        (is (not (str/includes? real-output "Error:")))
        (is (str/includes? real-output "true"))))))

(deftest repl-compiled-backend-generic-class-definition-deopts-cleanly-test
  (testing "compiled default deopts generic class definitions that still need the interpreter path"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "class Stack [G]
  create
    make() do
      items := []
    end
  feature
    items: Array[G]
    pop(): G do
      result := items.get(items.length - 1)
      items.remove(items.length - 1)
    end
end"))]
        (is (not (str/includes? output "Error:")))
        (is (contains? @(:classes ctx0) "Stack"))))))

(deftest repl-compiled-backend-unspecialized-generic-method-return-degrades-to-any-test
  (testing "compiled backend does not try to load raw generic parameter names when calling unspecialized generic methods"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (with-out-str
          (repl/eval-code ctx0 "class Stack [G]
  create
    make() do
      items := []
    end
  feature
    items: Array[G]
    push(value: G)
      do
        items.add(value)
      ensure
        last_is_value: items.get(items.length - 1) = value
      end
    pop(): G
      require
        not_empty: items.length > 0
      do
        result := items.get(items.length - 1)
      let old_len := items.length
        items.remove(items.length - 1)
      ensure
        length_decreased_by_on: items.length = old_len - 1
      end
end"))
        (with-out-str (repl/eval-code ctx0 "let s := create Stack.make"))
        (with-out-str (repl/eval-code ctx0 "s.push(1)"))
        (with-out-str (repl/eval-code ctx0 "s.push(2)"))
        (let [pop-output (with-out-str (repl/eval-code ctx0 "s.pop"))]
          (is (not (str/includes? pop-output "Error:")) pop-output)
          (is (str/includes? pop-output "2") pop-output))))))

(deftest repl-compiled-backend-redefined-class-constructor-available-after-deopt-test
  (testing "compiled backend refreshes class metadata after interpreter-side class redefinition"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (with-out-str
          (repl/eval-code ctx0 "class Point
  create
    make(px, py: Real) do
      x := px
      y := py
    end
  feature
    x: Real
    y: Real
end"))
        (with-out-str
          (repl/eval-code ctx0 "let p := create Point.make(3.0, 4.0)"))
        (let [redef-output (with-out-str
                             (repl/eval-code ctx0 "class Point
  create
    origin() do
      x := 0.0
      y := 0.0
    end
    make(px, py: Real) do
      x := px
      y := py
    end
  feature
    x: Real
    y: Real
end"))
              create-output (with-out-str
                              (repl/eval-code ctx0 "let p1 := create Point.origin"))
              x-output (with-out-str
                         (repl/eval-code ctx0 "p1.x"))]
          (is (not (str/includes? redef-output "Error:")))
          (is (not (str/includes? create-output "Error:")))
          (is (str/includes? x-output "0.0")))))))

(deftest repl-compiled-backend-redefined-class-method-available-after-deopt-test
  (testing "compiled backend refreshes class metadata when a redefined class gains a new method"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (with-out-str
          (repl/eval-code ctx0 "class Todo_Item
  create
    make(t: String) do
      title := t
      done := false
    end
  feature
    title: String
    done: Boolean
    mark_done() do
      done := true
    end
    is_done(): Boolean do
      result := done
    end
end"))
        (with-out-str
          (repl/eval-code ctx0 "class Task_List
  create
    make() do
      tasks := []
    end
  feature
    tasks: Array[Todo_Item]
    add_task(title: String) do
      tasks.add(create Todo_Item.make(title))
    end
    task_at(index: Integer): Todo_Item do
      result := tasks.get(index)
    end
end"))
        (with-out-str
          (repl/eval-code ctx0 "let todo := create Task_List.make"))
        (with-out-str
          (repl/eval-code ctx0 "todo.add_task(\"write tests\")"))
        (let [redef-output (with-out-str
                             (repl/eval-code ctx0 "class Task_List
  create
    make() do
      tasks := []
    end
  feature
    tasks: Array[Todo_Item]
    add_task(title: String) do
      tasks.add(create Todo_Item.make(title))
    end
    task_at(index: Integer): Todo_Item do
      result := tasks.get(index)
    end
    completed_count(): Integer do
      result := 0
      across tasks as task do
        if task.is_done() then
          result := result + 1
        end
      end
    end
end"))
              new-todo-output (with-out-str
                                (repl/eval-code ctx0 "let todo := create Task_List.make"))
              count-output (with-out-str
                             (repl/eval-code ctx0 "todo.completed_count"))]
          (is (not (str/includes? redef-output "Error:")))
          (is (not (str/includes? new-todo-output "Error:")))
          (is (not (str/includes? count-output "Error:")))
          (is (str/includes? count-output "0")))))))

(deftest repl-compiled-backend-inherited-field-read-test
  (testing "compiled backend reads inherited fields through the composition carrier"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (with-out-str
          (repl/eval-code ctx0 "class Shape
  create
    make(c: String) do
      colour := c
    end
  feature
    colour: String
    describe(): String do
      result := \"A \" + colour + \" shape\"
    end
end"))
        (with-out-str
          (repl/eval-code ctx0 "class Circle inherit Shape
  create
    make(c: String, r: Real) do
      super.make(c)
      radius := r
    end
  feature
    radius: Real
    describe(): String do
      result := \"A \" + colour + \" circle with radius \" + radius.to_string
    end
end"))
        (with-out-str
          (repl/eval-code ctx0 "let c := create Circle.make(\"red\", 5.0)"))
        (let [field-output (with-out-str
                             (repl/eval-code ctx0 "c.colour"))
              output (with-out-str
                       (repl/eval-code ctx0 "c.describe"))]
          (is (not (str/includes? field-output "Error:")))
          (is (str/includes? field-output "red"))
          (is (not (str/includes? output "Error:")))
          (is (str/includes? output "A red circle with radius 5.0")))))))

(deftest sync-session-interpreter-inherited-field-read-test
  (testing "interpreter fallback can read inherited public fields from compiled objects"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval!
                         session
                         (p/ast "class Account
feature
  balance: Real
end

class Savings_Account
inherit
  Account
create
  make(opening_balance: Real, current_interest_rate: Real) do
    balance := opening_balance
    interest_rate := current_interest_rate
  end
feature
  interest_rate: Real
end

let s := create Savings_Account.make(10.0, 0.2)"))
          ctx (repl/init-repl-context)]
      (is (:compiled? define-result))
      (compiled-repl/sync-session->interpreter! session ctx)
      (let [result (interp/eval-node ctx (-> (p/ast "s.balance") :statements first))]
        (is (= 10.0 result))))))

(deftest repl-compiled-backend-user-task-shadow-does-not-hit-builtin-task-runtime-test
  (testing "compiled backend treats a user-defined Task class as a user class, not the builtin Task runtime type"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (with-out-str
          (repl/eval-code ctx0 "class Task
create
  make(id: String, status: String) do
    this.id := id
    this.status := status
  end
feature
  id: String
  status: String
invariant
  id_present: id /= \"\"
  valid_status:
    status = \"PENDING\" or
    status = \"IN_TRANSIT\" or
    status = \"DELIVERED\" or
    status = \"FAILED\"
end"))
        (with-out-str
          (repl/eval-code ctx0 "class Task_Sequence
create
  make(t1: Task, t2: Task, t3: Task) do
    this.t1 := t1
    this.t2 := t2
    this.t3 := t3
  end
feature
  t1: Task
  t2: Task
  t3: Task

  find_by_id(task_id: String): String
    require
      id_present: task_id /= \"\"
    do
      if t1.id = task_id then
        result := t1.status
      elseif t2.id = task_id then
        result := t2.status
      elseif t3.id = task_id then
        result := t3.status
      else
        result := \"NOT_FOUND\"
      end
    ensure
      declared_result:
        result = \"PENDING\" or
        result = \"IN_TRANSIT\" or
        result = \"DELIVERED\" or
        result = \"FAILED\" or
        result = \"NOT_FOUND\"
    end
end"))
        (with-out-str
          (repl/eval-code ctx0 "let ts := create Task_Sequence.make(create Task.make(\"123\", \"PENDING\"), create Task.make(\"456\", \"IN_TRANSIT\"), create Task.make(\"789\", \"DELIVERED\"))"))
        (let [output (with-out-str
                       (repl/eval-code ctx0 "ts.find_by_id(\"456\")"))]
          (is (not (str/includes? output "Error:")))
          (is (str/includes? output "IN_TRANSIT")))))))

(deftest repl-compiled-backend-polymorphic-across-test
  (testing "compiled backend supports polymorphic method dispatch in across loops"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (with-out-str
          (repl/eval-code ctx0 "class Shape
  create
    make(c: String) do
      colour := c
    end
  feature
    colour: String
    describe(): String do
      result := \"A \" + colour + \" shape\"
    end
end"))
        (with-out-str
          (repl/eval-code ctx0 "class Circle inherit Shape
  create
    make(c: String, r: Real) do
      super.make(c)
      radius := r
    end
  feature
    radius: Real
    describe(): String do
      result := \"A \" + colour + \" circle with radius \" + radius.to_string
    end
end"))
        (with-out-str
          (repl/eval-code ctx0 "class Rectangle inherit Shape
  create
    make(c: String, w: Real, h: Real) do
      super.make(c)
      width := w
      height := h
    end
  feature
    width: Real
    height: Real
    describe(): String do
      result := \"A \" + colour + \" rectangle \" + width.to_string + \" x \" + height.to_string
    end
end"))
        (with-out-str
          (repl/eval-code ctx0 "let shapes: Array[Shape] := []"))
        (with-out-str
          (repl/eval-code ctx0 "shapes.add(create Circle.make(\"red\", 5.0))"))
        (with-out-str
          (repl/eval-code ctx0 "shapes.add(create Rectangle.make(\"blue\", 4.0, 3.0))"))
        (with-out-str
          (repl/eval-code ctx0 "shapes.add(create Circle.make(\"green\", 2.0))"))
        (let [output (with-out-str
                       (repl/eval-code ctx0 "across shapes as s do\n  print(s.describe)\nend"))]
          (is (not (str/includes? output "Error:")) (str "across dispatch: " output))
          (is (str/includes? output "red circle"))
          (is (str/includes? output "blue rectangle"))
          (is (str/includes? output "green circle")))))))

(deftest repl-compiled-backend-inherited-method-dispatch-test
  (testing "compiled backend dispatches inherited methods through composition"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (with-out-str
          (repl/eval-code ctx0 "class Shape
  create
    make(c: String) do
      colour := c
    end
  feature
    colour: String
    describe(): String do
      result := \"A \" + colour + \" shape\"
    end
end"))
        ;; Circle does NOT override describe — relies on delegation
        (with-out-str
          (repl/eval-code ctx0 "class Circle inherit Shape
  create
    make(c: String, r: Real) do
      super.make(c)
      radius := r
    end
  feature
    radius: Real
end"))
        (with-out-str
          (repl/eval-code ctx0 "let shapes: Array[Shape] := []"))
        (with-out-str
          (repl/eval-code ctx0 "shapes.add(create Circle.make(\"red\", 5.0))"))
        (let [output (with-out-str
                       (repl/eval-code ctx0 "across shapes as s do\n  print(s.describe)\nend"))]
          (is (not (str/includes? output "Error:")) (str "inherited dispatch: " output))
          (is (str/includes? output "A red shape")))))))

(deftest repl-compiled-backend-self-dispatch-through-override-test
  (testing "inherited method dispatches self-calls to child overrides"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (with-out-str
          (repl/eval-code ctx0 "class Shape
  create
    make(c: String) do
      colour := c
    end
  feature
    colour: String
    area(): Real do result := 0.0 end
    describe(): String do
      result := \"A \" + colour + \" shape with area \" + area.to_string
    end
end"))
        (with-out-str
          (repl/eval-code ctx0 "class Circle inherit Shape
  create
    make(c: String, r: Real) do
      super.make(c)
      radius := r
    end
  feature
    radius: Real
    area(): Real do
      result := 3.14159 * radius * radius
    end
end"))
        (with-out-str
          (repl/eval-code ctx0 "let c := create Circle.make(\"red\", 5.0)"))
        ;; Shape.describe() calls this.area() — should dispatch to Circle.area()
        (let [output (with-out-str
                       (repl/eval-code ctx0 "c.describe"))]
          (is (not (str/includes? output "Error:")) (str "self-dispatch: " output))
          (is (str/includes? output "78.53975") (str "expected Circle.area(): " output))
          (is (not (str/includes? output "area 0.0")) "should not use Shape.area()"))))))

(deftest repl-compiled-backend-generic-parent-inheritance-test
  (testing "compiled backend supports generic inheritance syntax like inherit Stack[G]"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (with-out-str
          (repl/eval-code ctx0 "class Stack [G]
  create
    make() do
      items := []
    end
  feature
    items: Array[G]
    push(value: G) do
      items.add(value)
    end
    size(): Integer do
      result := items.length
    end
end"))
        (let [def-output (with-out-str
                           (repl/eval-code ctx0 "class Bounded_Stack [G] inherit Stack[G]
  create
    make(max: Integer) do
      super.make
      max_size := max
    end
  feature
    max_size: Integer
    is_full(): Boolean do
      result := size = max_size
    end
    push(value: G) do
      if not is_full then
        super.push(value)
      end
    end
end"))
              _ (with-out-str
                  (repl/eval-code ctx0 "let s := create Bounded_Stack[Integer].make(3)"))
              _ (with-out-str (repl/eval-code ctx0 "s.push(1)"))
              _ (with-out-str (repl/eval-code ctx0 "s.push(2)"))
              _ (with-out-str (repl/eval-code ctx0 "s.push(3)"))
              _ (with-out-str (repl/eval-code ctx0 "s.push(4)"))
              size-output (with-out-str
                            (repl/eval-code ctx0 "s.size"))]
          (is (not (str/includes? def-output "Error:")))
          (is (not (str/includes? size-output "Error:")))
          (is (str/includes? size-output "3"))
          (is (contains? @(:compiled-classes @repl/*compiled-repl-session*) "Stack"))
          (is (contains? @(:compiled-classes @repl/*compiled-repl-session*) "Bounded_Stack")))))))

(deftest repl-compiled-backend-super-constructor-transcript-test
  (testing "compiled backend keeps account-style inheritance examples on the compiled path"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            define-output (with-out-str
                            (repl/eval-code ctx0 "class Account
  create
    make(name: String, initial: Real) do
      owner := name
      balance := initial
    end
  feature
    owner: String
    balance: Real
    deposit(amount: Real) do
      balance := balance + amount
    end
    withdraw(amount: Real): Boolean do
      if amount <= balance then
        balance := balance - amount
        result := true
      else
        result := false
      end
    end
    get_balance(): Real do
      result := balance
    end
    describe(): String do
      result := owner + \": \" + balance.to_string
    end
end

class SavingsAccount inherit Account
  create
    make(name: String, initial, rate: Real) do
      super.make(name, initial)
      interest_rate := rate
    end
  feature
    interest_rate: Real
    apply_interest() do
      balance := balance + balance * interest_rate
    end
    describe(): String do
      result := super.describe + \" (savings, rate: \" + interest_rate.to_string + \")\"
    end
end

class OverdraftAccount inherit Account
  create
    make(name: String, initial, limit: Real) do
      super.make(name, initial)
      overdraft_limit := limit
    end
  feature
    overdraft_limit: Real
    withdraw(amount: Real): Boolean do
      if balance - amount >= -overdraft_limit then
        balance := balance - amount
        result := true
      else
        result := false
      end
    end
    describe(): String do
      result := super.describe + \" (overdraft limit: \" + overdraft_limit.to_string + \")\"
    end
end"))
            create-output (with-out-str
                            (repl/eval-code ctx0 "let a := create SavingsAccount.make(\"Bob\", 1000.0, 0.02)"))
            describe-output (with-out-str
                              (repl/eval-code ctx0 "a.describe"))]
        (is (not (str/includes? define-output "Error:")))
        (is (not (str/includes? create-output "Error:")))
        (is (not (str/includes? describe-output "Error:")))
        (is (str/includes? describe-output "Bob: 1000.0 (savings, rate: 0.02)"))
        (is (contains? @(:compiled-classes @repl/*compiled-repl-session*) "Account"))
        (is (contains? @(:compiled-classes @repl/*compiled-repl-session*) "SavingsAccount"))
        (is (contains? @(:compiled-classes @repl/*compiled-repl-session*) "OverdraftAccount"))))))

(deftest repl-compiled-backend-map-across-entry-get-test
  (testing "compiled backend can iterate map entries whose static element type is Any"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx0 "let capitals := {\"France\": \"Paris\", \"Japan\": \"Tokyo\", \"Brazil\": \"Brasília\"}"))
            output (with-out-str
                     (repl/eval-code ctx0 "across capitals as entry do
  print(entry.get(0) + \" -> \" + entry.get(1))
end"))]
        (is (not (str/includes? output "Error:")))
        (is (str/includes? output "France -> Paris"))
        (is (str/includes? output "Japan -> Tokyo"))
        (is (str/includes? output "Brazil -> Brasília"))))))

(deftest repl-compiled-backend-across-array-item-length-test
  (testing "compiled backend can use length on across-bound array items lowered as Any"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx0 "let word_lengths: Map[String, Integer] := {}"))
            _ (with-out-str
                (repl/eval-code ctx0 "let words := [\"apple\", \"fig\", \"banana\", \"kiwi\"]"))
            output (with-out-str
                     (repl/eval-code ctx0 "across words as w do
  word_lengths.put(w, w.length)
end"))
            final-output (with-out-str (repl/eval-code ctx0 "word_lengths"))]
        (is (not (str/includes? output "Error:")))
        (is (str/includes? final-output "apple"))
        (is (str/includes? final-output "5"))
        (is (str/includes? final-output "banana"))
        (is (str/includes? final-output "6"))))))

(deftest repl-compiled-backend-across-string-test
  (testing "compiled backend can iterate a string through dynamic cursor-style Any methods"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "across \"hello\" as ch do
  print(ch)
end"))]
        (is (not (str/includes? output "Error:")))
        (is (str/includes? output "h"))
        (is (str/includes? output "e"))
        (is (str/includes? output "o"))))))

(deftest repl-compiled-backend-syncs-var-type-for-top-level-function-call-let-test
  (testing "compiled backend keeps top-level let types when the value is a compiled function call"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (with-out-str
          (repl/eval-code ctx0 "function word_frequencies(text: String): Map[String, Integer]
do
  result := {}
  let words := text.to_lower.split(\" \")
  across words as w do
    let count := result.try_get(w, 0)
    result.put(w, count + 1)
  end
end"))
        (with-out-str
          (repl/eval-code ctx0 "let text := \"to be or not to be that is the question to be to\""))
        (with-out-str
          (repl/eval-code ctx0 "let freq := word_frequencies(text)"))
        (is (contains? @repl/*repl-var-types* "freq"))
        (let [output (with-out-str (repl/eval-code ctx0 "freq.get(\"to\")"))]
          (is (not (str/includes? output "Error:")))
          (is (str/includes? output "4")))))))

(deftest repl-compiled-backend-across-integer-to-real-definition-test
  (testing "compiled backend no longer loses across element types to Any during class definition"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            def-output (with-out-str
                         (repl/eval-code ctx0 "class Student
create
  make() do
    scores := []
  end
feature
  scores: Array[Integer]
  add_score(s: Integer) do
    scores.add(s)
  end
  average(): Real do
    result := 0.0
    across scores as s do
      result := result + s.to_real
    end
    result := result / scores.length.to_real
  end
end"))
            type-output (with-out-str (repl/eval-code ctx0 "type_of(Student)"))]
        (is (not (str/includes? def-output "Error:")))
        (is (str/includes? type-output "Student"))))))

(deftest repl-compiled-backend-function-with-rescue-compiles-test
  (testing "compiled backend uses distinct slots for rescue throwable state and visible exception values"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            def-output (with-out-str
                         (repl/eval-code ctx0 "function load_configuration(path: String): String
require
  path_not_empty: path.length > 0
do
  raise \"file missing\"
rescue
  print(\"using built-in defaults: \" + exception.to_string)
                          result := \"theme=light%ntimeout=30\"
end"))
            call-output (with-out-str
                          (repl/eval-code ctx0 "load_configuration(\"config.txt\")"))]
        (is (not (str/includes? def-output "Error:")))
        (is (str/includes? call-output "using built-in defaults: "))
        (is (str/includes? call-output "file missing"))
        (is (str/includes? call-output "\"theme=light%ntimeout=30\""))
        (is (not (str/includes? call-output "Error:")))))))

(deftest repl-compiled-backend-string-split-test
  (testing "compiled backend keeps String.split on the compiled path with the compiler's Array representation"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str (repl/eval-code ctx0 "let s: String := \"A B\""))
            output (with-out-str (repl/eval-code ctx0 "type_of(s.split(\" \"))"))]
        (is (not (str/includes? output "Error:")))
        (is (str/includes? output "\"Array\""))))))

(deftest repl-compiled-backend-returning-function-statement-tail-test
  (testing "compiled backend handles returning routines whose tail is statement-shaped"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            def-output (with-out-str
                         (repl/eval-code ctx0 "function choose(flag: Boolean): String
do
  if flag then
    result := \"yes\"
  else
    result := \"no\"
  end
end"))
            call-output (with-out-str
                          (repl/eval-code ctx0 "choose(false)"))]
        (is (not (str/includes? def-output "Error:")))
        (is (str/includes? call-output "\"no\""))))))

(deftest repl-compiled-backend-closure-survives-deopt-test
  (testing "a function object defined in compiled mode remains callable after a later deopt/reopt cycle"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx0 "function plus10(n: Integer): Integer
do
  result := n + 10
end"))
            _ (with-out-str
                (repl/eval-code ctx0 "let f: Function := fn (n: Integer): Integer do
  result := plus10(n) + 5
end"))
            pre-output (with-out-str
                         (repl/eval-code ctx0 "f(1)"))
            _ (with-out-str
                (repl/eval-code ctx0 "intern io/Path"))
            _ (with-out-str
                (repl/eval-code ctx0 "let root: Path := create Path.make(\"/tmp\")"))
            post-output (with-out-str
                          (repl/eval-code ctx0 "f(1)"))
            session @repl/*compiled-repl-session*]
        (is (str/includes? pre-output "16"))
        (is (not (str/includes? post-output "Error:")))
        (is (str/includes? post-output "16"))
        (is (= "Function" (runtime/state-get-type (:state session) "f")))
        (is (some? (runtime/state-get-fn (:state session) "plus10")))))))

(deftest repl-compiled-backend-object-if-branch-coercion-test
  (testing "compiled backend coerces differing object branch JVM types to the if expression result type"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            def-output (with-out-str
                         (repl/eval-code ctx0 "function pick(flag: Boolean, x: Any): Any
do
  if flag then
    result := x
  else
    result := \"fallback\"
  end
end"))
            call-output (with-out-str
                          (repl/eval-code ctx0 "pick(false, \"x\")"))]
        (is (not (str/includes? def-output "Error:")))
        (is (str/includes? call-output "\"fallback\""))))))

(deftest repl-compiled-backend-raise-prints-message-test
  (testing "compiled backend surfaces raised exception messages in the user-facing REPL"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "raise \"some error\""))]
        (is (str/includes? output "Error: some error"))
        (is (not (str/includes? output "Error: nil")))))))

(deftest repl-compiled-backend-rescue-output-and-recovery-test
  (testing "compiled backend flushes rescue output and treats rescued exceptions as handled"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
        (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 (str "do\n"
                                               "  print(\"before\")\n"
                                               "  raise \"something went wrong\"\n"
                                               "  print(\"after\")\n"
                                               "rescue\n"
                                               "  print(\"rescued: \" + exception.to_string)\n"
                                               "end")))]
        (is (str/includes? output "\"before\""))
        (is (str/includes? output "rescued: "))
        (is (str/includes? output "something went wrong"))
        (is (not (str/includes? output "Error:")))
        (is (not (str/includes? output "Error: nil")))))))

(deftest repl-compiled-backend-function-contract-and-rescue-messages-test
  (testing "compiled backend preserves function-level require and rescue-raised messages"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx0 "function parse_positive(text: String): Integer
     require
       not_empty: text.length > 0
     do
       let value := text.to_integer
       if value <= 0 then
         raise \"number must be positive\"
       end
       result := value
     rescue
       raise \"invalid positive integer: \" + text
     end"))
            empty-output (with-out-str (repl/eval-code ctx0 "parse_positive(\"\")"))
            ok-output (with-out-str (repl/eval-code ctx0 "parse_positive(\"12\")"))
            negative-output (with-out-str (repl/eval-code ctx0 "parse_positive(\"-12\")"))]
        (is (str/includes? empty-output "Precondition violation: not_empty"))
        (is (not (str/includes? empty-output "Error: nil")))
        (is (str/includes? ok-output "12"))
        (is (str/includes? negative-output "invalid positive integer: -12"))
        (is (not (str/includes? negative-output "Error: nil")))))))

(deftest repl-compiled-backend-raw-statement-forms-test
  (testing "compiled backend tries raw compiled parsing for statement-shaped inputs before wrapping"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            wrapped-inputs (atom [])
            orig-wrap repl/wrap-as-method]
        (with-redefs [repl/wrap-as-method (fn [input]
                                            (swap! wrapped-inputs conj input)
                                            (orig-wrap input))]
          (let [if-output (with-out-str
                            (repl/eval-code ctx0 "if true then 42 else 0 end"))
                case-output (with-out-str
                              (repl/eval-code ctx0 "case 2 of\n  1 then print(10)\n  2 then print(42)\nelse\n  print(0)\nend"))
                do-output (with-out-str
                            (repl/eval-code ctx0 "do\n  print(42)\nend"))]
            (is (empty? @wrapped-inputs))
            (is (str/includes? if-output "42"))
            (is (str/includes? case-output "42"))
            (is (str/includes? do-output "42"))))))))

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
        (is (contains? globals-after-def "inc"))
        (is (contains? @(:function-asts session) "inc"))
        (is (some? (runtime/state-get-fn (:state session) "inc")))
        (is (str/blank? def-output))
        (is (str/includes? call-output "41"))))))

(deftest repl-compiled-backend-anonymous-function-test
  (testing "compiled backend can evaluate a top-level anonymous function without capture"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            let-output (with-out-str
                         (repl/eval-code ctx0 "let inc := fn (n: Integer): Integer do
  result := n + 1
end"))
            call-output (with-out-str
                          (repl/eval-code ctx0 "inc(41)"))
            session @repl/*compiled-repl-session*]
        (is (str/includes? let-output "AnonymousFunction_"))
        (is (some? (runtime/state-get-value (:state session) "inc")))
        (is (str/includes? call-output "42"))))))

(deftest repl-compiled-backend-higher-order-function-object-test
  (testing "compiled backend supports passing and returning no-capture function objects"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            apply-output (with-out-str
                           (repl/eval-code ctx0 "function apply(f: Function, n: Integer): Any
do
  result := f(n)
end

let inc := fn (n: Integer): Integer do
  result := n + 1
end

apply(inc, 41)"))
            mk-output (with-out-str
                        (repl/eval-code ctx0 "function mk(): Function
do
  result := fn (n: Integer): Integer do
    result := n + 1
  end
end

let inc2: Function := mk()
inc2(41)"))]
        (is (str/includes? apply-output "42"))
        (is (str/includes? mk-output "42"))))))

(deftest compiled-repl-captured-anonymous-function-test
  (testing "compiled helper keeps captured closures on the compiled path via runtime-backed closure objects"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval! session
                                                  (p/ast "let x := 30
let f := fn (n: Integer): Integer do
  result := n + x
end

f(12)"))]
      (is (:compiled? result))
      (is (= 42 (:result result)))
      (is (some? (runtime/state-get-value (:state session) "f"))))))

(deftest repl-compiled-backend-captured-function-object-test
  (testing "compiled backend supports captured closures across repeated calls"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            setup-output (with-out-str
                           (repl/eval-code ctx0 "function cf(): Function
do
  let x := 30
  result := fn(i: Integer): Integer do
    result := i + x
  end
end

let f1: Function := cf()"))
            call1-output (with-out-str
                           (repl/eval-code ctx0 "f1(10)"))
            call2-output (with-out-str
                           (repl/eval-code ctx0 "f1(20)"))]
        (is (not (str/includes? setup-output "Type checking failed")))
        (is (str/includes? call1-output "40"))
        (is (str/includes? call2-output "50"))))))

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
  (testing "compiled backend lowers builtin print through a direct helper path and preserves REPL output"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-redefs [runtime/invoke-builtin
                                 (fn [& _]
                                   (throw (ex-info "invoke-builtin should not be used for print" {})))]
                     (with-out-str
                       (repl/eval-code ctx0 "print(1)")))]
        (is (str/includes? output "1"))))))

(deftest repl-compiled-backend-print-uses-user-to-string-test
  (testing "compiled backend print calls user-defined to_string on objects"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (repl/eval-code ctx0 "class Box
  feature
    value: Integer

    to_string(): String do
      result := \"Box(\" + value.to_string() + \")\"
    end

  create
    make(v: Integer) do
      value := v
    end
end")
            _ (repl/eval-code ctx0 "let b: Box := create Box.make(7)")
            output (with-out-str
                     (repl/eval-code ctx0 "print(b)"))]
        (is (str/includes? output "Box(7)"))))))

(deftest repl-compiled-backend-builtin-type-of-test
  (testing "compiled backend lowers builtin type_of through a direct helper path"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-redefs [runtime/invoke-builtin
                                 (fn [& _]
                                   (throw (ex-info "invoke-builtin should not be used for type_of" {})))]
                     (with-out-str
                       (repl/eval-code ctx0 "type_of(1)")))]
        (is (str/includes? output "String"))
        (is (str/includes? output "\"Integer\""))))))

(deftest repl-compiled-backend-direct-operator-helper-test
  (testing "compiled backend does not need invoke-builtin for string concat or power helpers"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-redefs [runtime/invoke-builtin
                                 (fn [& _]
                                   (throw (ex-info "invoke-builtin should not be used for direct operator helpers" {})))]
                     (with-out-str
                       (repl/eval-code ctx0 "\"n=\" + 10")
                       (repl/eval-code ctx0 "2 ^ 3")))]
        (is (str/includes? output "\"n=10\""))
        (is (str/includes? output "8"))))))

(deftest compiled-repl-json-builtins-direct-helper-test
  (testing "compiled helper evaluates JSON builtins without the generic builtin trampoline"
    (let [session (compiled-repl/make-session)
          result (with-redefs [runtime/invoke-builtin
                               (fn [& _]
                                 (throw (ex-info "invoke-builtin should not be used for JSON builtins" {})))]
                   (compiled-repl/compile-and-eval!
                    session
                    (p/ast (str "let q: Char := #34\n"
                                "json_stringify(json_parse(\"{\" + q + \"name\" + q + \":\" + q + \"nex\" + q + \",\" + q + \"count\" + q + \":3}\"))"))))]
      (is (:compiled? result))
      (is (= "{\"name\":\"nex\",\"count\":3}" (:result result))))))

(deftest compiled-repl-http-client-builtins-direct-helper-test
  (testing "compiled helper evaluates HTTP client builtins without the generic builtin trampoline"
    (let [server (start-test-http-server)
          port (.getPort (.getAddress server))
          base-url (str "http://127.0.0.1:" port "/hello")]
      (try
        (let [session (compiled-repl/make-session)
              result (with-redefs [runtime/invoke-builtin
                                   (fn [& _]
                                     (throw (ex-info "invoke-builtin should not be used for HTTP client builtins" {})))]
                       (compiled-repl/compile-and-eval!
                        session
                        (p/ast (str "intern net/Http_Client\n"
                                    "print(type_of(http_get(\"" base-url "\", 500)))\n"
                                    "type_of(http_post(\"" base-url "\", \"payload\", 500))"))))]
          (is (:compiled? result))
          (is (= ["\"Http_Response\""] (:output result)))
          (is (= "Http_Response" (:result result))))
        (finally
          (.stop server 0))))))

(deftest compiled-repl-http-server-builtins-direct-helper-test
  (testing "compiled helper evaluates HTTP server builtins without the generic builtin trampoline"
    (let [session (compiled-repl/make-session)
          result (with-redefs [runtime/invoke-builtin
                               (fn [& _]
                                 (throw (ex-info "invoke-builtin should not be used for HTTP server builtins" {})))]
                   (compiled-repl/compile-and-eval!
                    session
                    (p/ast
                     (str "let handle := http_server_create(0)\n"
                          "let port: Integer := http_server_start(handle)\n"
                          "print(http_server_is_running(handle))\n"
                          "port"))))]
      (is (:compiled? result))
      (is (= ["true"] (:output result)))
      (let [port (:result result)
            client (java.net.http.HttpClient/newHttpClient)
            request (-> (java.net.http.HttpRequest/newBuilder
                         (java.net.URI/create (str "http://127.0.0.1:" port "/hello")))
                        (.GET)
                        (.build))
            response (.send client request (java.net.http.HttpResponse$BodyHandlers/ofString))]
        (try
          (is (= 404 (.statusCode response)))
          (is (= "Not Found" (.body response)))
          (finally
            (compiled-repl/compile-and-eval! session (p/ast "http_server_stop(handle)"))))))))

(deftest compiled-repl-regex-and-datetime-builtins-direct-helper-test
  (testing "compiled helper evaluates regex and datetime builtins without the generic builtin trampoline"
    (let [session (compiled-repl/make-session)
          result (with-redefs [runtime/invoke-builtin
                               (fn [& _]
                                 (throw (ex-info "invoke-builtin should not be used for regex/datetime builtins" {})))]
                   (compiled-repl/compile-and-eval!
                    session
                    (p/ast
                     (str "print(regex_validate(\"a+\", \"\"))\n"
                          "print(regex_replace(\"a\", \"\", \"banana\", \"o\"))\n"
                          "datetime_year(datetime_now())"))))]
      (is (:compiled? result))
      (is (= ["true" "\"bonono\""] (:output result)))
      (is (integer? (:result result))))))

(deftest compiled-repl-path-and-file-builtins-direct-helper-test
  (testing "compiled helper evaluates path and file builtins without the generic builtin trampoline"
    (let [tmp-dir (.toFile (Files/createTempDirectory "nex-compiled-builtins" (make-array java.nio.file.attribute.FileAttribute 0)))
          file-path (.getAbsolutePath (io/file tmp-dir "sample.txt"))
          file-path-bin (str file-path ".bin")]
      (try
        (let [session (compiled-repl/make-session)
              result (with-redefs [runtime/invoke-builtin
                                   (fn [& _]
                                     (throw (ex-info "invoke-builtin should not be used for path/file builtins" {})))]
                       (compiled-repl/compile-and-eval!
                        session
                        (p/ast
                         (str "path_write_text(\"" file-path "\", \"hello\")\n"
                              "print(path_exists(\"" file-path "\"))\n"
                              "let h := text_file_open_read(\"" file-path "\")\n"
                              "print(text_file_read_line(h))\n"
                              "text_file_close(h)\n"
                              "let b := binary_file_open_write(\"" file-path-bin "\")\n"
                              "binary_file_write(b, [65, 66, 67])\n"
                              "print(binary_file_position(b))\n"
                              "binary_file_seek(b, 1)\n"
                              "binary_file_write(b, [90])\n"
                              "binary_file_close(b)\n"
                              "let br := binary_file_open_read(\"" file-path-bin "\")\n"
                              "print(binary_file_read(br, 3))\n"
                              "binary_file_close(br)\n"
                              "path_read_text(\"" file-path "\")"))))]
          (is (:compiled? result))
          (is (= ["true" "\"hello\"" "3" "[65, 90, 67]"] (:output result)))
          (is (= "hello" (:result result))))
        (finally
          (when (.exists tmp-dir)
            (doseq [child (.listFiles tmp-dir)]
              (.delete child))
            (.delete tmp-dir)))))))

(deftest compiled-repl-runtime-backed-methods-direct-helper-test
  (testing "compiled helper evaluates remaining runtime-backed receiver methods without the generic builtin trampoline"
    (let [session (compiled-repl/make-session)
          _ (runtime/state-set-value! (:state session) "p" {:nex-builtin-type :Process})
          _ (runtime/state-set-type! (:state session) "p" "Process")
          result (with-redefs [runtime/invoke-builtin
                               (fn [& _]
                                 (throw (ex-info "invoke-builtin should not be used for runtime-backed receiver methods" {})))]
                   (compiled-repl/compile-and-eval!
                    session
                    (p/ast
                     (str "let s: String := \"  Abc  \"\n"
                          "print(s.trim.to_upper)\n"
                          "let n: Integer := 8\n"
                          "print(n.max(10))\n"
                          "let c: Cursor := s.cursor\n"
                          "c.start\n"
                          "print(c.item)\n"
                          "p.command_line.length"))))]
      (is (:compiled? result))
      (is (= ["\"ABC\"" "10" "#space"] (:output result)))
      (is (integer? (:result result)))
      (is (<= 0 (:result result))))))

(deftest compiled-repl-with-java-block-test
  (testing "compiled helper executes with \"java\" blocks on the JVM path"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast
                   (str "with \"java\" do\n"
                        "  let version_length: Integer := System.getProperty(\"java.version\").length()\n"
                        "end\n"
                        "version_length")))]
      (is (:compiled? result))
      (is (integer? (:result result)))
      (is (pos? (:result result))))))

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

(deftest repl-compiled-backend-spawn-and-await-test
  (testing "compiled backend can create tasks with spawn and await them without deopting"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            spawn-output (with-out-str
                           (repl/eval-code ctx0 "let t: Task[Integer] := spawn do result := 1 + 2 end"))
            await-output (with-out-str
                           (repl/eval-code ctx0 "t.await"))
            session @repl/*compiled-repl-session*]
        (is (str/includes? spawn-output "#<Task>"))
        (is (some? (runtime/state-get-value (:state session) "t")))
        (is (str/includes? await-output "3"))))))

(deftest repl-compiled-backend-channel-lifecycle-test
  (testing "compiled backend can create channels and use basic lifecycle methods"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "let ch: Channel[Integer] := create Channel[Integer].with_capacity(1)
ch.try_send(7)
print(ch.try_receive)
ch.close
ch.is_closed"))]
        (is (str/includes? output "true"))
        (is (str/includes? output "7"))))))

(deftest repl-compiled-backend-array-filled-constructor-test
  (testing "compiled backend can create Array.filled and use it as a normal array"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "let failure: Array[Integer] := create Array.filled(3, 0)
print(failure.length)
print(failure.get(0))
print(failure.get(2))"))]
        (is (str/includes? output "3"))
        (is (>= (count (re-seq #"0" output)) 2) output)))))

(deftest repl-compiled-backend-string-chars-test
  (testing "compiled backend keeps String.chars on the compiled path with Array[Char] semantics"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "let xs: Array[Char] := \"cat\".chars()
print(xs)
print(xs.length)
print(xs.get(1))"))]
        (is (str/includes? output "[#c, #a, #t]"))
        (is (str/includes? output "3"))
        (is (str/includes? output "#a"))))))

(deftest repl-compiled-backend-string-to-bytes-test
  (testing "compiled backend keeps String.to_bytes on the compiled path with UTF-8 bytes"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "let xs: Array[Integer] := \"cat\".to_bytes()
print(xs)
print(xs.length)
print(xs.get(1))"))]
        (is (str/includes? output "[99, 97, 116]"))
        (is (str/includes? output "3"))
        (is (str/includes? output "97"))))))

(deftest repl-compiled-backend-task-and-channel-state-methods-test
  (testing "compiled backend specializes task/channel state methods too"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "let t: Task := spawn do
  sleep(20)
  result := nil
end
print(t.cancel)
print(t.is_cancelled)
let ch: Channel[Integer] := create Channel[Integer].with_capacity(2)
print(ch.capacity)
print(ch.size)"))]
        (is (str/includes? output "true"))
        (is (str/includes? output "2"))
        (is (str/includes? output "0"))))))

(deftest repl-compiled-backend-await-any-all-test
  (testing "compiled backend can evaluate await_any and await_all on task arrays"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "let slow: Task[Integer] := spawn do
  sleep(5)
  result := 10
end
let fast: Task[Integer] := spawn do
  result := 20
end
print(await_any([slow, fast]))
print(await_all([slow, fast]))"))]
        (is (str/includes? output "20"))
        (is (str/includes? output "[10, 20]"))))))

(deftest repl-compiled-backend-select-test
  (testing "compiled backend can run top-level select without wrapper fallback"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str
                (repl/eval-code ctx0 "let ch: Channel[Integer] := create Channel[Integer].with_capacity(1)"))
            _ (with-out-str
                (repl/eval-code ctx0 "ch.send(9)"))
            output (with-out-str
                     (repl/eval-code ctx0 "select
  when ch.receive as value then
    print(value)
  timeout 5 then
    print(\"timeout\")
end"))]
        (is (str/includes? output "9"))
        (is (not (str/includes? output "timeout")))))))

(deftest repl-compiled-backend-status-command-test
  (testing "backend status command reports the current backend"
    (binding [repl/*repl-backend* (atom :compiled)]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/handle-command ctx ":backend status"))]
        (is (str/includes? output "COMPILED"))))))

(deftest repl-compiled-backend-generic-function-with-detachable-elseif-fallback-test
  (testing "compiled REPL accepts generic functions that require detachable refinement across elseif"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            class-output
            (with-out-str
              (repl/eval-code ctx0 "class Node [K -> Comparable, V]
  create
    make(key: K, value: V) do
      this.key := key
      this.value := value
      this.height := 1
    end
  feature
    key: K
    value: ?V
    left: ?Node[K, V]
    right: ?Node[K, V]
    height: Integer
end"))
            fn-output
            (with-out-str
              (repl/eval-code ctx0 "function search(node: ?Node[K, V], key: K): ?V
do
  if node = nil then
    result := nil
  elseif key < node.key then
    result := search(node.left, key)
  elseif key > node.key then
    result := search(node.right, key)
  else
    result := node.value
  end
end"))]
        (is (not (str/includes? class-output "Error:")))
        (is (not (str/includes? fn-output "Error:")))
        (is (not (str/includes? fn-output "Type checking failed")))
        (is (contains? @repl/*repl-var-types* "search"))))))

(deftest repl-compiled-backend-generic-function-call-infers-type-params-test
  (testing "compiled REPL infers explicit generic free-function type parameters from argument types"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            zip-output (with-out-str
                         (repl/eval-code ctx0 "function zip[T](a: Array[T], b: Array[T], f: Function): Array[T]
do
  result := a
end"))
            add-output (with-out-str
                         (repl/eval-code ctx0 "function add(a: Integer, b: Integer): Integer
do
  result := a + b
end"))
            call-output (with-out-str
                          (repl/eval-code ctx0 "zip([1, 2, 3], [4, 5, 6], add)"))]
        (is (not (str/includes? zip-output "Error:")))
        (is (not (str/includes? add-output "Error:")))
        (is (not (str/includes? call-output "Error:")))
        (is (str/includes? call-output "Array[Integer]"))
        (is (str/includes? call-output "[1, 2, 3]"))))))

(deftest repl-compiled-backend-generic-free-function-return-instantiates-test
  (testing "compiled REPL instantiates bare generic free-function return types from call arguments"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            reduce-output (with-out-str
                            (repl/eval-code ctx0 "function reduce[T](a: Array[T], f: Function, init: T): T do
  result := init
  across a as elem do
    result := f(result, elem)
  end
end"))
            call-output (with-out-str
                          (repl/eval-code ctx0 "reduce([1, 2, 3], fn(a: Integer, b: Integer): Integer do result := a + b end, 0)"))]
        (is (not (str/includes? reduce-output "Error:")))
        (is (not (str/includes? call-output "Error:")))
        (is (str/includes? call-output "Integer 6"))))))

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

(deftest compiled-repl-import-support-test
  (testing "compiled helper can keep Java imports on the compiled path"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast "import java.util.ArrayList

let xs: ArrayList := create ArrayList
xs.add(\"a\")
xs.size()"))]
      (is (:compiled? result))
      (is (= 1 (:result result)))
      (is (= "ArrayList" (runtime/state-get-type (:state session) "xs")))
      (is (= "java.util.ArrayList"
             (:qualified-name (first @(:import-asts session))))))))

(deftest compiled-repl-intern-support-test
  (testing "compiled helper resolves interned classes relative to source-id and keeps them on the compiled path"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                           (str "nex-compiled-intern-" (System/nanoTime)))
          a-file (io/file tmp-dir "A.nex")
          main-file (io/file tmp-dir "main.nex")
          session (compiled-repl/make-session)]
      (.mkdirs tmp-dir)
      (spit a-file "class A
feature
  answer(): Integer
  do
    result := 41
  end
end")
      (spit main-file "intern A

let a := create A
a.answer()")
      (try
        (let [result (compiled-repl/compile-and-eval!
                      session
                      (p/ast (slurp main-file))
                      (.getPath main-file))]
          (is (:compiled? result))
          (is (= 41 (:result result)))
          (is (contains? @(:class-asts session) "A"))
          (is (= "A" (:class-name (first @(:intern-asts session))))))
        (finally
          (.delete a-file)
          (.delete main-file)
          (.delete tmp-dir))))))

(deftest repl-compiled-backend-loads-cursor-subclass-file-test
  (testing "compiled backend can :load a file that defines a class inheriting Cursor"
    (binding [repl/*type-checking-enabled* (atom false)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir")
                             (str "nex-compiled-load-cursor-" (System/nanoTime)))
            source-file (io/file tmp-dir "c.nex")]
        (try
          (.mkdirs tmp-dir)
          (spit source-file "class C inherit Cursor
feature
  x: Integer
  start do x := 0 end
  item: Integer do result := x end
  next do x := x + 1 end
  at_end: Boolean do result := x = 3 end
end")
          (let [ctx0 (repl/init-repl-context)
                load-output (with-out-str
                              (repl/load-file-into-repl ctx0 (.getPath source-file)))
                create-output (with-out-str
                                (repl/eval-code ctx0 "let c: C := create C"))
                _ (with-out-str (repl/eval-code ctx0 "c.start"))
                item-output (with-out-str
                              (repl/eval-code ctx0 "c.item"))
                across-output (with-out-str
                                (repl/eval-code ctx0 "across c as i do print(i) end"))
                session @repl/*compiled-repl-session*]
            (is (not (str/includes? load-output "Error:")))
            (is (contains? @(:class-asts session) "C"))
            (is (not (str/includes? create-output "Error:")))
            (is (str/includes? item-output "0"))
            (is (not (str/includes? across-output "Error:")))
            (is (= ["0" "1" "2"]
                   (remove str/blank? (str/split-lines across-output)))))
          (finally
            (when (.exists source-file)
              (.delete source-file))
          (when (.exists tmp-dir)
            (.delete tmp-dir))))))))

(deftest repl-compiled-backend-convert-guard-inside-across-test
  (testing "compiled backend handles convert-bound locals inside across loops without verifier errors"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx (repl/init-repl-context)
            books-code "let books: Array[Map[String, Any]] := [
  {\"title\": \"Dune\", \"author\": \"Frank Herbert\", \"year\": 1965},
  {\"title\": \"Neuromancer\", \"author\": \"William Gibson\", \"year\": 1984},
  {\"title\": \"Foundation\", \"author\": \"Isaac Asimov\", \"year\": 1951}
]"
            filter-code "across books as book do
  if convert book.get(\"year\") to year: Integer then
    if year < 1970 then
      print(book.get(\"title\"))
    end
  end
end"
            _ (with-out-str (repl/eval-code ctx books-code))
            out (with-out-str (repl/eval-code ctx filter-code))]
        (is (not (str/includes? out "VerifyError")))
        (is (not (str/includes? out "Error:")))))))

(deftest repl-compiled-backend-min-heap-builtins-test
  (testing "compiled-default REPL supports Min_Heap natural ordering, comparator ordering, and safe variants"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str (repl/eval-code ctx0 "let h: Min_Heap[Integer] := create Min_Heap.empty"))
            _ (with-out-str (repl/eval-code ctx0 "h.insert(5)"))
            _ (with-out-str (repl/eval-code ctx0 "h.insert(1)"))
            _ (with-out-str (repl/eval-code ctx0 "h.insert(3)"))
            peek-output (with-out-str (repl/eval-code ctx0 "h.peek"))
            extract-output (with-out-str (repl/eval-code ctx0 "h.extract_min"))
            _ (with-out-str (repl/eval-code ctx0 "h.extract_min"))
            _ (with-out-str (repl/eval-code ctx0 "h.extract_min"))
            empty-peek-output (with-out-str (repl/eval-code ctx0 "h.try_peek = nil"))
            empty-extract-output (with-out-str (repl/eval-code ctx0 "h.try_extract_min = nil"))
            _ (with-out-str
                (repl/eval-code ctx0 "let cmp: Function := fn (a: Integer, b: Integer): Integer do
  if a > b then
    result := -1
  elseif a < b then
    result := 1
  else
    result := 0
  end
end"))
            _ (with-out-str (repl/eval-code ctx0 "let rev: Min_Heap[Integer] := create Min_Heap.from_comparator(cmp)"))
            _ (with-out-str (repl/eval-code ctx0 "rev.insert(5)"))
            _ (with-out-str (repl/eval-code ctx0 "rev.insert(1)"))
            _ (with-out-str (repl/eval-code ctx0 "rev.insert(3)"))
            reverse-output (with-out-str (repl/eval-code ctx0 "rev.extract_min"))]
        (is (str/includes? peek-output "1"))
        (is (str/includes? extract-output "1"))
        (is (str/includes? empty-peek-output "true"))
        (is (str/includes? empty-extract-output "true"))
        (is (str/includes? reverse-output "5"))))))

(deftest repl-compiled-backend-atomic-builtins-test
  (testing "compiled-default REPL supports atomic built-ins"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            _ (with-out-str (repl/eval-code ctx0 "let ai: Atomic_Integer := create Atomic_Integer.make(10)"))
            add-output (with-out-str (repl/eval-code ctx0 "ai.get_and_add(5)"))
            load-output (with-out-str (repl/eval-code ctx0 "ai.load"))
            cas-output (with-out-str (repl/eval-code ctx0 "ai.compare_and_set(15, 2)"))
            inc-output (with-out-str (repl/eval-code ctx0 "ai.increment"))
            _ (with-out-str (repl/eval-code ctx0 "let ar: Atomic_Reference[String] := create Atomic_Reference.make(\"a\")"))
            ref-cas-output (with-out-str (repl/eval-code ctx0 "ar.compare_and_set(\"a\", \"b\")"))
            ref-load-output (with-out-str (repl/eval-code ctx0 "ar.load"))
            _ (with-out-str (repl/eval-code ctx0 "ar.store(nil)"))
            ref-nil-output (with-out-str (repl/eval-code ctx0 "ar.load = nil"))]
        (is (str/includes? add-output "10"))
        (is (str/includes? load-output "15"))
        (is (str/includes? cas-output "true"))
        (is (str/includes? inc-output "3"))
        (is (str/includes? ref-cas-output "true"))
        (is (str/includes? ref-load-output "b"))
        (is (str/includes? ref-nil-output "true"))))))

(deftest repl-compiled-backend-random-real-builtin-test
  (testing "compiled-default REPL supports random_real as a Real-valued global builtin"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)]
        (repl/eval-code ctx0 "let r: Real := random_real()")
        (is (= "Real" (get @repl/*repl-var-types* "r")))
        (let [r (runtime/state-get-value (:state @repl/*compiled-repl-session*) "r")]
          (is (number? r))
          (is (<= 0.0 r))
          (is (< r 1.0)))))))

(deftest repl-compiled-backend-hint-spin-builtin-test
  (testing "compiled-default REPL supports hint_spin as a direct no-op builtin"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx0 "hint_spin()
print(\"ok\")"))]
        (is (str/includes? output "ok"))))))

(deftest repl-compiled-backend-generic-comparable-in-sort-comparator-test
  (testing "compiled REPL typechecks generic constrained method calls inside sort comparator lambdas"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx0 (repl/init-repl-context)
            define-output (with-out-str
                            (repl/eval-code ctx0 "function gradeUp[T -> Comparable](a: Array[T]): Array[Integer] do
  result := []
  from let i := 0
  until i >= a.length do
    result.add(i)
    i := i + 1
  end
  result.sort(fn(i: Integer, j: Integer): Integer do
    result := a.get(i).compare(a.get(j))
  end)
end"))
            call-output (with-out-str
                          (repl/eval-code ctx0 "gradeUp([3,1,4,1])"))]
        (is (not (str/includes? define-output "Type error")))
        (is (not (str/includes? define-output "Error:")))
        (is (not (str/includes? call-output "Type error")))
        (is (not (str/includes? call-output "Error:")))
        (is (str/includes? call-output "[0, 1, 2, 3]"))))))
