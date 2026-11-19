(ns nex.closure-this-test
  "A closure made in a routine reaches the routine's object: through a local
   holding `this`, through `this`, or through a routine called bare, alone
   or as a receiver. Compiled code could not lower the first and the last
   (\"Unable to infer expression type during lowering\"), and the checker
   typed a bare routine as a receiver as Any when the call had arguments.
   The interpreter lost a closure's object once the closure was written back
   into the field holding it, and routed its calls through whatever variable
   the routine had been called on, though that may hold another object by
   then."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run [code interpret?]
  (let [f (java.io.File/createTempFile "closure_this" ".nex")
        out (java.io.StringWriter.)]
    (try
      (spit f code)
      (binding [*out* out] (e/eval-file (.getPath f) {:interpret? interpret?}))
      {:out (str/split-lines (str/trim (str out)))}
      (catch Throwable t {:error (or (ex-message t) "")})
      (finally (.delete f)))))

(def cases
  [
   ["a local holding this, captured by a closure"
    "class Box
feature
  v: Integer
  get: Integer do result := v end
  run: Integer do
    let me := this
    let h := fn(): Integer do result := me.get end
    result := h()
  end
create
  make(x: Integer) do v := x end
end
let b := create Box.make(5)
print(b.run)"
    ["5"]]
   ["... in a generic class"
    "class Box[T]
feature
  v: T
  get: T do result := v end
  run: T do
    let me := this
    let h := fn(): T do result := me.get end
    result := h()
  end
create
  make(x: T) do v := x end
end
let b := create Box[Integer].make(5)
print(b.run)"
    ["5"]]
   ["a routine called bare in a closure"
    "class Box
feature
  v: Integer
  get: Integer do result := v end
  run: Integer do
    let h := fn(): Integer do result := get end
    result := h()
  end
create
  make(x: Integer) do v := x end
end
let b := create Box.make(5)
print(b.run)"
    ["5"]]
   ["... in a generic class"
    "class Box[T]
feature
  v: T
  get: T do result := v end
  run: T do
    let h := fn(): T do result := get end
    result := h()
  end
create
  make(x: T) do v := x end
end
let b := create Box[Integer].make(5)
print(b.run)"
    ["5"]]
   ["a routine called bare as a receiver in a closure"
    "class Box
feature
  v: Integer
  get: Integer do result := v end
  run: String do
    let h := fn(): String do result := get.to_string end
    result := h()
  end
  direct: String do result := get.to_string end
create
  make(x: Integer) do v := x end
end
let b := create Box.make(5)
print(b.run)
print(b.direct)"
    ["\"5\"" "\"5\""]]
   ["a routine called bare as the receiver of a call with arguments"
    "class S
feature
  v: Integer
  rest: S do result := this end
  id(x: Integer): S do result := this end
  run: S do
    result := rest.id(1)
  end
create
  make(x: Integer) do v := x end
end
let s := create S.make(1)
print(s.run.v)"
    ["1"]]
   ["... in a closure"
    "class S
feature
  v: Integer
  rest: S do result := this end
  id(x: Integer): S do result := this end
  run: S do
    let h := fn(): S do result := rest.id(1) end
    result := h()
  end
create
  make(x: Integer) do v := x end
end
let s := create S.make(1)
print(s.run.v)"
    ["1"]]
   ["a stream's map through a local holding this, untyped"
    "class Stream[T]
feature
  first: T
  delayed_rest: Function(T): Stream[T]

create
  make(f: T, r: Function(T): Stream[T])
  do
    first := f
    delayed_rest := r
  end

feature

  rest: Stream[T]
  do
    result := delayed_rest(first)
  end

  at(index: Integer): T
  do
    result := first
    if index > 0 then
      let stream := this
      from
        let i := 0
        let found := false
      until
        found
      do
        if i = index then
          result := stream.first
          found := true
        else
          i := i + 1
          stream := stream.rest
        end
      end
    end
  end

  map(f: Function(T): T): Stream[T]
  do
    let source := this
    let mapped_rest := fn(t: T): Stream[T] do
      result := source.rest.map(f)
    end
    result := create Stream[T].make(f(first), mapped_rest)
  end
end

function next_i(i: Integer): Stream[Integer]
do
  result := create Stream.make(i + 1, next_i)
end

let counter: Stream[Integer] := create Stream.make[Integer](1, next_i)
assert counter.at(8) = 9

let sqrs := counter.map(fn(i) => i * i)
print(sqrs.at(2))
print(sqrs.at(7))
assert counter.at(2) = 3

console.print_line(\"DONE\")"
    ["9" "64" "DONE"]]
   ["... through this.rest"
    "class Stream[T]
feature
  first: T
  delayed_rest: Function(T): Stream[T]

create
  make(f: T, r: Function(T): Stream[T])
  do
    first := f
    delayed_rest := r
  end

feature

  rest: Stream[T]
  do
    result := delayed_rest(first)
  end

  at(index: Integer): T
  do
    result := first
    if index > 0 then
      let stream := this
      from
        let i := 0
        let found := false
      until
        found
      do
        if i = index then
          result := stream.first
          found := true
        else
          i := i + 1
          stream := stream.rest
        end
      end
    end
  end

  map(f: Function(T): T): Stream[T]
  do
    let source: Stream[T] := this
    let mapped_rest := fn(t: T): Stream[T] do
      result := this.rest.map(f)
    end
    result := create Stream[T].make(f(first), mapped_rest)
  end
end

function next_i(i: Integer): Stream[Integer]
do
  result := create Stream.make(i + 1, next_i)
end

let counter: Stream[Integer] := create Stream.make[Integer](1, next_i)
assert counter.at(8) = 9

let sqrs := counter.map(fn(i) => i * i)
print(sqrs.at(2))
print(sqrs.at(7))
assert counter.at(2) = 3

console.print_line(\"DONE\")"
    ["9" "64" "DONE"]]
   ["... through rest called bare"
    "class Stream[T]
feature
  first: T
  delayed_rest: Function(T): Stream[T]

create
  make(f: T, r: Function(T): Stream[T])
  do
    first := f
    delayed_rest := r
  end

feature

  rest: Stream[T]
  do
    result := delayed_rest(first)
  end

  at(index: Integer): T
  do
    result := first
    if index > 0 then
      let stream := this
      from
        let i := 0
        let found := false
      until
        found
      do
        if i = index then
          result := stream.first
          found := true
        else
          i := i + 1
          stream := stream.rest
        end
      end
    end
  end

  map(f: Function(T): T): Stream[T]
  do
    let source: Stream[T] := this
    let mapped_rest := fn(t: T): Stream[T] do
      result := rest.map(f)
    end
    result := create Stream[T].make(f(first), mapped_rest)
  end
end

function next_i(i: Integer): Stream[Integer]
do
  result := create Stream.make(i + 1, next_i)
end

let counter: Stream[Integer] := create Stream.make[Integer](1, next_i)
assert counter.at(8) = 9

let sqrs := counter.map(fn(i) => i * i)
print(sqrs.at(2))
print(sqrs.at(7))
assert counter.at(2) = 3

console.print_line(\"DONE\")"
    ["9" "64" "DONE"]]
   ["a closure returned by a routine, which changes its object through a routine"
    "class Counter
feature
  n: Integer
  bump do n := n + 1 end
  bumper: Function(): Integer do
    result := fn(): Integer do
      bump
      result := n
    end
  end
end
let c := create Counter
let f := c.bumper
print(f())
print(f())
print(c.n)"
    ["1" "2" "2"]]])

(deftest a-closure-reaches-its-routine-object
  (doseq [[what code out] cases
          interpret? [false true]]
    (testing (str what (if interpret? " (interpreter)" " (compiled)"))
      (is (= {:out out} (run code interpret?))))))
