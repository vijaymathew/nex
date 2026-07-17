(ns nex.any-protocol-test
  "The Any protocol (`to_string`, `equals`) on a user class that declares
   neither. The typechecker admits both on every receiver (the \"Any\" case of
   its `builtin-method-signature`), so both must also lower; before this they
   threw \"Unsupported user-defined target access during lowering\" and any
   program calling `to_string` on a plain class ran only under --interpret.

   Every test asserts the two backends agree, because that is precisely what
   the bug broke: the object models differ — a compiled object is an instance
   of a generated JVM class, an interpreted one is a map — and the :Any defaults
   in nex.types.builtins are written against the map. Dispatching a compiled
   object through them silently misreads it: `equals` on two equal objects
   returned false and `to_string` rendered `#object[nex.file.m.P 0x...]`.

   Driven through `nex.eval/eval-file`, the same path `nex <file>` takes, so a
   construct the compiled backend declines would surface here as a fallback
   warning rather than passing on interpreter output."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- type-errors
  "The type errors CODE produces, or nil when it type-checks. Reported the same
   way for both backends — type checking runs before either of them."
  [code]
  (let [f (java.io.File/createTempFile "any_protocol" ".nex")]
    (try
      (spit f code)
      (try (with-out-str (e/eval-file (.getPath f) {}))
           nil
           (catch clojure.lang.ExceptionInfo ex
             (:errors (ex-data ex))))
      (finally (.delete f)))))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "any_protocol" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        ;; eval-file prints this banner when the compiled backend declines and
        ;; it silently reruns interpreted — which would make a compiled/
        ;; interpreted comparison vacuously pass.
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned."
  [code]
  (let [compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled)
        "compiled and interpreted output must agree")
    compiled))

(def ^:private plain-class
  "A class with no `to_string` and no `equals` — it inherits both from Any."
  "class P
  feature x: Integer
  create make(v: Integer) do x := v end
end
")

(deftest to-string-on-class-declaring-none
  (testing "a class without `to_string` renders as `#<Class object>`"
    (is (= ["\"#<P object>\""]
           (both (str plain-class "print(create P.make(1).to_string)"))))))

(deftest print-renders-object-in-nex-form
  (testing "print of an object without `to_string` uses the Nex form, not JVM's #object[...]"
    (is (= ["#<P object>"]
           (both (str plain-class "print(create P.make(1))"))))))

(deftest equals-on-class-declaring-none-is-structural
  (testing "`equals` without an override compares fields, agreeing with `=`"
    (is (= ["true" "false" "true" "false"]
           (both (str plain-class
                      "let a := create P.make(1)
let b := create P.make(1)
let c := create P.make(2)
print(a.equals(b))
print(a.equals(c))
print(a = b)
print(a = c)"))))))

(deftest to-string-honours-runtime-class-override
  (testing "a subclass `to_string` wins when the static type declares none"
    ;; Resolution must be on the runtime value, not the static type: `b` is
    ;; statically a Base (which declares no to_string) but runtime Sub (which
    ;; does), so anchoring to the static type would print `#<Sub object>`.
    (is (= ["\"Sub<7>\"" "Sub<7>"]
           (both "class Base
  feature x: Integer
  create make(v: Integer) do x := v end
end
class Sub
  inherit Base
  create make(v: Integer) do Base.make(v) end
  feature
    to_string: String do result := \"Sub<\" + x.to_string + \">\" end
end
let b: Base := create Sub.make(7)
print(b.to_string)
print(b)")))))

(deftest enum-member-has-any-to-string
  (testing "an enum union member, which declares no `to_string`, still renders"
    (is (= ["\"#<INR object>\""]
           (both "enum union Currency
  INR
  USD
end
print(Currency.INR.to_string)")))))

(deftest object-to-string-in-concatenation
  (testing "an object without `to_string` concatenates in its Nex form"
    (is (= ["\"total #<P object>\""]
           (both (str plain-class
                      "let p := create P.make(1)
print(\"total \" + p.to_string)"))))))

;; ─── What the universal protocol does *not* include ──────────────────────────
;;
;; The Any case of `builtin-method-signature` is consulted for every receiver,
;; so anything listed there typechecks against a class that never declares it.
;; That is only honest for names with a default implementation behind them.
;; The cursor protocol has none, so listing it promised iteration on every value
;; in the language and delivered it on none: `p.cursor` typechecked, then failed
;; at runtime ("Method not found") or refused to compile. It now belongs to the
;; types that implement it. docs/ref/foundational-classes.md has always
;; documented Any as to_string/equals/hash/clone; this is the checker agreeing.

(deftest cursor-protocol-is-not-universal
  (testing "cursor protocol names are rejected on a class that declares none"
    (doseq [m ["cursor" "start" "item" "next" "at_end"]]
      (let [errs (type-errors (str plain-class "print(create P.make(1)." m ")"))]
        (is (some #(re-find (re-pattern (str "Undefined field: " m)) %) errs)
            (str m " should be a type error on a plain class, got: " (pr-str errs)))))))

(deftest cursor-protocol-rejected-on-any-typed-receiver
  (testing "`cursor` on an Any-typed receiver is rejected, as `length` already was"
    ;; Any is a strict type whose members are the universal protocol; `x.length`
    ;; was always an error here, so admitting `x.cursor` was the inconsistency.
    (is (seq (type-errors "let x: Any := \"abc\"\nprint(x.cursor)")))
    (is (seq (type-errors "let x: Any := \"abc\"\nprint(x.length)")))))

(deftest universal-protocol-still-universal
  (testing "to_string/equals/clone remain callable on a class declaring none"
    (is (nil? (type-errors (str plain-class "print(create P.make(1).to_string)"))))
    (is (nil? (type-errors (str plain-class
                                "print(create P.make(1).equals(create P.make(1)))"))))
    (is (nil? (type-errors (str plain-class "let c: Any := create P.make(1).clone"))))))

(deftest builtins_that_have_a_cursor_keep_it
  (testing "Array/Map/Set/String iterate through their own declared cursor"
    (is (= ["1" "2"]
           (both "let c := [1, 2].cursor
from c.start until c.at_end do
  print(c.item)
  c.next
end")))
    ;; String had no `cursor` of its own and reached it through the Any
    ;; fallback; it needed declaring when that fallback stopped promising it.
    (is (= ["#a" "#b"]
           (both "let c := \"ab\".cursor
from c.start until c.at_end do
  print(c.item)
  c.next
end")))
    (is (nil? (type-errors "print(#{1}.cursor.at_end)")))
    (is (nil? (type-errors "print({\"a\": 1}.cursor.at_end)")))))

(deftest across-still-iterates
  (testing "`across` over each cursor-bearing builtin is unaffected"
    (is (= ["10" "20"] (both "across [10, 20] as x do\n  print(x)\nend")))
    (is (= ["#a" "#b"] (both "across \"ab\" as c do\n  print(c)\nend")))))

(deftest user-class-cursor-still-dispatches
  (testing "a class declaring its own `cursor` resolves to its own signature"
    (is (= ["false"]
           (both "class Ints
  feature
    i: Integer
    cursor: Cursor do result := [1, 2].cursor end
  create make() do i := 0 end
end
print(create Ints.make().cursor.at_end)")))))
