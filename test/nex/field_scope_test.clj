(ns nex.field-scope-test
  "Class fields are not global variables.

   `collect-class-info` (the first pass) binds each field name as a *variable*
   so that a constant's initializer can name a sibling constant (`B = A + 1`).
   It used to bind them into the caller's env — the program's global scope —
   which made every field name in the program a readable and assignable global
   initialized to nil:

       class Account
         feature balance: Integer
         create make(v: Integer) do balance := v end
       end
       print(balance)          -- typechecked, printed nil

   Private fields leaked the same way. That is a void-safety hole in the exact
   place the language makes its strongest promise, and it silently absorbed
   typos: any misspelling that collided with some field name anywhere in the
   program became nil instead of a compile error. It is also what hid an unbound
   `match` binding — `when Ok(value: 10) then print(value)` printed nil rather
   than being rejected, because `value` is a field of `Ok`.

   The scope is now local to the pass, seeded with inherited *constants* only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- errors [code]
  (let [r (tc/type-check (p/ast code))]
    (map #(if (map? %) (:message %) (str %)) (:errors r))))

(defn- accepted? [code]
  (:success (tc/type-check (p/ast code))))

(defn- run-both
  "Printed output of CODE on the compiled backend, asserted to match the
   interpreter. Uses the same entry point as `nex <file>`."
  [code]
  (letfn [(go [interpret?]
            (let [f (java.io.File/createTempFile "field_scope" ".nex")]
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

(def ^:private account
  "class Account
  feature balance: Integer
  private feature pin: Integer
  create make(v: Integer) do
    balance := v
    pin := 0
  end
end
")

(deftest field-name-is-not-a-global
  (testing "a bare field name does not resolve outside its class"
    (is (some #(re-find #"Undefined variable: balance" %)
              (errors (str account "print(balance)")))
        "a public field name must not be a global")
    (is (some #(re-find #"Undefined variable: pin" %)
              (errors (str account "print(pin)")))
        "a private field name must not be a global")))

(deftest field-name-is-not-an-assignable-global
  (testing "a bare field name cannot be assigned to outside its class"
    ;; This was the worst of it: not merely readable, but writable.
    (is (some #(re-find #"Undefined variable: balance" %)
              (errors (str account "balance := 5"))))))

(deftest field-name-does-not-leak-into-a-function
  (testing "an unrelated function body does not see class field names"
    (is (not (accepted? (str account
                             "function f(): Integer do result := balance end"))))))

(deftest field-access-inside-the-class-still-works
  (testing "a class still reads its own fields by bare name"
    ;; `bind-visible-class-fields!` in check-class is the real mechanism, and it
    ;; is the one that honours visibility and generic substitution.
    (is (= ["7"] (run-both "class P
  feature x: Integer
  feature get(): Integer do result := x end
  create make(v: Integer) do x := v end
end
print(create P.make(7).get())")))))

(deftest inherited-field-access-still-works
  (testing "a subclass reads an inherited public field by bare name"
    (is (= ["3"] (run-both "class B
  feature v: Integer
  create make(n: Integer) do v := n end
end
class D
  inherit B
  create make(n: Integer) do B.make(n) end
  feature show(): Integer do result := v end
end
print(create D.make(3).show())")))))

(deftest constant-may-name-a-sibling-constant
  (testing "a constant's initializer resolves an earlier constant of its class"
    ;; The one legitimate reason the pass needs any scope at all.
    (is (= ["15"] (run-both "class K
  feature
    A = 10
    B = A + 5
end
print(K.B)")))))

(deftest constant-may-name-an-inherited-constant
  (testing "a constant's initializer resolves a constant from a parent"
    ;; Read from the *top level*, so the initializer's type must be inferred in
    ;; the declaring class rather than wherever the constant is read.
    (is (= ["15" "16"] (run-both "class Base
  feature
    A = 10
    B = A + 5
end
class Derived inherit Base
  feature
    C = B + 1
end
print(Base.B)
print(Derived.C)")))))

(deftest constant-name-is-still-not-a-global
  (testing "a class constant is reachable as Class.NAME, not as a bare name"
    (is (= ["10"] (run-both "class K
  feature
    A = 10
end
print(K.A)")))
    (is (not (accepted? "class K
  feature
    A = 10
end
print(A)")))))

(deftest old-of-a-field-compiles
  (testing "`old <field>` in a postcondition infers without a global field scope"
    ;; `old e` has e's type; lowering had no case for it and fell back to an
    ;; inference env with no class context, resolving the field only via the
    ;; leak. examples/sample.nex is the program that caught this.
    (is (= ["12"] (run-both "class Acct
  feature balance: Integer
  create make(v: Integer) do balance := v end
  feature
    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        balance := balance + amount
      ensure
        increased: balance = old balance + amount
      end
end
let a := create Acct.make(2)
a.deposit(10)
print(a.balance)")))))
