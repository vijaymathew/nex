(ns nex.recursive-type-alias-test
  "A `declare type` alias stands for its definition wherever it is used, so
   one that refers back to itself (`declare type Pair = Function(Integer,
   Pair): ?Pair`, or A = Array[B] with B = Array[A]) has no finite expansion.
   Expanding it used to overflow the stack — in the type checker for a file,
   and on every later line of a REPL session that had recorded it. It is now
   a type error at the declaration (nex.typechecker/type-alias-cycle)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.eval :as e]
            [nex.repl :as repl]))

(defn- type-errors [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (:errors result))))

(defn- run-file [code interpret?]
  (let [f (java.io.File/createTempFile "recursive_alias" ".nex")]
    (try
      (spit f code)
      (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))
      (catch Throwable t
        (str "THROWN " (class t) " " (.getMessage t)))
      (finally (.delete f)))))

(deftest self-recursive-alias-is-a-type-error-at-its-declaration
  (let [[err :as errs] (type-errors
                        (str "declare type Pair = Function(Integer, Pair): ?Pair\n"
                             "print(1)\n"))]
    (is (= 1 (count errs)))
    (is (= 1 (:line err)))
    (is (= (str "Type alias Pair refers to itself (Pair -> Pair). A type alias "
                "stands for its definition, so it cannot be recursive; declare "
                "a class for a recursive type.")
           (:message err)))))

(deftest recursion-is-found-anywhere-inside-the-definition
  (doseq [[decl cycle] [["declare type T = Array[T]" "T -> T"]
                        ["declare type T = ?T" "T -> T"]
                        ["declare type T = Map[String, Array[?T]]" "T -> T"]
                        ["declare type T = Function(): T" "T -> T"]
                        ["declare type T = Function(T)" "T -> T"]
                        ["declare type T = Integer where n: n > 0\ndeclare type U = Array[U]" "U -> U"]
                        ["declare type Nest[X] = Array[Nest[X]]" "Nest -> Nest"]]]
    (testing decl
      (let [[err] (type-errors (str decl "\nprint(1)\n"))]
        (is (str/includes? (str (:message err)) (str "(" cycle ")")))))))

(deftest mutually-recursive-aliases-are-a-type-error
  (let [[err] (type-errors (str "declare type A = Array[B]\n"
                                "declare type B = Map[String, C]\n"
                                "declare type C = ?A\n"
                                "print(1)\n"))]
    (is (= 1 (:line err)))
    (is (str/includes? (:message err) "Type alias A refers to itself (A -> B -> C -> A)"))))

(deftest non-recursive-aliases-are-accepted
  (testing "aliases built on other aliases, and a generic param sharing an alias's name"
    (is (nil? (type-errors
               (str "declare type T = Integer\n"
                    "declare type Box[T] = Array[T]\n"
                    "declare type Nested = Box[Box[String]]\n"
                    "declare type Q = Integer where n: n > 0\n"
                    "declare type F = Function(Q, Nested): ?Nested\n"
                    "let n: Nested := [[\"a\"]]\n"
                    "print(n)\n"))))))

(deftest recursive-alias-is-reported-on-both-backends
  (doseq [interpret? [false true]]
    (testing (if interpret? "interpreter" "compiled")
      (let [out (run-file (str "declare type Pair = Function(Integer, Pair): ?Pair\n"
                                "function pair(head: Integer, tail: ?Pair): ?Pair\n"
                                "do\n"
                                "  result := nil\n"
                                "end\n"
                                "print(1)\n")
                          interpret?)]
        (is (not (str/includes? out "StackOverflow")) out)
        (is (str/includes? out "Type alias Pair refers to itself (Pair -> Pair)") out)))))

(deftest recursive-alias-is-turned-away-by-the-repl
  (doseq [typecheck? [false true]]
    (testing (str "typecheck " typecheck?)
      (let [ctx (repl/init-repl-context)
            out (fn [line] (with-out-str (repl/eval-code ctx line)))]
        (reset! repl/*type-checking-enabled* typecheck?)
        (try
          (is (str/includes? (out "declare type Pair = Function(Integer, Pair): ?Pair")
                             "Type alias Pair refers to itself (Pair -> Pair)"))
          (is (not (contains? @repl/*repl-type-aliases* "Pair")))
          (is (str/blank? (out "declare type A = Array[B]")))
          (testing "the cycle is named from the alias that closes it"
            (is (str/includes? (out "declare type B = Array[A]")
                               "Type alias B refers to itself (B -> A -> B)")))
          (testing "the session goes on working"
            (is (str/blank? (out "declare type Ok = Array[Integer]")))
            (is (str/includes? (out "let o: Ok := [1, 2]") "[1, 2]"))
            (is (str/includes? (out "o.length") "2")))
          (finally
            (reset! repl/*type-checking-enabled* false)
            (reset! repl/*repl-var-types* {})))))))
