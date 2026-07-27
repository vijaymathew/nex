(ns nex.cursor-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]))

(defn run-nex [code]
  (interp/interpret-and-get-output (p/ast code)))

;; ============================================================================
;; ARRAY CURSOR
;; ============================================================================

(deftest array-cursor-basic-test
  (testing "Iterate over array elements"
    (let [output (run-nex "function main() do
  let arr := [10, 20, 30]
  let c := arr.cursor()
  c.start()
  from let x := 0 until c.at_end() do
    print(c.item())
    c.next()
  end
end
main()")]
      (is (= ["10" "20" "30"] output)))))

(deftest array-cursor-empty-test
  (testing "Cursor on empty array is immediately at end"
    (let [output (run-nex "function main() do
  let arr := []
  let c := arr.cursor()
  c.start()
  print(c.at_end())
end
main()")]
      (is (= ["true"] output)))))

(deftest array-cursor-single-element-test
  (testing "Cursor on single-element array"
    (let [output (run-nex "function main() do
  let arr := [42]
  let c := arr.cursor()
  c.start()
  print(c.item())
  c.next()
  print(c.at_end())
end
main()")]
      (is (= ["42" "true"] output)))))

(deftest array-cursor-restart-test
  (testing "Calling start resets cursor to beginning"
    (let [output (run-nex "function main() do
  let arr := [1, 2, 3]
  let c := arr.cursor()
  c.start()
  c.next()
  c.next()
  c.start()
  print(c.item())
end
main()")]
      (is (= ["1"] output)))))

(deftest array-cursor-next-at-end-test
  (testing "Calling next when at end does nothing"
    (let [output (run-nex "function main() do
  let arr := [1]
  let c := arr.cursor()
  c.start()
  c.next()
  c.next()
  c.next()
  print(c.at_end())
end
main()")]
      (is (= ["true"] output)))))

;; ============================================================================
;; STRING CURSOR
;; ============================================================================

(deftest string-cursor-basic-test
  (testing "Iterate over string characters"
    (let [output (run-nex "function main() do
  let s := \"abc\"
  let c := s.cursor()
  c.start()
  from let x := 0 until c.at_end() do
    print(c.item())
    c.next()
  end
end
main()")]
      (is (= ["#a" "#b" "#c"] output)))))

(deftest string-cursor-empty-test
  (testing "Cursor on empty string is immediately at end"
    (let [output (run-nex "function main() do
  let s := \"\"
  let c := s.cursor()
  c.start()
  print(c.at_end())
end
main()")]
      (is (= ["true"] output)))))

(deftest string-cursor-restart-test
  (testing "Calling start resets string cursor"
    (let [output (run-nex "function main() do
  let s := \"xy\"
  let c := s.cursor()
  c.start()
  c.next()
  c.start()
  print(c.item())
end
main()")]
      (is (= ["#x"] output)))))

;; ============================================================================
;; MAP CURSOR
;; ============================================================================

(deftest map-cursor-basic-test
  (testing "Map cursor item returns [key, value] array"
    (let [output (run-nex "function main() do
  let m := {\"name\": \"Alice\"}
  let c := m.cursor()
  c.start()
  let pair := c.item()
  print(pair.get(0))
  print(pair.get(1))
  c.next()
  print(c.at_end())
end
main()")]
      (is (= ["\"name\"" "\"Alice\"" "true"] output)))))

(deftest map-cursor-empty-test
  (testing "Cursor on empty map is immediately at end"
    (let [output (run-nex "function main() do
  let m := {}
  let c := m.cursor()
  c.start()
  print(c.at_end())
end
main()")]
      (is (= ["true"] output)))))

(deftest map-cursor-multiple-entries-test
  (testing "Map cursor iterates all entries"
    (let [output (run-nex "function main() do
  let m := {\"a\": 1, \"b\": 2}
  let c := m.cursor()
  c.start()
  let count := 0
  from let x := 0 until c.at_end() do
    count := count + 1
    c.next()
  end
  print(count)
end
main()")]
      (is (= ["2"] output)))))

(deftest map-cursor-restart-test
  (testing "Calling start resets map cursor"
    (let [output (run-nex "function main() do
  let m := {\"x\": 10}
  let c := m.cursor()
  c.start()
  c.next()
  print(c.at_end())
  c.start()
  print(c.at_end())
end
main()")]
      (is (= ["true" "false"] output)))))

;; ============================================================================
;; SET CURSOR
;; ============================================================================

(deftest set-cursor-basic-test
  (testing "Set cursor iterates set elements"
    (let [output (run-nex "function main() do
  let s := #{1, 2, 3}
  let c := s.cursor()
  c.start()
  let count := 0
  from let x := 0 until c.at_end() do
    count := count + c.item()
    c.next()
  end
  print(count)
  print(type_is(\"Cursor\", c))
end
main()")]
      (is (= ["6" "true"] output)))))

(deftest set-cursor-restart-test
  (testing "Calling start resets set cursor"
    (let [output (run-nex "function main() do
  let s := #{4, 5}
  let c := s.cursor()
  c.start()
  c.next()
  c.start()
  print(c.item())
end
main()")]
      (is (= ["4"] output)))))

;; ============================================================================
;; ACROSS STATEMENT
;; ============================================================================

(deftest across-array-test
  (testing "across iterates over array elements"
    (let [output (run-nex "function main() do
  across [10, 20, 30] as x do
    print(x)
  end
end
main()")]
      (is (= ["10" "20" "30"] output)))))

(deftest across-string-test
  (testing "across iterates over string characters"
    (let [output (run-nex "function main() do
  across \"abc\" as ch do
    print(ch)
  end
end
main()")]
      (is (= ["#a" "#b" "#c"] output)))))

(deftest across-map-test
  (testing "across iterates over map entries"
    (let [output (run-nex "function main() do
  let m := {\"x\": 10}
  across m as pair do
    print(pair.get(0))
    print(pair.get(1))
  end
end
main()")]
      (is (= ["\"x\"" "10"] output)))))

(deftest across-set-test
  (testing "across iterates over set elements"
    (let [output (run-nex "function main() do
  across #{1, 2, 3} as x do
    print(x)
  end
end
main()")]
      (is (= ["1" "2" "3"] output)))))

(deftest across-empty-array-test
  (testing "across on empty collection does nothing"
    (let [output (run-nex "function main() do
  across [] as x do
    print(x)
  end
  print(42)
end
main()")]
      (is (= ["42"] output)))))

(deftest across-nested-test
  (testing "nested across loops"
    (let [output (run-nex "function main() do
  across [1, 2] as i do
    across [10, 20] as j do
      print(i * j)
    end
  end
end
main()")]
      (is (= ["10" "20" "20" "40"] output)))))

;; ============================================================================
;; CURSOR BASE CLASS
;; ============================================================================

(deftest cursor-class-registered-test
  (testing "Cursor class is registered in context"
    (let [ctx (interp/make-context)]
      (is (some? (interp/lookup-class-if-exists ctx "Cursor"))))))

;; ============================================================================
;; ACROSS DIAGNOSTICS
;; ============================================================================

(defn- type-errors [code]
  (map :message (:errors (tc/type-check (p/ast code)))))

(deftest across-over-a-non-iterable-names-across-not-cursor-test
  ;; `across` desugars to `<target>.cursor`, so a target that cannot be iterated
  ;; failed with "Method not found: cursor" — naming a method the programmer
  ;; never wrote, in a program with no `cursor` in it.
  (testing "a value typed Any says how to narrow it"
    (let [errs (type-errors (str "let m: Map[String, Any] := {\"xs\": [1, 2]}\n"
                                 "across m.get(\"xs\") as x do\n"
                                 "  print(x)\n"
                                 "end\n"))]
      (is (= 1 (count errs)))
      (is (re-find #"`across` needs an Array, Map, Set, String, or Cursor; this one is Any"
                   (first errs)))
      (is (re-find #"convert" (first errs)) "must point at the way out")
      (is (not (re-find #"\bcursor\b" (first errs)))
          "must not name the desugaring's own method (the type `Cursor` is fine)")))
  (testing "another non-iterable names its own type and offers no narrowing"
    (let [errs (type-errors "let n: Integer := 5\nacross n as x do\n  print(x)\nend\n")]
      (is (= 1 (count errs)))
      (is (re-find #"this one is Integer" (first errs)))
      (is (not (re-find #"convert" (first errs))))))
  (testing "the iterable targets are unaffected"
    (doseq [code ["let xs: Array[Integer] := [1]\nacross xs as x do print(x) end"
                  "across \"hi\" as c do print(c) end"
                  "across #{1} as s do print(s) end"
                  "across {\"k\": 1} as e do print(e.get(0)) end"
                  "let xs: Array[Integer] := [1]\nacross xs.cursor as c do print(c) end"]]
      (is (empty? (type-errors code)) code))))
