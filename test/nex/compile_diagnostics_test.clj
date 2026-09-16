(ns nex.compile-diagnostics-test
  "Diagnostics for a program the JVM backend cannot compile.

   Two failures reach this point and they need different advice. A *gap* is a
   valid program the backend has not implemented yet: --interpret is a real
   workaround and there is nothing to fix in the program. A *defect* is an
   earlier pass — usually the typechecker — admitting something it should
   have rejected, so \"Constructor not found\" or \"Unable to infer expression
   type\" describe something the compiler should have caught sooner. That is
   very often a real gap in type-checking itself (letting an invalid program
   through) rather than a genuine backend limitation, so this deliberately
   does not tell the user whose fault it is, direct them to --interpret
   (which hits the identical gap, just later and less clearly), or ask them
   to file a report — it just names the construct and the line, same as a
   gap does.

   Both used to print the same \"your program uses a construct the compiled
   backend does not support yet\" text, naming neither the construct nor the
   line — which sends the reader looking for a workaround to a compiler bug,
   and looking at the wrong language feature entirely."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(def ^:private message #'nex.eval/compile-error-message)

(def ^:private plain-class
  "class P
  feature x: Integer
  create make(v: Integer) do x := v end
end
")

(defn- compile-failure
  "Run CODE through the real `nex <file>` path and return the error text."
  [code]
  (let [f (java.io.File/createTempFile "diagnostics" ".nex")]
    (try
      (spit f code)
      (let [ex (try (with-out-str (e/eval-file (.getPath f) {}))
                    nil
                    (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? ex) "expected this program to fail compilation")
        (ex-message ex))
      (finally (.delete f)))))

(deftest gap-names-the-construct-and-the-line
  (testing "an unsupported construct reports the method, the receiver, and where"
    ;; `clone` is a real gap: the Any protocol admits it on every receiver and
    ;; the interpreter implements it, but the compiled object model does not.
    (let [msg (compile-failure (str plain-class "print(create P.make(1).clone)"))]
      (is (str/includes? msg "does not support yet") msg)
      (is (str/includes? msg "method \"clone\"") msg)
      (is (str/includes? msg "target-type \"P\"") msg)
      (is (str/includes? msg "at line 5") msg)
      (is (str/includes? msg "--interpret") msg))))

(deftest gap-is-not-reported-as-a-defect
  (testing "a gap does not ask the user to file a bug"
    (let [msg (compile-failure (str plain-class "print(create P.make(1).clone)"))]
      (is (not (str/includes? msg "please report")) msg)
      (is (not (str/includes? msg "internal error")) msg))))

(deftest defect-is-reported-tersely-without-blame-or-advice
  (testing "an unmarked lowering failure is named and located, but the message
            no longer presumes to say whose fault it is or what to do about
            it — often (as here) it is actually an earlier pass admitting an
            invalid program, e.g. an unchecked loop clause, rather than a
            genuine backend limitation, so telling the user to file a report
            or fall back to --interpret (which hits the identical gap, just
            later and less clearly) is not reliably good advice"
    ;; Exactly the ex-data the alias bug produced before it was fixed: the
    ;; typechecker had accepted the program, so lowering failing to infer a type
    ;; was the compiler's fault — and this is the message that says so.
    (let [msg (message (ex-info "Unable to infer expression type during lowering"
                                {:expr {:type :call :target "t" :method "length"
                                        :dbg/line 3 :dbg/col 7}
                                 :errors ["At line 3, column 7"]}))]
      (is (str/includes? msg "internal error in the compiled backend") msg)
      (is (not (str/includes? msg "please report")) msg)
      (is (not (str/includes? msg "--interpret")) msg)
      (is (str/includes? msg "at line 3, column 7") msg)
      (is (not (str/includes? msg "does not support yet")) msg))))

(deftest defect-without-location-still-reports-cleanly
  (testing "an IR invariant with no debug info degrades to a bare message"
    (let [msg (message (ex-info "If test did not lower to boolean" {}))]
      (is (str/includes? msg "internal error in the compiled backend") msg)
      (is (str/includes? msg "If test did not lower to boolean") msg)
      ;; no dangling "(...)" or "at line" fragments when there is nothing to say
      (is (not (str/includes? msg "()")) msg)
      (is (not (str/includes? msg "at line")) msg))))

(deftest gap-marker-drives-the-classification
  (testing "the :nex/unsupported marker, not the message text, picks the advice"
    (let [same-text "Unsupported thing during lowering"]
      (is (str/includes? (message (ex-info same-text {:nex/unsupported true}))
                         "does not support yet"))
      (is (str/includes? (message (ex-info same-text {}))
                         "internal error in the compiled backend")))))

;; Regression: `Grandparent.init(...)` from `Child` (skipping the immediate
;; parent `Parent`) type-checks fine — check-explicit-class-constructor-call
;; only requires Grandparent to be a real ancestor, not an immediate parent —
;; and runs correctly under the interpreter. But lower-call-stmt's qualified-
;; constructor-call case only recognizes an *immediate* parent, so this used
;; to fall through to the generic expression-lowering path, which cannot type
;; a bare ancestor class name as an expression at all, and died with the
;; unmarked "Unable to infer expression type during lowering" — reported as a
;; compiler defect rather than the real, nameable gap it is. Now reported as
;; a gap, naming the construct and pointing at --interpret (which still
;; works, unchanged).
(def ^:private non-immediate-ancestor-ctor-call
  "class Grandparent
create
  init(v: Integer) do print(\"Grandparent.init \" + v.to_string) end
end

class Parent
inherit Grandparent
create
  init(v: Integer) do super.init(v) end
end

class Child
inherit Parent
create
  init
  do
    Grandparent.init(99)
  end
end

let c := create Child.init
")

(deftest non-immediate-ancestor-constructor-call-is-a-named-gap
  (testing "qualifying a constructor call by a non-immediate ancestor's name
            is reported as an unsupported construct, not an internal defect"
    (let [msg (compile-failure non-immediate-ancestor-ctor-call)]
      (is (str/includes? msg "does not support yet") msg)
      (is (str/includes? msg "Grandparent.init(...)") msg)
      (is (str/includes? msg "non-immediate ancestor") msg)
      (is (str/includes? msg "--interpret") msg)
      (is (not (str/includes? msg "internal error")) msg))))

(deftest non-immediate-ancestor-constructor-call-still-runs-interpreted
  (testing "the same program the compiled backend declines still runs correctly
            under --interpret, since the typechecker and interpreter always
            supported reaching any ancestor, not just an immediate parent"
    (let [f (java.io.File/createTempFile "diagnostics" ".nex")]
      (try
        (spit f non-immediate-ancestor-ctor-call)
        (is (= "\"Grandparent.init 99\"\n"
               (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))
        (finally (.delete f))))))
