(ns nex.visibility-test
  "Tests for feature visibility modifiers"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.generator.java :as java]))

(deftest public-feature-parsing-test
  (testing "Parse public feature (default)"
    (let [code "class Test
  feature
    x: Integer
    demo() do
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          feature-section (first (:body class-def))]
      (is (= :feature-section (:type feature-section)))
      (is (= :public (-> feature-section :visibility :type))))))

(deftest private-feature-parsing-test
  (testing "Parse private feature"
    (let [code "class Test
  private feature
    x: Integer
    helper() do
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          feature-section (first (:body class-def))]
      (is (= :feature-section (:type feature-section)))
      (is (= :private (-> feature-section :visibility :type))))))

(deftest selective-feature-parsing-test
  (testing "Parse selective visibility feature"
    (let [code "class Test
  -> Friend, Helper feature
    x: Integer
    restricted() do
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          feature-section (first (:body class-def))]
      (is (= :feature-section (:type feature-section)))
      (is (= :selective (-> feature-section :visibility :type)))
      (is (= ["Friend" "Helper"] (-> feature-section :visibility :classes))))))

(deftest mixed-visibility-parsing-test
  (testing "Parse class with mixed visibility sections"
    (let [code "class Account
  feature
    balance: Integer

  private feature
    internal_balance: Integer
    calculate_fee() do
      print(internal_balance)
    end

  -> Bank, Auditor feature
    audit_log: String
    get_audit() do
      print(audit_log)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          sections (:body class-def)]
      (is (= 3 (count sections)))
      (is (= :public (-> sections (nth 0) :visibility :type)))
      (is (= :private (-> sections (nth 1) :visibility :type)))
      (is (= :selective (-> sections (nth 2) :visibility :type)))
      (is (= ["Bank" "Auditor"] (-> sections (nth 2) :visibility :classes))))))

(deftest public-java-generation-test
  (testing "Generate Java code with public features"
    (let [code "class Test
  feature
    x: Integer
    demo() do
      print(x)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "private int x = 0;"))
      (is (str/includes? java-code "public void demo()")))))

(deftest private-java-generation-test
  (testing "Generate Java code with private features"
    (let [code "class Test
  private feature
    x: Integer
    helper() do
      print(x)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "private int x = 0;"))
      (is (str/includes? java-code "private void helper()")))))

(deftest selective-java-generation-test
  (testing "Generate Java code with selective visibility"
    (let [code "class Test
  -> Friend, Helper feature
    x: Integer
    restricted() do
      print(x)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "/* Visible to: Friend, Helper */ int x = 0;"))
      (is (str/includes? java-code "/* Visible to: Friend, Helper */ void restricted()")))))

(deftest mixed-visibility-java-generation-test
  (testing "Generate Java code with mixed visibility"
    (let [code "class Account
  feature
    balance: Integer
    deposit(amount: Integer) do
      balance := balance + amount
    end

  private feature
    internal_id: String
    generate_id() do
      print(internal_id)
    end

  -> Bank, Auditor feature
    audit_log: String
    log_transaction() do
      print(audit_log)
    end
end"
          java-code (java/translate code)]
      ;; Public members
      (is (str/includes? java-code "private int balance = 0;"))
      (is (str/includes? java-code "public void deposit(int amount)"))
      ;; Private members
      (is (str/includes? java-code "private String internal_id = \"\";"))
      (is (str/includes? java-code "private void generate_id()"))
      ;; Selective visibility
      (is (str/includes? java-code "/* Visible to: Bank, Auditor */ String audit_log = \"\";"))
      (is (str/includes? java-code "/* Visible to: Bank, Auditor */ void log_transaction()")))))

(deftest multiple-selective-sections-test
  (testing "Multiple selective visibility sections with different classes"
    (let [code "class System
  -> Admin feature
    admin_only: Integer

  -> User, Guest feature
    user_visible: String
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          sections (:body class-def)]
      (is (= 2 (count sections)))
      (is (= ["Admin"] (-> sections (nth 0) :visibility :classes)))
      (is (= ["User" "Guest"] (-> sections (nth 1) :visibility :classes))))))

(deftest visibility-with-contracts-test
  (testing "Visibility modifiers work with contracts"
    (let [code "class Secure
  private feature
    secret: Integer
    validate(x: Integer)
      require
        positive: x > 0
      do
        print(x)
      ensure
        checked: secret >= 0
      end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "private int secret = 0;"))
      (is (str/includes? java-code "private void validate(int x)"))
      (is (str/includes? java-code "assert (x > 0)"))
      (is (str/includes? java-code "assert (secret >= 0)")))))

(deftest single-class-selective-test
  (testing "Selective visibility with single class"
    (let [code "class Test
  -> Friend feature
    helper() do
      print(42)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          feature-section (first (:body class-def))]
      (is (= :selective (-> feature-section :visibility :type)))
      (is (= ["Friend"] (-> feature-section :visibility :classes))))))

(deftest empty-feature-section-test
  (testing "Feature section visibility without members should parse"
    ;; This tests grammar correctness even if semantically unusual
    (let [code "class Test
  feature
    x: Integer
  private feature
    y: Integer
end"
          ast (p/ast code)]
      (is (some? ast)))))
