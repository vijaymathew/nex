(ns nex.iterable-test
  "The iteration protocol as deferred classes: `Iterable[T]` (`cursor`) and
   `Cursor[T]` (`start`/`item`/`next`/`at_end`, itself Iterable). The built-in
   collections are Iterable, `across` walks any Iterable with a typed loop
   variable, and user classes join by inheritance. Asserted on both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "iterable" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  [& lines]
  (let [code (str/join "\n" lines)
        compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- type-error
  [& lines]
  (let [f (java.io.File/createTempFile "iterable" ".nex")]
    (try
      (spit f (str/join "\n" lines))
      (with-out-str (e/eval-file (.getPath f)))
      nil
      (catch clojure.lang.ExceptionInfo ex (ex-message ex))
      (finally (.delete f)))))

(def ^:private stack-classes
  ["class Stack_Cursor [G]"
   "inherit Cursor[G]"
   "create"
   "  make(data: Array[G]) do items := data  pos := data.length - 1 end"
   "feature"
   "  items: Array[G]"
   "  pos: Integer"
   "  start() do pos := items.length - 1 end"
   "  item(): G do result := items.get(pos) end"
   "  next() do pos := pos - 1 end"
   "  at_end(): Boolean do result := pos < 0 end"
   "end"
   "class Stack [G]"
   "inherit Iterable[G]"
   "create"
   "  make() do items := [] end"
   "feature"
   "  items: Array[G]"
   "  push(v: G) do items.add(v) end"
   "  cursor(): Cursor[G] do result := create Stack_Cursor[G].make(items) end"
   "end"
   "function total(xs: Iterable[Integer]): Integer do"
   "  across xs as v do result := result + v end"
   "end"
   "function count_all[T](xs: Iterable[T]): Integer do"
   "  across xs as v do result := result + 1 end"
   "end"])

(deftest builtin-collections-are-iterable
  (is (= ["6" "30" "5" "2" "#x"]
         (apply both (concat stack-classes
                             ["print(total([1, 2, 3]))"
                              "print(total(#{10, 20}))"
                              "print(count_all(\"hello\"))"
                              "print(count_all({\"a\": 1, \"b\": 2}))"
                              "let c: Cursor[Char] := \"xyz\".cursor()"
                              "c.start()"
                              "print(c.item)"])))))

(deftest user-iterables-are-typed-and-substitutable
  (is (= ["6" "3" "\"P\"" "\"Q\"" "6" "4" "2"]
         (apply both (concat stack-classes
                             ["let s: Stack[Integer] := create Stack[Integer].make()"
                              "s.push(1)"
                              "s.push(2)"
                              "s.push(3)"
                              "print(total(s))"
                              "print(count_all(s))"
                              "let names: Stack[String] := create Stack[String].make()"
                              "names.push(\"q\")"
                              "names.push(\"p\")"
                              ;; the loop variable is a String, not Any
                              "across names as n do print(n.to_upper) end"
                              "let it: Iterable[Integer] := s"
                              "across it as v do print(v * 2) end"])))))

(deftest cursors-are-iterable-and-convertible
  (is (= ["0" "1" "5" "6" "true" "true" "true" "false"]
         (both "class Count_Cursor"
               "inherit Cursor"
               "create"
               "  make(n: Integer) do limit := n end"
               "feature"
               "  limit: Integer"
               "  i: Integer"
               "  start() do i := 0 end"
               "  item(): Integer do result := i end"
               "  next() do i := i + 1 end"
               "  at_end(): Boolean do result := i >= limit end"
               "end"
               ;; a cursor that narrows `item` keeps its own item type
               "across create Count_Cursor.make(2) as n do print(n * 1) end"
               "let xs: Array[Integer] := [5, 6]"
               "across xs.cursor() as v do print(v) end"
               "let a: Any := xs.cursor()"
               "print(convert a to c1: Cursor[Integer])"
               "let b: Any := xs"
               "print(convert b to i1: Iterable[Integer])"
               "print(convert \"s\" to i2: Iterable[Char])"
               "let n: Any := 5"
               "print(convert n to i3: Iterable[Integer])"))))

(deftest bare-cursor-annotations-still-work
  (is (= ["5"]
         (both "let xs: Array[Integer] := [5, 6]"
               "let c: Cursor := xs.cursor()"
               "c.start()"
               "print(c.item)"))))

(deftest protocol-errors
  (testing "a cursor class must provide every deferred feature"
    (is (str/includes? (str (type-error "class Half"
                                        "inherit Cursor[Integer]"
                                        "feature"
                                        "  start() do end"
                                        "  item(): Integer do result := 1 end"
                                        "  next() do end"
                                        "end"
                                        "print(1)"))
                       "does not implement deferred method 'at_end'")))
  (testing "item must conform to the element type"
    (is (str/includes? (str (type-error "class Bad"
                                        "inherit Cursor[Integer]"
                                        "feature"
                                        "  start() do end"
                                        "  item(): String do result := \"s\" end"
                                        "  next() do end"
                                        "  at_end(): Boolean do result := true end"
                                        "end"
                                        "print(1)"))
                       "changes the return type from Integer to String")))
  (testing "the protocol classes are deferred"
    (is (str/includes? (str (type-error "let c := create Cursor"))
                       "Cannot instantiate deferred class Cursor"))
    (is (str/includes? (str (type-error "let i := create Iterable"))
                       "Cannot instantiate deferred class Iterable")))
  (testing "element types are checked"
    (is (str/includes? (str (type-error "let x: Iterable[Integer] := [\"a\"]"))
                       "Cannot assign Array[String] to variable 'x' of type Iterable[Integer]")))
  (testing "across names what it needs"
    (is (str/includes? (str (type-error "across 5 as x do print(x) end"))
                       "`across` needs an Iterable"))))
