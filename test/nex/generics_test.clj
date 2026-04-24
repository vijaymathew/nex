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
