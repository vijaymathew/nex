(ns nex.fmt-test
  "Tests for `nex format`: it fixes indentation and changes nothing else."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.fmt :as fmt]))

(defn- lines [& ls] (str (str/join "\n" ls) "\n"))

(defn- formats-to
  "Formatting IN gives OUT, and formatting OUT again changes nothing."
  [in out]
  (let [formatted (fmt/format-code in)]
    (is (= out formatted))
    (is (= formatted (fmt/format-code formatted)) "formatting is idempotent")))

(deftest keeps-everything-but-indentation
  (testing "comments, top-level statements, blank lines and spacing inside lines stay"
    (formats-to (lines "-- a note"
                       "let x := 1   -- one"
                       ""
                       ""
                       "function f(n: Integer): Integer do"
                       "result := n*2"
                       "end"
                       "print(f(x))")
                (lines "-- a note"
                       "let x := 1   -- one"
                       ""
                       ""
                       "function f(n: Integer): Integer do"
                       "  result := n*2"
                       "end"
                       "print(f(x))")))
  (testing "trailing spaces go; a well-formatted file is unchanged"
    (formats-to "let x := 1   \nprint(x)\n" "let x := 1\nprint(x)\n")
    (let [src (lines "class A"
                     "  feature"
                     "    x: Integer"
                     "end")]
      (is (= src (fmt/format-code src))))))

(deftest class-sections-and-routines
  (formats-to (lines "class Counter"
                     "feature"
                     "count: Integer"
                     "bump(by: Integer)"
                     "  require"
                     "positive: by > 0"
                     "  do"
                     "count := count + by"
                     "  ensure"
                     "grew: count = old count + by"
                     "  end"
                     "create"
                     "make() do"
                     "count := 0"
                     "end"
                     "invariant"
                     "non_negative: count >= 0"
                     "end")
              (lines "class Counter"
                     "feature"
                     "  count: Integer"
                     "  bump(by: Integer)"
                     "    require"
                     "      positive: by > 0"
                     "    do"
                     "      count := count + by"
                     "    ensure"
                     "      grew: count = old count + by"
                     "    end"
                     "create"
                     "  make() do"
                     "    count := 0"
                     "  end"
                     "invariant"
                     "  non_negative: count >= 0"
                     "end")))

(deftest keeps-the-style-in-use
  (testing "class sections one level in, as the first one is written"
    (formats-to (lines "class A"
                       "  feature"
                       "x: Integer"
                       "create"
                       "make() do"
                       "x := 1"
                       "end"
                       "end")
                (lines "class A"
                       "  feature"
                       "    x: Integer"
                       "  create"
                       "    make() do"
                       "      x := 1"
                       "    end"
                       "end")))
  (testing "routine clauses flush with the header, as the first one is written"
    (formats-to (lines "function f(n: Integer): Integer"
                       "require"
                       "    ok: n > 0"
                       "do"
                       "result := n"
                       "end")
                (lines "function f(n: Integer): Integer"
                       "require"
                       "  ok: n > 0"
                       "do"
                       "  result := n"
                       "end"))))

(deftest statements
  (formats-to (lines "across xs as x do"
                     "if x > 1 then"
                     "total := total + x"
                     "elseif x = 1 then"
                     "total := 1"
                     "else"
                     "total := 0"
                     "end"
                     "end"
                     "from"
                     "let i := 0"
                     "invariant"
                     "small: i <= 3"
                     "until"
                     "i >= 3"
                     "do"
                     "i := i + 1"
                     "end"
                     "do"
                     "print(1)"
                     "rescue"
                     "print(2)"
                     "end"
                     "assert"
                     "a: total < 100"
                     "b: total > 0"
                     "print(total)")
              (lines "across xs as x do"
                     "  if x > 1 then"
                     "    total := total + x"
                     "  elseif x = 1 then"
                     "    total := 1"
                     "  else"
                     "    total := 0"
                     "  end"
                     "end"
                     "from"
                     "  let i := 0"
                     "invariant"
                     "  small: i <= 3"
                     "until"
                     "  i >= 3"
                     "do"
                     "  i := i + 1"
                     "end"
                     "do"
                     "  print(1)"
                     "rescue"
                     "  print(2)"
                     "end"
                     "assert"
                     "  a: total < 100"
                     "  b: total > 0"
                     "print(total)")))

(deftest case-match-and-select
  (formats-to (lines "case n of"
                     "1 then print(\"one\")"
                     "2 then"
                     "print(\"two\")"
                     "else print(\"many\")"
                     "end"
                     "match s of"
                     "Circle as c then print(c.r)"
                     "else"
                     "print(\"other\")"
                     "print(\"shape\")"
                     "end"
                     "select"
                     "when ch.receive as msg then"
                     "print(msg)"
                     "timeout 5 then"
                     "print(\"late\")"
                     "end")
              (lines "case n of"
                     "  1 then print(\"one\")"
                     "  2 then"
                     "    print(\"two\")"
                     "  else print(\"many\")"
                     "end"
                     "match s of"
                     "  Circle as c then print(c.r)"
                     "  else"
                     "    print(\"other\")"
                     "    print(\"shape\")"
                     "end"
                     "select"
                     "  when ch.receive as msg then"
                     "    print(msg)"
                     "  timeout 5 then"
                     "    print(\"late\")"
                     "end")))

(deftest unions-and-deferred-routines
  (formats-to (lines "union Shape"
                     "Circle(r: Real)"
                     "Square"
                     "end"
                     "deferred class Shape2"
                     "feature"
                     "area(): Real deferred"
                     "perimeter(): Real"
                     "require"
                     "ok: true"
                     "deferred"
                     "name(): String"
                     "deferred"
                     "ensure"
                     "named: result /= \"\""
                     "end"
                     "end")
              (lines "union Shape"
                     "  Circle(r: Real)"
                     "  Square"
                     "end"
                     "deferred class Shape2"
                     "feature"
                     "  area(): Real deferred"
                     "  perimeter(): Real"
                     "  require"
                     "    ok: true"
                     "  deferred"
                     "  name(): String"
                     "  deferred"
                     "  ensure"
                     "    named: result /= \"\""
                     "  end"
                     "end")))

(deftest continuation-lines
  (testing "a continued statement keeps its own alignment"
    (formats-to (lines "  print(\"a\" +"
                       "        \"b\")"
                       "let xs := [1,"
                       "           2]")
                (lines "print(\"a\" +"
                       "      \"b\")"
                       "let xs := [1,"
                       "           2]")))
  (testing "a continuation goes deeper than its statement, unless it closes it"
    (formats-to (lines "let m := ["
                       "1,"
                       "2"
                       "]")
                (lines "let m := ["
                       "  1,"
                       "  2"
                       "]")))
  (testing "a function passed as an argument indents from its line"
    (formats-to (lines "xs.each(fn (x: Integer) do"
                       "print(x)"
                       "end)")
                (lines "xs.each(fn (x: Integer) do"
                       "  print(x)"
                       "end)"))))

(deftest comments
  (testing "a comment moves with the code after it"
    (formats-to (lines "if a then"
                       "-- yes"
                       "print(1)"
                       "end")
                (lines "if a then"
                       "  -- yes"
                       "  print(1)"
                       "end")))
  (testing "a comment deeper than the next line ends the block above"
    (formats-to (lines "if a then"
                       "        print(1)"
                       "        -- done"
                       "end")
                (lines "if a then"
                       "  print(1)"
                       "  -- done"
                       "end")))
  (testing "a note lined up with end-of-line comments moves with its line"
    (formats-to (lines "  let x := 1      -- first"
                       "                  -- second")
                (lines "let x := 1      -- first"
                       "                -- second"))))

(deftest strings-and-line-endings
  (testing "lines inside a multi-line string are left alone"
    (formats-to (lines "if a then"
                       "print(\"one"
                       "   two  "
                       "three\")"
                       "end")
                (lines "if a then"
                       "  print(\"one"
                       "   two  "
                       "three\")"
                       "end")))
  (testing "`--` inside a string is not a comment"
    (formats-to (lines "if a then"
                       "print(\"--\")"
                       "end")
                (lines "if a then"
                       "  print(\"--\")"
                       "end")))
  (testing "tabs become spaces; Windows line breaks stay"
    (formats-to "if a then\n\tprint(1)\nend\n" "if a then\n  print(1)\nend\n")
    (formats-to "if a then\r\nprint(1)\r\nend\r\n" "if a then\r\n  print(1)\r\nend\r\n")))

(deftest refuses-what-does-not-parse
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Failed to format"
                        (fmt/format-code "if a then\nprint(1)\n"))))
