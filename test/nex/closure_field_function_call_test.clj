(ns nex.closure-field-function-call-test
  "Inside a closure or spawn body in a method, `g(t)` calls the function held
   in the field `g` of the enclosing object. Compiled code failed with
   \"Unable to infer expression type during lowering\": the closure's body is
   rewritten to reach the enclosing object's fields and routines through a
   captured `this`, but a call of a field was left bare, naming nothing in
   the closure's own class. The interpreter ran it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run [code interpret?]
  (let [f (java.io.File/createTempFile "closure_field_call" ".nex")
        out (java.io.StringWriter.)]
    (try
      (spit f code)
      (binding [*out* out] (e/eval-file (.getPath f) {:interpret? interpret?}))
      {:out (str/split-lines (str/trim (str out)))}
      (catch Throwable t {:error (or (ex-message t) "")})
      (finally (.delete f)))))

(def cases
  [
   ["a field's function called in a closure"
    "class Box
feature
  g: Function(Integer): Integer
  run(x: Integer): Integer do
    let h := fn(t: Integer): Integer do result := g(t) end
    result := h(x)
  end
create
  make(f: Function(Integer): Integer) do g := f end
end
let b := create Box.make(fn(i: Integer): Integer => i + 1)
print(b.run(1))"
    ["2"]]
   ["in a generic class, nested, with no arguments, and shadowed by a parameter"
    "class Box[T]
feature
  g: Function(T): T
  z: Function(): Integer
  twice(x: T): T do
    let h := fn(t: T): T do result := g(g(t)) end
    result := h(x)
  end
  zero: Integer do
    let h := fn(): Integer do result := z() end
    result := h()
  end
  nested(x: T): T do
    let outer := fn(a: T): T do
      let inner := fn(b: T): T do result := g(b) end
      result := inner(a)
    end
    result := outer(x)
  end
  shadowed(x: T): T do
    let h := fn(g: Function(T): T, t: T): T do result := g(t) end
    result := h(fn(q: T): T => q, x)
  end
create
  make(f: Function(T): T) do
    g := f
    z := fn(): Integer => 7
  end
end
let b := create Box[Integer].make(fn(i: Integer): Integer => i + 1)
print(b.twice(1))
print(b.zero)
print(b.nested(10))
print(b.shadowed(5))"
    ["3" "7" "11" "5"]]
   ["in a spawn body"
    "class Box
feature
  g: Function(Integer): Integer
  run(x: Integer): Integer do
    let t: Task[Integer] := spawn do result := g(x) end
    result := t.await
  end
create
  make(f: Function(Integer): Integer) do g := f end
end
let b := create Box.make(fn(i: Integer): Integer => i + 1)
print(b.run(1))"
    ["2"]]])

(deftest a-closure-calls-a-field-function
  (doseq [[what code out] cases
          interpret? [false true]]
    (testing (str what (if interpret? " (interpreter)" " (compiled)"))
      (is (= {:out out} (run code interpret?))))))
