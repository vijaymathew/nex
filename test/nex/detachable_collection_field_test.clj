(ns nex.detachable-collection-field-test
  "A detachable Array/Map/Set field (`?Array[T]`, `?Map[K, V]`, `?Set[T]`)
   left uninitialized by every constructor must default to nil, exactly like
   any other detachable reference type. Both backends used to instead
   construct an empty collection:

     class B feature b: ?Array[Integer] end
     let b := create B
     assert b.b = nil          -- used to fail: b.b was [], not nil

   Root cause was two separate places sharing the same bug shape — each
   dispatched on the field's *base type* (Array/Map/Set) without first
   checking whether the type was detachable:
   - interpreter.clj's `get-default-field-value` (also used for a method's
     `result` default, so a detachable `Array[Integer]` return type had the
     same bug — see nex.typechecker-test's auto-init tests for that side)
   - compiler/jvm/emit.clj's `emit-user-default-constructor!`, which always
     `NEW ArrayList/HashMap/LinkedHashSet`s a field of that jvm-type

   A non-detachable Array/Map/Set field is unaffected and still gets a real,
   mutable empty collection (see nex.exception-test-adjacent smoke coverage
   in class_smoke_test.clj for the analogous `result` case)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run-both
  "Printed output of CODE on the compiled backend, asserted to match the
   interpreter. Uses the same entry point as `nex <file>`."
  [code]
  (letfn [(go [interpret?]
            (let [f (java.io.File/createTempFile "detachable_collection_field" ".nex")]
              (try
                (spit f code)
                (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
                  (is (not (str/includes? out "falling back to the tree-walking interpreter"))
                      (str "compiled backend declined this program:\n" out))
                  (str/split-lines (str/trim-newline out)))
                (finally (.delete f)))))]
    (let [compiled (go false)]
      (is (= (go true) compiled) "compiled and interpreted output must agree")
      compiled)))

(deftest detachable-array-field-defaults-to-nil-test
  (testing "an uninitialized ?Array[T] field is nil, not an empty array"
    (is (= ["true"] (run-both "class B feature b: ?Array[Integer] end
let b := create B
print(b.b = nil)")))))

(deftest detachable-map-field-defaults-to-nil-test
  (testing "an uninitialized ?Map[K, V] field is nil, not an empty map"
    (is (= ["true"] (run-both "class B feature b: ?Map[String, Integer] end
let b := create B
print(b.b = nil)")))))

(deftest detachable-set-field-defaults-to-nil-test
  (testing "an uninitialized ?Set[T] field is nil, not an empty set"
    (is (= ["true"] (run-both "class B feature b: ?Set[Integer] end
let b := create B
print(b.b = nil)")))))

(deftest non-detachable-array-field-still-defaults-to-empty-test
  (testing "an attached Array[T] field is unaffected: still a real, mutable empty array"
    (is (= ["0" "[5]"] (run-both "class B feature b: Array[Integer] end
let b := create B
print(b.b.length)
b.b.add(5)
print(b.b)")))))

(deftest detachable-array-result-defaults-to-nil-test
  (testing "same bug, same fix, in `result`: a detachable ?Array[T] return type
            that's never assigned on a given path is nil, not an empty array
            (get-default-field-value backs both a field's and result's default)"
    (is (= ["nil"] (run-both "function maybe_list(x: Integer): ?Array[Integer]
do
  if x = 0 then
    result := [1, 2, 3]
  end
end
print(maybe_list(1))")))))
