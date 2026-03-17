(ns nex.this-super-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.walker :as walker]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [nex.generator.java :as java-gen]
            [nex.generator.javascript :as js-gen]
            [clojure.string :as str]))

;; ─── Parser / Walker Tests ───

(deftest parse-this-field-assignment
  (testing "this.x := 5 parses to :member-assign"
    (let [code "class A
  create
    make(x: Integer) do
      this.x := x
    end
  feature
    x: Integer
end"
          ast (p/ast code)
          ctor (-> ast :classes first :body
                   (->> (filter #(= (:type %) :constructors)))
                   first :constructors first)
          stmt (first (:body ctor))]
      (is (= :member-assign (:type stmt)))
      (is (= :this (-> stmt :object :type)))
      (is (= "x" (:field stmt))))))

(deftest parse-super-field-assignment
  (testing "super.field assignment now parses as a member assignment target"
    (let [code "class B
  inherit A
  create
    make(x: Integer) do
      super.x := x
    end
  feature
    y: Integer
end

class A
  feature
    x: Integer
end"
          ast (p/ast code)
          ctor (-> ast :classes first :body
                   (->> (filter #(= (:type %) :constructors)))
                   first :constructors first)
          stmt (first (:body ctor))]
      (is (= :member-assign (:type stmt)))
      (is (= :identifier (-> stmt :object :type)))
      (is (= "super" (-> stmt :object :name)))
      (is (= "x" (:field stmt))))))

(deftest parse-this-in-expression
  (testing "this parses to {:type :this} in primary position"
    (let [code "class A
  feature
    x: Integer
    get_x: Integer do
      result := this.x
    end
end"
          ast (p/ast code)
          method (-> ast :classes first :body
                     (->> (filter #(= (:type %) :feature-section)))
                     first :members
                     (->> (filter #(= (:type %) :method)))
                     first)
          ;; The body should have an assignment where value is a call on this
          stmt (first (:body method))]
      ;; result := this.x  =>  {:type :assign :target "result" :value {:type :call :target {:type :this} ...}}
      (is (= :assign (:type stmt)))
      (is (= :call (:type (:value stmt))))
      (is (= :this (:type (:target (:value stmt))))))))

;; ─── Interpreter Tests ───

(deftest this-field-assign-in-constructor
  (testing "this.field := param sets the field in a constructor"
    (let [code "class Point
  create
    make(x: Integer, y: Integer) do
      this.x := x
      this.y := y
    end
  feature
    x: Integer
    y: Integer
end"
          ast (p/ast code)
          ctx (interp/make-context)
          _ (interp/eval-node ctx ast)
          env (interp/make-env (:globals ctx))
          obj (interp/eval-node (assoc ctx :current-env env)
                                {:type :create
                                 :class-name "Point"
                                 :generic-args nil
                                 :constructor "make"
                                 :args [{:type :integer :value 10}
                                        {:type :integer :value 20}]})]
      (is (= 10 (:x (:fields obj))))
      (is (= 20 (:y (:fields obj)))))))

(deftest super-method-call
  (testing "super.method() is not executed by interpreter yet"
    (let [code "class A
  feature
    x: Integer
    show do
      print(x)
    end
end

class B
  inherit A
  create
    make(x: Integer, y: Integer) do
      this.x := x
      this.y := y
    end
  feature
    y: Integer
    show do
      super.show()
      print(y)
    end
end"
          ast (p/ast code)
          ctx (interp/make-context)
          _ (interp/eval-node ctx ast)
          env (interp/make-env (:globals ctx))
          obj (interp/eval-node (assoc ctx :current-env env)
                                {:type :create
                                 :class-name "B"
                                 :generic-args nil
                                 :constructor "make"
                                 :args [{:type :integer :value 10}
                                        {:type :integer :value 20}]})
          _ (interp/env-define env "b" obj)
          call-ctx (assoc ctx :current-env env)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Undefined variable: super"
           (interp/eval-node call-ctx {:type :call
                                       :target "b"
                                       :method "show"
                                       :args []}))))))

(deftest this-field-assign-with-same-param-name
  (testing "this.x := x disambiguates field from parameter"
    (let [code "class Box
  create
    make(value: Integer) do
      this.value := value
    end
  feature
    value: Integer
end"
          ast (p/ast code)
          ctx (interp/make-context)
          _ (interp/eval-node ctx ast)
          env (interp/make-env (:globals ctx))
          obj (interp/eval-node (assoc ctx :current-env env)
                                {:type :create
                                 :class-name "Box"
                                 :generic-args nil
                                 :constructor "make"
                                 :args [{:type :integer :value 42}]})]
      (is (= 42 (:value (:fields obj)))))))

;; ─── Typechecker Tests ───

(deftest typecheck-this-super-usage
  (testing "this and super usage passes type checking"
      (let [code "class A
  feature
    x: Integer
    show do
      print(x)
    end
end

class B
  inherit A
  create
    make(x: Integer, y: Integer) do
      this.x := x
      this.y := y
    end
  feature
    y: Integer
    show do
      super.show()
      print(y)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest typecheck-this-field-assign
  (testing "this.field := value type-checks correctly"
    (let [code "class Simple
  create
    make(v: Integer) do
      this.val := v
    end
  feature
    val: Integer
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

;; ─── Java Generator Tests ───

(deftest java-this-super-expression
  (testing "this and super generate correctly in Java"
    (let [code "class A
  feature
    x: Integer
    show do
      print(x)
    end
end

class B
  inherit A
  create
    make(x: Integer, y: Integer) do
      this.x := x
      this.y := y
    end
  feature
    y: Integer
    show do
      super.show()
      print(y)
    end
end"
          java-code (java-gen/translate code {:skip-type-check true})]
      ;; In factory method, this maps to local variable name
      (is (str/includes? java-code "b._parent_A.x = x;"))
      (is (str/includes? java-code "b.y = y;"))
      ;; Constructor is a static factory method
      (is (str/includes? java-code "public static B make("))
      (is (str/includes? java-code "B b = new B();"))
      (is (str/includes? java-code "return b;")))))

;; ─── JavaScript Generator Tests ───

(deftest js-this-super-expression
  (testing "this and super generate correctly in JavaScript"
    (let [code "class A
  feature
    x: Integer
    show do
      print(x)
    end
end

class B
  inherit A
  create
    make(x: Integer, y: Integer) do
      this.x := x
      this.y := y
    end
  feature
    y: Integer
    show do
      super.show()
      print(y)
    end
end"
          js-code (js-gen/translate code {:skip-type-check true})]
      ;; In factory method, this maps to local variable name
      (is (str/includes? js-code "b.x = x;"))
      (is (str/includes? js-code "b.y = y;"))
      ;; Constructor is a static factory method
      (is (str/includes? js-code "static async make("))
      (is (str/includes? js-code "let b = new B();"))
      (is (str/includes? js-code "return b;")))))

;; ─── Create Expression Tests ───

(deftest java-create-factory-method
  (testing "create A.make(10) generates A.make(10) in Java"
    (let [expr {:type :create :class-name "A" :generic-args nil
                :constructor "make" :args [{:type :integer :value 10}]}]
      (is (= "A.make(10)" (java-gen/generate-create-expr expr)))))
  (testing "create A generates new A() in Java"
    (let [expr {:type :create :class-name "A" :generic-args nil
                :constructor nil :args []}]
      (is (= "new A()" (java-gen/generate-create-expr expr))))))

(deftest js-create-factory-method
  (testing "create A.make(10) generates A.make(10) in JS"
    (let [expr {:type :create :class-name "A" :generic-args nil
                :constructor "make" :args [{:type :integer :value 10}]}]
    (is (= "await A.make(10)" (js-gen/generate-create-expr expr)))))
  (testing "create A generates new A() in JS"
    (let [expr {:type :create :class-name "A" :generic-args nil
                :constructor nil :args []}]
      (is (= "new A()" (js-gen/generate-create-expr expr))))))
