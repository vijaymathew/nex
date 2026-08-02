(ns nex.java-interop-extend-class-test
  "Phase 2 of docs/proposals/java-interop.md: a Nex class extending a
   concrete Java class via `inherit`. Typechecker validation and the
   interpreter's clear rejection (Phase 2 is compiled-backend-only — see
   test/nex/compiler/jvm/file_smoke_test.clj for the compiled-backend
   end-to-end coverage)."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- type-check [code]
  (tc/type-check (p/ast code)))

(deftest extends-thread-with-implicit-no-arg-super-typechecks-test
  (testing "extending Thread with no explicit super.new(...) typechecks (Thread has a public no-arg constructor)"
    (let [result (type-check "import java.lang.Thread

class My_Thread
  inherit
    Thread
  feature
    run() do
      print(\"running\")
    end
end")]
      (is (:success result) (pr-str (:errors result))))))

(deftest calling-inherited-non-overridden-java-method-typechecks-test
  (testing "t.start()/t.join() on a class extending Thread typecheck as inherited Java methods"
    (let [result (type-check "import java.lang.Thread

class My_Thread
  inherit
    Thread
  feature
    run() do
      print(\"running\")
    end
end

with \"java\" do
  let t: My_Thread := create My_Thread
  t.setName(\"worker\")
  t.start()
  t.join()
end")]
      (is (:success result) (pr-str (:errors result))))))

(deftest missing-abstract-method-fails-typecheck-test
  (testing "extending an abstract Java class without providing its abstract method fails, same precision as Phase 1 interfaces"
    (let [result (type-check "import java.lang.Number

class My_Number
  inherit
    Number
end")]
      (is (not (:success result)))
      (is (some #(re-find #"does not implement" (:message %)) (:errors result))))))

(deftest extending-two-java-classes-fails-typecheck-test
  (testing "the JVM allows extending only one class"
    (let [result (type-check "import java.lang.Thread
import java.lang.Exception

class Weird
  inherit
    Thread,
    Exception
feature
  run() do
    print(\"x\")
  end
end")]
      (is (not (:success result)))
      (is (some #(re-find #"more than one concrete Java class" (:message %)) (:errors result))))))

(deftest super-new-must-be-first-statement-test
  (testing "super.new(...) after another statement fails typechecking with a clear message"
    (let [result (type-check "import java.lang.Thread

class My_Thread
  inherit
    Thread
create
  make()
  do
    let x: Integer := 1
    super.new()
  end
feature
  run() do
    print(\"x\")
  end
end")]
      (is (not (:success result)))
      (is (some #(re-find #"must be the first statement" (:message %)) (:errors result))))))

(deftest super-new-wrong-arity-fails-typecheck-test
  (testing "super.new(...) with an arity no public Thread constructor has fails typechecking"
    (let [result (type-check "import java.lang.Thread

class My_Thread
  inherit
    Thread
create
  make(a: Integer, b: Integer, c: Integer, d: Integer, e: Integer, f: Integer)
  do
    super.new(a, b, c, d, e, f)
  end
feature
  run() do
    print(1)
  end
end")]
      (is (not (:success result)))
      (is (some #(re-find #"no public constructor of that arity" (:message %)) (:errors result))))))

(deftest interpreter-declines-extending-java-class-test
  (testing "the interpreter has no Proxy-equivalent for extending a concrete class, and says so clearly"
    (let [ast (p/ast "import java.lang.Thread

class My_Thread
  inherit
    Thread
  feature
    run() do
      print(\"running\")
    end
end")]
      ;; register-class alone (as Phase 1's inheritance_runtime_test does for
      ;; pure-Nex cases) doesn't see imports — those are only processed while
      ;; walking the whole program AST. Run the full AST through the
      ;; interpreter so imports are registered before the class is, matching
      ;; how a real program actually reaches this check.
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"does not support extending a Java class"
                            (interp/interpret ast))))))
