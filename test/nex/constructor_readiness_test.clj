(ns nex.constructor-readiness-test
  "A constructor may not use the object before it is ready: before every
   attachable field is assigned, on every path, and every parent that must
   be constructed has been. A parent constructor that reaches an override
   reading a field of the subclass must be called after that field is
   assigned. Each rejected program here type-checked before, and failed at
   run time with a void value."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run [code interpret?]
  (let [f (java.io.File/createTempFile "constructor_readiness" ".nex")
        out (java.io.StringWriter.)]
    (try
      (spit f code)
      (binding [*out* out] (e/eval-file (.getPath f) {:interpret? interpret?}))
      {:out (str/split-lines (str/trim (str out)))}
      (catch Throwable t {:error (or (ex-message t) "")})
      (finally (.delete f)))))

(def a-class
  "class A
feature
  hello: String do result := \"hi\" end
end
")

(def rejected
  [["a routine called before the field it reads is assigned"
    "class B
feature
  a: A
  call_a: String do result := a.hello end
create
  make() do
    this.call_a
    a := create A
  end
end
let b := create B.make"
    "Constructor 'make' calls `call_a` before `a` is assigned: `call_a` reads `a`"]

   ["a bare routine call"
    "class B
feature
  a: A
  call_a: String do result := a.hello end
create
  make() do
    print(call_a)
    a := create A
  end
end
let b := create B.make"
    "Constructor 'make' calls `call_a` before `a` is assigned"]

   ["a field read before it is assigned"
    "class B
feature
  a: A
create
  make() do
    print(a.hello)
    a := create A
  end
end
let b := create B.make"
    "Constructor 'make' reads `a` before it is assigned"]

   ["a field assigned on one path only"
    "class B
feature
  a: A
create
  make(c: Boolean) do
    if c then a := create A end
  end
end
let b := create B.make(false)"
    "must assign `a` on every path through it"]

   ["this passed on before the object is ready"
    "class B
feature
  a: A
  show: String do result := a.hello end
create
  make(h: H) do
    h.use(this)
    a := create A
  end
end
class H
feature
  use(b: B) do print(b.show) end
end
let b := create B.make(create H)"
    "Constructor 'make' passes `this` on before `a` is assigned"]

   ["a closure that reads a field not yet assigned"
    "class B
feature
  a: A
create
  make() do
    let f := fn(): String do result := a.hello end
    a := create A
  end
end
let b := create B.make"
    "Constructor 'make' reads `a` before it is assigned"]

   ["an inherited field read before the parent constructor"
    "class P
feature
  pa: A
create
  make() do pa := create A end
end
class C
inherit P
create
  make() do
    print(pa.hello)
    P.make
  end
end
let c := create C.make"
    "reads `pa` before a constructor of `P` runs"]

   ["a parent constructor that calls an override reading an unset field"
    "class P
feature
  describe: String do result := \"p\" end
create
  make() do print(this.describe) end
end
class C
inherit P
feature
  a: A
  describe: String do result := a.hello end
create
  make() do
    P.make
    a := create A
  end
end
let c := create C.make"
    "must assign `a` before it calls `P.make`: `P.make` → `describe` reads `a` (`describe` as 'C' defines it)"]

   ["through super, and through a chain of calls"
    "class P
feature
  describe: String do result := this.label end
  label: String do result := \"p\" end
create
  make() do print(describe) end
end
class C
inherit P
feature
  a: A
  label: String do result := a.hello end
create
  make() do
    super.make
    a := create A
  end
end
let c := create C.make"
    "before it calls `P.make`: `P.make` → `describe` → `label` reads `a`"]

   ["a parent constructor that passes this on"
    "class R
feature
  add(x: Q) do print(x.describe) end
end
class Q
feature
  describe: String do result := \"q\" end
create
  make(r: R) do r.add(this) end
end
class C
inherit Q
feature
  a: A
  describe: String do result := a.hello end
create
  make(r: R) do
    Q.make(r)
    a := create A
  end
end
let c := create C.make(create R)"
    "`Q.make` passes `this` to other code, which may call `describe`, which reads `a`"]

   ["a grandparent constructor that reaches an override"
    "class P
feature
  describe: String do result := \"p\" end
create
  make() do print(describe) end
end
class Q
inherit P
create
  make() do P.make end
end
class C
inherit Q
feature
  a: A
  describe: String do result := a.hello end
create
  make() do
    Q.make
    a := create A
  end
end
let c := create C.make"
    "must assign `a` before it calls `Q.make`"]

   ["`P.m(...)` of an ordinary routine that reaches a void field"
    "class P
feature
  label: String do result := \"p\" end
  show: String do result := this.label end
end
class C
inherit P
feature
  a: A
  label: String do result := a.hello end
create
  make() do
    print(P.show)
    a := create A
  end
end
let c := create C.make"
    "line 17, column 11: Constructor 'make' calls `P.show` before `a` is assigned: `P.show` → `label` reads `a`. Assign it first"]

   ["`super.m(...)` of an ordinary routine that reaches a void field"
    "class P
feature
  label: String do result := \"p\" end
  show: String do result := this.label end
end
class C
inherit P
feature
  a: A
  label: String do result := a.hello end
create
  make() do
    print(super.show)
    a := create A
  end
end
let c := create C.make"
    "Constructor 'make' calls `super.show` before `a` is assigned: `super.show` → `label` reads `a`"]

   ["an inherited routine that reads an inherited field, before the parent constructor"
    "class P
feature
  pa: A
  greet: String do result := pa.hello end
create
  make() do pa := create A end
end
class C
inherit P
create
  make() do
    print(greet)
    P.make
  end
end
let c := create C.make"
    "Constructor 'make' calls `greet` before a constructor of `P` runs: `greet` reads `pa`. Call the parent's constructor first."]

   ["a field assigned only in a loop body, which may run no times"
    "class B
feature
  a: A
create
  make(n: Integer) do
    from let i := 0 until i >= n do
      a := create A
      i := i + 1
    end
  end
end
let b := create B.make(1)"
    "must assign `a` on every path through it"]

   ["a field assigned in some clauses of a case with no else"
    "class B
feature
  a: A
create
  make(k: Integer) do
    case k of
      1 then a := create A
      2 then a := create A
    end
  end
end
let b := create B.make(1)"
    "must assign `a` on every path through it"]

   ["a field assigned in one clause of a match"
    "union Shape
  Round
  Square
end
class B
feature
  a: A
create
  make(s: Shape) do
    match s of
      Round as r then a := create A
      Square as q then print(\"square\")
    end
  end
end
let b := create B.make(create Round.make)"
    "must assign `a` on every path through it"]

   ["a rescue that completes without assigning"
    "class B
feature
  a: A
create
  make() do
    do
      a := create A
    rescue
      print(\"failed\")
    end
  end
end
let b := create B.make"
    "must assign `a` on every path through it"]

   ["a sibling constructor that assigns on some paths only"
    "class B
feature
  a: A
create
  again() do
    this.make(true)
    print(a.hello)
  end
  make(c: Boolean) do
    if c then a := create A end
  end
end
let b := create B.again"
    "Constructor 'again' reads `a` before it is assigned"]

   ["constructors that delegate to each other in a cycle"
    "class B
feature
  a: A
create
  one() do this.two end
  two() do this.one end
end
let b := create B.one"
    "Constructor 'one' in class 'B' must initialize attachable fields: a"]

   ["this passed to another constructor"
    "class H
feature
  b: ?B
create
  make(x: B) do b := x end
end
class B
feature
  a: A
create
  make() do
    let h := create H.make(this)
    a := create A
  end
end
let b := create B.make"
    "Constructor 'make' passes `this` on before `a` is assigned"]

   ["several fields not yet assigned"
    "class H
feature
  take(x: B) do print(\"taken\") end
end
class B
feature
  a: A
  a2: A
create
  make(h: H) do
    h.take(this)
    a := create A
    a2 := create A
  end
end
let b := create B.make(create H)"
    "passes `this` on before `a` and `a2` are assigned"]])

(def accepted
  [["fields first, then routines"
    "class B
feature
  a: A
  n: Integer
  show: String do result := a.hello end
create
  make(c: Boolean) do
    let x := n + 1
    if c then a := create A else a := create A end
    print(show)
    let f := fn(): String do result := this.show end
    print(f.call0())
  end
end
let b := create B.make(false)"
    ["\"hi\"" "\"hi\""]]

   ["after a sibling constructor, the object is ready"
    "class B
feature
  a: A
  show: String do result := a.hello end
create
  make() do a := create A end
  again() do
    this.make
    print(this.show)
  end
end
let b := create B.again"
    ["\"hi\""]]

   ["the parent first, when its constructor reaches no override that reads an unset field"
    "class P
feature
  name: String
  pa: A
  describe: String do result := name end
create
  make(n: String) do
    pa := create A
    name := n
    print(this.describe)
  end
end
class C
inherit P
feature
  a: A
  other: String do result := a.hello end
create
  make() do
    P.make(\"c\")
    print(pa.hello)
    a := create A
    print(this.other)
  end
end
let c := create C.make"
    ["\"c\"" "\"hi\"" "\"hi\""]]

   ["the subclass's fields first, when the parent constructor reaches an override"
    "class P
feature
  describe: String do result := \"p\" end
create
  make() do print(this.describe) end
end
class C
inherit P
feature
  a: A
  describe: String do result := a.hello end
create
  make() do
    a := create A
    P.make
  end
end
let c := create C.make"
    ["\"hi\""]]

   ["a routine that reads no unassigned field, called early"
    "class B
feature
  a: A
  n: Integer
  count(x: Integer) do n := n + x end
  twice(x: Integer): Integer do result := this.helper(x) * 2 end
  helper(x: Integer): Integer do result := x end
  show: String do result := a.hello end
create
  make() do
    count(twice(3))
    a := create A
    print(n)
    print(show)
  end
end
let b := create B.make"
    ["6" "\"hi\""]]

   ["this passed on when no field can be void"
    "class Log
feature
  record(x: P) do print(\"noted\") end
end
class P
feature
  n: Integer
create
  make() do n := 1 end
end
class C
inherit P
create
  make(log: Log) do
    log.record(this)
    P.make
  end
end
let c := create C.make(create Log)"
    ["\"noted\""]]

   ["a branch that raises need not assign"
    "class B
feature
  a: A
create
  make(c: Boolean) do
    if c then a := create A else raise \"no\" end
    print(a.hello)
  end
end
let b := create B.make(true)"
    ["\"hi\""]]

   ["a field assigned in a loop's from part"
    "class B
feature
  a: A
create
  make() do
    from a := create A until true do end
    print(a.hello)
  end
end
let b := create B.make"
    ["\"hi\""]]

   ["a case whose every clause assigns"
    "class B
feature
  a: A
create
  make(k: Integer) do
    case k of
      1 then a := create A
      else a := create A
    end
    print(a.hello)
  end
end
let b := create B.make(2)"
    ["\"hi\""]]

   ["a match whose every clause assigns"
    "union Shape
  Round
  Square
end
class B
feature
  a: A
create
  make(s: Shape) do
    match s of
      Round as r then a := create A
      Square as q then a := create A
    end
    print(a.hello)
  end
end
let b := create B.make(create Square.make)"
    ["\"hi\""]]

   ["a rescue that raises need not assign"
    "class B
feature
  a: A
create
  make() do
    do
      a := create A
    rescue
      raise \"no A\"
    end
    print(a.hello)
  end
end
let b := create B.make"
    ["\"hi\""]]])

(deftest a-constructor-does-not-use-the-object-before-it-is-ready
  (doseq [[what code message] rejected
          interpret? [false true]]
    (testing (str what (if interpret? " (interpreter)" " (compiled)"))
      (let [{:keys [error]} (run (str a-class code) interpret?)]
        (is (some? error) "expected a type error")
        (is (str/includes? (str error) "Type checking failed"))
        (is (str/includes? (str error) message) error)))))

(deftest a-ready-object-may-be-used
  (doseq [[what code out] accepted
          interpret? [false true]]
    (testing (str what (if interpret? " (interpreter)" " (compiled)"))
      (is (= {:out out} (run (str a-class code) interpret?))))))
