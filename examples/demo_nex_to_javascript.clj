#!/usr/bin/env clojure

(require '[nex.parser :as p])
(require '[nex.generator.javascript :as js])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║           NEX TO JAVASCRIPT TRANSLATOR DEMO                ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Example 1: Simple Class
(println "═══ Example 1: Simple Class ═══")
(println)

(def example1
  "class Person
  feature
    name: String
    age: Integer

    greet() do
      print(\"Hello, \" + name)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example1)
(println)
(println "JAVASCRIPT CODE:")
(println "────────────────")
(println (js/translate example1))
(println)

;; Example 2: Class with Constructor
(println "═══ Example 2: Class with Constructor ═══")
(println)

(def example2
  "class Point
  create
    make(newx, newy: Integer) do
      x := newx
      y := newy
    end

  feature
    x: Integer
    y: Integer

    show() do
      print(x + \",\" + y)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example2)
(println)
(println "JAVASCRIPT CODE:")
(println "────────────────")
(println (js/translate example2))
(println)

;; Example 3: Class with Inheritance
(println "═══ Example 3: Inheritance ═══")
(println)

(def example3
  "class Animal
  feature
    name: String

    speak() do
      print(\"Animal speaks\")
    end
end

class Dog
inherit
  Animal
  end
feature
  breed: String

  bark() do
    print(\"Woof!\")
  end
end")

(println "NEX CODE:")
(println "─────────")
(println example3)
(println)
(println "JAVASCRIPT CODE:")
(println "────────────────")
(println (js/translate example3))
(println)

;; Example 4: Design by Contract
(println "═══ Example 4: Design by Contract ═══")
(println)

(def example4
  "class Account
  feature
    balance: Integer

    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        balance := balance + amount
      ensure
        increased: balance >= 0
      end

    withdraw(amount: Integer)
      require
        positive: amount > 0
        sufficient: balance >= amount
      do
        balance := balance - amount
      ensure
        decreased: balance >= 0
      end

  invariant
    non_negative: balance >= 0
end")

(println "NEX CODE:")
(println "─────────")
(println example4)
(println)
(println "JAVASCRIPT CODE (with contracts):")
(println "──────────────────────────────────")
(println (js/translate example4))
(println)
(println "JAVASCRIPT CODE (without contracts - production):")
(println "──────────────────────────────────────────────────")
(println (js/translate example4 {:skip-contracts true}))
(println)

;; Example 5: Control Flow
(println "═══ Example 5: Control Flow ═══")
(println)

(def example5
  "class Math
  feature
    max(a, b: Integer) do
      if a > b then
        print(a)
      else
        print(b)
      end
    end

    sum(n: Integer) do
      from
        let i := 1
        let total := 0
      until
        i > n
      do
        total := total + i
        i := i + 1
      end
      print(total)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example5)
(println)
(println "JAVASCRIPT CODE:")
(println "────────────────")
(println (js/translate example5))
(println)

;; Example 6: Generic Types
(println "═══ Example 6: Generic Types ═══")
(println)

(def example6
  "class Stack [G]
  feature
    items: Array [G]
    top: G

    push(item: G) do
      items := items
    end

    pop() do
      print(top)
    end
end

class Pair [K, V]
  feature
    key: K
    value: V

    show() do
      print(key)
      print(value)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example6)
(println)
(println "JAVASCRIPT CODE:")
(println "────────────────")
(println (js/translate example6))
(println)

;; Example 7: Arrays and Maps
(println "═══ Example 7: Arrays and Maps ═══")
(println)

(def example7
  "class Store
  feature
    items: Array [String]
    prices: Map [String, Decimal]

  create
    make() do
      items := [\"apple\", \"banana\", \"orange\"]
      prices := {\"apple\": 1.50, \"banana\": 0.75, \"orange\": 1.25}
    end

  feature
    get_price(item: String) do
      let price := prices[item]
      print(price)
    end

    show_inventory() do
      let first := items[0]
      let second := items[1]
      print(first)
      print(second)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example7)
(println)
(println "JAVASCRIPT CODE:")
(println "────────────────")
(println (js/translate example7))
(println)

;; Example 8: Create Keyword
(println "═══ Example 8: Object Creation ═══")
(println)

(def example8
  "class Point
  create
    make(x, y: Integer) do
      print(x)
      print(y)
    end

  feature
    x: Integer
    y: Integer
end

class Demo
  feature
    demo() do
      let p := create Point.make(10, 20)
      let q := create Point.make(5, 15)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example8)
(println)
(println "JAVASCRIPT CODE:")
(println "────────────────")
(println (js/translate example8))
(println)

;; Example 9: Private Features
(println "═══ Example 9: Private Features ═══")
(println)

(def example9
  "class BankAccount
  private feature
    balance: Integer
    account_number: String

    validate() do
      print(balance)
    end

  feature
    deposit(amount: Integer) do
      validate
      balance := balance + amount
    end

    get_balance() do
      print(balance)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example9)
(println)
(println "JAVASCRIPT CODE:")
(println "────────────────")
(println (js/translate example9))
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║   KEY FEATURES:                                            ║")
(println "║   • ES6+ class syntax                                      ║")
(println "║   • JSDoc type annotations                                 ║")
(println "║   • Single inheritance support                             ║")
(println "║   • Error-based contract assertions                        ║")
(println "║   • Array → [], Map → new Map()                            ║")
(println "║   • Private members use _ prefix                           ║")
(println "║   • Generic types with @template JSDoc                     ║")
(println "╚════════════════════════════════════════════════════════════╝")
