(ns nex.direct-free-function-call-test
  "In a compiled file, a call to one of the program's own free functions is a
   direct INVOKESTATIC of the function's typed static method (`NAME$t`), with
   unboxed arguments and result. It used to look the function up by name in
   the program's function table and call it through
   java.lang.reflect.Method/invoke on every call, boxing every argument into
   two Object[] arrays: a recursive fib(40) took ~8 s against ~0.5 s now.
   The REPL keeps the lookup by name, since a later input may redefine a
   function. See nex.lower/*direct-free-functions* and
   nex.compiler.jvm.emit/emit-static-typed-fn-methods!."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.file :as file]
            [nex.eval :as e]
            [nex.parser :as p])
  (:import [org.objectweb.asm ClassReader ClassVisitor MethodVisitor Opcodes]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned."
  [code]
  (let [f (java.io.File/createTempFile "direct_free_fn" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})]
        (is (= (run {:interpret? true}) compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(defn- method-calls
  "{method name -> [\"owner.name\" ...]} of the method calls each method of
   the class in BYTES makes."
  [^bytes bytes]
  (let [calls (atom {})]
    (.accept (ClassReader. bytes)
             (proxy [ClassVisitor] [Opcodes/ASM9]
               (visitMethod [_access mname _desc _sig _exceptions]
                 (swap! calls assoc mname [])
                 (proxy [MethodVisitor] [Opcodes/ASM9]
                   (visitMethodInsn [_opcode owner name _desc _itf]
                     (swap! calls update mname conj (str owner "." name))))))
             0)
    @calls))

(defn- program-class-calls
  [code]
  (let [{:keys [program-class classes]} (file/compile-ast "direct_free_fn.nex" (p/ast code) {})]
    (method-calls (get classes program-class))))

(defn- reflective?
  [calls]
  (some #(= "java/lang/reflect/Method.invoke" %) calls))

(deftest calls-are-direct-test
  (let [calls (program-class-calls "function fib(n: Integer): Integer do
  if n < 2 then result := n else result := fib(n - 1) + fib(n - 2) end
end
print(fib(10))")
        body (get calls "__method_fib$arity1$t")]
    (testing "the function has a typed static method holding its body"
      (is (some? body)))
    (testing "a recursive call is a direct call of that method, not reflection"
      (is (= 2 (count (filter #(str/ends-with? % ".__method_fib$arity1$t") body))))
      (is (not (reflective? body))))
    (testing "top-level code calls it directly too"
      (is (not (reflective? (get calls "eval")))))
    (testing "the boxed method the function table registers delegates to it"
      (is (some #(str/ends-with? % ".__method_fib$arity1$t") (get calls "__method_fib$arity1"))))))

(deftest argument-and-result-types-test
  (testing "Integer, Real, Boolean, Char, String, detachable, Void and generic functions"
    (is (= ["55" "7.5" "true" "#b" "\"ab\"" "\"none\"" "\"some\"" "\"side effect\"" "3" "\"x\""]
           (both "function fib(n: Integer): Integer do
  if n < 2 then result := n else result := fib(n - 1) + fib(n - 2) end
end
function half(x: Real): Real do result := x / 2.0 end
function flip(b: Boolean): Boolean do result := not b end
function next_char(c: Char): Char do result := #b end
function join(a, b: String): String do result := a + b end
function describe(s: ?String): String do
  if s = nil then result := \"none\" else result := \"some\" end
end
function say(s: String) do print(s) end
function first[T](xs: Array[T]): T do result := xs.get(0) end
print(fib(10))
print(half(15.0))
print(flip(false))
print(next_char(#a))
print(join(\"a\", \"b\"))
print(describe(nil))
print(describe(\"x\"))
say(\"side effect\")
print(first([3, 4]))
print(first([\"x\"]))")))))

(deftest calls-from-classes-closures-and-mutual-recursion-test
  (is (= ["true" "false" "12" "30" "[2, 4]"]
         (both "declare function is_odd(n: Integer): Boolean
function is_even(n: Integer): Boolean do
  if n = 0 then result := true else result := is_odd(n - 1) end
end
function is_odd(n: Integer): Boolean do
  if n = 0 then result := false else result := is_even(n - 1) end
end
function triple(n: Integer): Integer do result := 3 * n end
class Calc
  feature
    run(n: Integer): Integer do result := triple(n) end
end
print(is_even(10))
print(is_odd(10))
print((create Calc).run(4))
let k := 10
let add_k := fn (x: Integer): Integer do result := triple(x) + k - 10 end
print(add_k(10))
print([1, 2].map(fn (x) => triple(x) - x))"))))

(deftest exceptions-and-contracts-pass-through-test
  (testing "a raise and a contract violation reach rescue as themselves"
    (is (= ["\"caught: boom\"" "\"caught: Precondition violation: positive\""]
           (both "function fail(n: Integer): Integer do
  raise \"boom\"
end
function checked(n: Integer): Integer
  require positive: n > 0
  do result := n end
function try_fail(): String do
  result := \"no\"
  do
    let x := fail(1)
  rescue
    result := \"caught: \" + exception.to_string
  end
end
function try_checked(): String do
  result := \"no\"
  do
    let x := checked(-1)
  rescue
    result := \"caught: \" + exception.to_string
  end
end
print(try_fail())
print(try_checked())")))))
