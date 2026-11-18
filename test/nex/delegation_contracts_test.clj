(ns nex.delegation-contracts-test
  "A call that runs a routine of a named class on the current object --
   `super.m(...)`, `P.m(...)`, a parent constructor `P.k(...)`, a sibling
   constructor `this.k(...)`, `this.m(...)` during construction -- checks the
   callee's precondition and postcondition, as any call does. The
   interpreter skipped them on all of these, while compiled code checked
   them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run [code interpret?]
  (let [f (java.io.File/createTempFile "delegation_contracts" ".nex")
        out (java.io.StringWriter.)]
    (try
      (spit f code)
      (binding [*out* out] (e/eval-file (.getPath f) {:interpret? interpret?}))
      {:out (str/trim (str out))}
      (catch Throwable t {:error (first (str/split-lines (or (ex-message t) "")))})
      (finally (.delete f)))))

(def cases
  [["a sibling constructor's precondition"
    "class R
feature
  lo: Integer
create
  make(a: Integer)
    require pos: a >= 0
  do lo := a end
  pair(a: Integer) do this.make(a) end
end
let r := create R.pair(-1)"
    {:error "Precondition violation: pos"}]

   ["a sibling constructor's postcondition"
    "class R
feature
  lo: Integer
create
  make(a: Integer) do lo := a ensure off: lo = a + 1 end
  pair(a: Integer) do this.make(a) end
end
let r := create R.pair(1)"
    {:error "Postcondition violation: off"}]

   ["a parent constructor's precondition"
    "class P
feature
  v: Integer
create
  make(a: Integer) require pos: a >= 0 do v := a end
end
class C
inherit P
create
  make(a: Integer) do P.make(a) end
end
let c := create C.make(-1)"
    {:error "Precondition violation: pos"}]

   ["a super call's precondition"
    "class P
feature
  v: Integer
  set(a: Integer) require pos: a >= 0 do v := a end
end
class C
inherit P
feature
  set(a: Integer) do super.set(a - 10) end
end
let c := create C
c.set(5)"
    {:error "Precondition violation: pos"}]

   ["old in a super call's postcondition"
    "class P
feature
  v: Integer
  bump(a: Integer) do v := v + a ensure grew: v = old v + a end
end
class C
inherit P
feature
  bump(a: Integer) do super.bump(a) end
end
let c := create C
c.bump(2)
c.bump(3)
print(c.v)"
    {:out "5"}]])

(deftest delegated-calls-check-the-callee-contracts
  (doseq [[what code expected] cases
          interpret? [false true]]
    (testing (str what (if interpret? " (interpreter)" " (compiled)"))
      (is (= expected (run code interpret?))))))
