(ns nex.raw-generic-hint-test
  "A generic class created without type arguments (`create Leaf.make` of
   `union Tree[A]`) does not pick them up from context, so it fails to
   conform to `Tree[Integer]` -- and, passed to a generic routine, its own
   parameter name clashes with the binding the other arguments give
   (\"Conflicting inferred types for generic parameter A: Integer and A\").
   Neither message said the fix: write the type arguments. These check that
   the error now names them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- type-error-message [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (str/join "\n" (map tc/format-type-error (:errors result))))))

(def ^:private tree-decl
  "union Tree[A]
  Leaf
  Node(value: A, left: Tree[A], right: Tree[A])
end

function node[A](value: A, left: Tree[A], right: Tree[A]): Node[A]
do
  result := create Node.make(value, left, right)
end
")

(deftest conflicting-inference-names-the-missing-type-arguments
  (testing "a bare variant held in a variable"
    (let [msg (type-error-message
               (str tree-decl
                    "let leaf := create Leaf.make
let t: Tree[Integer] := node(1, leaf, leaf)
"))]
      (is (str/includes? msg "Conflicting inferred types for generic parameter A: Integer and A."))
      (is (str/includes? msg "`Leaf` is generic, and this value was created without type arguments; write them where it is created, as `Leaf[Integer]`."))))
  (testing "a bare create passed directly to a generic function"
    (is (str/includes? (type-error-message
                        (str tree-decl "let t := node(1, create Leaf.make, create Leaf.make)\n"))
                       "`Leaf` is generic; write its type arguments: `create Leaf[Integer].make`.")))
  (testing "a bare create passed to a generic constructor"
    (is (str/includes? (type-error-message
                        (str tree-decl "let t := create Node.make(1, create Leaf.make, create Leaf.make)\n"))
                       "`create Leaf[Integer].make`"))))

(deftest assignment-and-argument-mismatch-name-the-missing-type-arguments
  (testing "assigning a bare create to a parameterized ancestor type"
    (is (str/includes? (type-error-message
                        (str tree-decl "let t: Tree[Integer] := create Leaf.make\n"))
                       "Cannot assign Leaf to variable 't' of type Tree[Integer]. `Leaf` is generic; write its type arguments: `create Leaf[Integer].make`.")))
  (testing "passing a bare create as an argument"
    (is (str/includes? (type-error-message
                        (str tree-decl
                             "function size(t: Tree[Integer]): Integer do result := 0 end
let n := size(create Leaf.make)
"))
                       "should be Tree[Integer], but got Leaf. `Leaf` is generic; write its type arguments: `create Leaf[Integer].make`.")))
  (testing "type arguments are mapped through a reordering inherit clause"
    (is (str/includes? (type-error-message
                        "class P[X, Y]
feature
  n: Integer
end
class C[Y, X] inherit P[X, Y]
create
  make do end
end
let b: P[Integer, String] := create C.make
")
                       "`create C[String, Integer].make`"))))

(deftest unrelated-mismatches-get-no-generic-hint
  (testing "an explicitly instantiated variant of the wrong type"
    (let [msg (type-error-message
               (str tree-decl "let t: Tree[Integer] := create Leaf[String].make\n"))]
      (is (str/includes? msg "Cannot assign Leaf[String] to variable 't' of type Tree[Integer]."))
      (is (not (str/includes? msg "is generic")))))
  (testing "a genuine conflict between concrete arguments"
    (let [msg (type-error-message
               (str tree-decl
                    "let s := create Node[String].make(\"x\", create Leaf[String].make, create Leaf[String].make)
let t := node(1, s, s)
"))]
      (is (str/includes? msg "Conflicting inferred types for generic parameter A"))
      (is (not (str/includes? msg "is generic")))))
  (testing "explicit type arguments type-check"
    (is (nil? (type-error-message
               (str tree-decl
                    "let leaf := create Leaf[Integer].make
let t: Tree[Integer] := node(1, leaf, leaf)
"))))))
