(ns nex.compiler.jvm.emit-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.emit :as emit]
            [nex.compiler.jvm.runtime :as runtime]
            [nex.ir :as ir]
            [nex.lower :as lower]
            [nex.parser :as p])
  (:import [java.lang.reflect Modifier]
           [org.objectweb.asm ClassReader ClassVisitor MethodVisitor Opcodes]))

(defn- method-line-numbers
  [bytecode method-name]
  (let [lines (atom [])]
    (.accept (ClassReader. bytecode)
             (proxy [ClassVisitor] [Opcodes/ASM9]
               (visitMethod [_access name _descriptor _signature _exceptions]
                 (proxy [MethodVisitor] [Opcodes/ASM9]
                   (visitLineNumber [line _start]
                     (when (= method-name name)
                       (swap! lines conj line))))))
             0)
    @lines))

(defn- class-source-file
  [bytecode]
  (let [source (atom nil)]
    (.accept (ClassReader. bytecode)
             (proxy [ClassVisitor] [Opcodes/ASM9]
               (visitSource [source-file _debug]
                 (reset! source source-file)))
             0)
    @source))

(defn- method-local-variable-names
  [bytecode method-name]
  (let [locals (atom [])]
    (.accept (ClassReader. bytecode)
             (proxy [ClassVisitor] [Opcodes/ASM9]
               (visitMethod [_access name _descriptor _signature _exceptions]
                 (proxy [MethodVisitor] [Opcodes/ASM9]
                   (visitLocalVariable [local-name _descriptor _signature _start _end _index]
                     (when (= method-name name)
                       (swap! locals conj local-name))))))
             0)
    @locals))

(defn- method-local-variable-ranges
  [bytecode method-name]
  (let [label-order (atom {})
        next-order (atom 0)
        locals (atom [])]
    (.accept (ClassReader. bytecode)
             (proxy [ClassVisitor] [Opcodes/ASM9]
               (visitMethod [_access name _descriptor _signature _exceptions]
                 (proxy [MethodVisitor] [Opcodes/ASM9]
                   (visitLabel [label]
                     (let [id (System/identityHashCode label)]
                       (when-not (contains? @label-order id)
                         (swap! label-order assoc id @next-order)
                         (swap! next-order inc))))
                   (visitLocalVariable [local-name _descriptor _signature start end index]
                     (when (= method-name name)
                       (swap! locals conj {:name local-name
                                           :index index
                                           :start-order (get @label-order (System/identityHashCode start))
                                           :end-order (get @label-order (System/identityHashCode end))})))))) 
             0)
    @locals))

(deftest minimal-class-spec-test
  (testing "minimal class spec for a repl cell is stable"
    (let [unit (ir/unit {:name "nex/repl/Cell_0001"
                         :kind :repl-cell
                         :functions []
                         :body []
                         :result-jvm-type (ir/object-jvm-type "java/lang/Object")})
          spec (emit/minimal-class-spec unit)]
      (is (= "nex/repl/Cell_0001" (:internal-name spec)))
      (is (= "nex.repl.Cell_0001" (:binary-name spec)))
      (is (= "java/lang/Object" (:super-name spec)))
      (is (= 2 (count (:methods spec))))
      (is (= "eval" (-> spec :methods second :name))))))

(deftest compile-trivial-repl-cell-test
  (testing "emitted repl cell class loads and its eval method returns nil"
    (let [unit (ir/unit {:name "nex/repl/Cell_0001"
                         :kind :repl-cell
                         :functions []
                         :body [(ir/return-node
                                 (ir/const-node nil "Any"
                                                (ir/object-jvm-type "java/lang/Object"))
                                 "Any"
                                 (ir/object-jvm-type "java/lang/Object"))]
                         :result-jvm-type (ir/object-jvm-type "java/lang/Object")})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0001" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (bytes? bytecode))
      (is (= "nex.repl.Cell_0001" (.getName cls)))
      (is (nil? result)))))

(deftest compile-constant-return-repl-cell-test
  (testing "emitted repl cell can return a boxed constant"
    (let [unit (ir/unit {:name "nex/repl/Cell_0002"
                         :kind :repl-cell
                         :functions []
                         :body [(ir/return-node
                                 (ir/const-node 42 "Integer" :int)
                                 "Any"
                                 (ir/object-jvm-type "java/lang/Object"))]
                         :result-jvm-type (ir/object-jvm-type "java/lang/Object")})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0002" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (= 42 result))
      (is (instance? Integer result)))))

(deftest compile-pop-then-return-test
  (testing "pop discards intermediate constants before returning"
    (let [unit (ir/unit {:name "nex/repl/Cell_0003"
                         :kind :repl-cell
                         :functions []
                         :body [(ir/pop-node (ir/const-node 7 "Integer" :int))
                                (ir/return-node
                                 (ir/const-node "done" "String"
                                                (ir/object-jvm-type "java/lang/String"))
                                 "Any"
                                 (ir/object-jvm-type "java/lang/Object"))]
                         :result-jvm-type (ir/object-jvm-type "java/lang/Object")})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0003" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (= "done" result)))))

(deftest lower-and-compile-repl-expression-smoke-test
  (testing "parsed repl expression lowers and compiles end-to-end"
    (let [program (p/ast "42")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0042"})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0042" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (= 42 result)))))

(deftest lower-and-compile-let-expression-smoke-test
  (testing "compiled repl cells support local let bindings end-to-end"
    (let [program (p/ast "let x := 40\nx")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0043"})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0043" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (= 40 result)))))

(deftest lower-and-compile-let-plus-expression-smoke-test
  (testing "compiled repl cells support arithmetic over local lets end-to-end"
    (let [program (p/ast "let x := 40\nx + 2")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0044"})
          bytecode (emit/compile-unit->bytes unit)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Cell_0044" bytecode)
          state (runtime/make-repl-state l)
          method (.getMethod cls "eval" (into-array Class [(class state)]))
          result (.invoke method nil (object-array [state]))]
      (is (= 42 result)))))

(deftest emitted-line-number-table-smoke-test
  (testing "compiled eval methods carry source line metadata"
    (let [program (p/ast "let x := 40\nx + 2")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0045"
                                                         :source-file "debug_lines.nex"})
          bytecode (emit/compile-unit->bytes unit)
          lines (method-line-numbers bytecode "eval")]
      (is (seq lines))
      (is (some #{1} lines))
      (is (some #{2} lines))
      (is (= "debug_lines.nex" (class-source-file bytecode)))
      (is (some #{"state"} (method-local-variable-names bytecode "eval")))))
  (testing "compiled function methods carry local-variable debug metadata"
    (let [program (p/ast "function inc(n: Integer): Integer\ndo\n  let x := n + 1\n  result := x\nend")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0046"
                                                         :source-file "debug_fn.nex"})
          fn-node (first (:functions unit))
          bytecode (emit/compile-unit->bytes unit)
          locals (method-local-variable-names bytecode (:emitted-name fn-node))
          ranges (method-local-variable-ranges bytecode (:emitted-name fn-node))
          state-range (first (filter #(= "state" (:name %)) ranges))
          n-range (first (filter #(= "n" (:name %)) ranges))
          x-range (first (filter #(= "x" (:name %)) ranges))
          result-range (first (filter #(= "result" (:name %)) ranges))]
      (is (some #{"state"} locals))
      (is (some #{"__args"} locals))
      (is (some #{"n"} locals))
      (is (some #{"x"} locals))
      (is (some #{"result"} locals))
      (is (< (:start-order state-range) (:start-order x-range)))
      (is (<= (:start-order n-range) (:start-order x-range)))
      (is (<= (:start-order result-range) (:start-order x-range)))
      (is (< (:end-order x-range) (:end-order state-range))))))

(deftest compile-top-set-and-top-get-smoke-test
  (testing "compiled repl cells can persist top-level values through NexReplState"
    (let [state-loader (loader/make-loader)
          state (runtime/make-repl-state state-loader)
          unit-a (-> (p/ast "score := 40")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0050"
                                             :var-types {"score" "Integer"}})
                     :unit)
          unit-b (-> (p/ast "score")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0051"
                                             :var-types {"score" "Integer"}})
                     :unit)
          class-a (loader/define-class! state-loader
                                        "nex.repl.Cell_0050"
                                        (emit/compile-unit->bytes unit-a))
          class-b (loader/define-class! state-loader
                                        "nex.repl.Cell_0051"
                                        (emit/compile-unit->bytes unit-b))
          eval-a (.getMethod class-a "eval" (into-array Class [(class state)]))
          eval-b (.getMethod class-b "eval" (into-array Class [(class state)]))]
      (.invoke eval-a nil (object-array [state]))
      (is (= 40 (runtime/state-get-value state "score")))
      (is (= 40 (.invoke eval-b nil (object-array [state])))))))

(deftest compile-multi-cell-repl-state-smoke-test
  (testing "compiled cells share top-level state across multiple evaluations"
    (let [state-loader (loader/make-loader)
          state (runtime/make-repl-state state-loader)
          unit-a (-> (p/ast "x := 40")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0052"
                                             :var-types {"x" "Integer"}})
                     :unit)
          unit-b (-> (p/ast "x + 2")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0053"
                                             :var-types {"x" "Integer"}})
                     :unit)
          class-a (loader/define-class! state-loader
                                        "nex.repl.Cell_0052"
                                        (emit/compile-unit->bytes unit-a))
          class-b (loader/define-class! state-loader
                                        "nex.repl.Cell_0053"
                                        (emit/compile-unit->bytes unit-b))
          eval-a (.getMethod class-a "eval" (into-array Class [(class state)]))
          eval-b (.getMethod class-b "eval" (into-array Class [(class state)]))]
      (.invoke eval-a nil (object-array [state]))
      (is (= 40 (runtime/state-get-value state "x")))
      (is (= 42 (.invoke eval-b nil (object-array [state])))))))

(deftest compile-top-level-state-with-if-smoke-test
  (testing "compiled cells support compare and if over shared top-level state"
    (let [state-loader (loader/make-loader)
          state (runtime/make-repl-state state-loader)
          unit-a (-> (p/ast "x := 40")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0054"
                                             :var-types {"x" "Integer"}})
                     :unit)
          unit-b (-> (p/ast "if x > 0 then x + 2 else 0 end")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0055"
                                             :var-types {"x" "Integer"}})
                     :unit)
          class-a (loader/define-class! state-loader
                                        "nex.repl.Cell_0054"
                                        (emit/compile-unit->bytes unit-a))
          class-b (loader/define-class! state-loader
                                        "nex.repl.Cell_0055"
                                        (emit/compile-unit->bytes unit-b))
          eval-a (.getMethod class-a "eval" (into-array Class [(class state)]))
          eval-b (.getMethod class-b "eval" (into-array Class [(class state)]))]
      (.invoke eval-a nil (object-array [state]))
      (is (= 42 (.invoke eval-b nil (object-array [state])))))))

(deftest compile-top-level-function-call-smoke-test
  (testing "compiled repl cells can define and call a top-level function through state"
    (let [state-loader (loader/make-loader)
          state (runtime/make-repl-state state-loader)
          def-ast (p/ast "function inc(n: Integer): Integer\ndo\n  result := n + 1\nend")
          unit-a (-> def-ast
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0061"})
                     :unit)
          unit-b (-> (p/ast "inc(41)")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0062"
                                             :functions (:functions def-ast)})
                     :unit)
          class-a (loader/define-class! state-loader
                                        "nex.repl.Cell_0061"
                                        (emit/compile-unit->bytes unit-a))
          class-b (loader/define-class! state-loader
                                        "nex.repl.Cell_0062"
                                        (emit/compile-unit->bytes unit-b))
          eval-a (.getMethod class-a "eval" (into-array Class [(class state)]))
          eval-b (.getMethod class-b "eval" (into-array Class [(class state)]))]
      (.invoke eval-a nil (object-array [state]))
      (is (some? (runtime/state-get-fn state "inc")))
      (is (= 42 (.invoke eval-b nil (object-array [state])))))))

(deftest compile-function-redefinition-smoke-test
  (testing "later compiled cells can redefine a top-level function through state"
    (let [state-loader (loader/make-loader)
          state (runtime/make-repl-state state-loader)
          def-ast-a (p/ast "function inc(n: Integer): Integer\ndo\n  result := n + 1\nend")
          def-ast-b (p/ast "function inc(n: Integer): Integer\ndo\n  result := n + 2\nend")
          unit-a (-> def-ast-a
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0063"})
                     :unit)
          unit-b (-> def-ast-b
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0064"})
                     :unit)
          unit-c (-> (p/ast "inc(40)")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0065"
                                             :functions (:functions def-ast-b)})
                     :unit)
          class-a (loader/define-class! state-loader
                                        "nex.repl.Cell_0063"
                                        (emit/compile-unit->bytes unit-a))
          class-b (loader/define-class! state-loader
                                        "nex.repl.Cell_0064"
                                        (emit/compile-unit->bytes unit-b))
          class-c (loader/define-class! state-loader
                                        "nex.repl.Cell_0065"
                                        (emit/compile-unit->bytes unit-c))
          eval-a (.getMethod class-a "eval" (into-array Class [(class state)]))
          eval-b (.getMethod class-b "eval" (into-array Class [(class state)]))
          eval-c (.getMethod class-c "eval" (into-array Class [(class state)]))]
      (.invoke eval-a nil (object-array [state]))
      (is (= 41 (.invoke eval-c nil (object-array [state]))))
      (.invoke eval-b nil (object-array [state]))
      (is (= 42 (.invoke eval-c nil (object-array [state])))))))

(deftest compile-function-body-calls-top-level-function-smoke-test
  (testing "compiled function bodies can call other top-level functions through shared state"
    (let [state-loader (loader/make-loader)
          state (runtime/make-repl-state state-loader)
          def-ast (p/ast "function add1(n: Integer): Integer\ndo\n  result := n + 1\nend\n\nfunction add2(n: Integer): Integer\ndo\n  result := add1(n) + 1\nend")
          unit-a (-> def-ast
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0066"})
                     :unit)
          unit-b (-> (p/ast "add2(40)")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0067"
                                             :functions (:functions def-ast)})
                     :unit)
          class-a (loader/define-class! state-loader
                                        "nex.repl.Cell_0066"
                                        (emit/compile-unit->bytes unit-a))
          class-b (loader/define-class! state-loader
                                        "nex.repl.Cell_0067"
                                        (emit/compile-unit->bytes unit-b))
          eval-a (.getMethod class-a "eval" (into-array Class [(class state)]))
          eval-b (.getMethod class-b "eval" (into-array Class [(class state)]))]
      (.invoke eval-a nil (object-array [state]))
      (is (= 42 (.invoke eval-b nil (object-array [state])))))))

(deftest compile-mutually-dependent-multi-cell-functions-smoke-test
  (testing "compiled mutually dependent functions across cells resolve dynamically through state"
    (let [state-loader (loader/make-loader)
          state (runtime/make-repl-state state-loader)
          def-ast-a (p/ast "declare function is_odd(n: Integer): Boolean\n\nfunction is_even(n: Integer): Boolean\ndo\n  if n = 0 then\n    result := true\n  else\n    result := is_odd(n - 1)\n  end\nend")
          def-ast-b (p/ast "declare function is_even(n: Integer): Boolean\n\nfunction is_odd(n: Integer): Boolean\ndo\n  if n = 0 then\n    result := false\n  else\n    result := is_even(n - 1)\n  end\nend")
          visible-fns (vec (concat (:functions def-ast-a) (:functions def-ast-b)))
          unit-a (-> def-ast-a
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0068"
                                             :functions (:functions def-ast-b)})
                     :unit)
          unit-b (-> def-ast-b
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0069"
                                             :functions (:functions def-ast-a)})
                     :unit)
          unit-c (-> (p/ast "is_even(4)")
                     (lower/lower-repl-cell {:name "nex/repl/Cell_0070"
                                             :functions visible-fns})
                     :unit)
          class-a (loader/define-class! state-loader
                                        "nex.repl.Cell_0068"
                                        (emit/compile-unit->bytes unit-a))
          class-b (loader/define-class! state-loader
                                        "nex.repl.Cell_0069"
                                        (emit/compile-unit->bytes unit-b))
          class-c (loader/define-class! state-loader
                                        "nex.repl.Cell_0070"
                                        (emit/compile-unit->bytes unit-c))
          eval-a (.getMethod class-a "eval" (into-array Class [(class state)]))
          eval-b (.getMethod class-b "eval" (into-array Class [(class state)]))
          eval-c (.getMethod class-c "eval" (into-array Class [(class state)]))]
      (.invoke eval-a nil (object-array [state]))
      (.invoke eval-b nil (object-array [state]))
      (is (= true (.invoke eval-c nil (object-array [state])))))))

(deftest compile-deferred-class-emits-abstract-jvm-members-test
  (testing "deferred classes emit as abstract JVM classes with abstract methods"
    (let [program (p/ast "deferred class Shape
feature
  area(): Real do end
end")
          shape (first (:classes program))
          lowered (lower/lower-class-def shape {:compiled-classes {"Shape" {:name "Shape"
                                                                           :internal-name "nex/repl/Shape_0001"
                                                                           :jvm-name "nex/repl/Shape_0001"
                                                                           :binary-name "nex.repl.Shape_0001"}}
                                                :classes (:classes program)
                                                :functions []
                                                :imports []})
          bytecode (emit/compile-user-class->bytes lowered)
          l (loader/make-loader)
          cls (loader/define-class! l "nex.repl.Shape_0001" bytecode)
          area (.getDeclaredMethod cls "__method_area$arity0"
                                   (into-array Class [(class (runtime/make-repl-state l))
                                                      (class (object-array 0))]))]
      (is (Modifier/isAbstract (.getModifiers cls)))
      (is (Modifier/isAbstract (.getModifiers area))))))

(deftest compile-child-class-uses-composition-parent-fields-test
  (testing "compiled child classes keep Object as JVM superclass and use composition fields for parents"
    (let [program (p/ast "deferred class Shape
feature
  area(): Real do end
end

class Square inherit Shape
feature
  side: Real

  area(): Real
  do
    result := side * side
  end
end")
          [shape square] (:classes program)
          compiled-classes {"Shape" {:name "Shape"
                                     :internal-name "nex/repl/Shape_0001"
                                     :jvm-name "nex/repl/Shape_0001"
                                     :binary-name "nex.repl.Shape_0001"}
                            "Square" {:name "Square"
                                      :internal-name "nex/repl/Square_0002"
                                      :jvm-name "nex/repl/Square_0002"
                                      :binary-name "nex.repl.Square_0002"}}
          shape-lowered (lower/lower-class-def shape {:compiled-classes compiled-classes
                                                      :classes (:classes program)
                                                      :functions []
                                                      :imports []})
          square-lowered (lower/lower-class-def square {:compiled-classes compiled-classes
                                                        :classes (:classes program)
                                                        :functions []
                                                        :imports []})
          l (loader/make-loader)
          shape-cls (loader/define-class! l "nex.repl.Shape_0001"
                                          (emit/compile-user-class->bytes shape-lowered))
          square-cls (loader/define-class! l "nex.repl.Square_0002"
                                           (emit/compile-user-class->bytes square-lowered))
          parent-field (.getDeclaredField square-cls "_parent_Shape")]
      (is (= "java.lang.Object" (.getName (.getSuperclass square-cls))))
      (is (= shape-cls (.getType parent-field)))
      (is (= "_parent_Shape" (.getName parent-field)))))) 

(deftest compile-multi-parent-class-emits-composition-fields-test
  (testing "compiled classes with multiple parents emit one composition field per parent"
    (let [program (p/ast "class A
feature
  ping(): Integer do
    result := 1
  end
end

class B
feature
  pong(): Integer do
    result := 2
  end
end

class C inherit A, B
feature
  sum(): Integer do
    result := ping() + pong()
  end
end")
          [a b c] (:classes program)
          compiled-classes {"A" {:name "A"
                                 :internal-name "nex/repl/A_0001"
                                 :jvm-name "nex/repl/A_0001"
                                 :binary-name "nex.repl.A_0001"}
                            "B" {:name "B"
                                 :internal-name "nex/repl/B_0002"
                                 :jvm-name "nex/repl/B_0002"
                                 :binary-name "nex.repl.B_0002"}
                            "C" {:name "C"
                                 :internal-name "nex/repl/C_0003"
                                 :jvm-name "nex/repl/C_0003"
                                 :binary-name "nex.repl.C_0003"}}
          a-lowered (lower/lower-class-def a {:compiled-classes compiled-classes
                                              :classes (:classes program)
                                              :functions []
                                              :imports []})
          b-lowered (lower/lower-class-def b {:compiled-classes compiled-classes
                                              :classes (:classes program)
                                              :functions []
                                              :imports []})
          c-lowered (lower/lower-class-def c {:compiled-classes compiled-classes
                                              :classes (:classes program)
                                              :functions []
                                              :imports []})
          l (loader/make-loader)
          a-cls (loader/define-class! l "nex.repl.A_0001" (emit/compile-user-class->bytes a-lowered))
          b-cls (loader/define-class! l "nex.repl.B_0002" (emit/compile-user-class->bytes b-lowered))
          c-cls (loader/define-class! l "nex.repl.C_0003" (emit/compile-user-class->bytes c-lowered))
          parent-a (.getDeclaredField c-cls "_parent_A")
          parent-b (.getDeclaredField c-cls "_parent_B")]
      (is (= "java.lang.Object" (.getName (.getSuperclass c-cls))))
      (is (= a-cls (.getType parent-a)))
      (is (= b-cls (.getType parent-b))))))
