(ns nex.builtin-exceptions-test
  "The built-in exceptions (Definition B.7, lib/lang/exception.nex): when the
   language itself raises a failure, a `rescue` block's `exception` is an
   object of the class for that kind of failure, not a message string, so a
   handler can tell failures apart with `convert` or `match`. A value raised
   with `raise` reaches `exception` unchanged.

   Both backends classify the caught throwable the same way
   (nex.types.runtime/builtin-failure) and build the object through the
   library's `__builtin_exception`; the library is interned implicitly into a
   program with a `rescue` (nex.walker/add-implied-exception-intern)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]
            [nex.parser :as p]))

(defn- run-file
  [code opts]
  (let [f (java.io.File/createTempFile "builtin_exceptions" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) opts))))
      (finally (.delete f)))))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [compiled (run-file code {})
        interpreted (run-file code {:interpret? true})]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- uncaught-message
  "The message an uncaught failure of CODE is reported with, on OPTS' backend."
  [code opts]
  (let [f (java.io.File/createTempFile "builtin_exceptions" ".nex")]
    (try
      (spit f code)
      (with-out-str (e/eval-file (.getPath f) opts))
      nil
      (catch Exception ex (ex-message ex))
      (finally (.delete f)))))

(def ^:private kind-of
  "A function naming the built-in exception class of its argument."
  "function kind_of(e: Any): String do
  result := \"not an exception\"
  if convert e to h: Host_Exception then result := \"Host_Exception\"
  elseif convert e to v: Contract_Violation then
    result := \"Contract_Violation\"
    if convert e to a: Precondition_Violation then result := \"Precondition_Violation\" end
    if convert e to b: Postcondition_Violation then result := \"Postcondition_Violation\" end
    if convert e to c: Invariant_Violation then result := \"Invariant_Violation\" end
    if convert e to d: Assertion_Violation then result := \"Assertion_Violation\" end
    if convert e to f: Refinement_Violation then result := \"Refinement_Violation\" end
    if convert e to g: Variant_Violation then result := \"Variant_Violation\" end
    if ?v.label as l then result := result + \" [\" + l + \"]\" end
  elseif convert e to i: Division_by_Zero then result := \"Division_by_Zero\"
  elseif convert e to j: Arithmetic_Overflow then result := \"Arithmetic_Overflow\"
  elseif convert e to k: Index_Out_Of_Bounds then result := \"Index_Out_Of_Bounds\"
  elseif convert e to m: Conversion_Error then result := \"Conversion_Error\"
  elseif convert e to n: Channel_Closed then result := \"Channel_Closed\"
  elseif convert e to o: No_Matching_Clause then result := \"No_Matching_Clause\"
  elseif convert e to q: Exception then result := \"Exception\"
  end
end
")

(deftest each-failure-is-its-class-test
  (testing "every kind of built-in failure binds `exception` to its class, with
            a contract's label, and the message it is reported with"
    (is (= ["\"Division_by_Zero: Division by zero\""
            "\"Arithmetic_Overflow: Arithmetic overflow\""
            "\"Precondition_Violation [pos]: Precondition violation: pos\""
            "\"Postcondition_Violation [big]: Postcondition violation: big\""
            "\"Invariant_Violation [inv_pos]: Class invariant violation: inv_pos\""
            "\"Assertion_Violation [named]: Assertion violation: named\""
            "\"Refinement_Violation [Quantity]: Refinement Quantity violated\""
            "\"Index_Out_Of_Bounds: Index 5 out of bounds for length 1\""
            "\"Precondition_Violation [key_must_exist]: Precondition violation: key_must_exist\""
            "\"Conversion_Error: Byte value must be in range 0..255, got 300\""
            "\"Channel_Closed: Cannot send on a closed channel\""
            "\"No_Matching_Clause: No matching clause in match\""
            "\"Index_Out_Of_Bounds: Index 10 out of bounds for length 3\""
            "\"Conversion_Error: Not a valid number\""
            "\"Variant_Violation: Loop variant must be non-negative\""
            "\"Assertion_Violation: Assertion violation (line 86)\""]
           (both (str "declare type Quantity = Integer where n: n > 0
class P
create
  make do
    v := 1
  end
feature
  v: Integer
  f(x: Integer): Integer
  require
    pos: x > 0
  do
    result := x
  ensure
    big: result > 100
  end
  breaker do
    v := -1
  end
invariant
  inv_pos: v > 0
end
class Base
end
class Leaf
inherit Base
end
class Other
inherit Base
end
function q(x: Quantity): Integer do result := x end
" kind-of "
function probe(k: Integer)
do
  do
    if k = 1 then print(1 / 0) end
    if k = 2 then print(9223372036854775807 + 1) end
    if k = 3 then print((create P.make).f(-1)) end
    if k = 4 then print((create P.make).f(1)) end
    if k = 5 then (create P.make).breaker end
    if k = 6 then assert named: k = 0 end
    if k = 7 then print(q(0)) end
    if k = 8 then print([1].get(5)) end
    if k = 9 then
      let m: Map[String, Integer] := {}
      print(m.get(\"x\"))
    end
    if k = 10 then print(300.to_byte) end
    if k = 11 then
      let c := create Channel[Integer].with_capacity(1)
      c.close
      c.send(1)
    end
    if k = 12 then
      let a: Base := create Other
      match a of Leaf then print(1) end
    end
    if k = 13 then print(\"abc\".char_at(10)) end
    if k = 14 then print(\"x\".to_integer) end
    if k = 15 then
      from let i := 0 variant 1 - i until i > 3 do
        i := i + 1
      end
    end
    if k = 16 then assert k = 0 end
  rescue
    print(kind_of(exception) + \": \" + exception)
  end
end
across [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16] as k do probe(k) end"))))))

(deftest raised-values-are-not-wrapped-test
  (testing "`raise` hands the handler exactly the value raised"
    (is (= ["\"plain\"" "42" "true" "\"not an exception\""]
           (both (str kind-of "
do
  raise \"plain\"
rescue
  print(exception)
end
do
  raise 42
rescue
  print(exception)
end
do
  raise 7
rescue
  print(exception = 7)
end
do
  raise \"Division by zero\"
rescue
  print(kind_of(exception))
end"))))))

(deftest exceptions-can-be-matched-test
  (testing "a handler can `match` on `exception` and read the message"
    (is (= ["\"div: Division by zero\"" "\"index\"" "\"other: custom\""]
           (both "function classify(k: Integer): String do
  result := \"?\"
  do
    if k = 1 then print(1 / 0) end
    if k = 2 then print([1].get(4)) end
    if k = 3 then raise \"custom\" end
  rescue
    match exception of
      Division_by_Zero as d then result := \"div: \" + d.message
      Index_Out_Of_Bounds then result := \"index\"
    else
      result := \"other: \" + exception
    end
  end
end
print(classify(1))
print(classify(2))
print(classify(3))")))))

(deftest program-exception-classes-test
  (testing "a program may inherit Exception, raise its own, and catch it by
            class; an uncaught one is reported through its to_string"
    (let [decl "class Parse_Error
inherit Exception
create
  make(m: String) do
    Exception.make(m)
  end
end
"]
      (is (= ["\"parse: bad token\""]
             (both (str decl "do
  raise create Parse_Error.make(\"bad token\")
rescue
  if convert exception to p: Parse_Error then print(\"parse: \" + p.message) end
end"))))
      (doseq [opts [{} {:interpret? true}]]
        (is (= "bad token"
               (uncaught-message (str decl "raise create Parse_Error.make(\"bad token\")") opts))
            (str "uncaught, opts " opts))))))

(deftest rethrown-builtin-exception-reports-its-message-test
  (testing "re-raising a caught built-in exception reports its message, not an
            object identity"
    (doseq [opts [{} {:interpret? true}]]
      (is (= "Division by zero"
             (uncaught-message "do
  print(1 / 0)
rescue
  raise exception
end" opts))
          (str "opts " opts)))))

(deftest retry-and-task-rescue-see-exception-objects-test
  (testing "a routine's own rescue (with retry) and a spawn's rescue get objects too"
    ;; The task hands its finding back rather than printing it: a task's
    ;; print runs on its own thread, outside this test's with-out-str.
    (is (= ["10" "\"task: Index 3 out of bounds for length 1\""]
           (both "class W
feature
  n: Integer
  run(): Integer
  do
    n := n + 1
    result := 10 / (n - 1)
  rescue
    if convert exception to d: Division_by_Zero then retry end
  end
end
print((create W).run)
let t: Task[String] := spawn do
  result := [1].get(3).to_string
rescue
  if convert exception to i: Index_Out_Of_Bounds then result := \"task: \" + i.message end
end
print(t.await)")))))

(deftest host-failure-is-a-host-exception-test
  (testing "a failure the host raises with no Nex class is a Host_Exception
            naming the host's class (compiled backend: the interpreter cannot
            survive a stack overflow at all)"
    (is (= ["\"java.lang.StackOverflowError\""]
           (run-file "function deep(n: Integer): Integer do
  result := deep(n + 1)
end
do
  print(deep(0))
rescue
  if convert exception to h: Host_Exception then print(h.host_class) end
end" {})))))

(deftest declaring-a-colliding-class-falls-back-to-messages-test
  (testing "a program that declares its own `Exception` does not get the library,
            and its handlers see the message string as before"
    (is (= ["\"Division by zero\""]
           (both "class Exception
end
do
  print(1 / 0)
rescue
  print(exception)
end")))))

(deftest library-is-implied-only-where-observable-test
  (testing "the exception library is interned into a program with a rescue or
            naming one of its classes, and into no other"
    (let [implied? (fn [code]
                     (boolean (some #(and (:implied %) (= "lang" (:path %)) (= "Exception" (:class-name %)))
                                    (:interns (p/ast code)))))]
      (is (implied? "do\n  print(1)\nrescue\n  print(exception)\nend"))
      (is (implied? "function f(): Integer do\n  result := 1\nrescue\n  result := 0\nend"))
      (is (implied? "let x: Any := 1\nprint(convert x to e: Division_by_Zero)"))
      (is (not (implied? "print(1 / 2)")))
      (is (not (implied? "class Exception\nend\ndo\n  print(1)\nrescue\n  print(2)\nend"))))))
