(ns nex.safe-call-field-test
  "`x?.m` in expression position, with a bare name as the receiver, used to
   become `when x /= nil then x.m else nil`. A nil check narrows a local but
   not a field (any call may reset one), so `field?.m` was rejected as a call
   on a detachable field. A bare-name receiver now gets the object-test form
   compound receivers already used, `when ?x as tmp then tmp.m else nil`,
   which works for a field or a local and reads the receiver once."
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
  (let [{:keys [main-class classes]} (file/compile-ast "safe_call_field_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(def ^:private node-class
  "class Node
feature
  name: String
  next: ?Node
  next_name: ?String do
    result := next?.name
  end
  next_name_via_local: ?String do
    let n := next
    result := n?.name
  end
create
  make(s: String, n: ?Node) do
    name := s
    next := n
  end
end
")

(def ^:private program
  (str node-class
       "let tail := create Node.make(\"b\", nil)
let head := create Node.make(\"a\", tail)
print(head.next_name)
print(tail.next_name)
print(head.next_name_via_local)
print(tail.next_name_via_local)
"))

(deftest safe-call-on-a-field-in-an-expression
  (is (nil? (type-error-message program)))
  (testing "the interpreter"
    (is (= ["\"b\"" "nil" "\"b\"" "nil"] (run program))))
  (testing "the compiled backend"
    (is (= ["\"b\"" "nil" "\"b\"" "nil"] (run-compiled program)))))

(deftest safe-call-on-a-field-as-a-string-operand
  (let [code (str node-class
                  "class Chain
feature
  first: ?Node
  label: String do
    result := \"<\" + first?.name + \">\"
  end
create
  make(n: ?Node) do first := n end
end
let c := create Chain.make(create Node.make(\"a\", nil))
print(c.label)
")]
    (is (nil? (type-error-message code)))
    (is (= ["\"<a>\""] (run code)))
    (is (= ["\"<a>\""] (run-compiled code)))))
