(ns nex.fmt-test
  "Tests for Nex code formatter"
  (:require [clojure.test :refer [deftest is testing]]
            [nex.fmt :as fmt]
            [nex.parser :as p]))

(deftest format-simple-class-test
  (testing "Format a simple class"
    (let [unformatted "class Test
feature
x: Integer
y: Integer
end"
          formatted (fmt/format-code unformatted)
          expected "class Test
feature
  x: Integer
  y: Integer
end"]
      (is (= expected formatted)))))

(deftest format-with-method-test
  (testing "Format class with method"
    (let [unformatted "class Test
feature
demo() do
print(42)
end
end"
          formatted (fmt/format-code unformatted)
          expected "class Test
feature
  demo() do
      print(42)
  end
end"]
      (is (= expected formatted)))))

(deftest format-selective-visibility-test
  (testing "Format selective visibility"
    (let [unformatted "class Test
-> Friend, Helper feature
show() do
print(1)
end
end"
          formatted (fmt/format-code unformatted)
          expected "class Test
-> Friend, Helper feature
  show() do
      print(1)
  end
end"]
      (is (= expected formatted)))))

(deftest format-with-constructor-test
  (testing "Format class with constructor"
    (let [unformatted "class Point
feature
x: Integer
create
make(px: Integer) do
let x := px
end
end"
          formatted (fmt/format-code unformatted)
          expected "class Point
feature
  x: Integer

create
  make(px: Integer) do
      let x := px
  end
end"]
      (is (= expected formatted)))))

(deftest format-binary-expression-test
  (testing "Format binary expressions correctly"
    (let [unformatted "class Test
feature
demo() do
let x := 1 + 2
end
end"
          formatted (fmt/format-code unformatted)
          expected "class Test
feature
  demo() do
      let x := 1 + 2
  end
end"]
      (is (= expected formatted)))))

(deftest formatted-code-is-parseable-test
  (testing "Formatted code can be parsed"
    (let [code "class Test feature x: Integer end"
          formatted (fmt/format-code code)
          ast (p/ast formatted)]
      (is (some? ast))
      (is (= :program (:type ast)))
      (is (= 1 (count (:classes ast)))))))

(deftest idempotent-formatting-test
  (testing "Formatting is idempotent"
    (let [code "class Test
feature
  x: Integer
  y: Integer
end"
          formatted1 (fmt/format-code code)
          formatted2 (fmt/format-code formatted1)]
      (is (= formatted1 formatted2)))))

(deftest format-multiple-sections-test
  (testing "Format multiple feature sections"
    (let [unformatted "class Test
feature
x: Integer
private feature
y: Integer
end"
          formatted (fmt/format-code unformatted)
          expected "class Test
feature
  x: Integer

private feature
  y: Integer
end"]
      (is (= expected formatted)))))
