(ns nex.static-restrictions-test
  "Compile-time enforcement of the Definition's 'Syntactic Restrictions' (§2.9):
   duplicate parameters/fields, and the placement of `old` and `retry`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- check [code]
  (tc/type-check (p/ast code)))

(defn- check-strict [code]
  (tc/type-check (p/ast code) {:strict-undefined-targets? true}))

(defn- strict-rejected-with [code substr]
  (let [r (check-strict code)]
    (and (not (:success r))
         (some #(str/includes? (str (tc/format-type-error %)) substr) (:errors r)))))

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

(deftest duplicate-methods-rejected
  (testing "two routines of one class may not share both a name and an arity"
    (is (rejected-with
         (str "class A feature "
              "f(a: Integer): Integer do result := 1 + a end "
              "f(s: String): String do result := s end end")
         "Duplicate routine 'f' taking 1 argument")))
  (testing "same-name routines that differ in arity are accepted (arity dispatch)"
    (is (accepted?
         (str "class A feature "
              "f(a: Integer): Integer do result := 1 + a end "
              "f(a: Integer, b: Integer): Integer do result := a + b end end"))))
  (testing "two constructors of one class may not share both a name and an arity"
    (is (rejected-with
         (str "class C feature v: Integer create "
              "make(a: Integer) do v := a end "
              "make(b: Integer) do v := b end end")
         "Duplicate constructor 'make' taking 1 argument"))))

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

(deftest duplicate-locals-in-same-block-rejected
  (testing "two `let` declarations of the same name in one block are a compile error"
    (is (rejected-with "class C feature f() do let x := 1 let x := 2 print(x) end end"
                       "Duplicate local variable 'x'"))
    (is (rejected-with "class C feature f() do let x := 1 print(x) let x := 2 print(x) end end"
                       "Duplicate local variable 'x'")))
  (testing "a nested block may shadow an outer binding"
    (is (accepted?
         "class C feature f() do let x := 1 if true then let x := 2 print(x) end print(x) end end")))
  (testing "sibling blocks may reuse a name"
    (is (accepted?
         "class C feature f() do if true then let x := 1 print(x) else let x := 2 print(x) end end end")))
  (testing "different routines may reuse a name"
    (is (accepted?
         "class C feature f() do let x := 1 print(x) end g() do let x := 2 print(x) end end"))))

(deftest undefined-member-access-target-rejected-when-strict
  (testing "an undefined identifier used as a member-access/call target is rejected"
    (is (strict-rejected-with "class C feature f() do print(zzz.size()) end end"
                              "Undefined variable: zzz"))
    (is (strict-rejected-with "class C feature f() do print(zzz.name) end end"
                              "Undefined variable: zzz"))
    (is (strict-rejected-with "function f() do print(zzz.size()) end\nf()"
                              "Undefined variable: zzz")))
  (testing "valid targets are still accepted under strict checking"
    (is (:success (check-strict "class C feature s: String f() do print(s.length()) end end")))
    (is (:success (check-strict "class C feature name(): String do result := \"x\" end f() do print(name.length()) end end")))
    (is (:success (check-strict "class A create make() do end end\nclass B inherit A create make() do super.make() end end"))))
  (testing "the check is off by default (interactive/REPL inputs stay lenient)"
    (is (:success (check "class C feature f() do print(zzz.size()) end end")))))

(deftest retry-only-in-rescue
  (testing "`retry` outside a rescue block is a compile-time error"
    (is (rejected-with "class C feature f() do retry end end"
                       "'retry' may appear only inside a rescue block")))
  (testing "`retry` inside a rescue block is accepted"
    (is (accepted? "class C feature f() do print(1) rescue retry end end"))
    (is (accepted?
         "class C feature f() do do print(1) rescue retry end end end"))))

(deftest retry-at-top-level-rejected
  (testing "`retry` in top-level code outside a rescue block is a compile-time
            error, not a backend failure at run time"
    (is (rejected-with "retry"
                       "'retry' may appear only inside a rescue block; found it elsewhere in top-level code"))
    (is (rejected-with "if true then\n  retry\nend"
                       "found it elsewhere in top-level code")))
  (testing "a top-level `do ... rescue retry end` is accepted"
    (is (accepted? "let n := 0\ndo\n  n := n + 1\n  if n < 3 then raise \"again\" end\nrescue\n  retry\nend"))))

(deftest routine-messages-name-what-the-program-wrote
  (testing "a free function is named as itself, not as its wrapper's `call0`"
    (is (rejected-with "function f()\ndo\n  result := 3\nend"
                       "Function 'f' uses Result but does not declare a return type. Use: f(...)"))
    (is (rejected-with "function f()\ndo\n  retry\nend"
                       "found it elsewhere in function 'f'"))
    (is (rejected-with "function k(a, a: Integer): Integer\ndo\n  result := a\nend"
                       "Duplicate parameter 'a' in function 'k'"))
    (is (rejected-with "function q(x) do\n  print(x)\nend"
                       "Parameter 'x' of function 'q' must declare a type"))
    (is (rejected-with "class T\nend\nfunction r(b: Boolean): T do\n  if b then result := create T end\nend"
                       "Function 'r' declares return type T but does not definitely assign result")))
  (testing "a method is named as a method, an anonymous function as one"
    (is (rejected-with "class C\nfeature\n  g()\n  do\n    result := 3\n  end\nend"
                       "Method 'g' uses Result but does not declare a return type"))
    (is (rejected-with "let h := fn(x: Integer) do\n  result := x\nend"
                       "Anonymous function uses Result but does not declare a return type. Use: fn(...)"))))

(deftest integer-literal-out-of-range-rejected
  (testing "an Integer literal outside the 64-bit range is reported as such,
            not with the host's number-parsing message"
    (doseq [lit ["9223372036854775808" "0xFFFF_FFFF_FFFF_FFFF"]]
      (let [msg (try (p/ast (str "print(" lit ")")) nil
                     (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (= (str "Integer literal out of range -9223372036854775808..9223372036854775807: " lit) msg)))))
  (testing "the largest Integer is accepted"
    (is (accepted? "print(9223372036854775807)"))))
