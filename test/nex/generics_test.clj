(ns nex.generics-test
  "Tests for generic types (parameterized classes)"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]))

(deftest simple-generic-class-parsing-test
  (testing "Parse simple generic class"
    (let [code "class List [G]
  feature
    item: G
end"
          ast (p/ast code)
          class-def (first (:classes ast))]
      (is (= "List" (:name class-def)))
      (is (= 1 (count (:generic-params class-def))))
      (is (= "G" (-> class-def :generic-params first :name)))
      (is (nil? (-> class-def :generic-params first :constraint))))))

(deftest constrained-generic-parsing-test
  (testing "Parse generic class with constraint"
    (let [code "class Sorted_List [G -> Comparable]
  feature
    item: G
end"
          ast (p/ast code)
          class-def (first (:classes ast))]
      (is (= "Sorted_List" (:name class-def)))
      (is (= 1 (count (:generic-params class-def))))
      (is (= "G" (-> class-def :generic-params first :name)))
      (is (= "Comparable" (-> class-def :generic-params first :constraint))))))

(deftest multiple-generic-params-parsing-test
  (testing "Parse generic class with multiple parameters"
    (let [code "class Hash_Table [G, KEY -> Hashable]
  feature
    value: G
    key: KEY
end"
          ast (p/ast code)
          class-def (first (:classes ast))]
      (is (= "Hash_Table" (:name class-def)))
      (is (= 2 (count (:generic-params class-def))))
      (is (= "G" (-> class-def :generic-params first :name)))
      (is (nil? (-> class-def :generic-params first :constraint)))
      (is (= "KEY" (-> class-def :generic-params second :name)))
      (is (= "Hashable" (-> class-def :generic-params second :constraint))))))

(deftest detachable-generic-parameter-parsing-test
  (testing "Parse detachable generic parameter syntax"
    (let [code "class Linked_List [?T]
  feature
    value: ?T
    next: ?Linked_List [?T]
end"
          ast (p/ast code)
          class-def (first (:classes ast))]
      (is (= "Linked_List" (:name class-def)))
      (is (= 1 (count (:generic-params class-def))))
      (is (= {:name "T" :constraint nil :detachable true}
             (-> class-def :generic-params first)))
      (is (= {:base-type "T" :detachable true}
             (-> class-def :body first :members first :field-type)))
      (is (= {:base-type "Linked_List"
              :type-args [{:base-type "T" :detachable true}]
              :detachable true}
             (-> class-def :body first :members second :field-type))))))

(deftest linked-list-detachable-generic-typecheck-test
  (testing "Linked list can use detachable generic value for nil termination"
    (let [code "class Linked_List [?T]
  feature
    value: ?T
    next: ?Linked_List [?T]

    terminate() do
      value := nil
      next := nil
    end
end

class Main
  feature
    demo() do
      let list: Linked_List [Integer] := create Linked_List [Integer]
      list.terminate()
    end
end"]
      (is (some? (tc/type-check (p/ast code)))))))

(deftest parameterized-type-usage-parsing-test
  (testing "Parse parameterized type usage"
    (let [code "class Container
  feature
    cats: List [Cat]
    numbers: List [Integer]
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          fields (-> class-def :body first :members)]
      (is (= 2 (count fields)))
      ;; Check cats field
      (let [cats-field (first fields)]
        (is (= "cats" (:name cats-field)))
        (is (map? (:field-type cats-field)))
        (is (= "List" (-> cats-field :field-type :base-type)))
        (is (= ["Cat"] (-> cats-field :field-type :type-args))))
      ;; Check numbers field
      (let [numbers-field (second fields)]
        (is (= "numbers" (:name numbers-field)))
        (is (map? (:field-type numbers-field)))
        (is (= "List" (-> numbers-field :field-type :base-type)))
        (is (= ["Integer"] (-> numbers-field :field-type :type-args)))))))

(deftest create-with-generic-args-walker-test
  (testing "Walker parses create Box[Integer].make(42) with generic-args"
    (let [code "class Box [T]
  create
    make(val: T) do
      let value: T := val
    end
  feature
    value: T
end

class Main
  feature
    demo() do
      let b: Box[Integer] := create Box[Integer].make(42)
    end
end"
          ast (p/ast code)
          main-class (second (:classes ast))
          method (-> main-class :body first :members first)
          let-stmt (-> method :body first)
          create-expr (:value let-stmt)]
      (is (= :create (:type create-expr)))
      (is (= "Box" (:class-name create-expr)))
      (is (= ["Integer"] (:generic-args create-expr)))
      (is (= "make" (:constructor create-expr)))
      (is (= 1 (count (:args create-expr)))))))

(deftest create-with-generic-args-interpreter-test
  (testing "Interpreter specializes generic class on create"
    (let [code "class Box [T]
  create
    make(val: T) do
      let value: T := val
    end
  feature
    value: T

    get_value(): T do
      let result: T := value
    end
end

class Main
  feature
    demo() do
      let b: Box[Integer] := create Box[Integer].make(42)
      println(b.get_value())
    end
end"
          ast (p/ast code)
          ctx (interp/interpret ast)
          ;; Create Main instance and call demo
          _ (interp/eval-node ctx {:type :let :name "main_obj"
                                    :value {:type :create :class-name "Main"
                                            :generic-args nil :constructor nil :args []}})
          _ (interp/eval-node ctx {:type :call :target "main_obj" :method "demo" :args []})
          output @(:output ctx)]
      (is (= ["42"] output)))))

(deftest logged-box-generic-inheritance-test
  (testing "Generic subclasses can inherit from generic parents and extend behavior"
    (let [code "class Box [T]
  create
    make(v: T) do
      value := v
    end
  feature
    value: T

    get(): T do
      result := value
    end

    set(v: T) do
      value := v
    end
end

class Logged_Box [T] inherit Box[T]
  create
    make(v: T) do
      Box.make(v)
      change_count := 0
    end
  feature
    change_count: Integer

    set(v: T) do
      Box.set(v)
      change_count := change_count + 1
    end

    changes(): Integer do
      result := change_count
    end
end

let box: Logged_Box[Integer] := create Logged_Box[Integer].make(7)
print(box.get())
box.set(9)
box.set(11)
print(box.get())
print(box.changes())"
          ast (p/ast code)
          typecheck-result (tc/type-check ast)
          ctx (interp/interpret ast)
          output @(:output ctx)]
      (is (:success typecheck-result))
      (is (= ["7" "11" "2"] output)))))

(def ^:private instantiated-generic-parent-prelude
  "class Draft
  create
    make() do
    end
end

class Final
  create
    make() do
    end
end

class Spec [T]
  create
    make() do
    end
  feature
    item: T
    get_item(): T do
      result := item
    end
    describe(): String do
      result := \"spec\"
    end
end

class Pair [A, B]
  create
    make() do
    end
  feature
    first: A
    get_first(): A do
      result := first
    end
end

-- Non-generic heir of an instantiated generic: carries no type arguments of its
-- own, but still conforms to Spec[Draft].
class Over_Amount inherit Spec[Draft]
  create
    make() do
      Spec.make()
    end
  feature
    describe(): String do
      result := \"over amount\"
    end
end

class Runner
  feature
    use(s: Spec[Draft]): String do
      result := s.describe()
    end
    use_final(s: Spec[Final]): String do
      result := s.describe()
    end
    use_pair(p: Pair[Draft, Final]): String do
      result := \"pair\"
    end
end
")

(deftest non-generic-heir-of-instantiated-generic-test
  (testing "A non-generic class inheriting Spec[Draft] is assignable to Spec[Draft]"
    (let [code (str instantiated-generic-parent-prelude
                    "let r: Runner := create Runner
let a: Over_Amount := create Over_Amount.make()
print(r.use(a))")
          ast (p/ast code)
          typecheck-result (tc/type-check ast)
          ctx (interp/interpret ast)]
      (is (:success typecheck-result))
      ;; and the override dispatches through the Spec[Draft] parameter
      (is (= ["\"over amount\""] @(:output ctx))))))

(deftest non-generic-heir-conforms-transitively-test
  (testing "An indirect non-generic heir still conforms to the instantiated parent"
    (let [code (str instantiated-generic-parent-prelude
                    "class Deep inherit Over_Amount
  create
    make() do
      Over_Amount.make()
    end
end

let r: Runner := create Runner
let d: Deep := create Deep.make()
print(r.use(d))")]
      (is (:success (tc/type-check (p/ast code)))))))

(deftest non-generic-heir-rejects-wrong-type-argument-test
  (testing "A heir of Spec[Draft] does NOT conform to Spec[Final]"
    (let [code (str instantiated-generic-parent-prelude
                    "let r: Runner := create Runner
let a: Over_Amount := create Over_Amount.make()
print(r.use_final(a))")
          result (tc/type-check (p/ast code))]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest generic-heir-resolves-reordered-parent-arguments-test
  (testing "Type arguments are resolved through the inherit clause, not positionally"
    (let [code "class Swapped [X, Y] inherit Pair[Y, X]
  create
    make() do
      Pair.make()
    end
end
"]
      ;; Swapped[Final, Draft] IS a Pair[Draft, Final]
      (is (:success (tc/type-check
                     (p/ast (str instantiated-generic-parent-prelude code
                                 "let r: Runner := create Runner
let s: Swapped[Final, Draft] := create Swapped[Final, Draft].make()
print(r.use_pair(s))")))))
      ;; ...and Swapped[Draft, Final] is a Pair[Final, Draft], so it is not.
      (is (not (:success (tc/type-check
                          (p/ast (str instantiated-generic-parent-prelude code
                                      "let r: Runner := create Runner
let s: Swapped[Draft, Final] := create Swapped[Draft, Final].make()
print(r.use_pair(s))")))))))))

(def ^:private swapped-heir
  "class Swapped [X, Y] inherit Pair[Y, X]
  create
    make() do
      Pair.make()
    end
end
")

(deftest inherited-generic-member-resolves-through-inherit-clause-test
  (testing "An inherited member's type is resolved in its declaring class's parameters"
    ;; Over_Amount inherit Spec[Draft], so the inherited get_item(): T is a Draft.
    (is (:success (tc/type-check
                   (p/ast (str instantiated-generic-parent-prelude
                               "let a: Over_Amount := create Over_Amount.make()
let d: Draft := a.get_item()")))))
    ;; Swapped[Final, Draft] IS a Pair[Draft, Final], so the inherited first: A
    ;; and get_first(): A are Drafts — not Finals — even though the heir renames
    ;; and reorders its parent's parameters.
    (is (:success (tc/type-check
                   (p/ast (str instantiated-generic-parent-prelude swapped-heir
                               "let s: Swapped[Final, Draft] := create Swapped[Final, Draft].make()
let d: Draft := s.get_first()
let e: Draft := s.first")))))))

(deftest inherited-generic-member-rejects-wrong-type-test
  (testing "An inherited member's type is not a wildcard: the wrong type is rejected"
    ;; Before the inherit clause was resolved, these member types stayed as the
    ;; parent's raw parameter name (T / A), which conformed to anything.
    (is (not (:success (tc/type-check
                        (p/ast (str instantiated-generic-parent-prelude
                                    "let a: Over_Amount := create Over_Amount.make()
let f: Final := a.get_item()"))))))
    (is (not (:success (tc/type-check
                        (p/ast (str instantiated-generic-parent-prelude swapped-heir
                                    "let s: Swapped[Final, Draft] := create Swapped[Final, Draft].make()
let f: Final := s.get_first()"))))))
    (is (not (:success (tc/type-check
                        (p/ast (str instantiated-generic-parent-prelude swapped-heir
                                    "let s: Swapped[Final, Draft] := create Swapped[Final, Draft].make()
let f: Final := s.first"))))))))

(deftest inherited-generic-field-resolves-inside-heir-test
  (testing "Inside the heir, an inherited generic field binds to the supplied argument"
    (let [heir "class Fixed inherit Pair[Draft, Final]
  create
    make() do
      Pair.make()
    end
  feature
    peek(): Draft do
      result := first
    end
end
"]
      (is (:success (tc/type-check
                     (p/ast (str instantiated-generic-parent-prelude heir)))))
      ;; ...and it is a Draft, not a Final.
      (is (not (:success (tc/type-check
                          (p/ast (str instantiated-generic-parent-prelude
                                      (str/replace heir "peek(): Draft" "peek(): Final"))))))))))

(deftest create-generic-without-constructor-test
  (testing "Create generic class without constructor"
    (let [code "class Holder [T]
  feature
    item: T
end

class Main
  feature
    demo() do
      let h: Holder[String] := create Holder[String]
    end
end"
          ast (p/ast code)
          main-class (second (:classes ast))
          method (-> main-class :body first :members first)
          let-stmt (-> method :body first)
          create-expr (:value let-stmt)]
      (is (= :create (:type create-expr)))
      (is (= "Holder" (:class-name create-expr)))
      (is (= ["String"] (:generic-args create-expr)))
      (is (nil? (:constructor create-expr))))))
