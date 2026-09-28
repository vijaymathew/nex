(ns nex.field-shadowing-test
  "A `let` inside a class's routines may not reuse a visible field's name."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.repl :as repl]))

(defn- rejection [src]
  (try (p/ast src) nil
       (catch Exception e (.getMessage e))))

(deftest let-shadowing-a-field-is-rejected
  (doseq [[label line src]
          [["in a constructor" 5 "class Box
feature
  value: Integer
create
  make(v: Integer) do let value := v end
end"]
           ["in a method" 4 "class Box
feature
  value: Integer
  reset do let value := 0 end
end"]
           ["in a nested block" 6 "class Box
feature
  value: Integer
  reset(flag: Boolean)
  do
    if flag then let value := 0 end
  end
end"]
           ["in a from-loop initializer" 6 "class Box
feature
  i: Integer
  count
  do
    from let i := 0 until i > 3 do end
  end
end"]
           ["in a rescue clause" 8 "class Box
feature
  value: Integer
  risky
  do
    raise \"x\"
  rescue
    let value := 1
  end
end"]
           ["inside a closure in a method" 4 "class Box
feature
  value: Integer
  make_setter: Function do result := fn(v: Integer) do let value := v end end
end"]
           ["a class constant" 4 "class Box
feature
  LIMIT = 10
  check do let LIMIT := 3 end
end"]]]
    (testing label
      (let [msg (rejection src)]
        (is (some? msg) "should be rejected")
        (is (str/starts-with? (str msg) (str "Line " line ":")) msg)
        (is (str/includes? (str msg) "would hide it") msg)))))

(deftest inherited-fields-are-covered
  (testing "a public field inherited from a class in the same unit"
    (is (re-find #"field 'value' that class 'Child' inherits from 'Base'"
                 (str (rejection "class Base feature value: Integer end
class Child inherit Base
feature
  reset do let value := 0 end
end")))))
  (testing "two levels up"
    (is (re-find #"inherits from 'A'"
                 (str (rejection "class A feature value: Integer end
class B inherit A end
class C inherit B feature reset do let value := 0 end end")))))
  (testing "a private field of a parent is not visible, so it is not shadowed"
    (is (nil? (rejection "class Base private feature value: Integer end
class Child inherit Base
feature
  reset do let value := 0 end
end")))))

(deftest deliberate-bindings-stay-allowed
  (is (nil? (rejection "class Pet
feature
  name: String
  items: Array[Integer]
  total: Integer
create
  make(name: String) do this.name := name items := [] end
feature
  sum do across items as total do print(total) end end
end
function f(): Integer do let name := 1 result := name end")))
  (testing "a match destructuring binding named like a field"
    (is (nil? (rejection "union S
  A(x: Integer)
end
class Box
feature
  x: Integer
  show(s: S) do match s of A(x) then print(x) end end
end")))))

(deftest parent-in-another-unit-is-caught-by-the-type-checker
  (let [parent (:classes (p/ast "class Base feature value: Integer end"))
        child (p/ast "class Child inherit Base feature reset do let value := 0 end end")
        result (tc/type-check (update child :classes #(vec (concat parent %))))]
    (is (not (:success result)))
    (is (re-find #"inherits from 'Base'"
                 (str/join "\n" (map tc/format-type-error (:errors result)))))))

(defmacro ^:private with-repl [backend type-check? ctx-sym & body]
  `(binding [repl/*type-checking-enabled* (atom ~type-check?)
             repl/*repl-var-types* (atom {})
             repl/*repl-backend* (atom ~backend)
             repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
     (let [~ctx-sym (repl/init-repl-context)]
       ~@body)))

(deftest rejected-in-the-repl
  (doseq [backend [:compiled :interpreter]]
    (testing (str backend ", type checking off, one input")
      (with-repl backend false ctx
        (let [out (with-out-str
                    (repl/eval-code ctx "class Box feature value: Integer create make(v: Integer) do let value := v end end"))]
          (is (str/includes? out "would hide it") out))))
    (testing (str backend ", type checking on, parent in an earlier input")
      (with-repl backend true ctx
        (with-out-str (repl/eval-code ctx "class Base feature value: Integer end"))
        (let [out (with-out-str
                    (repl/eval-code ctx "class Child inherit Base feature reset do let value := 0 end end"))]
          (is (str/includes? out "would hide it") out))))))
