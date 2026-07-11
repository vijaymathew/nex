(ns nex.import-test
  "Tests for import statement to use Java and JavaScript classes"
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]))

(deftest import-parsing-test
  (testing "Parse Java import statement"
    (let [code "import java.util.Scanner\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          import-node (first (:imports ast))]
      (is (= :import (:type import-node)))
      (is (= "java.util.Scanner" (:qualified-name import-node)))
      (is (nil? (:source import-node)))))

  (testing "Parse JavaScript import statement with double quotes"
    (let [code "import Math from \"./utils.js\"\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          import-node (first (:imports ast))]
      (is (= :import (:type import-node)))
      (is (= "Math" (:qualified-name import-node)))
      (is (= "\"./utils.js\"" (:source import-node)))))

  (testing "Parse JavaScript import statement with single quotes"
    (let [code "import React from './react.js'\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          import-node (first (:imports ast))]
      (is (= :import (:type import-node)))
      (is (= "React" (:qualified-name import-node)))
      (is (= "'./react.js'" (:source import-node)))))

  (testing "Parse multiple import statements"
    (let [code "import java.util.ArrayList\nimport java.util.HashMap\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          imports (:imports ast)]
      (is (= 2 (count imports)))
      (is (= "java.util.ArrayList" (:qualified-name (first imports))))
      (is (= "java.util.HashMap" (:qualified-name (second imports))))))

  (testing "Parse mixed imports"
    (let [code "import java.util.Scanner\nimport Lodash from './lodash.js'\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)
          imports (:imports ast)]
      (is (= 2 (count imports)))
      (is (= "java.util.Scanner" (:qualified-name (first imports))))
      (is (nil? (:source (first imports))))
      (is (= "Lodash" (:qualified-name (second imports))))
      (is (= "'./lodash.js'" (:source (second imports)))))))

(deftest import-with-intern-test
  (testing "Import and intern can coexist"
    (let [code "import java.util.Scanner\nintern math/Calculator\n\nclass Main feature test() do print(\"x\") end end"
          ast (p/ast code)]
      (is (= 1 (count (:imports ast))))
      (is (= 1 (count (:interns ast))))
      (is (= 1 (count (:classes ast))))
      (is (= "java.util.Scanner" (:qualified-name (first (:imports ast)))))
      (is (= "Calculator" (:class-name (first (:interns ast))))))))
