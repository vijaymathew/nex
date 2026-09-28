(ns nex.reserved-result-test
  "`result` names a routine's return value and cannot be declared as anything
   else — a local, parameter, field, loop variable or pattern binding would
   hide it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.repl :as repl]))

(defn- rejection [src]
  (try (p/ast src) nil
       (catch Exception e (.getMessage e))))

(def ^:private declarations
  [["local variable" 2
    "function f(x: Integer): Integer do
  let result := x
end"]
   ["local variable" 1 "let result := 7"]
   ["parameter" 1 "function f(result: Integer) do end"]
   ["parameter" 3 "class C
feature
  m(result: Integer) do end
end"]
   ["parameter" 3 "class C
create
  make(result: Integer) do end
end"]
   ["parameter" 1 "let g := fn(result: Integer) do end"]
   ["field" 3 "class C
feature
  result: Integer
end"]
   ["field" 1 "union S
  A(result: Integer)
end"]
   ["loop variable" 1 "across [1, 2] as result do end"]
   ["pattern binding" 2 "let s: ?Integer := 1
if ?s as result then print(1) end"]
   ["pattern binding" 6 "union S
  A
  B(x: Integer)
end
let s: S := create A.make
match s of
  A as result then print(1)
  B then print(2)
end"]
   ["pattern binding" 6 "union S
  A
  B(x: Integer)
end
let s: S := create A.make
match s of
  A then print(1)
  B(x as result) then print(2)
end"]
   ["select binding" 2 "let ch := create Channel[Integer]
select
  when ch.receive as result then print(1)
  timeout 10 then print(0)
end"]])

(deftest every-declaration-of-result-is-rejected
  (doseq [[kind line src] declarations]
    (testing (str kind ": " (first (str/split-lines src)))
      (let [msg (rejection src)]
        (is (some? msg) "should be rejected")
        (is (str/includes? (str msg) (str "cannot be declared as a " kind)) msg)
        (is (str/starts-with? (str msg) (str "Line " line ":")) msg)))))

(deftest using-result-is-still-allowed
  (is (nil? (rejection "function f(x: Integer): Integer do
  result := x * 2
  result := result + 1
end
class C
feature
  twice(n: Integer): Integer do result := n * 2 end
  pick(xs: Array[Integer]): Integer do
    across xs as x do result := result + x end
  end
end
let g := fn(n: Integer): Integer do result := n end
print(f(1))"))))

(defmacro ^:private with-repl [backend ctx-sym & body]
  `(binding [repl/*type-checking-enabled* (atom false)
             repl/*repl-var-types* (atom {})
             repl/*repl-backend* (atom ~backend)
             repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
     (let [~ctx-sym (repl/init-repl-context)]
       ~@body)))

(deftest rejected-in-the-repl-even-without-type-checking
  (doseq [backend [:compiled :interpreter]]
    (testing (str backend)
      (with-repl backend ctx
        (with-out-str (repl/eval-code ctx "class A feature b: Integer kick do b := b + 1 end end"))
        (let [out (with-out-str
                    (repl/eval-code ctx "function f(x: Integer): A do let result := create A repeat x do result.kick end end"))]
          (is (str/includes? out "'result' is reserved") out))
        (let [out (with-out-str
                    (repl/eval-code ctx "function f(x: Integer): A do result := create A repeat x do result.kick end end"))]
          (is (not (str/includes? out "Error")) out))
        (let [out (with-out-str (repl/eval-code ctx "print(f(10).b)"))]
          (is (str/includes? out "10") out))))))
