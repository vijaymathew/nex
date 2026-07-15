(ns nex.object-value-equality-test
  "Object `=` is structural by default and ordering dispatches through a
   Comparable object's `compare`, identically on the interpreter and the compiled
   JVM backend. Regression tests for two backend divergences:

     1. JVM `=` on distinct objects degraded to reference identity, because the
        structural walk only understood interpreter-shaped objects — compiled
        instances are generated JVM classes. This broke `old`-style postconditions
        such as `balance = old balance - amount` (both sides fresh objects).
     2. Interpreter `<`/`<=`/`>`/`>=` on Comparable objects never called `compare`;
        they fell back to ordering the object's *printed form*, giving inverted
        results."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.repl :as repl]))

(def money-and-account
  "class Money inherit Comparable
  feature
    value: Real
  create
    with_value(v: Real) do value := v end
  feature
    compare(m: Money): Integer do
      if value > m.value then result := 1
      elseif value < m.value then result := -1
      else result := 0 end
    end
    minus(m: Money): Money alias \"-\" do
      result := create Money.with_value(value - m.value)
    end
    is_negative: Boolean do result := value < 0.0 end
  end

class Account
  create
    open(initial: Money) do balance := initial end
  feature
    balance: Money
    withdraw(amount: Money)
      require
        sufficient_funds: amount <= balance
      do
        balance := balance - amount
      ensure
        balance_reduced: balance = old balance - amount
        never_negative: not balance.is_negative()
      end
  end
")

;; ─── Interpreter ─────────────────────────────────────────────────────────────

(defn- run-interpreted
  "Run a whole program on the interpreter, returning printed output lines."
  [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    @(:output ctx)))

(deftest interpreter-object-equality-is-structural
  (testing "two distinct objects with equal fields are `=`, distinct fields `/=`"
    (is (= ["true" "false" "true"]
           (run-interpreted (str money-and-account
                                 "let a := create Money.with_value(10.0)
let b := create Money.with_value(10.0)
let c := create Money.with_value(9.0)
print(a = b)
print(a = c)
print(a /= c)"))))))

(deftest interpreter-comparable-ordering-uses-compare
  (testing "ordering operators dispatch through `compare`, not printed order"
    ;; "2.0" vs "12.0" orders the opposite way lexically — the old bug.
    (is (= ["true" "true" "false" "false"]
           (run-interpreted (str money-and-account
                                 "let a := create Money.with_value(2.0)
let b := create Money.with_value(12.0)
print(a <= b)
print(a < b)
print(a >= b)
print(a > b)"))))))

(deftest interpreter-old-object-postcondition-holds
  (testing "`balance = old balance - amount` succeeds for object-valued fields"
    (is (= ["10.0"]
           (run-interpreted (str money-and-account
                                 "let acc := create Account.open(create Money.with_value(12.0))
acc.withdraw(create Money.with_value(2.0))
print(acc.balance.value)"))))))

;; ─── Compiled JVM backend ────────────────────────────────────────────────────

(defmacro with-compiled-repl [ctx-sym & body]
  `(binding [repl/*type-checking-enabled* (atom true)
             repl/*repl-var-types* (atom {})
             repl/*repl-backend* (atom :compiled)
             repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
     (let [~ctx-sym (repl/init-repl-context)]
       ~@body)))

(defn- feed
  "Evaluate each program fragment, then return the output of the final expression."
  [ctx setup-fragments expr]
  (doseq [frag setup-fragments]
    (with-out-str (repl/eval-code ctx frag)))
  (let [out (with-out-str (repl/eval-code ctx expr))]
    (is (not (str/includes? out "Error:")) out)
    out))

(def ^:private compiled-setup
  ["class Money inherit Comparable
  feature
    value: Real
  create
    with_value(v: Real) do value := v end
  feature
    compare(m: Money): Integer do
      if value > m.value then result := 1
      elseif value < m.value then result := -1
      else result := 0 end
    end
    minus(m: Money): Money alias \"-\" do
      result := create Money.with_value(value - m.value)
    end
    is_negative: Boolean do result := value < 0.0 end
  end"
   "class Account
  create
    open(initial: Money) do balance := initial end
  feature
    balance: Money
    withdraw(amount: Money)
      require
        sufficient_funds: amount <= balance
      do
        balance := balance - amount
      ensure
        balance_reduced: balance = old balance - amount
        never_negative: not balance.is_negative()
      end
  end"])

(deftest compiled-object-equality-is-structural
  (testing "compiled `=` compares fields, not JVM reference identity"
    (with-compiled-repl ctx
      (let [same (feed ctx compiled-setup
                       "print(create Money.with_value(10.0) = create Money.with_value(10.0))")
            diff (feed ctx compiled-setup
                       "print(create Money.with_value(10.0) = create Money.with_value(9.0))")]
        (is (str/includes? same "true") same)
        (is (str/includes? diff "false") diff)))))

(deftest compiled-old-object-postcondition-holds
  (testing "`balance = old balance - amount` holds on the compiled backend"
    (with-compiled-repl ctx
      (feed ctx compiled-setup
            "let acc := create Account.open(create Money.with_value(12.0))")
      (let [out (feed ctx [] "acc.withdraw(create Money.with_value(2.0))")]
        (is (not (str/includes? out "violation")) out))
      (let [out (feed ctx [] "print(acc.balance.value)")]
        (is (str/includes? out "10.0") out)))))
