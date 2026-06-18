(ns nex.string-escapes-test
  "String literals: double-quoted strings interpret a standard set of backslash
   escapes (\\n \\t \\r \\0 \\\\ \\\" and \\u{HHHH}); single-quoted strings are
   raw. Previously Nex interpreted no escapes at all (\"\\n\" was two characters),
   and single-quoted strings were not recognised as string literals.

   Assertions use `length` and `char_at(i).to_integer` (code points) rather than
   printing the string, since `print` wraps a string in quotes."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]))

(defn- run [body]
  (let [ast (p/ast (str "class T\n  feature\n    d() do\n      " body "\n    end\nend"))
        ctx (interp/make-context)
        class-def (first (:classes ast))]
    (interp/register-class ctx class-def)
    (let [method-def (-> class-def :body first :members first)
          env (interp/make-env (:globals ctx))
          c (assoc ctx :current-env env)]
      (doseq [s (:body method-def)] (interp/eval-node c s))
      @(:output c))))

(deftest double-quoted-interprets-escapes
  (testing "the standard escapes become their single character"
    (is (= ["1"] (run "print(\"\\n\".length)")))
    (is (= ["3"] (run "print(\"a\\nb\".length)")))
    (is (= ["2"] (run "print(\"\\t\\r\".length)")))
    (is (= ["1"] (run "print(\"\\0\".length)")))
    (is (= ["3"] (run "print(\"a\\\\b\".length)"))
        "\\\\ is one backslash")
    (is (= ["10"] (run "print((\"x\\ny\").char_at(1).to_integer)"))
        "\\n is code point 10 (LF)")
    (is (= ["9"] (run "print((\"x\\ty\").char_at(1).to_integer)"))
        "\\t is code point 9")
    (is (= ["92"] (run "print((\"a\\\\b\").char_at(1).to_integer)"))
        "\\\\ is code point 92 (backslash)")))

(deftest double-quote-escape-embeds-a-quote
  (testing "\\\" puts a literal double quote (code point 34) inside the string"
    (is (= ["1"] (run "print(\"\\\"\".length)")))
    (is (= ["34"] (run "print(\"\\\"\".char_at(0).to_integer)")))
    (is (= ["8"] (run "print(\"say \\\"hi\\\"\".length)"))
        "say \"hi\" is eight characters")))

(deftest unicode-escape
  (testing "\\u{HHHH} denotes a code point in hexadecimal"
    (is (= ["65"] (run "print(\"\\u{41}\".char_at(0).to_integer)")))
    (is (= ["233"] (run "print(\"caf\\u{E9}\".char_at(3).to_integer)"))
        "\\u{E9} is é, code point 233")
    (is (= ["2"] (run "print(\"\\u{1F600}\".length)"))
        "an astral code point occupies two UTF-16 code units")))

(deftest single-quoted-is-raw
  (testing "single-quoted strings interpret no escapes and keep backslashes"
    (is (= ["4"] (run "print('a\\nb'.length)")))
    (is (= ["2"] (run "print('hi'.length)"))
        "single-quoted strings are recognised as string literals (was a bug)")
    (is (= ["92"] (run "print('a\\nb'.char_at(1).to_integer)"))
        "the backslash is preserved literally")))

(defn- root-cause [^Throwable t]
  (loop [x t] (if-let [c (.getCause x)] (recur c) x)))

(deftest unknown-escape-is-an-error
  (testing "an unrecognised escape is rejected rather than silently passed through"
    (let [e (try (p/ast "class T\n feature\n  d() do print(\"\\q\") end\nend") nil
                 (catch Throwable t t))]
      (is (some? e) "an unknown escape raises")
      (is (re-find #"Invalid escape sequence"
                   (str (.getMessage (root-cause e))))))))
