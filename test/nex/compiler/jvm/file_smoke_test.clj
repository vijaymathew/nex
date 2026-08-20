(ns nex.compiler.jvm.file-smoke-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.runtime :as runtime]
            [nex.parser :as p]))

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
  ([jar-path] (run-jar! jar-path []))
  ([jar-path program-args]
   (let [proc (.exec (Runtime/getRuntime)
                     (into-array String (concat ["java" "-jar" jar-path] program-args)))]
     (.waitFor proc)
     {:exit (.exitValue proc)
      :out (slurp (.getInputStream proc))
      :err (slurp (.getErrorStream proc))})))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(deftest compile-ast-rejects-duplicate-name-and-arity-methods-test
  (testing "lowering rejects same-name/same-arity routines even with the typechecker skipped,
            so bytecode generation never emits a class that fails at defineClass"
    (let [src (str "class A feature "
                   "f(a: Integer): Integer do result := 1 + a end "
                   "f(s: String): String do result := s end end")
          ast (p/ast src)
          ex (try (file/compile-ast "dup.nex" ast {:skip-type-check true})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "duplicate method must raise rather than emit an invalid class")
      (is (str/includes? (.getMessage ex) "Duplicate routine 'f' taking 1 argument")))))

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

let y: Integer := when double(5) > 5 then 10 else 0 end
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

function before[K -> Comparable](a: K, b: K): Boolean
do
  result := a < b
end

function traverse[K -> Comparable](node: ?Node[K], result_arr: Array[K])
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
          (is (= "true" (str/trim out))))
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
print(r)
print(r = 6)")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["6" "true"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-identity-equality-smoke-test
  (testing "compile-jar supports identity equality operators"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-identity-eq")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "let a := [1]
let b := a
let c := [1]
print(a == b)
print(a == c)
print(a != c)
print(1 == 1)")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["true" "false" "true" "true"] output-lines)))
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

(deftest compile-jar-imported-java-class-static-field-access-smoke-test
  (testing "compile-jar reads a static field on an imported Java class (no-parens class-target access)"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-static-field")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit main-file "import java.lang.Math
import javax.swing.SwingConstants

with \"java\" do
  print(Math.PI)
  print(SwingConstants.CENTER)
end")
        (let [result (file/compile-jar (.getPath main-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= [(str Math/PI) "0"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-implements-runnable-and-is-callable-from-java-test
  (testing "a compiled Nex class inheriting Runnable really `implements` it and a real Java Thread calls back into it"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-runnable")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit main-file "import java.lang.Runnable
import java.lang.Thread

class My_Task
  inherit
    Runnable
  feature
    run() do
      print(\"ran\")
    end
end

with \"java\" do
  let task: My_Task := create My_Task
  let t: Thread := Thread.new(task)
  t.start()
  t.join()
end")
        (let [result (file/compile-jar (.getPath main-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["\"ran\""] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-implements-comparator-multi-call-round-trip-test
  (testing "java.util.Collections.sort repeatedly calls back into a compiled Nex Comparator, with correct int-return unboxing/narrowing"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-comparator")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit main-file "import java.util.Comparator
import java.util.ArrayList
import java.util.Collections

class Reverse_Order
  inherit
    Comparator
  feature
    compare(a: Integer, b: Integer): Integer do
      result := b - a
    end
end

with \"java\" do
  let list: ArrayList := create ArrayList
  list.add(3)
  list.add(1)
  list.add(2)
  let cmp: Reverse_Order := create Reverse_Order
  Collections.sort(list, cmp)
  print(list.get(0))
  print(list.get(1))
  print(list.get(2))
end")
        (let [result (file/compile-jar (.getPath main-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["3" "2" "1"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-imported-java-typed-field-smoke-test
  (testing "a field typed as an imported Java class (not java.lang.*) compiles with a correctly qualified descriptor, on a plain class and on one that also implements a Java interface"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-java-typed-field")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit main-file "import java.lang.StringBuilder
import java.awt.event.ActionListener
import java.awt.event.ActionEvent

class Builder_Holder
  create
    make() do
      with \"java\" do
        this.sb := StringBuilder.new()
      end
    end
  feature
    sb: StringBuilder

    append(s: String) do
      with \"java\" do
        sb.append(s)
      end
    end
end

class Click_Counter
  inherit
    ActionListener
  create
    make(sb: StringBuilder) do
      this.sb := sb
      this.count := 0
    end
  feature
    sb: StringBuilder
    count: Integer

    actionPerformed(e: ActionEvent) do
      count := count + 1
    end
end

with \"java\" do
  let holder: Builder_Holder := create Builder_Holder.make()
  holder.append(\"a\")
  holder.append(\"b\")
  print(\"result=\" + holder.sb.toString())

  let counter: Click_Counter := create Click_Counter.make(holder.sb)
  print(\"count=\" + counter.count)
end")
        (let [result (file/compile-jar (.getPath main-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["\"result=ab\"" "\"count=0\""] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-extends-thread-override-and-inherited-call-smoke-test
  (testing "a compiled Nex class extending Thread is a real subclass: overriding run() is what a real Thread.start() calls, and inherited (non-overridden) methods like start()/join() are callable from Nex"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-extend-thread")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit main-file "import java.lang.Thread

class My_Thread
  inherit
    Thread
  feature
    run() do
      super.run()
      print(\"ran\")
    end
end

with \"java\" do
  let t: My_Thread := create My_Thread
  t.setName(\"worker\")
  t.start()
  t.join()
  print(t.getName())
end")
        (let [result (file/compile-jar (.getPath main-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["\"ran\"" "\"worker\""] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-extends-java-class-with-args-super-new-fails-with-clear-error-test
  (testing "super.new(args) with real arguments is a named, honest gap on the compiled backend, not silent wrong behavior"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-extend-args-gap")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit main-file "import java.lang.Thread

class My_Thread
  inherit
    Thread
create
  make(name: String)
  do
    super.new(name)
  end
feature
  run() do
    print(\"ran\")
  end
end")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"not supported yet"
                              (file/compile-jar (.getPath main-file) (.getPath out-dir) {})))
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

(deftest compile-jar-user-defined-method-overload-by-arity-smoke-test
  (testing "compile-jar dispatches user-defined methods with the same name by arity"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-method-arity-overload")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Greeter
feature
  greet(): String
  do
    result := \"hello\"
  end

  greet(name: String): String
  do
    result := \"hello, \" + name
  end
end

let g: Greeter := create Greeter
print(g.greet())
print(g.greet(\"Ada\"))")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["\"hello\"" "\"hello, Ada\""] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-array-sort-with-comparator-smoke-test
  (testing "compile-jar supports Array.sort(compareFn)"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-array-sort-comparefn")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "let xs: Array[Integer] := [10, 2, 3]
let sorted := xs.sort(fn(a: Integer, b: Integer): Integer do
  result := b - a
end)
print(sorted)")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["[10, 3, 2]"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-function-type-alias-call-smoke-test
  (testing "compile-jar lowers calls through a function-type alias (declare type)"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-fn-alias")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "declare type IntFn = Function(x: Integer): Integer
declare type Pred = Function(x: Integer): Boolean

function apply_twice(f: IntFn, x: Integer): Integer do
  result := f(f(x))
end

let inc: IntFn := fn (x: Integer): Integer do result := x + 1 end
let is_even: Pred := fn (x: Integer): Boolean do result := x % 2 = 0 end

print(apply_twice(inc, 10))
if is_even(4) then print(1) else print(0) end")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["12" "1"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-collections-clone-and-tostring-smoke-test
  (testing "compile-jar supports Set.from_array, set printing, Map.clone independence, and object to_string"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-collections")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Point
  feature
    x: Integer
    y: Integer
    to_string(): String do result := \"P(\" + x + \", \" + y + \")\" end
  create make(a: Integer, b: Integer) do x := a y := b end
end

let s: Set[String] := create Set[String].from_array([\"a\", \"b\", \"a\"])
print(s.size)
print(s)

let m: Map[String, Integer] := {}
m.put(\"k\", 1)
let c: Map[String, Integer] := m.clone()
c.put(\"j\", 2)
print(m.contains_key(\"j\"))
print(c.contains_key(\"j\"))

let p: Point := create Point.make(3, 4)
print(p)")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["2" "#{\"a\", \"b\"}" "false" "true" "P(3, 4)"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest closure-that-captures-and-constructs-smoke-test
  (testing "a fn closure that BOTH captures an enclosing binding AND runs `create`
            must compile and execute -- currently fails at runtime with
            'Undefined compiled field' because the constructed object's fields are
            reflected against the wrong class inside the captured closure.

            Capture-without-construct (e.g. `compose`, an integer adder) works, and
            construct-without-capture works; only the combination is broken. This is
            a known-failing regression guard -- it should pass once the closure
            codegen resolves the constructed object's own class for field access."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-closure-capture-construct")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Box
  feature v: Integer
  create make(n: Integer) do v := n end
end

declare type Maker = Function(): Box

function make_maker(n: Integer): Maker do
  result := fn (): Box do result := create Box.make(n) end
end

let m: Maker := make_maker(7)
print(m().v)")
        ;; Run a real standalone jar (as `nex file.nex` does) rather than loading
        ;; the class in-process: the closure path deoptimizes to the interpreter,
        ;; and an in-process URLClassLoader would cross runtime classloaders. The
        ;; defect surfaced at run time as "Undefined compiled field: v" because the
        ;; interpreter-produced object's fields were read via JVM reflection.
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))]
          (is (= 0 exit) err)
          (is (= "7" (str/trim out))
              (str "closure capturing `n` and constructing a Box should print the "
                   "Box's field; stderr: " err)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-method-call-on-generic-type-param-field-as-nested-match-scrutinee-test
  (testing "a method call on a generic type-parameter field (`s.value` where
            `Some[T].value: T`) used as a nested-match scrutinee must lower and run.
            The match binding `Some as s` has to carry the subject `Wrap[Box]`'s
            type argument onto `s` so `s.value` resolves to `Box` (not the erased
            `T`); otherwise the compiled backend fails lowering with 'Unable to
            infer expression type'. Regression guard for that fix -- the whole-
            program path throws on lowering failure rather than deoptimizing, so a
            clean run here proves the program lowered."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-generic-nested-match")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "union Color
  Red
  Blue(shade: String)
end

class Box
  create make() do end
  feature pick(): Color do
    result := create Blue.make(\"navy\")
  end
end

union Wrap[T]
  Some(value: T)
  None
end

let w: Wrap[Box] := create Some[Box].make(create Box.make())
match w of
  Some as s then
    match s.value.pick() of
      Blue as b then
        print(\"blue \" + b.shade)
      else
        print(\"other\")
    end
  None then
    print(\"none\")
end")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))]
          (is (= 0 exit) err)
          ;; `print` of a String renders it quoted, matching --interpret.
          (is (= "\"blue navy\"" (str/trim out))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-inherited-generic-members-through-inherit-clause-test
  (testing "members inherited through a renamed/reordered/instantiated inherit clause compile and link"
    ;; An inherited member's types are declared in the *parent's* generic
    ;; parameters. Resolving them against the heir's own parameters left the
    ;; parent's `A` unbound, and it was emitted as a phantom class named "A", so
    ;; the program failed to link (NoClassDefFoundError) at run time.
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-file-smoke-generic-inherit")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Draft
create
  make() do end
feature
  label(): String do
    result := \"draft\"
  end
end

class Note
create
  make() do end
end

class Pair [A, B]
create
  make(a: A, b: B) do
    first := a
    second := b
  end
feature
  first: A
  second: B

  get_first(): A do
    result := first
  end
end

-- renames AND reorders its parent's parameters: Swapped[X, Y] IS a Pair[Y, X]
class Swapped [X, Y] inherit Pair[Y, X]
create
  make(y: Y, x: X) do
    Pair.make(y, x)
  end
end

-- non-generic heir: the parent's arguments are fixed in the inherit clause
class Fixed inherit Pair[Draft, Note]
create
  make() do
    Pair.make(create Draft.make(), create Note.make())
  end
feature
  peek(): String do
    let d: Draft := first
    result := d.label()
  end
end

let s: Swapped[Note, Draft] := create Swapped[Note, Draft].make(create Draft.make(), create Note.make())
let d: Draft := s.get_first()
print(d.label())

let f: Fixed := create Fixed.make()
print(f.peek())")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))]
          (is (= 0 exit) err)
          (is (= ["\"draft\"" "\"draft\""] (str/split-lines (str/trim out)))))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-process-command-line-returns-real-argv-test
  (testing "Process.command_line() on a standalone jar returns the program's own argv, not the JVM's launch flags"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-argv")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit main-file "let proc := create Process
let args := proc.command_line()
print(args.length())
let i := 0
from let j := 0 until j >= args.length() do
  print(args.get(j))
  j := j + 1
end")
        (let [result (file/compile-jar (.getPath main-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result) ["hello" "world" "42"])
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["3" "\"hello\"" "\"world\"" "\"42\""] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-http-client-get-returns-real-response-test
  (testing "net/Http_Client.get() on the compiled backend returns the server's real status/body/headers, not a silently-defaulted 0/empty object"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-http-client")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "out")
          port (free-port)
          server (com.sun.net.httpserver.HttpServer/create
                  (java.net.InetSocketAddress. "127.0.0.1" port) 0)]
      (try
        (.mkdirs tmp-dir)
        (.createContext server "/hello"
                        (reify com.sun.net.httpserver.HttpHandler
                          (handle [_ exchange]
                            (let [body (.getBytes "hello from server" java.nio.charset.StandardCharsets/UTF_8)]
                              (.add (.getResponseHeaders exchange) "Content-Type" "text/plain")
                              (.sendResponseHeaders exchange 200 (alength body))
                              (with-open [os (.getResponseBody exchange)]
                                (.write os body))))))
        (.start server)
        (spit main-file (str "intern net/Http_Client

let client := create Http_Client.make()
let response := client.get(\"http://127.0.0.1:" port "/hello\")
print(response.status())
print(response.body())
print(response.headers().get(\"content-type\"))"))
        (let [result (file/compile-jar (.getPath main-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["200" "\"hello from server\"" "\"text/plain\""] output-lines)))
        (finally
          (.stop server 0)
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-json-parse-convert-map-get-test
  (testing "method calls on a `convert ... to Map[...]` result from data/Json.parse(...) work on the compiled backend"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-json")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit main-file "intern data/Json

let j := create Json.make()
let raw := j.parse(\"{\\\"a\\\": 1}\")
if convert raw to obj:Map[String, Any] then
  print(obj.get(\"a\"))
else
  print(\"convert failed\")
end")
        (let [result (file/compile-jar (.getPath main-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["1"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-reassigned-function-param-across-branches-smoke-test
  (testing "reassigning a Function(...)-typed parameter to a *different* anonymous-function
            literal in each branch of an if/else, then calling it, must compile and run.

            Previously failed with 'internal error in the compiled backend: Type
            .../AnonymousFunction_2 not present' -- ASM's COMPUTE_FRAMES needs the common
            superclass of the two distinct anonymous-function classes merging into one local
            at the join point after the if/else, and its default algorithm resolves that via
            Class.forName, which cannot find a class this very compile is still emitting
            bytecode for (neither class is loaded on any classloader yet). Regression guard
            for the fix: `nex.compiler.jvm.emit/make-class-writer` resolves the common
            superclass locally (via `known-supers`, built in `nex.compiler.jvm.file/compile-ast`
            from the program's own class metadata) before falling back to ASM's default."
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-reassigned-fn-branches")
          nex-file (io/file tmp-dir "app.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "declare type Transformer = Function(Integer): Integer

function apply(t: Transformer, cond: Boolean): Integer
do
  if cond then
    t := fn(x): Integer do result := x + 1 end
  else
    t := fn(x): Integer do result := x - 1 end
  end
  result := t(10)
end

let t0: Transformer := fn(x): Integer do result := x * 2 end
print(apply(t0, true))
print(apply(t0, false))")
        (let [result (file/compile-jar (.getPath nex-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["11" "9"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))

(deftest compile-jar-json-parse-declared-map-let-test
  (testing "a `let root: Map[...] := json.parse(...)` binding (no explicit convert) works on the compiled backend, including a nested Map[...]-typed field read"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-jvm-jar-smoke-json-let")
          main-file (io/file tmp-dir "main.nex")
          out-dir (io/file tmp-dir "out")]
      (try
        (.mkdirs tmp-dir)
        (spit main-file "intern data/Json

let json: Json := create Json.make()
let root: Map[String, Any] := json.parse(\"{\\\"name\\\":\\\"nex\\\",\\\"meta\\\":{\\\"ok\\\":true}}\")
let meta: Map[String, Any] := root.get(\"meta\")
print(root.get(\"name\"))
print(meta.get(\"ok\"))")
        (let [result (file/compile-jar (.getPath main-file) (.getPath out-dir) {})
              {:keys [exit out err]} (run-jar! (:jar result))
              output-lines (remove str/blank? (str/split-lines out))]
          (is (= 0 exit) err)
          (is (= ["\"nex\"" "true"] output-lines)))
        (finally
          (when (.exists tmp-dir)
            (delete-tree! tmp-dir)))))))
