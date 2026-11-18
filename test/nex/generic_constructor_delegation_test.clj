(ns nex.generic-constructor-delegation-test
  "`this` in a generic class `C[T]` has type `C[T]`, so a call through it
   accepts arguments typed with `T`. As a bare `C`, every parameter read as
   Any, and `this.make(d)` with `d: Function(T): T` was refused. A
   constructor called by its bare name (`make(...)`) is a type error that
   says to write `this.make(...)`; before, the checker accepted it and both
   backends failed at run time."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- error-text [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (str/join "\n" (map tc/format-type-error (:errors result))))))

(defn- run-lines [code interpret?]
  (let [f (java.io.File/createTempFile "delegation" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline
                        (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))))
      (finally (.delete f)))))

(def stream-code
  "class Stream[T]
feature
  current: T
  step: Function(T): T
  stop: Function(T): Boolean
  next do current := step(current) end
  at_end: Boolean do result := stop(current) end
create
  finite(t: T, d: Function(T): T, e: Function(T): Boolean) do
    current := t
    step := d
    stop := e
  end
  infinite(t: T, d: Function(T): T) do
    this.finite(t, d, fn(i: T): Boolean => false)
  end
end

function next_i(i: Integer): Integer do result := i + 1 end

let s: Stream[Integer] := create Stream[Integer].infinite(1, next_i)
s.next
s.next
print(s.current)
print(s.at_end)
")

(deftest generic-delegation-passes-type-parameter-arguments
  (is (nil? (error-text stream-code)))
  (doseq [interpret? [false true]]
    (testing (if interpret? "interpreter" "compiled")
      (is (= ["3" "false"] (run-lines stream-code interpret?))))))

(deftest this-in-a-generic-class-keeps-its-type-parameters
  (is (nil? (error-text "class G[T]
feature
  f: ?Function(T): T
  set_f(d: Function(T): T) do f := d end
  use(d: Function(T): T) do this.set_f(d) end
  me: G[T] do result := this end
end
"))))

(deftest a-constructor-called-by-its-bare-name-is-a-type-error
  (let [err (error-text "class Box
feature
  value: Integer
create
  make(v: Integer) do value := v end
  default do make(42) end
end
")]
    (is (str/includes? (str err) "`make` is a constructor"))
    (is (str/includes? (str err) "`this.make(...)`"))
    (is (str/includes? (str err) "line 6")))
  (testing "a routine of the same name and arity is still called by its bare name"
    (is (nil? (error-text "class Box
feature
  value: Integer
  reset(v: Integer) do value := v end
create
  reset(v: Integer) do value := v end
  default do value := 1 end
feature
  clear do reset(0) end
end
")))))
