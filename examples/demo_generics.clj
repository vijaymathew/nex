#!/usr/bin/env clojure

(require '[nex.parser :as p])
(require '[nex.generator.java :as java])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║          NEX GENERIC TYPES DEMONSTRATION                   ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Example 1: Simple Generic Class
(println "═══ Example 1: Simple Generic Class ═══")
(println)

(def example1
  "class Box [T]
  constructors
    make(initial: T) do
      let value := initial
    end

  feature
    value: T

    get() do
      print(value)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example1)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example1))
(println)

;; Example 2: Constrained Generic
(println "═══ Example 2: Constrained Generic ═══")
(println)

(def example2
  "class Sorted_List [G -> Comparable]
  feature
    item: G

    insert(new_item: G) do
      item := new_item
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example2)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example2))
(println)

;; Example 3: Multiple Type Parameters
(println "═══ Example 3: Multiple Type Parameters ═══")
(println)

(def example3
  "class Pair [F, S]
  constructors
    make(first: F, second: S) do
      let first := first
      let second := second
    end

  feature
    first: F
    second: S

    get_first() do
      print(first)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example3)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example3))
(println)

;; Example 4: Multiple Parameters with Constraints
(println "═══ Example 4: Multiple Parameters with Constraints ═══")
(println)

(def example4
  "class Dictionary [K -> Hashable, V]
  feature
    key: K
    value: V

    put(k: K, v: V) do
      key := k
      value := v
    end

    get(k: K) do
      print(value)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example4)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example4))
(println)

;; Example 5: Using Generic Classes
(println "═══ Example 5: Using Generic Classes ═══")
(println)

(def example5
  "class Stack [G]
  feature
    top: G
    count: Integer

    push(item: G) do
      top := item
    end
end

class Container
  feature
    int_stack: Stack [Integer]
    string_stack: Stack [String]
    cat_stack: Stack [Cat]
end")

(println "NEX CODE:")
(println "─────────")
(println example5)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example5))
(println)

;; Example 6: Nested Generic Types
(println "═══ Example 6: Nested Generic Types ═══")
(println)

(def example6
  "class Container
  feature
    lists: List [List [Integer]]
    map: Dictionary [String, List [Cat]]
end")

(println "NEX CODE:")
(println "─────────")
(println example6)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example6))
(println)

;; Example 7: Generic with Contracts
(println "═══ Example 7: Generic with Contracts ═══")
(println)

(def example7
  "class BoundedStack [G]
  feature
    top: G
    count: Integer
    max_size: Integer

    push(item: G)
      require
        not_full: count < max_size
        not_null: item /= 0
      do
        top := item
        count := count + 1
      ensure
        pushed: count > 0
      end

    pop()
      require
        not_empty: count > 0
      do
        count := count - 1
      ensure
        decreased: count >= 0
      end
end")

(println "NEX CODE:")
(println "─────────")
(println example7)
(println)
(println "JAVA CODE (with contracts):")
(println "───────────────────────────")
(println (java/translate example7))
(println)
(println "JAVA CODE (without contracts - production):")
(println "────────────────────────────────────────────")
(println (java/translate example7 {:skip-contracts true}))
(println)

;; Example 8: Boxed Types in Generics
(println "═══ Example 8: Automatic Type Boxing ═══")
(println)

(def example8
  "class Wrapper
  feature
    int_list: List [Integer]
    real_list: List [Real]
    bool_list: List [Boolean]
    char_list: List [Char]
    decimal_list: List [Decimal]
end")

(println "NEX CODE:")
(println "─────────")
(println example8)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example8))
(println)
(println "Note: Nex basic types are automatically boxed in generic contexts:")
(println "  Integer → Integer (not int)")
(println "  Real → Float (not float)")
(println "  Boolean → Boolean (not boolean)")
(println "  etc.")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                    DEMO COMPLETE                           ║")
(println "╚════════════════════════════════════════════════════════════╝")
