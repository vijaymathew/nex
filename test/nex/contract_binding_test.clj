(ns nex.contract-binding-test
  "A `convert`/object-test binding in a contract clause (require, ensure, or
   a loop or class invariant) is local to that clause, as Eiffel scopes an
   object-test local: the rest of the clause sees it, the body and other
   clauses do not. Its name must be new -- not a parameter, local or field
   in scope, and not bound twice in the clause.

   The compiled backend used to fail on any such binding (\"convert binding
   must exist before lowering expression\"): only statements had their
   bindings allocated before lowering, and a contract clause is not one.
   Programs are run through a real class load (loader/define-class!), not
   nex.eval, whose interpreter fallback would hide a VerifyError."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.file :as file]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- type-error-message [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (str/join "\n" (map tc/format-type-error (:errors result))))))

(defn- run [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    @(:output ctx)))

(defn- run-compiled [code]
  (let [{:keys [main-class classes]} (file/compile-ast "contract_binding_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(defn- violation [f code]
  (try (f code) nil
       (catch Throwable t
         (let [msg (str/join " " (keep ex-message (take-while some? (iterate ex-cause t))))]
           (some #(when (str/includes? msg %) %)
                 ["Precondition violation" "Postcondition violation"
                  "Loop invariant violation" "Class invariant violation"
                  "Invariant violation"])))))

(def ^:private number-decl
  "union Number
  Zero
  Succ(pred: Number)
end
")

(def ^:private pred-program
  (str number-decl
       "let zero := create Zero.make

function pred(n: Number): Number
require
  zero_has_no_pred: not(convert n to z: Zero)
do
  result := zero
  if convert n to s: Succ then
    result := s.pred
  end
ensure
  pred_of_succ: convert n to s2: Succ and result = s2.pred
end

let two := create Succ.make(create Succ.make(zero))
print(pred(pred(two)) = zero)
"))

(deftest convert-in-require-and-ensure-runs
  (is (nil? (type-error-message pred-program)))
  (is (= ["true"] (run pred-program)))
  (is (= ["true"] (run-compiled pred-program)))
  (testing "a failing precondition with a binding still reports the violation"
    (let [bad (str pred-program "print(pred(zero))\n")]
      (is (nil? (type-error-message bad)))
      (is (= "Precondition violation" (violation run-compiled bad))))))

(def ^:private constructor-and-invariant-program
  (str number-decl
       "class Box
feature
  item: Number
create
  make(x: Number)
  require
    not_zero: not(convert x to z: Zero)
  do
    item := x
  ensure
    kept: convert item to s: Succ
  end
invariant
  is_succ: convert item to n: Succ
end

function depth(n: Number): Integer
do
  from
    let cur: Number := n
    result := 0
  invariant
    is_number: convert cur to c: Number
  until
    convert cur to z: Zero
  do
    if convert cur to s: Succ then cur := s.pred end
    result := result + 1
  end
end

let b := create Box.make(create Succ.make(create Zero.make))
print(depth(b.item))
"))

(deftest convert-in-constructor-loop-and-class-contracts-runs
  (is (nil? (type-error-message constructor-and-invariant-program)))
  (is (= ["1"] (run constructor-and-invariant-program)))
  (is (= ["1"] (run-compiled constructor-and-invariant-program)))
  (testing "a failing constructor precondition with a binding"
    (is (= "Precondition violation"
           (violation run-compiled
                      (str constructor-and-invariant-program
                           "let b2 := create Box.make(create Zero.make)\n"))))))

(deftest contract-binding-is-local-to-its-clause
  (testing "the body cannot see a require binding"
    (is (str/includes? (type-error-message
                        (str number-decl
                             "function f(n: Number): ?Succ
require
  ok: convert n to s: Succ
do
  result := s
end
"))
                       "Undefined variable: s")))
  (testing "a later clause cannot see an earlier one's binding"
    (is (str/includes? (type-error-message
                        (str number-decl
                             "function f(n: Number): Boolean
require
  ok: convert n to s: Succ
do
  result := true
ensure
  later: s /= nil
end
"))
                       "Undefined variable: s")))
  (testing "the body may declare a local of the same name"
    (let [code (str number-decl
                    "function f(n: Number): Integer
require
  ok: convert n to s: Succ
do
  let s := 1
  result := s
end
print(f(create Succ.make(create Zero.make)))
")]
      (is (nil? (type-error-message code)))
      (is (= ["1"] (run-compiled code)))))
  (testing "an ensure may reuse a name the body bound with `if convert`"
    (let [code (str number-decl
                    "function f(n: Number): Boolean
do
  if convert n to s: Succ then result := true end
ensure
  ok: convert n to s: Succ
end
print(f(create Succ.make(create Zero.make)))
")]
      (is (nil? (type-error-message code)))
      (is (= ["true"] (run-compiled code))))))

(deftest contract-binding-name-must-be-new
  (testing "a parameter"
    (is (str/includes? (type-error-message
                        (str number-decl
                             "function f(n: Number): Boolean
require
  ok: convert n to n: Succ
do
  result := true
end
"))
                       "`n` is already defined here, so a contract cannot bind it.")))
  (testing "a field"
    (is (str/includes? (type-error-message
                        (str number-decl
                             "class Box
feature
  item: Number
create
  make(x: Number)
  require
    ok: convert x to item: Succ
  do
    item := x
  end
end
"))
                       "`item` is already defined here, so a contract cannot bind it.")))
  (testing "a body local, in an ensure"
    (is (str/includes? (type-error-message
                        (str number-decl
                             "function f(n: Number): Boolean
do
  let s := 1
  result := true
ensure
  ok: convert n to s: Succ
end
"))
                       "`s` is already defined here, so a contract cannot bind it.")))
  (testing "twice in one clause"
    (is (str/includes? (type-error-message
                        (str number-decl
                             "function f(n: Number): Boolean
require
  ok: convert n to s: Succ and convert s.pred to s: Succ
do
  result := true
end
"))
                       "`s` is bound more than once in this clause.")))
  (testing "the same name in separate clauses is fine"
    (is (nil? (type-error-message
               (str number-decl
                    "function f(n: Number): Boolean
require
  a: convert n to s: Succ
  b: not(convert n to s: Zero)
do
  result := true
end
"))))))
