(ns nex.nil-narrowing-test
  "Type-checker tests for nil-check narrowing (`x /= nil`, the else of
   `x = nil`, `and` chains) staying sound: a narrowing ends when the variable
   may have become nil again. Covers reassignment (in the block, in a branch,
   in a loop body), closures that write or read a narrowed local, shadowing,
   and fields, which a nil check never narrows."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(def ^:private prelude
  "class Dog feature fetch: String do result := \"f\" end create make do end end
function maybe(): ?Dog do result := nil end
let c := true
let d: ?Dog := maybe()\n")

(defn- accepts? [code]
  (let [r (tc/type-check (p/ast (str prelude code)))]
    (and (:success r) (empty? (:errors r)))))

(defn- error-text [code]
  (let [r (tc/type-check (p/ast (str prelude code)))]
    (when-not (:success r)
      (apply str (map tc/format-type-error (:errors r))))))

(defn- rejects-call-on-detachable? [code]
  (boolean (some->> (error-text code) (re-find #"Cannot call feature 'fetch' on detachable"))))

;; ---------------------------------------------------------------------------
;; Reassignment
;; ---------------------------------------------------------------------------

(deftest assigning-nil-ends-narrowing
  (testing "d := nil inside `if d /= nil` makes d detachable again"
    (is (rejects-call-on-detachable? "if d /= nil then\n d := nil\n print(d.fetch)\nend"))))

(deftest assigning-detachable-value-ends-narrowing
  (testing "d := a ?Dog value makes d detachable again"
    (is (rejects-call-on-detachable? "if d /= nil then\n d := maybe()\n print(d.fetch)\nend"))))

(deftest assigning-attached-value-keeps-narrowing
  (testing "d := a Dog leaves d narrowed"
    (is (accepts? "if d /= nil then\n d := create Dog.make\n print(d.fetch)\nend"))))

(deftest narrowing-again-after-reassignment
  (testing "a fresh nil check narrows again"
    (is (accepts? "if d /= nil then d := nil end\nif d /= nil then print(d.fetch) end"))))

(deftest reassignment-in-a-branch-ends-narrowing-after-it
  (testing "after `if c then d := nil end`, d may be nil"
    (is (rejects-call-on-detachable? "if d /= nil then\n if c then d := nil end\n print(d.fetch)\nend"))
    (is (rejects-call-on-detachable? "if d /= nil then\n if c then d := nil else d := maybe() end\n print(d.fetch)\nend"))))

(deftest reassignment-in-one-branch-does-not-affect-its-sibling
  (testing "the else branch still sees d narrowed"
    (is (accepts? "if d /= nil then\n if c then d := nil else print(d.fetch) end\nend"))
    (is (accepts? "if d /= nil then\n if c then d := nil elseif not c then print(d.fetch) else print(d.fetch) end\nend"))))

(deftest reassignment-in-a-match-clause-does-not-affect-its-sibling
  (testing "match clauses are alternatives too, and the narrowing ends after the match"
    (let [shapes "sealed deferred class Shape end
class Sq inherit Shape create make do end end
class Ci inherit Shape create make do end end
let s: Shape := create Sq.make\n"]
      (is (accepts? (str shapes "if d /= nil then\n match s of\n  Sq as q then d := nil\n  Ci as k then print(d.fetch)\n end\nend")))
      (is (rejects-call-on-detachable?
           (str shapes "if d /= nil then\n match s of\n  Sq as q then d := nil\n  Ci as k then print(d.fetch)\n end\n print(d.fetch)\nend"))))))

(deftest narrowed-local-passed-on-after-reassignment-rejected
  (testing "the narrowing ends for argument passing as well as calls"
    (is (some->> (error-text "function g(x: Dog): String do result := x.fetch end
if d /= nil then
 d := maybe()
 print(g(d))
end")
                 (re-find #"should be Dog, but got \?Dog")))))

;; ---------------------------------------------------------------------------
;; Loops
;; ---------------------------------------------------------------------------

(deftest loop-body-reassigning-narrowed-local-rejected
  (testing "the second iteration sees the first one's assignment"
    (is (rejects-call-on-detachable? "let i := 0
if d /= nil then
 from until i > 2 do
  print(d.fetch)
  d := nil
  i := i + 1
 end
end"))))

(deftest loop-body-leaving-narrowed-local-alone-accepts
  (testing "a loop that never assigns d keeps the outer narrowing"
    (is (accepts? "let i := 0
if d /= nil then
 from until i > 2 do
  print(d.fetch)
  i := i + 1
 end
end"))))

(deftest narrowing-inside-the-loop-body-accepts
  (testing "a nil check inside the body is redone every iteration"
    (is (accepts? "let i := 0
from until i > 2 do
 if d /= nil then print(d.fetch) end
 d := maybe()
 i := i + 1
end"))))

(deftest parameter-reassigned-in-loop-rejected
  (testing "parameters follow the same rules as locals"
    (is (rejects-call-on-detachable? "function f(x: ?Dog): String do
 result := \"\"
 let i := 0
 if x /= nil then
  from until i > 1 do result := x.fetch x := maybe() i := i + 1 end
 end
end"))))

;; ---------------------------------------------------------------------------
;; Closures and spawn
;; ---------------------------------------------------------------------------

(deftest closure-reading-reassigned-local-rejected
  (testing "a closure may run after the local it captured is set back to nil"
    (is (rejects-call-on-detachable? "if d /= nil then
 let k := fn (): String do result := d.fetch end
 d := nil
 print(k())
end"))))

(deftest closure-reading-effectively-final-local-accepts
  (testing "a narrowed local nothing reassigns stays narrowed inside a closure"
    (is (accepts? "let e: ?Dog := maybe()
if e /= nil then
 let k := fn (): String do result := e.fetch end
 print(k())
end"))))

(deftest local-written-by-a-closure-is-never-narrowed
  (testing "any call may run the closure, so a nil check on d proves nothing"
    (is (rejects-call-on-detachable? "let reset := fn () do d := nil end
if d /= nil then
 reset()
 print(d.fetch)
end"))
    (is (rejects-call-on-detachable? "if d /= nil then print(d.fetch) end
let r := fn () do d := nil end
r()"))))

(deftest closure-narrowing-its-own-local-accepts
  (testing "a closure's own local is not a capture, even if the closure reassigns it"
    (is (accepts? "let k := fn () do
 let x: ?Dog := maybe()
 if x /= nil then print(x.fetch) end
 x := nil
end
k()"))))

(deftest spawn-writing-captured-local-rejected
  (testing "a spawn body runs concurrently with the narrowed code"
    (is (rejects-call-on-detachable? "let t := spawn do d := nil end
if d /= nil then print(d.fetch) end"))))

(deftest spawn-reading-effectively-final-local-accepts
  (is (accepts? "let e: ?Dog := maybe()
if e /= nil then
 let t := spawn do print(e.fetch) end
 t.await
end")))

;; ---------------------------------------------------------------------------
;; Shadowing
;; ---------------------------------------------------------------------------

(deftest narrowing-does-not-reach-a-shadowing-local
  (testing "a nested `let d` is a different variable from the narrowed d"
    (is (rejects-call-on-detachable? "if d /= nil then
 if c then
  let d: ?Dog := maybe()
  print(d.fetch)
 end
end"))))

;; ---------------------------------------------------------------------------
;; Fields: a nil check never narrows one; bind it to a local instead.
;; ---------------------------------------------------------------------------

(deftest nil-check-does-not-narrow-a-field
  (testing "any call may reset the field, so `/= nil` is not enough"
    (let [err (error-text "class H
  feature
    x: ?Dog
    clear do x := nil end
    go: String do
      result := \"\"
      if x /= nil then
        clear()
        result := x.fetch
      end
    end
  create make do end
end")]
      (is (re-find #"Cannot call feature 'fetch' on detachable field 'x'" err))
      (is (re-find #"if \?x as x_ then" err)))))

(deftest attached-test-narrows-a-field-across-calls
  (testing "`?x as y` binds a local that stays attached whatever clear() does"
    (is (accepts? "class H
  feature
    x: ?Dog
    clear do x := nil end
    go: String do
      result := \"\"
      if ?x as y then
        clear()
        result := y.fetch
      end
    end
  create make do end
end"))))

;; ---------------------------------------------------------------------------
;; Forms that must keep narrowing.
;; ---------------------------------------------------------------------------

(deftest unchanged-narrowing-forms-still-accept
  (is (accepts? "if d /= nil and d.fetch = \"f\" then print(1) end"))
  (is (accepts? "if d = nil then print(0) else print(d.fetch) end"))
  (is (accepts? "if d = nil then print(0) elseif c then print(d.fetch) else print(d.fetch) end"))
  (is (accepts? "if convert d to dd: Dog then print(dd.fetch) end"))
  (is (accepts? "if ?d as dd then print(dd.fetch) end"))
  (is (accepts? "function g(x: Dog): String do result := x.fetch end\nif d /= nil then print(g(d)) end")))
