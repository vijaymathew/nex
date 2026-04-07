(ns nex.safe-call-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.repl :as repl]
            [nex.typechecker :as tc]))

(def safe-call-code
  "class Node
create
  make() do
    this.right := nil
  end
feature
  right: ?Node
  set_right(y: Node) do
    this.right := y
  end
end

class Factory
create
  make(node: ?Node) do
    this.node := node
    this.calls := 0
  end
feature
  node: ?Node
  calls: Integer
  next(): ?Node do
    calls := calls + 1
    result := node
  end
end

let target: Node := create Node.make()
let y: Node := create Node.make()
let f: Factory := create Factory.make(target)
f.next()?.set_right(y)
print(f.calls)
if convert target.right to attached_right: Node then
  print(attached_right = y)
else
  print(false)
end
let empty: Factory := create Factory.make(nil)
empty.next()?.set_right(y)
print(empty.calls)")

(deftest safe-call-desugars-to-temp-guarded-scoped-block-test
  (testing "safe method call syntax desugars to a receiver temp plus nil guard"
    (let [ast (p/ast "class Node
feature
  set_right(y: Node) do end
end
let x: ?Node := nil
let y: Node := create Node
x?.set_right(y)")
          safe-stmt (last (:statements ast))
          [temp-binding guard] (:body safe-stmt)]
      (is (= :scoped-block (:type safe-stmt)))
      (is (= :let (:type temp-binding)))
      (is (:synthetic temp-binding))
      (is (str/starts-with? (:name temp-binding) "__safe_receiver_"))
      (is (= {:type :identifier :name "x"} (:value temp-binding)))
      (is (= :if (:type guard)))
      (is (= "/=" (-> guard :condition :operator)))
      (is (= (:name temp-binding) (-> guard :condition :left :name)))
      (is (= :nil (-> guard :condition :right :type)))
      (is (= (:name temp-binding) (-> guard :then first :target)))
      (is (= "set_right" (-> guard :then first :method))))))

(deftest safe-call-typechecks-and-evaluates-receiver-once-test
  (testing "safe method call works on detachable receivers and evaluates receiver expression once"
    (let [ast (p/ast safe-call-code)
          checked (tc/type-check ast)]
      (is (:success checked) (pr-str (:errors checked))))
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx safe-call-code))]
        (is (not (str/includes? output "Error:")))
        (is (str/includes? output "1\ntrue\n1"))))))

(deftest safe-call-expression-desugars-to-when-test
  (testing "safe field access in expression position desugars to a nil-producing when expression"
    (let [ast (p/ast "class Node
feature
  left: ?Node
end
let node: ?Node := nil
print(node?.left)")
          print-call (last (:statements ast))
          arg (-> print-call :args first)]
      (is (= :when (:type arg)))
      (is (= "/=" (-> arg :condition :operator)))
      (is (= "node" (-> arg :condition :left :name)))
      (is (= :nil (-> arg :condition :right :type)))
      (is (= :call (-> arg :consequent :type)))
      (is (= "node" (-> arg :consequent :target)))
      (is (= "left" (-> arg :consequent :method)))
      (is (= :nil (-> arg :alternative :type))))))

(deftest safe-call-expression-in-update-height-test
  (testing "safe field access can be used inside safe method call arguments"
    (let [code "class Node
create
  make() do
    this.left := nil
    this.right := nil
    this.height_value := 0
  end
feature
  left: ?Node
  right: ?Node
  height_value: Integer
  set_left(child: Node) do
    this.left := child
  end
  set_right(child: Node) do
    this.right := child
  end
  set_height(h: Integer) do
    this.height_value := h
  end
end

function height(node: ?Node): Integer
do
  if node /= nil then
    result := node.height_value
  else
    result := 0
  end
end

function update_height(node: ?Node)
do
  node?.set_height(1 + height(node?.left).max(height(node?.right)))
end

let root: Node := create Node.make()
let left: Node := create Node.make()
let right: Node := create Node.make()
left.set_height(2)
right.set_height(4)
root.set_left(left)
root.set_right(right)
update_height(root)
print(root.height_value)
update_height(nil)"
          ast (p/ast code)
          checked (tc/type-check ast)]
      (is (:success checked) (pr-str (:errors checked)))
      (binding [repl/*type-checking-enabled* (atom true)
                repl/*repl-var-types* (atom {})]
        (let [ctx (repl/init-repl-context)
              output (with-out-str
                       (repl/eval-code ctx code))]
          (is (not (str/includes? output "Error:")))
          (is (str/includes? output "5")))))))

(deftest safe-call-expression-generic-update-height-typechecks-test
  (testing "safe field access works in the generic update-height shape"
    (let [code "class Node [K, V]
create
  make() do
    this.left := nil
    this.right := nil
    this.height_value := 0
  end
feature
  left: ?Node[K, V]
  right: ?Node[K, V]
  height_value: Integer
  set_height(h: Integer) do
    this.height_value := h
  end
end

function height(node: ?Node[Integer, String]): Integer
do
  if node /= nil then
    result := node.height_value
  else
    result := 0
  end
end

function update_height(node: ?Node[Integer, String])
do
  node?.set_height(1 + height(node?.left).max(height(node?.right)))
end"
          checked (tc/type-check (p/ast code))]
      (is (:success checked) (pr-str (:errors checked))))))
