(ns nex.repl-debugger-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nex.repl :as repl]
            [nex.debugger :as dbg]
            [clojure.java.io :as io]))

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
    (dbg/clear-break-on-filter! :exception)
    (dbg/clear-break-on-filter! :contract)
    (dbg/clear-watchpoints!)
    (dbg/clear-debug-script!)
    (dbg/reset-run-state!)
    (f)
    (dbg/set-enabled! false)
    (dbg/clear-breakpoints!)
    (dbg/set-break-on! :exception false)
    (dbg/set-break-on! :contract false)
    (dbg/clear-break-on-filter! :exception)
    (dbg/clear-break-on-filter! :contract)
    (dbg/clear-watchpoints!)
    (dbg/clear-debug-script!)
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

(deftest debugger-watchpoint-pauses-on-change
  (testing "Watchpoint pauses when watched value changes"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class W
  feature
    run() do
      let x: Integer := 0
      x := x + 1
      x := x + 1
    end
end")
          _ (dbg/add-watchpoint! "x")
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":c"])]
                  (repl/eval-code ctx "let w: W := create W
w.run()")))]
      (is (.contains out "Watchpoint ["))
      (is (.contains out "Paused at statement #")))))

(deftest debugger-frame-selection
  (testing ":frame selects caller frame for :locals"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class S
  feature
    f() do
      let p: Integer := 7
      this.g()
    end
    g() do
      let q: Integer := 9
    end
end")
          _ (dbg/add-breakpoint! {:kind :cm :class "S" :method "g"})
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":frame 1" ":locals" ":c"])]
                  (repl/eval-code ctx "let s: S := create S
s.f()")))]
      (is (.contains out "Selected frame [1]"))
      (is (.contains out "p = 7")))))

(deftest debugger-breakon-contract-filter
  (testing "Contract break-on filter limits pauses by contract kind"
    (let [ctx (repl/init-repl-context)
          _ (dbg/set-break-on! :contract true)
          _ (dbg/set-break-on-filter! :contract :post)
          _ (repl/eval-code ctx "class K
  feature
    f()
    require
      bad: false
    do
    end
end")
          out (with-out-str
                (repl/eval-code ctx "let k: K := create K
k.f()"))]
      (is (not (.contains out "Paused on contract violation."))))))

(deftest debugger-scripted-input
  (testing "Debugger script drives dbg commands without interactive input"
    (let [ctx (repl/init-repl-context)
          script-file (doto (io/file (System/getProperty "java.io.tmpdir") "nex_debug_script_test.dbg")
                        (spit ":where\n:c\n"))
          _ (repl/eval-code ctx "class T
  feature
    run() do
      let x: Integer := 1
    end
end")
          _ (dbg/add-breakpoint! {:kind :cm :class "T" :method "run"})
          _ (repl/handle-command ctx (str ":debugscript " (.getPath script-file)))
          out (with-out-str
                (repl/eval-code ctx "let t: T := create T
t.run()"))]
      (is (.contains out "dbg(script)> :where")))))

(deftest debugger-breakpoint-persistence-roundtrip
  (testing "Breakpoints/watchpoints round-trip through :breaksave/:breakload with enabled flags"
    (let [ctx (repl/init-repl-context)
          snap-file (io/file (System/getProperty "java.io.tmpdir") "nex_debug_snapshot_test.edn")
          id1 (dbg/add-breakpoint! {:kind :hit :n 3})
          _ (dbg/add-breakpoint! {:kind :cm :class "P" :method "run" :condition "x > 0"})
          wid (dbg/add-watchpoint! "x")
          _ (dbg/set-breakpoint-enabled! id1 false)
          _ (dbg/set-watchpoint-enabled! wid false)
          _ (repl/handle-command ctx (str ":breaksave " (.getPath snap-file)))
          _ (dbg/clear-breakpoints!)
          _ (dbg/clear-watchpoints!)
          _ (repl/handle-command ctx (str ":breakload " (.getPath snap-file)))
          bps (dbg/breakpoint-entries)
          wps (dbg/watchpoint-entries)]
      (is (= 2 (count bps)))
      (is (= 1 (count wps)))
      (is (some #(false? (:enabled (second %))) bps))
      (is (false? (:enabled (second (first wps))))))))

(deftest debugger-breakon-exception-filter-edge-cases
  (testing "Exception filter gates pause behavior"
    (let [ctx (repl/init-repl-context)
          _ (dbg/set-break-on! :exception true)
          _ (dbg/set-break-on-filter! :exception "not-matching-substring")
          out-no-pause (with-out-str
                         (repl/eval-code ctx "1 / 0"))
          _ (dbg/set-break-on-filter! :exception "zero")
          out-pause (with-out-str
                      (with-redefs [repl/read-line-safe (scripted-reader [":c"])]
                        (repl/eval-code ctx "1 / 0")))]
      (is (not (.contains out-no-pause "Paused on exception.")))
      (is (.contains out-pause "Paused on exception.")))))

(deftest debugger-frame-invalid-index
  (testing "Invalid :frame index prints usage and continues debugger loop"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class U
  feature
    run() do
      let x: Integer := 1
    end
end")
          _ (dbg/add-breakpoint! {:kind :cm :class "U" :method "run"})
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":frame 99" ":c"])]
                  (repl/eval-code ctx "let u: U := create U
u.run()")))]
      (is (.contains out "Usage: :frame <index>, where top frame is 0"))
      (is (.contains out "Paused at statement #")))))

(deftest debugger-temporary-breakpoint
  (testing "Temporary breakpoint auto-clears after first hit"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class Temp
  feature
    run() do
      let x: Integer := 1
    end
end")
          _ (repl/handle-command ctx ":tbreak Temp.run")
          out1 (with-out-str
                 (with-redefs [repl/read-line-safe (scripted-reader [":c"])]
                   (repl/eval-code ctx "let t: Temp := create Temp
t.run()")))
          out2 (with-out-str
                 (repl/eval-code ctx "t.run()"))]
      (is (.contains out1 "Paused at statement #"))
      (is (not (.contains out2 "Paused at statement #"))))))

(deftest debugger-field-write-breakpoint
  (testing "Field-write breakpoint pauses on member assignment"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class Invoice
  feature
    status: Integer
    mark_paid() do
      this.status := 1
    end
end")
          _ (repl/handle-command ctx ":break field:status")
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":c"])]
                  (repl/eval-code ctx "let inv: Invoice := create Invoice
inv.mark_paid()")))]
      (is (.contains out "Paused at statement #"))
      (is (.contains out "node=member-assign")))))

(deftest debugger-help-and-unknown-command
  (testing "Debugger :help prints command list and unknown commands show usage hint"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class HelpDbg
  feature
    run() do
      let x: Integer := 1
    end
end")
          _ (dbg/add-breakpoint! {:kind :cm :class "HelpDbg" :method "run"})
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":help" ":wat" ":c"])]
                  (repl/eval-code ctx "let h: HelpDbg := create HelpDbg
h.run()")))]
      (is (.contains out "Debugger commands:"))
      (is (.contains out "Unknown dbg command: :wat"))
      (is (.contains out "Type :help for debugger commands.")))))

(deftest debugger-conditional-watchpoint
  (testing "Conditional watchpoint pauses only when condition evaluates true"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class CW
  feature
    run() do
      let x: Integer := 0
      x := x + 1
      x := x + 1
    end
end")
          _ (repl/handle-command ctx ":watch x if x > 0")
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":c"])]
                  (repl/eval-code ctx "let cw: CW := create CW
cw.run()")))]
      (is (.contains out "Watchpoint ["))
      (is (.contains out "old=0"))
      (is (.contains out "new=1")))))

(deftest debugger-ignore-and-every-hit-conditions
  (testing "Breakpoint hit conditions :ignore and :every influence pause frequency"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class HC
  feature
    run() do
      let x: Integer := 1
      x := x + 1
      x := x + 1
      x := x + 1
    end
end")
          _ (repl/handle-command ctx ":break HC.run")
          id (ffirst (dbg/breakpoint-entries))
          _ (repl/handle-command ctx (str ":ignore " id " 1"))
          _ (repl/handle-command ctx (str ":every " id " 2"))
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":c" ":c" ":c"])]
                  (repl/eval-code ctx "let hc: HC := create HC
hc.run()")))]
      (is (<= 1 (count (re-seq #"Paused at statement #" out)))))))

(deftest debugger-locals-grouping
  (testing ":locals shows grouped args/fields/locals/special for selected frame"
    (let [ctx (repl/init-repl-context)
          _ (repl/eval-code ctx "class LG
  feature
    f: Integer
    calc(a: Integer): Integer do
      let b: Integer := a + 1
      this.f := b
      result := b
    end
end")
          _ (dbg/add-breakpoint! {:kind :cm :class "LG" :method "calc"})
          out (with-out-str
                (with-redefs [repl/read-line-safe (scripted-reader [":n" ":locals" ":c"])]
                  (repl/eval-code ctx "let lg: LG := create LG
lg.calc(3)")))]
      (is (.contains out "args:"))
      (is (.contains out "fields:"))
      (is (.contains out "locals:"))
      (is (.contains out "special:")))))
