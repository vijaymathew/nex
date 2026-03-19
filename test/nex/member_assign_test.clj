(ns nex.member-assign-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.generator.javascript :as js]
            [nex.interpreter :as interp]
            [nex.parser :as p]))

(deftest member-assign-parsing-test
  (testing "Parse field assignment on an explicit target object"
    (let [ast (p/ast "class Point
feature
  x: Integer
end

let p: Point := create Point
p.x := 10")
          assign-stmt (last (:statements ast))]
      (is (= :member-assign (:type assign-stmt)))
      (is (= "x" (:field assign-stmt)))
      (is (= :identifier (-> assign-stmt :object :type)))
      (is (= "p" (-> assign-stmt :object :name)))
      (is (= :integer (-> assign-stmt :value :type))))))

(deftest member-assign-interpreter-test
  (testing "Interpreter updates a field on an explicit target object"
    (let [ctx (interp/make-context)
          ast (p/ast "class Point
feature
  x: Integer
end

let p: Point := create Point
p.x := 10")]
      (doseq [class-def (:classes ast)]
        (interp/register-class ctx class-def))
      (doseq [stmt (:statements ast)]
        (interp/eval-node ctx stmt))
      (let [p (interp/env-lookup (:globals ctx) "p")]
        (is (= 10 (get (:fields p) :x)))))))

(deftest member-assign-generator-test
  (testing "JavaScript generator emits explicit target field assignment"
    (let [code "class Point
feature
  x: Integer
end

class Main
feature
  run() do
    let p: Point := create Point
    p.x := 10
  end
end"]
      (is (str/includes? (js/translate code) "p.x = 10;")))))
