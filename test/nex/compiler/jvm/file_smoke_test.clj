(ns nex.compiler.jvm.file-smoke-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.runtime :as runtime]))

(defn- delete-tree!
  [root]
  (doseq [f (reverse (file-seq (io/file root)))]
    (.delete f)))

(defn- invoke-main!
  [output-dir main-class]
  (let [url (.toURL (.toURI (io/file output-dir)))
        parent (.getClassLoader nex.compiler.jvm.runtime.NexReplState)]
    (with-open [loader (java.net.URLClassLoader. (into-array java.net.URL [url]) parent)]
      (let [^Class cls (.loadClass loader main-class)
            ^java.lang.reflect.Method main-method (.getMethod cls "main" (into-array Class [(class (into-array String []))]))]
        (binding [*out* (java.io.StringWriter.)]
          (.invoke main-method nil (object-array [(into-array String [])]))
          (str *out*))))))

(defn- run-jar!
  [jar-path]
  (let [proc (.exec (Runtime/getRuntime) (into-array String ["java" "-jar" jar-path]))]
    (.waitFor proc)
    {:exit (.exitValue proc)
     :out (slurp (.getInputStream proc))
     :err (slurp (.getErrorStream proc))}))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(deftest compile-jar-functions-and-control-flow-smoke-test
  (testing "compile-jar runs top-level functions, lets, and control flow end-to-end"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-smoke-core")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "function double(n: Integer): Integer
do
  result := n * 2
end

let y: Integer := when double(5) > 5 10 else 0 end
print(y)")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))]
          (is (= 0 exit) err)
          (is (= "10" (str/trim out))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-class-method-calls-generic-top-level-helpers-test
  (testing "class methods can call generic top-level helpers and compare Comparable generic values"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-smoke-generic-helpers")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Node [K -> Comparable]
create
  make(key: K) do
    this.key := key
    this.left := nil
  end
feature
  key: K
  left: ?Node[K]

  set_left(n: ?Node[K]) do
    this.left := n
  end
end

function before(a: K, b: K): Boolean
do
  result := a < b
end

function traverse(node: ?Node[K], result_arr: Array[K])
do
  if convert node to current: Node[K] then
    traverse(current.left, result_arr)
    result_arr.add(current.key)
  end
end

class Tree [K -> Comparable]
create
  make(root: ?Node[K]) do
    this.root := root
  end
feature
  root: ?Node[K]

  keys(): Array[K] do
    let result_arr: Array[K] := []
    traverse(root, result_arr)
    result := result_arr
  end
end

let root: Node[Integer] := create Node[Integer].make(5)
let tree: Tree[Integer] := create Tree[Integer].make(root)
print(before(3, 5))
print(tree.keys().length)")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))]
          (is (= 0 exit) err)
          (is (= "true\n1" (str/trim out))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-top-level-print-of-array-equality-smoke-test
  (testing "compiled top-level print can lower built-in calls whose arguments contain array equality"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-smoke-print-array-eq")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "let xs := [5, 7, 9]
print(xs = [5, 7, 9])")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))]
          (is (= 0 exit) err)
          (is (= "false" (str/trim out))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-invariant-helper-method-and-comparable-param-test
  (testing "compiled invariants can call helper methods and Comparable-typed params use builtin compare"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-smoke-invariant-helper")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "function before_key(key: Comparable, other: K): Boolean
do
  result := key.compare(other) < 0
end

class Box [K -> Comparable]
create
  make(value: K) do
    this.value := value
  end
feature
  value: K

  invariant_ok(): Boolean do
    result := true
  end
invariant
  valid: invariant_ok()
end

let box: Box[Integer] := create Box[Integer].make(5)
print(before_key(3, box.value))
print(box.value)")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))]
          (is (= 0 exit) err)
          (is (= "true\n5" (str/trim out))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-object-model-and-contracts-smoke-test
  (testing "compile-jar runs inheritance, super, contracts, old, and invariants end-to-end"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-smoke-oo")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Account
create
  with_balance(initial: Integer)
    require
      non_negative: initial >= 0
    do
      this.balance := initial
    ensure
      set: balance = initial
    end
feature
  balance: Integer

  deposit(n: Integer): Integer
    require
      amount_non_negative: n >= 0
    do
      this.balance := this.balance + n
      result := this.balance
    ensure
      updated: balance = old balance + n
      result_matches: result = balance
    end
invariant
  balance_ok: balance >= 0
end

class Premium inherit Account
create
  with_bonus(initial, bonus: Integer) do
    super.with_balance(initial)
    this.bonus := bonus
  end
feature
  bonus: Integer

  total(): Integer
  do
    result := super.deposit(bonus)
  end
end

let p: Premium := create Premium.with_bonus(40, 2)
print(p.total())")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))]
          (is (= 0 exit) err)
          (is (= "42" (str/trim out))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-closures-and-generics-smoke-test
  (testing "compile-jar runs captured closures, higher-order calls, and generic classes end-to-end"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-closures")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Box[T]
create
  with_value(v: T) do
    this.value := v
  end
feature
  value: T

  get: T
  do
    result := value
  end
end

function apply(f: Function, n: Integer): Any
do
  result := f(n)
end

function make_adder(base: Integer): Function
do
  result := fn (n: Integer): Integer do
    result := n + base
  end
end

let box: Box[String] := create Box[String].with_value(\"ok\")
let add2: Function := make_adder(2)
print(box.get)
print(apply(add2, 40))")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))]
          (is (= 0 exit) err)
          (is (= ["\"ok\"" "42"]
                 (remove str/blank? (str/split-lines out)))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-convert-to-generic-parameter-smoke-test
  (testing "compile-jar supports convert to a bare generic parameter inside compiled class methods"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-generic-convert")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Box[T]
create
  with_value(v: Any) do
    this.value := v
  end
feature
  value: Any

  typed_or(default: T): T
  do
    if convert value to current: T then
      result := current
    else
      result := default
    end
  end
end

let ok: Box[Integer] := create Box[Integer].with_value(7)
let fallback: Box[Integer] := create Box[Integer].with_value(\"oops\")
print(ok.typed_or(99))
print(fallback.typed_or(99))")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["7" "99"] output-lines)))
      (finally
        (when (.exists tmp-dir)
          (delete-tree! tmp-dir)))))))

(deftest compile-jar-convert-in-and-condition-smoke-test
  (testing "compile-jar supports convert bindings nested under and conditions"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-convert-and")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Node
create
  make(left: ?Node) do
    this.left := left
  end
feature
  left: ?Node
end

function is_attached(node: ?Node): Boolean do
  result := node /= nil
end

function check(node: Node)
do
  if is_attached(node.left) and convert node.left to left_child: Node then
    if is_attached(left_child.left) then
      print(\"nested\")
    else
      print(\"leaf\")
    end
  else
    print(\"empty\")
  end
end

let leaf := create Node.make(nil)
let root := create Node.make(leaf)
check(root)")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["\"leaf\""] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-generic-free-function-return-instantiation-smoke-test
  (testing "compile-jar instantiates bare generic free-function return types from call arguments"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-generic-free-return")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "function reduce[T](a: Array[T], f: Function, init: T): T do
  result := init
  across a as elem do
    result := f(result, elem)
  end
end

let r := reduce([1, 2, 3], fn(a: Integer, b: Integer): Integer do result := a + b end, 0)
print(r)")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["6"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-auto-initializes-builtin-collection-fields-test
  (testing "compiled constructors see empty defaults for non-detachable Array/Map/Set/String fields"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-field-defaults")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Holder
create
  make() do
    xs.add(1)
    ys.put(\"a\", 2)
  end
feature
  xs: Array[Integer]
  ys: Map[String, Integer]
  zs: Set[Integer]
  s: String
end

let h := create Holder.make()
print(h.xs.length)
print(h.ys.get(\"a\"))
print(h.zs.size)
print(h.s)")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["1" "2" "0" "\"\""] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-concurrency-and-select-smoke-test
  (testing "compile-jar runs spawn, channels, select, and await_any end-to-end"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-smoke-concurrency")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "let ch: Channel[String] := create Channel.with_capacity(1)
ch.send(\"ready\")
let worker: Task[Integer] := spawn do
  result := 7
end

select
  when ch.receive() as msg then
    print(msg)
  timeout 1000 then
    print(\"timeout\")
end

print(await_any([worker]))")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["\"ready\"" "7"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-import-and-intern-smoke-test
  (testing "compile-jar runs imported Java classes and interned Nex classes together"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-modules")
          main-file (io/file tmp-dir "main.nex")
          intern-file (io/file tmp-dir "A.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit intern-file "class A
feature
  name(): String
  do
    result := \"interned\"
  end
end")
        (spit main-file "import java.lang.StringBuilder
intern A

let sb: StringBuilder := create StringBuilder
sb.append(\"host\")

let a: A := create A
print(a.name())
print(sb.length())")
        (let [result (file/compile-jar (.getPath main-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["\"interned\"" "4"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-cursor-subclass-across-smoke-test
  (testing "compile-jar runs across over a value typed as Cursor when the runtime instance is a Cursor subclass"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-cursor")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class C inherit Cursor
feature
  x: Integer
  start do x := 0 end
  item: Integer do result := x end
  next do x := x + 1 end
  at_end: Boolean do result := x = 3 end
  cursor: Cursor do result := this end
end

let c: Cursor := create C
across c as i do print(i) end")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["0" "1" "2"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))
