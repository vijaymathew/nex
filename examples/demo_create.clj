#!/usr/bin/env clojure

(require '[nex.parser :as p])
(require '[nex.generator.java :as java])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║          NEX CREATE KEYWORD DEMONSTRATION                  ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Example 1: Simple create with default initialization
(println "═══ Example 1: Default Initialization ═══")
(println)

(def example1
  "class Point
  feature
    x: Integer
    y: Integer
end")

(println "NEX CODE:")
(println "─────────")
(println example1)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example1))
(println)

;; Example 2: Create with named constructor
(println "═══ Example 2: Named Constructor ═══")
(println)

(def example2
  "class Account
  create
    with_balance(initial: Integer)
      require
        non_negative: initial >= 0
      do
        let balance := initial
      ensure
        balance_set: balance = initial
      end

  feature
    balance: Integer

    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        balance := balance + amount
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

;; Example 3: Using create in code
(println "═══ Example 3: Using Create Keyword ═══")
(println)

(def example3
  "class Point
  feature
    x: Integer
    y: Integer
end

class Account
  create
    with_balance(initial: Integer) do
      balance := initial
    end

  feature
    balance: Integer
end

class Main
  feature
    demo() do
      -- Default initialization
      let p: Point := create Point

      -- Named constructor
      let acc: Account := create Account.with_balance(1000)

      -- Multiple objects
      let p1: Point := create Point
      let p2: Point := create Point
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

;; Example 4: Constructor with multiple parameters
(println "═══ Example 4: Multi-Parameter Constructor ═══")
(println)

(def example4
  "class Rectangle
  create
    make(w, h: Integer)
      require
        positive_width: w > 0
        positive_height: h > 0
      do
        let width := w
        let height := h
      ensure
        width_set: width = w
        height_set: height = h
      end

  feature
    width: Integer
    height: Integer

    area() do
      print(width * height)
    end
end

class Main
  feature
    demo() do
      let rect: Rectangle := create Rectangle.make(10, 20)
      rect.area()
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

;; Example 5: Without contracts (production mode)
(println "═══ Example 5: Production Mode (No Contracts) ═══")
(println)

(println "JAVA CODE (with contracts):")
(println "───────────────────────────")
(println (java/translate example4))
(println)

(println "JAVA CODE (without contracts - production):")
(println "────────────────────────────────────────────")
(println (java/translate example4 {:skip-contracts true}))
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                    DEMO COMPLETE                           ║")
(println "╚════════════════════════════════════════════════════════════╝")
