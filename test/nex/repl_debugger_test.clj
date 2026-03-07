(ns nex.repl-debugger-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.debugger :as dbg]
            [nex.parser :as p]))

(deftest test-debug-should-pause-breakpoint
  (testing "Pauses when statement hit matches breakpoint"
    (is (true? (dbg/debug-should-pause?
                {:breakpoints {1 {:kind :hit :n 3}} :mode :continue}
                3
                0
                {}
                {:type :let})))
    (is (false? (dbg/debug-should-pause?
                 {:breakpoints {1 {:kind :hit :n 3}} :mode :continue}
                 2
                 0
                 {}
                 {:type :let})))))

(deftest test-debug-should-pause-step
  (testing "Step mode pauses on every statement"
    (is (true? (dbg/debug-should-pause?
                {:breakpoints {} :mode :step}
                1
                0
                {}
                {:type :assign})))
    (is (true? (dbg/debug-should-pause?
                {:breakpoints {} :mode :step}
                99
                5
                {}
                {:type :assign})))))

(deftest test-debug-should-pause-next
  (testing "Next mode pauses only when depth unwinds to target or shallower"
    (is (true? (dbg/debug-should-pause?
                {:breakpoints {} :mode :next :next-depth 1}
                10
                1
                {}
                {:type :call})))
    (is (true? (dbg/debug-should-pause?
                {:breakpoints {} :mode :next :next-depth 1}
                10
                0
                {}
                {:type :call})))
    (is (false? (dbg/debug-should-pause?
                 {:breakpoints {} :mode :next :next-depth 1}
                 10
                 2
                 {}
                 {:type :call})))))

(deftest test-debug-should-pause-finish
  (testing "Finish mode pauses when depth returns to caller"
    (is (true? (dbg/debug-should-pause?
                {:breakpoints {} :mode :finish :finish-depth 2}
                10
                1
                {}
                {:type :call})))
    (is (false? (dbg/debug-should-pause?
                 {:breakpoints {} :mode :finish :finish-depth 2}
                 10
                 2
                 {}
                 {:type :call})))))

(deftest test-breakpoint-spec-parse
  (testing "Parse source-oriented breakpoints"
    (is (= {:kind :cm :class "OrderService" :method "place"}
           (dbg/parse-breakpoint-spec "OrderService.place")))
    (is (= {:kind :cm-line :class "OrderService" :method "place" :line 42}
           (dbg/parse-breakpoint-spec "OrderService.place:42")))
    (is (= {:kind :file-line :source "examples/demo.nex" :line 10}
           (dbg/parse-breakpoint-spec "examples/demo.nex:10")))))

(deftest test-break-command-parse
  (testing "Parse conditional break command"
    (is (= {:spec {:kind :cm :class "A" :method "f"}
            :condition "x > 0"}
           (dbg/parse-break-command "A.f if x > 0")))
    (is (= {:spec {:kind :hit :n 10}}
           (dbg/parse-break-command "10")))))

(deftest test-breakpoint-hit-matching
  (testing "Class.method and Class.method:line breakpoints match runtime context"
    (let [ctx {:current-class-name "OrderService"
               :current-method-name "place"
               :debug-source "examples/demo.nex"}
          node {:type :assign :dbg/line 42}]
      (is (true? (dbg/breakpoint-hit? {:kind :cm :class "OrderService" :method "place"} 1 ctx node)))
      (is (true? (dbg/breakpoint-hit? {:kind :cm-line :class "OrderService" :method "place" :line 42} 1 ctx node)))
      (is (true? (dbg/breakpoint-hit? {:kind :file-line :source "examples/demo.nex" :line 42} 1 ctx node)))
      (is (false? (dbg/breakpoint-hit? {:kind :cm-line :class "OrderService" :method "place" :line 41} 1 ctx node))))))

(deftest test-ast-has-debug-line-info
  (testing "Walker attaches dbg line/column metadata on statement nodes"
    (let [ast (p/ast "class A
  feature
    f() do
      let x := 1
    end
end")
          stmt (-> ast :classes first :body first :members first :body first)]
      (is (= :let (:type stmt)))
      (is (integer? (:dbg/line stmt)))
      (is (integer? (:dbg/col stmt))))))
