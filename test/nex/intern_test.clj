(ns nex.intern-test
  "Tests for intern statement to load external classes"
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]))

(deftest intern-parsing-test
  (testing "Parse intern statement with path and alias"
    (let [code "intern math/Factorial as Fact\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          intern-node (first (:interns ast))]
      (is (= :intern (:type intern-node)))
      (is (= "math" (:path intern-node)))
      (is (= "Factorial" (:class-name intern-node)))
      (is (= "Fact" (:alias intern-node)))))

  (testing "Parse intern statement without alias"
    (let [code "intern utils/Logger\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          intern-node (first (:interns ast))]
      (is (= :intern (:type intern-node)))
      (is (= "utils" (:path intern-node)))
      (is (= "Logger" (:class-name intern-node)))
      (is (nil? (:alias intern-node)))))

  (testing "Parse intern statement with deep path"
    (let [code "intern org/example/utils/Helper\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          intern-node (first (:interns ast))]
      (is (= :intern (:type intern-node)))
      (is (= "org/example/utils" (:path intern-node)))
      (is (= "Helper" (:class-name intern-node)))
      (is (nil? (:alias intern-node)))))

  (testing "Parse multiple intern statements"
    (let [code "intern math/Factorial\nintern utils/Logger as Log\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          interns (:interns ast)]
      (is (= 2 (count interns)))
      (is (= "Factorial" (:class-name (first interns))))
      (is (= "Logger" (:class-name (second interns))))
      (is (= "Log" (:alias (second interns)))))))
