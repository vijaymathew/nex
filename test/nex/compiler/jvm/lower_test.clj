(ns nex.compiler.jvm.lower-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.ir :as ir]
            [nex.lower :as lower]
            [nex.parser :as p]))

(deftest make-lowering-env-test
  (testing "initial lowering env shape is stable"
    (let [env (lower/make-lowering-env)]
      (is (= {} (:locals env)))
      (is (= true (:top-level? env)))
      (is (= true (:repl? env)))
      (is (= 0 (:state-slot env)))
      (is (= 1 (:next-slot env)))
      (is (= [] (:classes env)))
      (is (= [] (:imports env)))
      (is (= {} (:var-types env))))))

(deftest lower-literals-and-binary-test
  (testing "binary arithmetic lowers to typed IR"
    (let [program (p/ast "let x := 1 + 2")
          {:keys [unit env]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0001"})
          stmt (first (:body unit))
          ret (second (:body unit))]
      (is (= :top-set (:op stmt)))
      (is (= "x" (:name stmt)))
      (is (= "Integer" (:nex-type stmt)))
      (is (= :int (:jvm-type stmt)))
      (is (= :binary (-> stmt :expr :op)))
      (is (= :add (-> stmt :expr :operator)))
      (is (= :const (-> stmt :expr :left :op)))
      (is (= 1 (-> stmt :expr :left :value)))
      (is (= 2 (-> stmt :expr :right :value)))
      (is (= :return (:op ret)))
      (is (= :top-get (-> ret :expr :op)))
      (is (= "x" (-> ret :expr :name)))
      (is (= "Integer" (get-in env [:var-types "x"])))
      (is (= 1 (:next-slot env))))))

(deftest lower-final-expression-to-return-test
  (testing "final repl expression lowers to a return"
    (let [program (p/ast "42")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0042"})
          stmt (first (:body unit))]
      (is (= 1 (count (:body unit))))
      (is (= :return (:op stmt)))
      (is (= :const (-> stmt :expr :op)))
      (is (= 42 (-> stmt :expr :value)))
      (is (= :int (-> stmt :expr :jvm-type)))
      (is (= (ir/object-jvm-type "java/lang/Object") (:jvm-type stmt))))))

(deftest lower-if-expression-to-return-test
  (testing "final if expressions lower to a return of an ir if node"
    (let [program (p/ast "if x > 0 then 1 else 2 end")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0045"
                                                         :var-types {"x" "Integer"}})
          stmt (first (:body unit))]
      (is (= 1 (count (:body unit))))
      (is (= :return (:op stmt)))
      (is (= :if (-> stmt :expr :op)))
      (is (= :compare (-> stmt :expr :test :op)))
      (is (= :gt (-> stmt :expr :test :operator)))
      (is (= :const (-> stmt :expr :then first :op)))
      (is (= 1 (-> stmt :expr :then first :value)))
      (is (= 2 (-> stmt :expr :else first :value))))))

(deftest lower-elseif-expression-to-nested-if-test
  (testing "elseif expressions lower to nested ir if nodes"
    (let [program (p/ast "if x > 10 then 1 elseif x > 5 then 2 else 3 end")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0046"
                                                         :var-types {"x" "Integer"}})
          stmt (first (:body unit))]
      (is (= :return (:op stmt)))
      (is (= :if (-> stmt :expr :op)))
      (is (= 1 (-> stmt :expr :then first :value)))
      (is (= :if (-> stmt :expr :else first :op)))
      (is (= 2 (-> stmt :expr :else first :then first :value)))
      (is (= 3 (-> stmt :expr :else first :else first :value))))))

(deftest lower-when-expression-test
  (testing "when expressions lower to ir if nodes"
    (let [program (p/ast "when x > 0 1 else 2 end")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0047"
                                                         :var-types {"x" "Integer"}})
          stmt (first (:body unit))]
      (is (= :return (:op stmt)))
      (is (= :if (-> stmt :expr :op)))
      (is (= 1 (-> stmt :expr :then first :value)))
      (is (= 2 (-> stmt :expr :else first :value))))))

(deftest lower-top-level-identifier-and-assign-test
  (testing "top-level identifiers and assignments lower through REPL state ops"
    (let [program (p/ast "x := x + 1")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0002"
                                                         :var-types {"x" "Integer"}})
          stmt (first (:body unit))]
      (is (= :top-set (:op stmt)))
      (is (= "x" (:name stmt)))
      (is (= :binary (-> stmt :expr :op)))
      (is (= :top-get (-> stmt :expr :left :op)))
      (is (= "x" (-> stmt :expr :left :name)))
      (is (= 1 (-> stmt :expr :right :value))))))

(deftest lower-identifier-expression-test
  (testing "identifier-only expressions lower as top-level gets when not local"
    (let [expr {:type :identifier :name "score"}
          env (lower/make-lowering-env {:var-types {"score" "Integer"}})
          lowered (lower/lower-expression env expr)]
      (is (= :top-get (:op lowered)))
      (is (= "score" (:name lowered)))
      (is (= "Integer" (:nex-type lowered)))
      (is (= :int (:jvm-type lowered))))))

(deftest lower-top-level-function-call-test
  (testing "top-level function calls lower to call-repl-fn nodes"
    (let [program (p/ast "function inc(n: Integer): Integer\ndo\n  result := n + 1\nend\n\ninc(4)")
          {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/Cell_0060"})
          stmt (first (:body unit))]
      (is (= 1 (count (:functions unit))))
      (is (= :return (:op stmt)))
      (is (= :call-repl-fn (-> stmt :expr :op)))
      (is (= "inc" (-> stmt :expr :name)))
      (is (= 1 (count (-> stmt :expr :args))))
      (is (= 4 (-> stmt :expr :args first :value))))))


(deftest lower-collection-literals-test
  (testing "collection literals lower to explicit IR nodes"
    (let [array-unit (:unit (lower/lower-repl-cell (p/ast "[1, 2, 3]") {:name "nex/repl/ArrayLit_0001"}))
          map-unit (:unit (lower/lower-repl-cell (p/ast "{\"a\": 1, \"b\": 2}") {:name "nex/repl/MapLit_0001"}))
          set-unit (:unit (lower/lower-repl-cell (p/ast "#{1, 2, 3}") {:name "nex/repl/SetLit_0001"}))]
      (is (= :array-literal (-> array-unit :body first :expr :op)))
      (is (= 3 (count (-> array-unit :body first :expr :elements))))
      (is (= :map-literal (-> map-unit :body first :expr :op)))
      (is (= 2 (count (-> map-unit :body first :expr :entries))))
      (is (= :set-literal (-> set-unit :body first :expr :op)))
      (is (= 3 (count (-> set-unit :body first :expr :elements)))))))

(deftest lower-collection-target-call-to-collection-method-test
  (let [program {:type :program
                 :imports []
                 :interns []
                 :classes []
                 :functions []
                 :statements [{:type :call
                               :target {:type :identifier :name "numbers"}
                               :method "length"
                               :args []
                               :has-parens false}]
                 :calls []}
        {:keys [unit]} (lower/lower-repl-cell program {:name "nex/repl/TestBuiltinTarget"
                                                       :var-types {"numbers" {:base-type "Array" :type-params ["Integer"]}}})
        ret-expr (-> unit :body last :expr)]
    (is (= :collection-method (:op ret-expr)))
    (is (= :array (:collection-kind ret-expr)))
    (is (= "length" (:method ret-expr)))
    (is (= 0 (count (:args ret-expr))))))

(deftest lower-operators-to-explicit-ir-test
  (testing "unary, concat, modulo, and bitwise operators lower to explicit operator-aware IR"
    (let [concat-program (p/ast "let s: String := \"n=\" + 10")
          concat-unit (:unit (lower/lower-repl-cell concat-program {:name "nex/repl/Concat_0001"}))
          concat-expr (-> concat-unit :body first :expr)
          unary-program (p/ast "let x: Integer := -5")
          unary-unit (:unit (lower/lower-repl-cell unary-program {:name "nex/repl/Unary_0001"}))
          unary-expr (-> unary-unit :body first :expr)
          mod-program (p/ast "let m: Integer := 10 % 3")
          mod-unit (:unit (lower/lower-repl-cell mod-program {:name "nex/repl/Mod_0001"}))
          mod-expr (-> mod-unit :body first :expr)
          bitwise-program (p/ast "let v: Integer := (6).bitwise_and(3)")
          bitwise-unit (:unit (lower/lower-repl-cell bitwise-program {:name "nex/repl/Bitwise_0001"}))
          bitwise-expr (-> bitwise-unit :body first :expr)]
      (is (= :call-runtime (:op concat-expr)))
      (is (= "op:string-concat" (:helper concat-expr)))
      (is (= :unary (:op unary-expr)))
      (is (= :neg (:operator unary-expr)))
      (is (= :binary (:op mod-expr)))
      (is (= :mod (:operator mod-expr)))
      (is (= :binary (:op bitwise-expr)))
      (is (= :bit-and (:operator bitwise-expr))))))

(deftest lower-deferred-class-metadata-test
  (testing "class lowering carries deferred and parent metadata for later compiler phases"
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
          shape-ir (lower/lower-class-def shape {:compiled-classes compiled-classes
                                                 :classes (:classes program)
                                                 :functions []
                                                 :imports []})
          square-ir (lower/lower-class-def square {:compiled-classes compiled-classes
                                                   :classes (:classes program)
                                                   :functions []
                                                   :imports []})
          shape-area (first (:methods shape-ir))
          square-area (first (:methods square-ir))]
      (is (true? (:deferred? shape-ir)))
      (is (= [] (:parents shape-ir)))
      (is (true? (:deferred? shape-area)))
      (is (false? (:override? shape-area)))
      (is (= [] (:body shape-area)))
      (is (false? (:deferred? square-ir)))
      (is (= [{:nex-name "Shape"
               :jvm-name "nex/repl/Shape_0001"
               :internal-name "nex/repl/Shape_0001"
               :binary-name "nex.repl.Shape_0001"
               :composition-field "_parent_Shape"
               :deferred? true}]
             (:parents square-ir)))
      (is (= [{:name "_parent_Shape"
               :parent "Shape"
               :deferred? true
               :jvm-type [:object "nex/repl/Shape_0001"]}]
             (:composition-fields square-ir)))
      (is (false? (:deferred? square-area)))
      (is (true? (:override? square-area))))))

(deftest lower-create-deferred-class-disallowed-test
  (testing "compiled lowering rejects direct instantiation of deferred classes"
    (let [program (p/ast "deferred class Shape
feature
  area(): Real do end
end

create Shape")
          shape (first (:classes program))
          env (lower/make-lowering-env {:classes (:classes program)
                                        :compiled-classes {"Shape" {:name "Shape"
                                                                    :internal-name "nex/repl/Shape_0001"
                                                                    :jvm-name "nex/repl/Shape_0001"
                                                                    :binary-name "nex.repl.Shape_0001"}}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unsupported create of deferred class"
                            (lower/lower-expression env (first (:statements program))))))))

(deftest lower-loop-basic-test
  (testing "compiled lowering lowers a simple from/until/do loop to :loop IR"
    (let [program (p/ast "from
  let i := 0
until
  i = 10
do
  i := i + 1
end")
          env (lower/make-lowering-env {:top-level? true})
          loop-stmt (first (:statements program))
          [env' block-ir] (lower/lower-statement env loop-stmt)
          loop-ir (-> block-ir :body last)]
      (is (= :block (:op block-ir)))
      (is (= :loop (:op loop-ir)))
      (is (= :set-local (-> block-ir :body first :op)))
      (is (some? (:test loop-ir)))
      (is (= :compare (:op (:test loop-ir))))
      (is (= 1 (count (:body loop-ir))))
      (is (= :set-local (:op (first (:body loop-ir)))))
      (is (= {} (:var-types env'))))))

(deftest lower-scoped-block-test
  (testing "compiled lowering lowers scoped do/end blocks to block ir without leaking bindings"
    (let [program (p/ast "do
  let y := 2
  print(y)
end")
          env (lower/make-lowering-env {:top-level? true})
          scoped-stmt (first (:statements program))
          [env' block-ir] (lower/lower-statement env scoped-stmt)]
      (is (= :block (:op block-ir)))
      (is (= 2 (count (:body block-ir))))
      (is (= :local (-> block-ir :body second :expr :args first :op)))
      (is (= {} (:var-types env'))))))

(deftest lower-scoped-block-with-rescue-test
  (testing "compiled lowering lowers scoped do/rescue/end blocks to try ir with exception binding"
    (let [program (p/ast "do
  raise \"boom\"
rescue
  print(exception)
  retry
end")
          env (lower/make-lowering-env {:top-level? true})
          scoped-stmt (first (:statements program))
          [env' try-ir] (lower/lower-statement env scoped-stmt)]
      (is (= :try (:op try-ir)))
      (is (= 1 (count (:body try-ir))))
      (is (= :raise (-> try-ir :body first :op)))
      (is (= 2 (count (:rescue try-ir))))
      (is (= :pop (-> try-ir :rescue first :op)))
      (is (= :retry (-> try-ir :rescue second :op)))
      (is (integer? (:throwable-slot try-ir)))
      (is (integer? (:rescue-throwable-slot try-ir)))
      (is (integer? (:exception-slot try-ir)))
      (is (= {} (:var-types env'))))))

(deftest lower-case-statement-test
  (testing "compiled lowering lowers case statements to block plus nested if-stmt ir"
    (let [program (p/ast "case x of
  1, 2 then print(\"a\")
  3 then print(\"b\")
  else print(\"c\")
end")
          env (lower/make-lowering-env {:top-level? true
                                        :var-types {"x" "Integer"}})
          case-stmt (first (:statements program))
          [_ block-ir] (lower/lower-statement env case-stmt)]
      (is (= :block (:op block-ir)))
      (is (= :set-local (-> block-ir :body first :op)))
      (is (= :if-stmt (-> block-ir :body second :op)))
      (is (= :if (-> block-ir :body second :test :op))))))

(deftest lower-method-require-ensure-old-test
  (testing "instance-method lowering snapshots old fields and lowers require/ensure to asserts"
    (let [program (p/ast "class Counter
feature
  value: Integer

  bump(): Integer
    require
      non_negative: value >= 0
    do
      value := value + 1
      result := value
    ensure
      advanced: value = old value + 1
      result_matches: result = value
    end
end")
          compiled-classes {"Counter" {:name "Counter"
                                       :internal-name "nex/repl/Counter_0001"
                                       :jvm-name "nex/repl/Counter_0001"
                                       :binary-name "nex.repl.Counter_0001"}}
          class-ir (lower/lower-class-def (first (:classes program))
                                          {:compiled-classes compiled-classes
                                           :classes (:classes program)
                                           :functions []
                                           :imports []})
          method-ir (first (:methods class-ir))
          ops (mapv :op (:body method-ir))]
      (is (= :set-local (first ops)))
      (is (= :assert (second ops)))
      (is (= :field-set (nth ops 2)))
      (is (= :set-local (nth ops 3)))
      (is (= :assert (nth ops 4)))
      (is (= :assert (nth ops 5)))
      (is (= :return (last ops)))
      (is (= :require (-> method-ir :body second :kind)))
      (is (= :ensure (-> method-ir :body (nth 4) :kind)))
      (is (= :ensure (-> method-ir :body (nth 5) :kind))))))

(deftest lower-loop-contracts-test
  (testing "compiled lowering lowers loop invariants and variants through block/assert IR"
    (let [program (p/ast "from
  let i := 3
invariant
  non_negative: i >= 0
variant
  i
until
  i = 0
do
  i := i - 1
end")
          env (lower/make-lowering-env {:top-level? true})
          loop-stmt (first (:statements program))
          [_ block-ir] (lower/lower-statement env loop-stmt)
          loop-ir (-> block-ir :body last)]
      (is (= :block (:op block-ir)))
      (is (= :assert (-> block-ir :body second :op)))
      (is (= :set-local (-> block-ir :body (nth 2) :op)))
      (is (= :set-local (-> block-ir :body (nth 3) :op)))
      (is (= :loop (:op loop-ir)))
      (is (= :set-local (-> loop-ir :body first :op)))
      (is (= :if-stmt (-> loop-ir :body second :op)))
      (is (= :assert (-> loop-ir :body second :then first :op)))
      (is (= :set-local (-> loop-ir :body (nth 2) :op)))
      (is (= :set-local (-> loop-ir :body (nth 3) :op)))
      (is (= :assert (:op (last (:body loop-ir))))))))
