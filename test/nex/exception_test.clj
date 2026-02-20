(ns nex.exception-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [nex.generator.java :as java-gen]
            [nex.generator.javascript :as js-gen]))

;; Helper to get AST
(defn parse [code]
  (p/ast code))

;; Helper to execute a method body and get output
(defn execute-method [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [c (:classes ast)]
            (interp/register-class ctx c))
        method-body (-> ast :classes first :body first :members first :body)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt method-body]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

;; Helper that captures output even if an exception is thrown
(defn execute-method-with-exception [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [c (:classes ast)]
            (interp/register-class ctx c))
        method-body (-> ast :classes first :body first :members first :body)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (try
      (doseq [stmt method-body]
        (interp/eval-node ctx-with-env stmt))
      {:output @(:output ctx-with-env) :exception nil}
      (catch clojure.lang.ExceptionInfo e
        {:output @(:output ctx-with-env) :exception e}))))

;; ===== Parser Tests =====

(deftest parse-raise-test
  (testing "raise statement parses correctly"
    (let [ast (parse "class Test
  feature
    demo() do
      raise \"error\"
    end
end")
          stmt (-> ast :classes first :body first :members first :body first)]
      (is (= :raise (:type stmt)))
      (is (= :string (:type (:value stmt)))))))

(deftest parse-retry-test
  (testing "retry statement parses correctly"
    (let [ast (parse "class Test
  feature
    demo() do
      do
        raise \"oops\"
      rescue
        retry
      end
    end
end")
          scoped (-> ast :classes first :body first :members first :body first)
          retry-stmt (first (:rescue scoped))]
      (is (= :scoped-block (:type scoped)))
      (is (some? (:rescue scoped)))
      (is (= :retry (:type retry-stmt))))))

(deftest parse-rescue-in-scoped-block-test
  (testing "do...rescue...end parses with rescue in AST"
    (let [ast (parse "class Test
  feature
    demo() do
      do
        raise \"fail\"
      rescue
        print(exception)
      end
    end
end")
          scoped (-> ast :classes first :body first :members first :body first)]
      (is (= :scoped-block (:type scoped)))
      (is (vector? (:rescue scoped)))
      (is (= 1 (count (:rescue scoped)))))))

(deftest parse-rescue-in-method-test
  (testing "method with rescue parses correctly"
    (let [ast (parse "class Test
  feature
    demo() do
      raise \"fail\"
    rescue
      print(exception)
    end
end")
          method (-> ast :classes first :body first :members first)]
      (is (= :method (:type method)))
      (is (vector? (:rescue method)))
      (is (= 1 (count (:rescue method)))))))

;; ===== Interpreter Tests =====

(deftest raise-without-rescue-test
  (testing "raise without rescue propagates exception"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"error msg"
          (execute-method "class Test
  feature
    demo() do
      raise \"error msg\"
    end
end")))))

(deftest rescue-catches-exception-test
  (testing "rescue catches exception and exception variable has correct value"
    (let [result (execute-method-with-exception "class Test
  feature
    demo() do
      do
        raise \"caught me\"
      rescue
        print(exception)
      end
    end
end")]
      ;; The rescue block runs print(exception), then rethrows
      (is (= ["\"caught me\""] (:output result)))
      (is (some? (:exception result))))))

(deftest rescue-with-retry-test
  (testing "rescue with retry re-executes body"
    (let [output (execute-method "class Test
  feature
    demo() do
      let count := 0
      do
        count := count + 1
        if count < 3 then
          raise \"retry me\"
        else
          print(count)
        end
      rescue
        retry
      end
    end
end")]
      (is (= ["3"] output)))))

(deftest rescue-without-retry-rethrows-test
  (testing "rescue without retry rethrows after rescue block executes"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"rethrown"
          (execute-method "class Test
  feature
    demo() do
      do
        raise \"rethrown\"
      rescue
        print(exception)
      end
    end
end")))))

(deftest method-rescue-test
  (testing "method-level rescue catches and rethrows without retry"
    (let [code "class Test
  feature
    demo() do
      raise \"method fail\"
    rescue
      print(exception)
    end
end"
          ast (p/ast code)
          ctx (interp/make-context)
          _ (doseq [c (:classes ast)]
              (interp/register-class ctx c))
          obj (interp/make-object "Test" {})
          _ (interp/env-define (:current-env ctx) "t" obj)
          result (try
                   (interp/eval-node ctx {:type :call :target "t" :method "demo" :args []})
                   {:exception nil}
                   (catch clojure.lang.ExceptionInfo e
                     {:exception e}))]
      ;; The rescue block runs print(exception), then rethrows
      (is (= ["\"method fail\""] @(:output ctx)))
      (is (some? (:exception result))))))

(deftest raise-integer-value-test
  (testing "raise with non-string value"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"42"
          (execute-method "class Test
  feature
    demo() do
      raise 42
    end
end")))))

;; ===== Typechecker Tests =====

(deftest typecheck-raise-rescue-retry-test
  (testing "raise/rescue/retry pass type checking"
    (let [result (tc/type-check (parse "class Test
  feature
    demo() do
      do
        raise \"oops\"
      rescue
        print(exception)
        retry
      end
    end
end"))]
      (is (:success result)))))

(deftest typecheck-method-rescue-test
  (testing "method with rescue passes type checking"
    (let [result (tc/type-check (parse "class Test
  feature
    demo() do
      raise \"fail\"
    rescue
      print(exception)
    end
end"))]
      (is (:success result)))))

;; ===== Java Generator Tests =====

(deftest java-raise-test
  (testing "raise generates throw in Java"
    (let [ast (parse "class Test
  feature
    demo() do
      raise \"error\"
    end
end")
          java (java-gen/translate-ast ast {:skip-contracts true})]
      (is (re-find #"throw new RuntimeException" java)))))

(deftest java-rescue-retry-test
  (testing "rescue with retry generates while/try/catch in Java"
    (let [ast (parse "class Test
  feature
    demo() do
      do
        raise \"oops\"
      rescue
        retry
      end
    end
end")
          java (java-gen/translate-ast ast {:skip-contracts true})]
      (is (re-find #"while \(true\)" java))
      (is (re-find #"try \{" java))
      (is (re-find #"catch \(Exception _nex_e\)" java))
      (is (re-find #"continue;" java))
      (is (re-find #"break;" java)))))

(deftest java-rescue-no-retry-test
  (testing "rescue without retry generates try/catch with rethrow in Java"
    (let [ast (parse "class Test
  feature
    demo() do
      do
        raise \"oops\"
      rescue
        print(exception)
      end
    end
end")
          java (java-gen/translate-ast ast {:skip-contracts true})]
      (is (re-find #"try \{" java))
      (is (re-find #"catch \(Exception _nex_e\)" java))
      (is (re-find #"throw _nex_e;" java))
      (is (not (re-find #"while \(true\)" java))))))

;; ===== JavaScript Generator Tests =====

(deftest js-raise-test
  (testing "raise generates throw in JavaScript"
    (let [ast (parse "class Test
  feature
    demo() do
      raise \"error\"
    end
end")
          js (js-gen/translate-ast ast {:skip-contracts true})]
      (is (re-find #"throw \"error\";" js)))))

(deftest js-rescue-retry-test
  (testing "rescue with retry generates while/try/catch in JavaScript"
    (let [ast (parse "class Test
  feature
    demo() do
      do
        raise \"oops\"
      rescue
        retry
      end
    end
end")
          js (js-gen/translate-ast ast {:skip-contracts true})]
      (is (re-find #"while \(true\)" js))
      (is (re-find #"try \{" js))
      (is (re-find #"catch \(_nex_e\)" js))
      (is (re-find #"continue;" js))
      (is (re-find #"break;" js)))))

(deftest js-rescue-no-retry-test
  (testing "rescue without retry generates try/catch with rethrow in JavaScript"
    (let [ast (parse "class Test
  feature
    demo() do
      do
        raise \"oops\"
      rescue
        print(exception)
      end
    end
end")
          js (js-gen/translate-ast ast {:skip-contracts true})]
      (is (re-find #"try \{" js))
      (is (re-find #"catch \(_nex_e\)" js))
      (is (re-find #"throw _nex_e;" js))
      (is (not (re-find #"while \(true\)" js))))))
