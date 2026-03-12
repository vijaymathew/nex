(ns nex.integer-literals-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.generator.java :as java]
            [nex.generator.javascript :as js]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- execute-method-output [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        class-def (first (:classes ast))
        method-def (-> class-def :body first :members first)
        _ (interp/register-class ctx class-def)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt (:body method-def)]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

(deftest parser-normalizes-base-prefixed-integers
  (testing "binary, octal, hex, and grouped decimal literals parse as integers"
    (let [ast (p/ast "class Test
  feature
    demo() do
      let a: Integer := 0b1010
      let b: Integer := 0o755
      let c: Integer := 0xFF
      let d: Integer64 := 1_000_000
    end
end")
          lets (-> ast :classes first :body first :members first :body)]
      (is (= 10 (-> lets first :value :value)))
      (is (= 493 (-> lets second :value :value)))
      (is (= 255 (-> lets (nth 2) :value :value)))
      (is (= 1000000 (-> lets (nth 3) :value :value))))))

(deftest negative-prefixed-integers-work-via-unary-minus
  (testing "sign remains outside the integer token"
    (let [ast (p/ast "class Test
  feature
    demo() do
      print(-0x10)
      print(-0b11)
    end
end")
          output (execute-method-output "class Test
  feature
    demo() do
      print(-0x10)
      print(-0b11)
    end
end")]
      (is (= :unary (-> ast :classes first :body first :members first :body first :args first :type)))
      (is (= ["-16" "-3"] output)))))

(deftest prefixed-integers-typecheck-cleanly
  (testing "typed lets accept base-prefixed integer literals"
    (let [ast (p/ast "class Test
  feature
    demo() do
      let mask: Integer := 0b1111_0000
      let perms: Integer := 0o644
      let color: Integer64 := 0x7fff_ffff
    end
end")
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest prefixed-integer-runtime-behavior
  (testing "interpreter evaluates prefixed integer literals as ordinary integers"
    (let [output (execute-method-output "class Test
  feature
    demo() do
      print(0b1010 + 1)
      print(0o10 + 1)
      print(0x10 + 1)
    end
end")]
      (is (= ["11" "9" "17"] output)))))

(deftest prefixed-integer-java-generation
  (testing "Java generation emits normalized integer values"
    (let [java-code (java/translate "class Test
  feature
    demo() do
      let mask: Integer := 0b1111_0000
      let perms: Integer := 0o644
      let color: Integer64 := 0xFF_AA
    end
end")]
      (is (str/includes? java-code "int mask = 240;"))
      (is (str/includes? java-code "int perms = 420;"))
      (is (str/includes? java-code "long color = 65450;")))))

(deftest prefixed-integer-javascript-generation
  (testing "JavaScript generation emits normalized integer values"
    (let [js-code (js/translate "class Test
  feature
    demo() do
      let mask: Integer := 0b1010
      let perms: Integer := 0o10
      let color: Integer64 := 0xFF
    end
end")]
      (is (str/includes? js-code "let mask = 10;"))
      (is (str/includes? js-code "let perms = 8;"))
      (is (str/includes? js-code "let color = 255;")))))
