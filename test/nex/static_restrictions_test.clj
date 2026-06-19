(ns nex.static-restrictions-test
  "Compile-time enforcement of the Definition's 'Syntactic Restrictions' (§2.9):
   duplicate parameters/fields, and the placement of `old` and `retry`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- check [code]
  (tc/type-check (p/ast code)))

(defn- rejected-with [code substr]
  (let [r (check code)]
    (and (not (:success r))
         (some #(str/includes? (str (tc/format-type-error %)) substr) (:errors r)))))

(defn- accepted? [code]
  (:success (check code)))

(deftest duplicate-parameters-rejected
  (testing "two parameters of one routine may not share a name"
    (is (rejected-with "class C feature f(x: Integer, x: Integer) do end end"
                       "Duplicate parameter 'x'"))
    (is (rejected-with
         "class C feature v: Integer create make(a: Integer, a: Integer) do v := a end end"
         "Duplicate parameter 'a'")))
  (testing "distinct parameters are accepted"
    (is (accepted? "class C feature f(x: Integer, y: Integer): Integer do result := x + y end end"))))

(deftest duplicate-fields-rejected
  (testing "two fields of one class may not share a name"
    (is (rejected-with "class C feature x: Integer y: String x: Integer end"
                       "Duplicate field 'x'")))
  (testing "distinct fields are accepted"
    (is (accepted? "class C feature a: Integer b: String end"))))

(deftest old-only-in-ensure
  (testing "`old` outside an ensure clause is a compile-time error"
    (is (rejected-with "class C feature x: Integer f() do let y := old x print(y) end end"
                       "'old' may appear only in an ensure"))
    (is (rejected-with "class C feature x: Integer f() require r: old x = 1 do end end"
                       "'old' may appear only in an ensure")))
  (testing "`old` on a field inside ensure is accepted"
    (is (accepted?
         "class C feature x: Integer set(v: Integer) do x := v ensure k: x = old x + 0 end end"))))

(deftest old-not-applied-to-parameter
  (testing "`old` may not be applied to a parameter"
    (is (rejected-with
         "class C feature f(p: Integer): Integer do result := p ensure k: result = old p end end"
         "may not be applied to the parameter 'p'"))))

(deftest retry-only-in-rescue
  (testing "`retry` outside a rescue block is a compile-time error"
    (is (rejected-with "class C feature f() do retry end end"
                       "'retry' may appear only inside a rescue block")))
  (testing "`retry` inside a rescue block is accepted"
    (is (accepted? "class C feature f() do print(1) rescue retry end end"))
    (is (accepted?
         "class C feature f() do do print(1) rescue retry end end end"))))
