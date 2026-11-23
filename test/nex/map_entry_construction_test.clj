(ns nex.map-entry-construction-test
  "`create Map_Entry.make(key, value)` builds an entry (K and V inferred
   unless written), and `create Map.from_entries(entries)` builds a Map from
   an Array of them, a repeated key keeping its last value. Together they
   turn a Map's `map` (which gives an Array) back into a Map."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.eval :as e]
            [nex.repl :as repl]))

(defn- type-errors [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (mapv :message (:errors result)))))

(defn- run-file [code interpret?]
  (let [f (java.io.File/createTempFile "map_entry" ".nex")]
    (try
      (spit f code)
      (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))
      (catch Throwable t
        (str "THROWN " (class t) " " (.getMessage t)))
      (finally (.delete f)))))

(def ^:private program
  (str "let e := create Map_Entry.make(\"abc\", 123)\n"
       "print(e)\n"
       "print(e.value + 1)\n"
       "let f := create Map_Entry[String, Integer].make(\"x\", 1)\n"
       "print(f.key)\n"
       "let ages: Map[String, Integer] := {\"ann\": 31, \"bo\": 17}\n"
       "let doubled := create Map.from_entries(\n"
       "  ages.map(fn (x) => create Map_Entry.make(x.key, x.value * 2)))\n"
       "print(doubled)\n"
       "print(doubled.get(\"bo\") + 1)\n"
       "print(create Map.from_entries([e, create Map_Entry.make(\"abc\", 7)]))\n"
       "let none: Array[Map_Entry[String, Integer]] := []\n"
       "print(create Map[String, Integer].from_entries(none))\n"
       "print(e = create Map_Entry.make(\"abc\", 123))\n"))

(def ^:private expected-output
  ["\"abc\": 123" "124" "\"x\"" "{\"ann\": 62, \"bo\": 34}" "35" "{\"abc\": 7}" "{}" "true"])

(deftest runs-on-both-backends
  (is (nil? (type-errors program)))
  (doseq [interpret? [false true]]
    (testing (if interpret? "interpreter" "compiled")
      (is (= expected-output (str/split-lines (run-file program interpret?)))))))

(deftest types-are-inferred-and-checked
  (testing "K and V come from make's arguments"
    (is (some #(str/includes? % "Cannot assign Integer to variable 'k' of type String")
              (type-errors "let k: String := create Map_Entry.make(1, 2).key\n"))))
  (testing "from_entries's K and V come from the entries"
    (is (some #(str/includes? % "should be Array[Map_Entry[String, Integer]], but got Array[Map_Entry[Integer, Integer]]")
              (type-errors (str "let m: Map[String, Integer] := "
                                "create Map.from_entries([create Map_Entry.make(1, 2)])\n")))))
  (testing "from_entries takes entries"
    (is (some #(str/includes? % "Argument `entries` of `Map.from_entries`")
              (type-errors "let m := create Map.from_entries([1, 2])\n"))))
  (testing "make takes a key and a value"
    (is (some #(str/includes? % "Constructor `Map_Entry.make` takes 2 arguments, only 1 given")
              (type-errors "let e := create Map_Entry.make(1)\n"))))
  (testing "an unknown constructor"
    (is (some #(str/includes? % "Constructor not found: Map.nope")
              (type-errors "let m := create Map.nope()\n")))))

(deftest works-in-the-repl
  (doseq [typecheck? [false true]]
    (testing (str "typecheck " typecheck?)
      (let [ctx (repl/init-repl-context)
            out (fn [line] (with-out-str (repl/eval-code ctx line)))]
        (reset! repl/*type-checking-enabled* typecheck?)
        (try
          (is (str/includes? (out "create Map_Entry.make(\"abc\", 123)") "\"abc\": 123"))
          (is (str/includes? (out "create Map.from_entries([create Map_Entry.make(\"a\", 1)])")
                             "{\"a\": 1}"))
          (is (str/includes? (out "create Map_Entry") "create Map_Entry needs a key and a value"))
          (is (str/includes? (out "create Map.nope()") "Constructor not found: Map.nope"))
          (finally
            (reset! repl/*type-checking-enabled* false)
            (reset! repl/*repl-var-types* {})))))))
