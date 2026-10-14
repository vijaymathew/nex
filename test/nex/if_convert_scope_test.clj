(ns nex.if-convert-scope-test
  "A guard binding in an `if`/`elseif` condition (`if convert a to c: C`)
   is scoped to that `if`: attached in its then-branch, gone after it. The
   type checker used to check the condition in the enclosing scope, so `c`
   outlived the `if` as a `?C` and `print(c)` after it type-checked -- then
   crashed the compiled backend, which scopes it to the `if` (\"Unable to
   infer expression type during lowering\"). A `convert` nested deeper in the
   condition (`if f(convert a to d: D)`) still binds for the rest of the
   scope, as in any other statement, in both."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.file :as file]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- type-error-message [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (str/join "\n" (map tc/format-type-error (:errors result))))))

(defn- run-compiled [code]
  (let [{:keys [main-class classes]} (file/compile-ast "if_convert_scope_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(def ^:private number-decl
  "union Number
  Zero
  Succ(pred: Number)
end
")

(deftest guard-binding-does-not-outlive-its-if
  (testing "after an if"
    (is (str/includes? (type-error-message
                        (str number-decl
                             "function f(a: Number) do
  if convert a to c: Succ then print(c.pred) end
  print(c)
end
"))
                       "Undefined variable: c")))
  (testing "after an elseif"
    (is (str/includes? (type-error-message
                        (str number-decl
                             "function f(a: Number) do
  if false then
    print(1)
  elseif convert a to c: Succ then
    print(c.pred)
  end
  print(c)
end
"))
                       "Undefined variable: c"))))

(deftest guard-binding-still-works-in-its-branches
  (let [code (str number-decl
                  "function f(a: Number): Integer do
  if convert a to s: Succ then
    if convert s.pred to z: Zero then result := 1 else result := 2 end
  elseif convert a to z: Zero then
    result := 0
  end
end
print(f(create Zero.make))
print(f(create Succ.make(create Zero.make)))
print(f(create Succ.make(create Succ.make(create Zero.make))))
")]
    (is (nil? (type-error-message code)))
    (is (= ["0" "1" "2"] (run-compiled code)))))

(deftest name-is-free-again-after-the-if
  (testing "a later local may take the name"
    (let [code (str number-decl
                    "function f(a: Number): Integer do
  if convert a to c: Succ then result := 1 end
  let c := 5
  result := result + c
end
print(f(create Succ.make(create Zero.make)))
")]
      (is (nil? (type-error-message code)))
      (is (= ["6"] (run-compiled code)))))
  (testing "an ensure may bind it"
    (let [code (str number-decl
                    "function f(a: Number): Boolean do
  if convert a to c: Succ then result := true end
ensure
  ok: convert a to c: Succ or not result
end
print(f(create Succ.make(create Zero.make)))
")]
      (is (nil? (type-error-message code)))
      (is (= ["true"] (run-compiled code))))))

(deftest nested-convert-in-condition-still-binds-for-the-scope
  (let [code (str number-decl
                  "function id(b: Boolean): Boolean do result := b end

function f(a: Number): Boolean do
  if id(convert a to d: Succ) then print(1) end
  result := d /= nil
end
print(f(create Succ.make(create Zero.make)))
print(f(create Zero.make))
")]
    (is (nil? (type-error-message code)))
    (is (= ["1" "true" "false"] (run-compiled code)))))
