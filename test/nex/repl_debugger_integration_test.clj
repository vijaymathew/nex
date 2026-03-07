(ns nex.repl-debugger-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nex.repl :as repl]
            [nex.debugger :as dbg]))

(defn- scripted-reader [inputs]
  (let [q (atom (vec inputs))]
    (fn [_prompt]
      (let [line (first @q)]
        (swap! q #(vec (rest %)))
        line))))

(use-fixtures
  :each
  (fn [f]
    (dbg/set-enabled! true)
    (dbg/clear-breakpoints!)
    (dbg/set-break-on! :exception false)
    (dbg/set-break-on! :contract false)
    (dbg/reset-run-state!)
    (f)
    (dbg/set-enabled! false)
    (dbg/clear-breakpoints!)
    (dbg/set-break-on! :exception false)
    (dbg/set-break-on! :contract false)
    (dbg/reset-run-state!)))

(deftest debugger-where-shows-full-stack
  (testing "Debugger :where prints nested stack frames"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class A
  feature
    f() do
      this.g()
    end
    g() do
      let z: Integer := 1
    end
end")
          _ (dbg/add-breakpoint! {:kind :cm :class "A" :method "g"})
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":where" ":c"])]
                  (repl/eval-code ctx "let a: A := create A
a.f()")))]
      (is (.contains out "Paused at statement #"))
      (is (.contains out "stack:"))
      (is (.contains out "A.f"))
      (is (.contains out "A.g")))))

(deftest debugger-break-on-contract
  (testing "break-on contract pauses on contract violations"
    (let [ctx (repl/init-repl-context)
          _ (dbg/set-break-on! :contract true)
          _ (repl/eval-code ctx "class C
  feature
    f()
    require
      bad: false
    do
    end
end")
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":where" ":c"])]
                  (repl/eval-code ctx "let c: C := create C
c.f()")))]
      (is (.contains out "Paused on contract violation.")))))

(deftest debugger-break-on-exception
  (testing "break-on exception pauses on runtime exceptions"
    (let [ctx (repl/init-repl-context)
          _ (dbg/set-break-on! :exception true)
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":where" ":c"])]
                  (repl/eval-code ctx "1 / 0")))]
      (is (.contains out "Paused on exception.")))))

(deftest debugger-conditional-breakpoint
  (testing "Conditional breakpoint pauses only when condition evaluates true"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class D
  feature
    run() do
      let x: Integer := 0
      x := x + 1
      x := x + 1
    end
end")
          _ (dbg/add-breakpoint! {:kind :cm :class "D" :method "run" :condition "x > 0"})
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":where" ":c"])]
                  (repl/eval-code ctx "let d: D := create D
d.run()")))]
      (is (.contains out "Paused at statement #"))
      (is (.contains out "D.run")))))

(deftest debugger-clearbreak-by-id
  (testing "Breakpoints can be removed by id"
    (let [id1 (dbg/add-breakpoint! {:kind :hit :n 1})
          id2 (dbg/add-breakpoint! {:kind :hit :n 2})]
      (is (= 2 (count (dbg/breakpoint-entries))))
      (is (= 1 (dbg/remove-breakpoint! id1)))
      (is (= 1 (count (dbg/breakpoint-entries))))
      (is (= id2 (ffirst (dbg/breakpoint-entries)))))))
