(ns nex.free-function-value-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned."
  [code]
  (let [f (java.io.File/createTempFile "free_fn_value" ".nex")]
    (try
      (spit f code)
      (let [compiled (clojure.string/split-lines
                      (clojure.string/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
            interpreted (clojure.string/split-lines
                         (clojure.string/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest free-function-passed-by-name-typechecks-test
  (testing "a free function's bare name is accepted where its matching Function(...) type is expected"
    (is (= ["[]"]
           (both "class Box
feature
  v: Integer
create
  make(x: Integer) do v := x end
end
class Boxes
feature
  filter_boxes(predicate: Function(b: Box): Boolean): Array[Box]
  do
    result := []
  end
end
function is_big(b: Box): Boolean
do
  result := b.v > 10
end
let bs := create Boxes
let out := bs.filter_boxes(is_big)
print(out)")))))

(deftest free-function-passed-by-name-mismatched-signature-still-rejected-test
  (testing "a free function whose signature does not match the expected Function(...) type is still a type error"
    (let [code "class Box
feature
  v: Integer
create
  make(x: Integer) do v := x end
end
class Boxes
feature
  filter_boxes(predicate: Function(b: Box): Boolean): Array[Box]
  do
    result := []
  end
end
function wrong_sig(b: Box): String
do
  result := \"no\"
end
let bs := create Boxes
let out := bs.filter_boxes(wrong_sig)
print(out)"
          f (java.io.File/createTempFile "free_fn_value_bad" ".nex")]
      (try
        (spit f code)
        (is (thrown-with-msg? Exception #"Type checking failed"
                              (with-out-str (e/eval-file (.getPath f) {}))))
        (finally (.delete f))))))

(deftest free-function-passed-by-name-actually-invoked-test
  (testing "a free function passed by name is a real callable, not a null value that crashes on invocation"
    (is (= ["[#<Box object>]"]
           (both "class Box
feature
  v: Integer
create
  make(x: Integer) do v := x end
end
class Boxes
feature
  filter_boxes(predicate: Function(b: Box): Boolean): Array[Box]
  do
    result := []
    let b1 := create Box.make(5)
    let b2 := create Box.make(20)
    if predicate(b1) then result.add(b1) end
    if predicate(b2) then result.add(b2) end
  end
end
function is_big(b: Box): Boolean
do
  result := b.v > 10
end
let bs := create Boxes
let out := bs.filter_boxes(is_big)
print(out)")))))

(deftest function-param-shadowing-same-arity-top-level-function-test
  (testing "a Function-typed parameter is resolved as itself, not as an unrelated
            top-level free function that happens to share its name and arity"
    (is (= ["42"]
           (both "function f(z: Integer): Array[Integer]
do
end
function apply_it[T, U](x: T, f: Function(y: T): U): U do
  result := f(x)
end
let r := apply_it(21, fn (y: Integer): Integer do result := y * 2 end)
print(r)")))))
