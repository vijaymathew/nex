(ns nex.safe-call-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.repl :as repl]
            [nex.typechecker :as tc]))

(defn- eval-on-both-backends
  "Printed output of CODE on the compiled (whole-program JVM) and
   tree-walking-interpreter backends, via nex.eval/eval-file -- the same
   entry point the `nex` CLI itself uses to run a script, unlike
   nex.repl/eval-code's incremental per-cell compilation, which has its own,
   narrower eligibility check and can silently decline a construct (falling
   back to the interpreter) that whole-program compilation handles fine.
   Returns [compiled-output interpreted-output]."
  [code]
  (let [f (java.io.File/createTempFile "safe_call" ".nex")]
    (try
      (spit f code)
      [(with-out-str (e/eval-file (.getPath f) {}))
       (with-out-str (e/eval-file (.getPath f) {:interpret? true}))]
      (finally (.delete f)))))

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

(def ^:private box-class-code
  "class Box
create
  with_value(v: Integer) do
    value := v
  end
feature
  value: Integer
  get_value(): Integer do
    result := value
  end
end")

(deftest safe-call-compound-receiver-desugars-to-attached-test-when-test
  (testing "`?.` on a compound (non-identifier) receiver binds it once via an attached-test guard, instead of silently dropping the guard the way a bare `/= nil` comparison would for a receiver that isn't a plain variable"
    (let [ast (p/ast "class Node
feature
  value: Integer
  next: ?Node
end
class Box
feature
  head: ?Node
end
let b: Box := create Box
print(b.head?.value)")
          print-call (last (:statements ast))
          arg (-> print-call :args first)]
      (is (= :when (:type arg)))
      (is (= :attached-test (-> arg :condition :type)))
      (is (str/starts-with? (-> arg :condition :var-name) "__safe_receiver_"))
      (is (= :call (-> arg :condition :value :type)))
      (is (= "b" (-> arg :condition :value :target)))
      (is (= "head" (-> arg :condition :value :method)))
      (is (= :call (-> arg :consequent :type)))
      (is (= (-> arg :condition :var-name) (-> arg :consequent :target)))
      (is (= "value" (-> arg :consequent :method)))
      (is (= :nil (-> arg :alternative :type))))))

(deftest safe-call-chained-compound-receiver-desugars-to-and-chain-test
  (testing "`a?.b?.c` folds into a flat `and`-chain of attached-tests rather than nesting a :when inside an attached-test's own :value -- the latter shape type-checks fine but crashes the JVM backend at runtime (lower-attached-test-expression allocates its own scratch local only after choosing its VALUE's lowering env, so a VALUE that itself needs a fresh local, as any nested :when does, collides with it)"
    (let [ast (p/ast "class Node
feature
  value: Integer
  next: ?Node
end
let n: Node := create Node
print(n.next?.next?.value)")
          print-call (last (:statements ast))
          arg (-> print-call :args first)
          condition (:condition arg)]
      (is (= :when (:type arg)))
      (is (= :binary (:type condition)))
      (is (= "and" (:operator condition)))
      (is (= :attached-test (-> condition :left :type)))
      (is (= :attached-test (-> condition :right :type)))
      ;; The right guard's own VALUE reads the left guard's binding by name
      ;; -- a plain call, never a nested :when.
      (is (= :call (-> condition :right :value :type)))
      (is (= (-> condition :left :var-name) (-> condition :right :value :target)))
      (is (= "next" (-> condition :right :value :method)))
      (is (= (-> condition :right :var-name) (-> arg :consequent :target)))
      (is (= "value" (-> arg :consequent :method)))
      (is (= :nil (-> arg :alternative :type))))))

(def ^:private compound-safe-nav-code
  "class Node
create
  make(v: Integer) do
    value := v
  end
feature
  value: Integer
  next: ?Node
  link(n: Node) do
    this.next := n
  end
end

class Box
create
  make(n: ?Node) do
    head := n
  end
feature
  head: ?Node
end

let n1: Node := create Node.make(1)
let n2: Node := create Node.make(2)
n1.link(n2)
let full: Box := create Box.make(n1)
let empty: Box := create Box.make(nil)
print(full.head?.value)
print(empty.head?.value)
print(full.head?.next?.value)
print(n2.next?.next?.value)")

(deftest safe-call-compound-and-chained-receiver-evaluates-test
  (testing "`?.` on a compound receiver, including a chained `a?.b?.c`, type-checks and evaluates correctly on both backends -- regression coverage for: (1) a compound receiver's guard being silently dropped, rejected at typecheck time as an unguarded detachable access; (2) fixing that exposing a JVM-backend gap where a nil-producing safe-nav's result inferred as a bare (non-detachable) Integer instead of ?Integer, crashing on unboxing null at runtime"
    (let [checked (tc/type-check (p/ast compound-safe-nav-code))]
      (is (:success checked) (pr-str (:errors checked))))
    (let [[compiled interpreted] (eval-on-both-backends compound-safe-nav-code)]
      (doseq [[backend output] [["compiled" compiled] ["interpreted" interpreted]]]
        (is (not (str/includes? output "Error")) (str backend ": " output))
        (is (= "1\nnil\n2\nnil\n" output) (str backend ": " (pr-str output)))))))

(deftest and-chain-attached-test-guard-can-reference-earlier-binding-test
  (testing "a later `and` conjunct's own guarded VALUE expression can reference an earlier conjunct's attached-test binding, not just the branch body -- this is what the chained `?.` desugaring above relies on (see safe-call-chained-compound-receiver-desugars-to-and-chain-test), and matches the documented behavior of `?p.age as a and ?q.age as b` guards more generally"
    (let [code "class Node
create
  make(v: Integer) do
    value := v
  end
feature
  value: Integer
  next: ?Node
end
let n: Node := create Node.make(1)
if ?n.next as t1 and ?t1.next as t2 then
  print(t2.value)
else
  print(-1)
end"
          checked (tc/type-check (p/ast code))]
      (is (:success checked) (pr-str (:errors checked))))
    (let [[compiled interpreted] (eval-on-both-backends
                                   "class Node
create
  make(v: Integer) do
    value := v
  end
feature
  value: Integer
  next: ?Node
end
let n: Node := create Node.make(1)
if ?n.next as t1 and ?t1.next as t2 then
  print(t2.value)
else
  print(-1)
end")]
      (doseq [[backend output] [["compiled" compiled] ["interpreted" interpreted]]]
        (is (= "-1\n" output) (str backend ": " (pr-str output)))))))

(deftest bare-safe-call-expression-echoes-value-test
  (testing "a bare safe call typed at the REPL echoes the receiver's value"
    (doseq [backend [:compiled :interpreter]]
      (binding [repl/*repl-backend* (atom backend)]
        (let [ctx (repl/init-repl-context)]
          (with-out-str (repl/eval-code ctx box-class-code))
          (with-out-str (repl/eval-code ctx "let b: ?Box := create Box.with_value(10)"))
          (let [present (with-out-str (repl/eval-code ctx "b?.get_value"))]
            (is (str/includes? present "10")
                (str "backend " backend ": " (pr-str present))))
          ;; A nil receiver short-circuits and echoes nothing.
          (with-out-str (repl/eval-code ctx "let n: ?Box := nil"))
          (let [absent (with-out-str (repl/eval-code ctx "n?.get_value"))]
            (is (not (str/includes? absent "10"))
                (str "backend " backend ": " (pr-str absent)))))))))
