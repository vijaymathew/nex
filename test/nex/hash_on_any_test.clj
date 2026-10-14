(ns nex.hash-on-any-test
  "`hash` is a feature of Any, like `equals`, with which it agrees: every value
   has it, whatever its type -- a scalar, a collection, a plain class, a class
   that overrides it, a value typed `Any`. It is the structural hash Map and
   Set already placed keys and elements with. There is no Hashable class."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.file :as file]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- type-error-message [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (str/join "\n" (map tc/format-type-error (:errors result))))))

(defn- run [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    @(:output ctx)))

(defn- run-compiled [code]
  (let [{:keys [main-class classes]} (file/compile-ast "hash_on_any_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(def ^:private classes
  "class P
feature
  x: Integer
create
  make(v: Integer) do x := v end
end

class Q
feature
  x: Integer
  equals(other: Any): Boolean do
    result := false
    if convert other to o: Q then result := x = o.x end
  end
  hash: Integer do result := 42 end
create
  make(v: Integer) do x := v end
end
")

(def ^:private program
  (str classes
       "print(\"ab\".hash = \"ab\".hash)
print(5.hash = 5.hash)
print(true.hash = true.hash)
print([1, 2, 3].hash = [1, 2, 3].hash)
print({1: \"a\"}.hash = {1: \"a\"}.hash)
print(#{1, 2}.hash = #{2, 1}.hash)
print(create P.make(1).hash = create P.make(1).hash)
print(create Q.make(1).hash)
let a: Any := create Q.make(7)
print(a.hash)
let b: Any := [create P.make(1)]
print(b.hash = [create P.make(1)].hash)
"))

(def ^:private expected
  ["true" "true" "true" "true" "true" "true" "true" "42" "42" "true"])

(deftest every-value-has-hash
  (is (nil? (type-error-message program)))
  (testing "the interpreter"
    (is (= expected (run program))))
  (testing "the compiled backend"
    (is (= expected (run-compiled program)))))

(deftest a-collection-hashes-through-its-elements-overrides
  (let [code (str classes
                  "print([create Q.make(1)].hash = [create Q.make(2)].hash)
print([create P.make(1)].hash = [create P.make(2)].hash)
")]
    (testing "Q's hash ignores x, so arrays of Qs hash alike; P's is structural"
      (is (= ["true" "false"] (run-compiled code))))))

(deftest hash-overrides-conform-to-any
  (testing "an override's signature is checked against Any.hash"
    (is (str/includes? (or (type-error-message "class R
feature
  hash: String do result := \"x\" end
end
") "")
                       "hash"))))

(deftest hashable-is-gone
  (is (str/includes? (or (type-error-message "class K inherit Hashable end\n") "")
                     "Hashable")))
