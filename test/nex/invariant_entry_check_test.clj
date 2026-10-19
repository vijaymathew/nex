(ns nex.invariant-entry-check-test
  "A qualified call checks the class invariant on entry as well as on exit,
   as Eiffel's run-time checking does. The last check on exit left the
   invariant true, so an entry failure means the object changed in between:
   a callback into it mid-update (furtive access) or a change through a
   shared reference (reference leak). Such a violation carries :on-entry-of."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(def ^:private backends [{} {:interpret? true}])

(defn- run
  "[printed-lines error] for CODE run with OPTS; error is nil on success."
  [code opts]
  (let [f (java.io.File/createTempFile "invariant_entry_check" ".nex")
        out (java.io.StringWriter.)]
    (try
      (spit f code)
      (let [error (binding [*out* out]
                    (try (e/eval-file (.getPath f) opts) nil
                         (catch Exception ex ex)))]
        [(remove str/blank? (str/split-lines (str out))) error])
      (finally (.delete f)))))

(defn- error-data
  "ex-data of the first exception in E's cause chain that has any."
  [e]
  (some ex-data (take-while some? (iterate #(.getCause ^Throwable %) e))))

(defn- passes-on-both [code expected]
  (doseq [opts backends]
    (let [[lines error] (run code opts)]
      (is (nil? error) (str "backend " opts ": " (some-> error .getMessage)))
      (is (= expected lines) (str "backend " opts)))))

(defn- fails-on-entry-on-both
  "CODE fails with invariant LABEL broken on entry to ROUTINE, having printed
   EXPECTED first."
  [code label routine expected]
  (doseq [opts backends]
    (let [[lines error] (run code opts)]
      (is (some? error) (str "backend " opts ": expected a violation"))
      (when error
        (is (str/includes? (.getMessage ^Throwable error)
                           (str "Class invariant violation: " label))
            (str "backend " opts ": " (.getMessage ^Throwable error)))
        (is (= routine (:on-entry-of (error-data error))) (str "backend " opts)))
      (is (= expected lines) (str "backend " opts)))))

(def ^:private account
  "class Account
create
  make do balance := 0 logged := 0 end
feature
  balance: Integer
  logged: Integer
  deposit(v: Integer, auditor: Auditor) do
    %s
  end
  statement: String do
    result := balance.to_string + \" / \" + logged.to_string
  end
invariant
  consistent: balance = logged
end

class Auditor
feature
  on_deposit(a: Account) do print(\"audit \" + a.statement) end
end

class Logger
feature
  write(s: String) do print(\"log \" + s) end
end

let acc: Account := create Account.make
acc.deposit(10, create Auditor)
print(acc.logged)
")

(deftest callback-into-object-mid-update-fails-on-entry-test
  (testing "the callback's body does not run on the inconsistent object"
    (fails-on-entry-on-both
     (format account "balance := balance + v
    auditor.on_deposit(this)
    logged := logged + v")
     "consistent" "Account.statement" [])))

(deftest callback-after-update-is-finished-passes-test
  (passes-on-both
   (format account "balance := balance + v
    logged := logged + v
    auditor.on_deposit(this)")
   ["\"audit 10 / 10\"" "10"]))

(deftest outgoing-call-without-callback-passes-test
  (testing "the invariant may be broken across a call that does not come back"
    (passes-on-both
     (format account "balance := balance + v
    let log: Logger := create Logger
    log.write(v.to_string)
    logged := logged + v")
     ["\"log 10\"" "10"])))

(deftest qualified-call-on-this-mid-update-fails-on-entry-test
  (fails-on-entry-on-both
   (format account "balance := balance + v
    print(this.statement)
    logged := logged + v")
   "consistent" "Account.statement" []))

(deftest unqualified-call-mid-update-passes-test
  (passes-on-both
   (format account "balance := balance + v
    print(statement)
    logged := logged + v")
   ["\"10 / 0\"" "10"]))

(deftest callback-from-constructor-fails-on-entry-test
  (fails-on-entry-on-both
   "class Node
create
  make(r: Registry) do
    r.register(this)
    size := 1
  end
feature
  size: Integer
  describe: String do result := size.to_string end
invariant
  positive: size > 0
end

class Registry
feature
  register(n: Node) do print(n.describe) end
end

let n: Node := create Node.make(create Registry)
print(n.size)"
   "positive" "Node.describe" []))

(deftest reference-leak-is-reported-on-entry-test
  (testing "a shared array changed behind the object's back"
    (fails-on-entry-on-both
     "class Bag
create
  make do items := [] total := 0 end
feature
  items: Array[Integer]
  total: Integer
  put(v: Integer) do items.add(v) total := total + v end
  sum: Integer do
    result := 0
    across items as x do result := result + x end
  end
invariant
  consistent: total = sum
end

let b: Bag := create Bag.make
b.put(10)
b.items.add(80)
print(b.total)
b.put(1)
print(\"unreachable\")"
     "consistent" "Bag.put" ["10"])))

(deftest invariant-calling-qualified-query-does-not-recurse-test
  (passes-on-both
   "class Pair
create
  make do a := 0 b := 0 end
feature
  a: Integer
  b: Integer
  diff: Integer do result := a - b end
  bump do a := a + 1 b := b + 1 end
invariant
  same: this.diff = 0
end

let p: Pair := create Pair.make
p.bump
print(p.a)"
   ["1"]))

(deftest skip-contracts-disables-entry-check-test
  (doseq [opts (map #(assoc % :skip-contracts? true) backends)]
    (let [[lines error] (run (format account "balance := balance + v
    auditor.on_deposit(this)
    logged := logged + v")
                             opts)]
      (is (nil? error) (str "backend " opts))
      (is (= ["\"audit 10 / 0\"" "10"] lines) (str "backend " opts)))))
