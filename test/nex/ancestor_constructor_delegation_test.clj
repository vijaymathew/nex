(ns nex.ancestor-constructor-delegation-test
  "A constructor may delegate to any proper ancestor that declares the
   constructor itself (Definition 4.4), not only to an immediate parent:
   `A.make(...)` from `C inherit B inherit A`. The compiled backend used to
   refuse anything past an immediate parent; it now reaches the ancestor's
   part of the object through the same carrier path an `Ancestor.m(...)` call
   takes (Definition 4.9), and passes the ancestor's type arguments as the
   inherit chain binds them."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "ancestor_ctor" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})
            interpreted (run {:interpret? true})]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(def ^:private a-decl
  "class A
create
  make(x: Integer) do
    a := x
  end
feature
  a: Integer
end
")

(deftest delegates-to-a-grandparent-test
  (testing "C's constructor runs A's on A's part of the object, past a B with
            no constructor of its own"
    (is (= ["7"]
           (both (str a-decl "class B
inherit A
end
class C
inherit B
create
  make do
    A.make(7)
  end
end
print((create C.make).a)"))))))

(deftest delegates-after-an-intermediate-constructor-test
  (testing "delegating to B, then to A again, re-runs A's on the same part"
    (is (= ["7"]
           (both (str a-decl "class B
inherit A
create
  make do
    A.make(1)
  end
end
class C
inherit B
create
  make do
    B.make
    A.make(7)
  end
end
print((create C.make).a)"))))))

(deftest delegates-through-a-renaming-generic-chain-test
  (testing "the ancestor's type arguments follow the inherit chain, reordered
            and partly fixed on the way"
    (is (= ["\"k\"" "6"]
           (both "class A [K, V]
create
  make(k: K, v: V) do
    key := k
    value := v
  end
feature
  key: K
  value: V
end
class B [X, Y]
inherit A[Y, X]
end
class C [Z]
inherit B[Z, String]
create
  make(z: Z) do
    A.make(\"k\", z)
  end
end
let c := create C[Integer].make(5)
print(c.key)
print(c.value + 1)")))))

(deftest delegates-through-a-second-parent-test
  (testing "an ancestor reached only through a later parent in `inherit` order"
    (is (= ["\"r2\""]
           (both "class Named
create
  make(n: String) do
    name := n
  end
feature
  name: String
end
class Walker
end
class Labelled
inherit Named
end
class Robot
inherit Walker, Labelled
create
  make do
    Named.make(\"r2\")
  end
end
print((create Robot.make).name)")))))

(deftest skipping-an-intermediate-that-attaches-fields-is-rejected-test
  (testing "when B has fields to attach, C must still go through B's constructor"
    (let [result (tc/type-check (p/ast (str a-decl "class Thing
end
class B
inherit A
create
  make do
    A.make(1)
    t := create Thing
  end
feature
  t: Thing
end
class C
inherit B
create
  make do
    A.make(7)
  end
end
print((create C.make).a)")))]
      (is (not (:success result)))
      (is (re-find #"must call a constructor of B" (pr-str (:errors result)))))))
