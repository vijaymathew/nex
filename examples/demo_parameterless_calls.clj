#!/usr/bin/env clojure

(require '[nex.parser :as p])
(require '[nex.generator.java :as java])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║       PARAMETERLESS METHOD CALLS DEMONSTRATION             ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Example 1: Basic Parameterless Call
(println "═══ Example 1: Basic Parameterless Call ═══")
(println)

(def example1
  "class Point
  feature
    x: Integer
    y: Integer

    show() do
      print(x)
      print(y)
    end

    demo() do
      show       -- No parentheses!
      show()     -- Parentheses still work
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

;; Example 2: Object Method Calls
(println "═══ Example 2: Object Method Calls ═══")
(println)

(def example2
  "class Point
  private feature
    x: Integer
    y: Integer

  create
    make(newx, newy: Integer) do
      x := newx
      y := newy
    end

  feature
    show() do
      print(x)
      print(y)
    end

    reset() do
      x := 0
      y := 0
    end
end

class Main
  feature
    demo() do
      let p: Point := create Point.make(10, 20)
      p.show    -- Call without parentheses
      p.reset   -- Another parameterless call
      p.show    -- Show again
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

;; Example 3: Chained Calls
(println "═══ Example 3: Chained Parameterless Calls ═══")
(println)

(def example3
  "class Account
  feature
    balance: Integer
    name: String

  feature
    show_balance() do
      print(balance)
    end

    show_name() do
      print(name)
    end

    show_all() do
      show_name
      show_balance
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

;; Example 4: Mixed Calls
(println "═══ Example 4: Mixed Parameterless and Parameterized Calls ═══")
(println)

(def example4
  "class Calculator
  feature
    result: Integer

    clear() do
      result := 0
    end

    show() do
      print(result)
    end

    add(x: Integer) do
      result := result + x
    end

    demo() do
      clear              -- No params, no parens
      add(5)             -- Has params, needs parens
      add(10)            -- Has params, needs parens
      show               -- No params, no parens
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

;; Example 5: With Contracts
(println "═══ Example 5: Parameterless Methods with Contracts ═══")
(println)

(def example5
  "class Account
  feature
    balance: Integer

    validate()
      require
        non_negative: balance >= 0
      do
        print(\"Valid\")
      ensure
        still_valid: balance >= 0
      end

    check() do
      validate    -- Call without parentheses
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example5)
(println)
(println "JAVA CODE (with contracts):")
(println "───────────────────────────")
(println (java/translate example5))
(println)
(println "JAVA CODE (without contracts - production):")
(println "────────────────────────────────────────────")
(println (java/translate example5 {:skip-contracts true}))
(println)

;; Example 6: User's Original Example
(println "═══ Example 6: User's Original Example ═══")
(println)

(def example6
  "class Point
  private feature
    x: Integer
    y: Integer

  create
    make(newx, newy: Integer) do
      x := newx
      y := newy
    end

  feature
    show() do
      print(x)
      print(\":\")
      print(y)
    end
end

class Main
  feature
    demo() do
      let p: Point := create Point.make(10, 20)
      p.show    -- Prints 10:20
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example6)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example6))
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║   KEY POINTS:                                              ║")
(println "║   • Method DECLARATIONS always need ()                     ║")
(println "║   • Method CALLS can omit () when no parameters            ║")
(println "║   • Both p.show and p.show() are valid                     ║")
(println "║   • Makes code more readable and natural                   ║")
(println "╚════════════════════════════════════════════════════════════╝")
