(ns nex.repl-qualified-names-and-output-test
  "Two REPL bugs, from one session:

   - A qualified class name (`create time/Date_Time.now`, a field or `let`
     typed `time/Date_Time`) in an input after the `intern` failed with
     \"Undefined class: time.Date_Time\". A whole program registers an
     interned class under its qualified name too; the REPL's compiled
     session -- its eligibility check, and the class table it lowers against
     -- and its interpreter fallback knew only the bare name.

   - An input that failed before it ran (an undefined name or class) printed
     the previous input's output again ahead of its error: the compiled
     session's output buffer was cleared only just before an input ran, and
     the error path flushed whatever it held."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.repl :as repl]))

(defn- session-output
  "Evaluate INPUTS in one fresh REPL session; return the printed lines."
  [& inputs]
  (let [ctx (repl/init-repl-context)]
    (->> (with-out-str
           (doseq [input inputs]
             (repl/eval-code ctx input)))
         str/split-lines
         (map str/trim)
         (remove str/blank?)
         vec)))

(deftest qualified-class-name-in-a-later-input-test
  (testing "create, and a type annotation, by the interned class's qualified name"
    (let [out (session-output "intern time/Date_Time"
                              "let d := create time/Date_Time.now"
                              "print(d.year > 2000)"
                              "let e: time/Date_Time := create time/Date_Time.now"
                              "print(e.year > 2000)")]
      (is (not-any? #(str/includes? % "Undefined class") out) (pr-str out))
      (is (= 2 (count (filter #(= "true" %) out))) (pr-str out)))))

(deftest qualified-class-in-a-union-payload-test
  (testing "the reported session: a union variant with a qualified payload type,
            created and matched in later inputs"
    (let [out (session-output
               "intern time/Date_Time"
               "union Order  Draft  Placed(id: String, total: Real)  Shipped(tracking: String, at: time/Date_Time) end"
               "function check_order(order: Order) do match order of Placed(id, total) then print(\"Placed: \" + id + \", \" + total) Shipped(tracking, at) then print(\"Shipped with tracking id: \" + tracking) Draft then print(\"Draft\") end end"
               "check_order(create Draft.make)"
               "check_order(create Shipped.make(\"T55617\", create time/Date_Time.now))")]
      (is (= ["\"Draft\"" "\"Shipped with tracking id: T55617\""] out)))))

(deftest failing-input-does-not-replay-previous-output-test
  (testing "an input that fails before running prints only its own error"
    (let [out (session-output "print(\"one\")"
                              "print(undefined_thing)"
                              "let z := create Nope.make"
                              "print(\"two\")")]
      (is (= 1 (count (filter #(= "\"one\"" %) out))) (pr-str out))
      (is (some #(str/includes? % "Undefined variable: undefined_thing") out) (pr-str out))
      (is (some #(str/includes? % "Undefined class: Nope") out) (pr-str out))
      (is (= "\"two\"" (last out)) (pr-str out))))
  (testing "a runtime failure still shows the output the failing input made first"
    (let [out (session-output "print(\"before\")"
                              "do\n  print(\"b\")\n  print(1 / 0)\nend")]
      (is (= ["\"before\"" "\"b\""] (filterv #(str/starts-with? % "\"") out)) (pr-str out))
      (is (some #(str/includes? % "Division by zero") out) (pr-str out)))))
