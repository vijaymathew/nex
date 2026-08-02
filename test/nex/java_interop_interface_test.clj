(ns nex.java-interop-interface-test
  "Phase 1 of docs/proposals/java-interop.md: a Nex class implementing a Java
   interface via `inherit`. Covers both the typechecker's conformance check
   and the interpreter's java.lang.reflect.Proxy bridge, which lets a real
   Java API call back into Nex through the interface."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- run [code]
  (let [ast (p/ast code)
        result (tc/type-check ast)]
    (when-not (:success result)
      (throw (ex-info "type check failed" result)))
    @(:output (interp/interpret ast))))

(deftest implements-runnable-and-is-callable-from-java-test
  (testing "a Nex class inheriting Runnable is a real Runnable a Java Thread can call back into"
    (let [output (run "import java.lang.Runnable
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
end")]
      (is (= ["\"ran\""] output)))))

(deftest implements-comparator-multi-call-round-trip-test
  (testing "java.util.Collections.sort repeatedly calls back into a Nex Comparator via the proxy"
    (let [output (run "import java.util.Comparator
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
end")]
      (is (= ["3" "2" "1"] output)))))

(deftest direct-nex-call-still-works-alongside-java-dispatch-test
  (testing "a method satisfying a Java interface is still an ordinary callable Nex method"
    (let [output (run "import java.lang.Runnable

class My_Task
  inherit
    Runnable
  feature
    run() do
      print(\"direct call\")
    end
end

let task: My_Task := create My_Task
task.run()")]
      (is (= ["\"direct call\""] output)))))

(deftest missing-interface-method-fails-typecheck-test
  (testing "a class inheriting Runnable without a matching run() fails typechecking, not silently at runtime"
    (let [ast (p/ast "import java.lang.Runnable

class My_Task
  inherit
    Runnable
  feature
    not_run() do
      print(\"wrong name\")
    end
end")
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"does not implement" (:message %)) (:errors result))))))

(deftest mutable-state-survives-repeated-proxy-dispatch-test
  (testing "field mutations from repeated calls through the Proxy are visible both across calls and to an ordinary Nex-side read afterward"
    (let [output (run "import java.awt.event.ActionListener
import java.awt.event.ActionEvent
import javax.swing.JButton

class Click_Counter
  inherit
    ActionListener
  create
    make() do
      this.count := 0
    end
  feature
    count: Integer

    actionPerformed(e: ActionEvent) do
      count := count + 1
    end
end

with \"java\" do
  let button: JButton := JButton.new(\"Click me\")
  let counter: Click_Counter := create Click_Counter.make()
  button.addActionListener(counter)

  button.doClick()
  print(counter.count)
  button.doClick()
  print(counter.count)
  button.doClick()
  print(counter.count)
end")]
      (is (= ["1" "2" "3"] output)))))

;; Extending a concrete Java class (Phase 2 of docs/proposals/java-interop.md)
;; has its own test file, test/nex/java_interop_extend_class_test.clj.
