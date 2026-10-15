(ns nex.java-block-arithmetic-test
  "Mixed Integer/Real arithmetic on a Nex field read inside `with \"java\"`.
   There `k.a` infers as Any during lowering, and the binary-op fallback took
   the left operand's type alone, so `k.a / 255.0` lowered as an integer
   division (0 instead of 0.50...) and, at top level, failed outright with
   \"Unsupported JVM stack coercion\". The interpreter was never affected."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.file :as file]
            [nex.interpreter :as interp]
            [nex.parser :as p]))

(defn- run-interpreted
  [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    @(:output ctx)))

(defn- run-compiled
  [code]
  (let [{:keys [main-class classes]} (file/compile-ast "java_block_arith.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out)
           (remove str/blank?)
           vec))))

(def program
  "class K
  feature
    a: Integer
  create
    make() do a := 128 end
end

function f(k: K) do
  with \"java\" do
    print(k.a / 256.0)
    print(k.a * 1.5)
    print(k.a + 0.5)
    print(k.a / 3)
  end
end

f(create K.make())
let top := create K.make()
with \"java\" do
  print(top.a / 256.0)
end")

(deftest field-arithmetic-promotes-to-real-in-java-block
  (let [expected ["0.5" "192.0" "128.5" "42" "0.5"]]
    (is (= expected (run-compiled program)))
    (is (= expected (run-interpreted program)))))
